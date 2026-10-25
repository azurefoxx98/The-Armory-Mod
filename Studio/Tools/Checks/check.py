"""Run The Armory's checks on this copy of the mod, and say what they found in plain words.

Git runs this after every commit, pull and branch switch (Studio/Tools/hooks), in the background,
so working never waits for it. Studio/Check.cmd runs it in a window any time. The result goes to
last-check.json beside this file, which the HyCreator plugin shows in its checks panel, with a
notice when something is wrong. Nothing is ever blocked: a commit and a push go through whatever
the checks say, and the mod's own review runs the same checks again before anything is merged.

Five checks are the mod's own gates, copied here by the tool that keeps their code identical to
the originals. Three more look at this branch's own work: that it is not main, that no language
file lost names, and that no file the mod's tools generate was changed by hand.

  python check.py            run every check, print the result, exit 1 when something is wrong
  python check.py --spawn    start a run in the background and return at once (the git hooks)
  python check.py --quiet    run without printing, for the background run
"""
from __future__ import annotations

import datetime
import json
import os
import re
import subprocess
import sys
import time

sys.dont_write_bytecode = True

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
RESOURCES = os.path.join(REPO, "src", "main", "resources")
RESULT = os.path.join(HERE, "last-check.json")
RUNNING = os.path.join(HERE, "last-check.running.json")
FORMAT = 1

# Every program this starts runs without a window. A background run has no console of its own, so
# without this each git call and each gate would flash a console window open on the screen.
NO_WINDOW = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0

# The gates, in the order they run, each with what it means for a player when it fails.
GATES = [
    ("itemcheck", "Every file, sound and effect an item names exists",
     "A missing name draws the placeholder, shows no icon, or plays nothing, with no error anywhere."),
    ("recipecheck", "Every recipe can be crafted",
     "A broken recipe never lights up at its bench, or hides the item from the bench entirely."),
    ("familycheck", "Every Alteration Table family is safe",
     "Pieces of one family must share their stats and slot, or the table changes stats as it changes colour."),
    ("dupkeycheck", "No field is written twice in one file",
     "The game keeps only the last copy, so an edit made to the first one is silently thrown away."),
    ("assetcheck", "Benches, hitboxes and tooltip names",
     "A tooltip line with no name shows its raw key in game, and a wrong hitbox makes a block unplaceable."),
]

# Files the mod's tools write whole from a list of their own. The HyCreator plugin warns before
# one of them is edited (madeByTool in armory-studio.js), and its test fails when the two rules
# differ, so keep them the same.
MADE_BY_TOOL = [
    (r"(^|[/_])(Armory_Showcase|Showcase_Theatre)([_./]|$)", "the showcase world"),
    (r"^Server/Drops/TheArmory/", "the chest loot list"),
    (r"^Server/Drops/Prefabs/[^/]+\.patch$", "the chest loot list"),
]


def now() -> str:
    return datetime.datetime.now().astimezone().isoformat(timespec="seconds")


def git(*args: str) -> str | None:
    try:
        r = subprocess.run(["git", "-C", REPO, "-c", "core.quotepath=off", *args], capture_output=True, text=True, encoding="utf-8", timeout=60,
                           stdin=subprocess.DEVNULL, creationflags=NO_WINDOW)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return r.stdout.strip() if r.returncode == 0 else None


def find_game() -> str | None:
    """The installed game's folder, the one that holds Assets.zip.

    ARMORY_GAME wins. Then the game HyCreator is linked to, read from its settings, so a game
    installed somewhere unusual is still found. Then the launcher's own default place.
    """
    candidates = [os.environ.get("ARMORY_GAME")]
    settings = os.path.join(os.environ.get("LOCALAPPDATA", ""), "HyCreator", "settings.json")
    try:
        with open(settings, encoding="utf-8-sig") as fh:
            release = (json.load(fh).get("sources") or {}).get("release")
        if isinstance(release, str) and release:
            candidates.append(os.path.dirname(release) if release.lower().endswith(".zip") else release)
    except (OSError, ValueError, AttributeError):
        pass
    candidates.append(os.path.join(os.environ.get("APPDATA", ""), "Hytale", "install", "release", "package", "game", "latest"))
    for place in candidates:
        if place and os.path.isfile(os.path.join(place, "Assets.zip")):
            return place
    return None


