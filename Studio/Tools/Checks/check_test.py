"""Test check.py the way Lady meets it, in a throwaway clone of this repository.

  python check_test.py

The clone gets a branch of its own made from this one, the way her branch is made, and must pass
every check. Then one mistake per check is committed there, each the kind the mod has really
received, and every check must name its own. Then the branch is switched to main, which must be
named too. Last, a run started the way the git hooks start one must leave its result behind.
Nothing outside the throwaway folder is touched. Needs the installed game.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
import check  # noqa: E402


def git(cwd: str, *args: str) -> str:
    r = subprocess.run(["git", "-C", cwd, "-c", "core.autocrlf=false", *args], capture_output=True, text=True,
                       encoding="utf-8")
    if r.returncode != 0:
        raise SystemExit(f"git {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout


def run(clone: str, *args: str) -> tuple[int, dict]:
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    script = os.path.join(clone, "Studio", "Tools", "Checks", "check.py")
    r = subprocess.run([sys.executable, script, *args], capture_output=True, text=True, encoding="utf-8", env=env)
    with open(os.path.join(clone, "Studio", "Tools", "Checks", "last-check.json"), encoding="utf-8") as fh:
        return r.returncode, json.load(fh)


def by_id(result: dict) -> dict:
    return {c["id"]: c for c in result["checks"]}


def edit(path: str, change) -> None:
    with open(path, encoding="utf-8-sig") as fh:
        text = fh.read()
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write(change(text))


def main() -> int:
    if not check.find_game():
        print("check_test: cannot run, the installed game was not found")
        return 2
    failures = []

    def expect(label: str, ok: bool, detail="") -> None:
        print(("ok    " if ok else "WRONG ") + label + ("" if ok else f"\n      {detail}"))
        if not ok:
            failures.append(label)

    with tempfile.TemporaryDirectory() as tmp:
        clone = os.path.join(tmp, "mod")
        git(tmp, "clone", "-q", "--no-hardlinks", REPO, clone)
        git(clone, "config", "core.autocrlf", "false")
        git(clone, "checkout", "-q", git(REPO, "rev-parse", "HEAD").strip())
        git(clone, "checkout", "-q", "-b", "lady")
        # The checks as they stand in this working copy, committed or not.
        target = os.path.join(clone, "Studio", "Tools", "Checks")
        os.makedirs(target, exist_ok=True)
        for name in os.listdir(HERE):
            if name.endswith(".py"):
                shutil.copyfile(os.path.join(HERE, name), os.path.join(target, name))
        git(clone, "add", "Studio/Tools/Checks")
        git(clone, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-q", "--allow-empty",
            "-m", "The checks as they stand")
        # That commit is the test's setup, not her work, so it is pushed where her branch goes.
        git(clone, "update-ref", "refs/remotes/origin/feature/test-setup", "HEAD")

        code, result = run(clone)
        checks = by_id(result)
        expect("a branch made from ours passes every check",
               code == 0 and not result["error"] and all(c["status"] == "pass" for c in result["checks"]),
               json.dumps({k: (v["status"], v["problems"][:2], v["output_tail"][-3:]) for k, v in checks.items()}))
        expect("it finds the game and counts no commit as hers", bool(result["game"]) and result.get("own_commits") == 0,
               f"game {result['game']}, own commits {result.get('own_commits')}")

        res = os.path.join(clone, "src", "main", "resources")
        item = None
        for dirpath, _dirs, files in os.walk(os.path.join(res, "Server", "Item", "Items", "AtlanteanSet")):
            for f in sorted(files):
                if f.endswith(".json"):
                    item = os.path.join(dirpath, f)
                    break
            if item:
                break
        rel_item = os.path.relpath(item, res).replace(os.sep, "/")
        # A sound set that exists nowhere, written twice at the end, where the game reads it.
        edit(item, lambda t: t.rstrip()[:-1].rstrip() + ',\n  "ItemSoundSetId": "ISS_Ghost",\n  "ItemSoundSetId": "ISS_Ghost"\n}\n')
        # A language file saved from an older copy: its last name is lost.
        lang = os.path.join(res, "Server", "Languages", "en-US", "server.lang")
        lost = None

        def drop_last(text: str) -> str:
            nonlocal lost
            lines = text.splitlines(keepends=True)
            for i in range(len(lines) - 1, -1, -1):
                if "=" in lines[i] and not lines[i].lstrip().startswith("#"):
                    lost = lines[i].split("=", 1)[0].strip()
                    return "".join(lines[:i] + lines[i + 1:])
            raise SystemExit("server.lang holds no name")
        edit(lang, drop_last)
        # A hand edit to a loot table the loot tool writes.
        drops = os.path.join(res, "Server", "Drops", "TheArmory")
        loot = os.path.join(drops, sorted(os.listdir(drops))[0])
        edit(loot, lambda t: t + "\n")
        git(clone, "-c", "user.name=Lady", "-c", "user.email=lady@example.invalid", "commit", "-q", "-am", "Her work")

        code, result = run(clone)
        checks = by_id(result)
        expect("a failing run exits 1", code == 1, f"exit {code}")
        expect("it counts her one commit", result.get("own_commits") == 1, str(result.get("own_commits")))
        expect("the item check names the sound set that does not exist",
               checks["itemcheck"]["status"] == "fail" and any("ISS_Ghost" in p and rel_item in p for p in checks["itemcheck"]["problems"]),
               checks["itemcheck"])
        expect("the repeated field is named", checks["dupkeycheck"]["status"] == "fail"
               and any("ItemSoundSetId" in p for p in checks["dupkeycheck"]["problems"]), checks["dupkeycheck"])
        expect("the lost language name is named", checks["languages"]["status"] == "fail"
               and any(lost in p for p in checks["languages"]["problems"]), checks["languages"])
        expect("the edited loot table is named", checks["generated"]["status"] == "fail"
               and any(os.path.basename(loot) in p for p in checks["generated"]["problems"]), checks["generated"])
        expect("the checks with nothing planted still pass",
               all(checks[k]["status"] == "pass" for k in ("recipecheck", "familycheck", "assetcheck", "branch")),
               {k: checks[k]["status"] for k in checks})

        git(clone, "checkout", "-q", "-f", "main")
        # Main does not carry the checks yet, so they are put back by hand, and the last result
        # is removed so an old one can never pass for this run.
        os.makedirs(target, exist_ok=True)
        for name in os.listdir(HERE):
            if name.endswith(".py"):
                shutil.copyfile(os.path.join(HERE, name), os.path.join(target, name))
        os.remove(os.path.join(target, "last-check.json"))
        code, result = run(clone)
        expect("working on main is named", by_id(result)["branch"]["status"] == "fail", by_id(result)["branch"])

        git(clone, "clean", "-q", "-f", "--", "Studio/Tools/Checks")
        git(clone, "checkout", "-q", "-f", "lady")
        if os.path.isfile(os.path.join(target, "last-check.json")):
            os.remove(os.path.join(target, "last-check.json"))
        subprocess.run([sys.executable, os.path.join(target, "check.py"), "--spawn"], timeout=20)
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline and not os.path.isfile(os.path.join(target, "last-check.json")):
            time.sleep(1)
        expect("a run started in the background leaves its result", os.path.isfile(os.path.join(target, "last-check.json")))
        expect("and no run is left marked as running", not os.path.isfile(os.path.join(target, "last-check.running.json")))

    print(f"check_test: {len(failures)} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