def problems_in(name: str, output: str) -> list[str]:
    """The failure lines a gate printed, without its own prefixes."""
    lines = output.splitlines()
    if name == "dupkeycheck":
        return [line.strip() for line in lines if line.startswith("  ") and line.strip()]
    if name == "assetcheck":
        if not any(line.startswith("FAILED,") for line in lines):
            return []
        start = next(i for i, line in enumerate(lines) if line.startswith("FAILED,"))
        return [line.strip() for line in lines[start + 1:] if line.strip()]
    out = []
    following = False
    for line in lines:
        if line.startswith("FAIL:"):
            out.append(line[5:].strip())
            following = True
        elif following and line.startswith("    ") and name == "familycheck":
            # The family check prints a failure's detail on the indented lines below it.
            out[-1] += " " + line.strip()
        else:
            following = False
    return out


def run_gate(name: str, game: str) -> dict:
    env = dict(os.environ, ARMORY_RESOURCES=RESOURCES, ARMORY_GAME=game, PYTHONDONTWRITEBYTECODE="1",
               PYTHONIOENCODING="utf-8")
    start = time.monotonic()
    try:
        r = subprocess.run([sys.executable, os.path.join(HERE, name + ".py")], capture_output=True, text=True,
                           encoding="utf-8", errors="replace", env=env, cwd=HERE, timeout=900,
                           stdin=subprocess.DEVNULL, creationflags=NO_WINDOW)
        code, output = r.returncode, r.stdout + r.stderr
    except subprocess.TimeoutExpired:
        code, output = None, "it did not finish within 15 minutes"
    except OSError as exc:
        code, output = None, f"it could not be started: {exc}"
    seconds = round(time.monotonic() - start, 1)
    problems = problems_in(name, output) if code in (0, 1) else []
    if code == 0 and not problems:
        status = "pass"
    elif code == 1 and problems:
        status = "fail"
    else:
        # A gate that stopped early, or failed without naming why, says nothing about the work.
        status = "error"
    tail = [] if status == "pass" else output.strip().splitlines()[-12:]
    return {"status": status, "problems": problems, "seconds": seconds, "output_tail": tail}


def lang_keys(text: str) -> set[str]:
    keys = set()
    for line in text.splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            keys.add(line.split("=", 1)[0].strip())
    return keys


def own_work() -> tuple[list[str] | None, list[str], str]:
    """The commits made on this branch alone, and the commits of ours they start from.

    A commit is this branch's own when no other branch on GitHub holds it. Everything else came
    from us, through a pull or a merge: main, a release branch, or a branch of work not yet
    released, such as a showcase. So comparing against main alone would blame this branch for
    our unreleased work, and comparing against these starting points blames it only for its own.
    Returns None for the commits when there is nothing to compare with, with the reason.
    """
    branch = git("rev-parse", "--abbrev-ref", "HEAD") or ""
    remotes = [r for r in (git("for-each-ref", "--format=%(refname:short)", "refs/remotes") or "").splitlines()
               if r and not r.endswith("/HEAD") and r != "origin/" + branch]
    if not remotes:
        return None, [], "this copy has never been pulled from GitHub"
    listing = git("rev-list", "--boundary", "HEAD", "--not", *remotes)
    if listing is None:
        return None, [], "git could not list this branch's commits"
    own = [line for line in listing.splitlines() if line and not line.startswith("-")]
    starts = [line[1:] for line in listing.splitlines() if line.startswith("-")]
    return own, starts or [git("rev-parse", "HEAD") or "HEAD"], ""


def check_branch() -> dict:
    branch = git("rev-parse", "--abbrev-ref", "HEAD") or ""
    if branch in ("main", "HEAD", ""):
        where = "main" if branch == "main" else "no branch"
        return {"status": "fail", "output_tail": [], "problems": [
            f"This copy is on {where}. Switch to your own branch in HyCreator's git menu, Branches..., so your work "
            "reaches us as yours and main stays as released."]}
    return {"status": "pass", "problems": [], "output_tail": []}


def check_languages(own: list[str] | None, starts: list[str], why: str) -> dict:
    """Names every starting point of ours holds that are gone from this copy now.

    A language file edited from an older copy deletes every name added since, and it reads as an
    ordinary edit. A name counts only when every version of ours this branch started from holds
    it, so a name we removed ourselves is never blamed on this branch.
    """
    if own is None:
        return {"status": "error", "problems": [], "output_tail": [why]}
    problems = []
    paths = set()
    for start in starts:
        listing = git("ls-tree", "-r", "--name-only", start, "--", "src/main/resources/Server/Languages") or ""
        paths |= {p for p in listing.splitlines() if p.endswith(".lang")}
    for path in sorted(paths):
        versions = [git("show", f"{start}:{path}") for start in starts]
        if any(v is None for v in versions):
            continue
        expected = set.intersection(*(lang_keys(v) for v in versions))
        here = os.path.join(REPO, *path.split("/"))
        rel = path[len("src/main/resources/"):]
        if not os.path.isfile(here):
            problems.append(f"{rel} is gone. It held {len(expected)} names.")
            continue
        with open(here, encoding="utf-8-sig") as fh:
            gone = sorted(expected - lang_keys(fh.read()))
        if gone:
            shown = ", ".join(gone[:12]) + (f" and {len(gone) - 12} more" if len(gone) > 12 else "")
            problems.append(f"{rel} lost {len(gone)} name(s) the mod has: {shown}")
    return {"status": "fail" if problems else "pass", "problems": problems, "output_tail": []}


def made_by(rel: str) -> str | None:
    for pattern, tool in MADE_BY_TOOL:
        if re.search(pattern, rel):
            return tool
    return None


def check_generated(own: list[str] | None, starts: list[str], why: str) -> dict:
    """Files the mod's tools write that this branch's own commits, or its uncommitted work, changed."""
    if own is None:
        return {"status": "error", "problems": [], "output_tail": [why]}
    changed = set()
    for commit in own:
        listing = git("show", "--format=", "--name-only", "--no-renames", commit)
        parents = (git("rev-list", "--parents", "-n", "1", commit) or "").split()
        if len(parents) > 2:
            continue  # a merge brings work from elsewhere, its own changes are the branches it joins
        changed |= set((listing or "").splitlines())
    for line in (git("status", "--porcelain", "--no-renames", "--untracked-files=all", "--", "src/main/resources") or "").splitlines():
        changed.add(line[3:].strip().strip('"'))
    problems = []
    for path in sorted(p for p in changed if p.startswith("src/main/resources/")):
        rel = path[len("src/main/resources/"):]
        tool = made_by(rel)
        if tool:
            problems.append(f"{rel} is made by a tool from {tool}, and this branch changed it. "
                            "The change is erased the next time the tool runs, so tell Larsonix what should change.")
    return {"status": "fail" if problems else "pass", "problems": problems, "output_tail": []}


def run_all() -> dict:
    started = now()
    clock = time.monotonic()
    result = {
        "format": FORMAT, "started": started, "finished": None, "seconds": None,
        "commit": git("rev-parse", "HEAD"), "branch": git("rev-parse", "--abbrev-ref", "HEAD"),
        "uncommitted": bool(git("status", "--porcelain", "--", "src/main/resources")),
        "python": sys.version.split()[0], "game": None, "error": None, "checks": [],
    }
    game = find_game()
    result["game"] = game
    if not os.path.isfile(os.path.join(RESOURCES, "manifest.json")):
        result["error"] = "The mod's files were not found at " + RESOURCES
    elif not game:
        result["error"] = ("The game's files were not found. Start Hytale once, or link HyCreator to the game "
                           "in its settings, then commit again.")
    else:
        for name, title, meaning in GATES:
            entry = {"id": name, "title": title, "meaning": meaning}
            entry.update(run_gate(name, game))
            result["checks"].append(entry)
        own, starts, why = own_work()
        result["own_commits"] = None if own is None else len(own)
        for name, title, meaning, outcome in (
            ("branch", "Work happens on your own branch", "Work committed on main skips the review every change goes through.", check_branch()),
            ("languages", "No language file lost names", "A language file saved from an older copy deletes the names added since, and players see raw keys.", check_languages(own, starts, why)),
            ("generated", "No file made by a tool was changed", "These files are rewritten whole by the mod's tools, which erases any change made in them.", check_generated(own, starts, why)),
        ):
            entry = {"id": name, "title": title, "meaning": meaning, "seconds": None}
            entry.update(outcome)
            result["checks"].append(entry)
    result["finished"] = now()
    result["seconds"] = round(time.monotonic() - clock, 1)
    return result


def write_json(path: str, value: dict) -> None:
    temp = path + ".tmp"
    with open(temp, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(value, fh, indent=1, ensure_ascii=False)
        fh.write("\n")
    os.replace(temp, path)


def summary(result: dict) -> tuple[str, bool]:
    if result["error"]:
        return "The checks could not run: " + result["error"], False
    lines, ok = [], True
    for c in result["checks"]:
        if c["status"] == "pass":
            lines.append("  ok       " + c["title"])
            continue
        ok = False
        if c["status"] == "fail":
            lines.append(f"  PROBLEM  {c['title']}: {len(c['problems'])}")
            lines += ["           - " + p for p in c["problems"]]
        else:
            lines.append("  COULD NOT RUN  " + c["title"])
            lines += ["           " + line for line in c["output_tail"]]
    head = "Everything passes." if ok else "Something needs a look."
    return f"The Armory checks, {result['finished']}, {result['seconds']} s. {head}\n" + "\n".join(lines), ok


def spawn() -> int:
    """Start a quiet run with no window and return at once, so a commit never waits for it.

    The run gets a console that is never shown, rather than none at all, and the programs it
    starts share that hidden console. A run with no console at all would open a new visible one
    for every program it starts.
    """
    flags = 0
    if os.name == "nt":
        flags = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW
    subprocess.Popen([sys.executable, os.path.abspath(__file__), "--quiet"], cwd=HERE, creationflags=flags,
                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     close_fds=True)
    return 0


def main(argv: list[str]) -> int:
    if argv == ["--spawn"]:
        return spawn()
    quiet = argv == ["--quiet"]
    if argv not in ([], ["--quiet"]):
        print(__doc__)
        return 2
    write_json(RUNNING, {"format": FORMAT, "started": now(), "pid": os.getpid(), "commit": git("rev-parse", "HEAD")})
    try:
        try:
            result = run_all()
        except Exception as exc:  # noqa: BLE001
            # Whatever stopped the run, a result saying so replaces the last one, so the panel never
            # goes on showing an older pass as if it were this run's.
            result = {"format": FORMAT, "started": None, "finished": now(), "seconds": None,
                      "commit": git("rev-parse", "HEAD"), "branch": git("rev-parse", "--abbrev-ref", "HEAD"),
                      "uncommitted": None, "python": sys.version.split()[0], "game": None, "checks": [],
                      "error": f"The checks stopped on something unexpected ({type(exc).__name__}: {exc}). Tell Larsonix."}
        write_json(RESULT, result)
    finally:
        # Only this run's own marker is removed: a later run that started meanwhile keeps its own.
        try:
            with open(RUNNING, encoding="utf-8") as fh:
                mine = json.load(fh).get("pid") == os.getpid()
            if mine:
                os.remove(RUNNING)
        except (OSError, ValueError):
            pass
    text, ok = summary(result)
    if not quiet:
        print(text)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
