"""Run The Armory's checks on this copy of the mod, and say what they found in plain words.

Git runs this after every commit, pull and branch switch (Studio/Tools/hooks), in the background,
so working never waits for it. Studio/Check.cmd runs it in a window any time. The result goes to
last-check.json beside this file, which the HyCreator plugin shows in its checks panel, with a
notice when something is wrong. Nothing is ever blocked: a commit and a push go through whatever
the checks say, and the mod's own review runs the same checks again before anything is merged.

Six checks are the mod's own gates, copied here by the tool that keeps their code identical to
the originals. Four more look at this branch's own work: that it is not main, that no language
file lost names, that no file the mod's tools generate was changed by hand, and what her work
changed in the game itself (the game check, in game/: the game's own server loads her work
exactly as her game does, and only what is new since her branch started is shown).

The gates print for us, so their words never change here. Every line they print is translated
below into what Lady reads: what is wrong in her words, and the one thing to change. Both places
she reads checks in, the HyCreator panel and this script's own window, show that translation. A
line no rule knows is shown whole, with the screenshot hand-off, never dropped.

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
# 2 adds "lady" to each check: its problems as she reads them.
FORMAT = 2

# Every program this starts runs without a window. A background run has no console of its own, so
# without this each git call and each gate would flash a console window open on the screen.
NO_WINDOW = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0

# The gates, in the order they run, each with the name Lady sees when it cannot run.
GATES = [
    ("itemcheck", "Item files"),
    ("lootcheck", "Chest loot"),
    ("recipecheck", "Recipes"),
    ("familycheck", "Alteration Table"),
    ("dupkeycheck", "Fields written twice"),
    ("assetcheck", "Benches and tooltips"),
]

# The one hand-off for anything she cannot fix herself.
SEND = "Send Larsonix a screenshot."

# Files the mod's tools write whole from a list of their own. The HyCreator plugin warns before
# one of them is edited (madeByTool in armory-studio.js), and its test fails when the two rules
# differ, so keep them the same. The chest loot tables themselves, Drops/TheArmory, are Lady's
# to edit in HyCreator's loot editor. Only the patches that put them in chests stay generated.
MADE_BY_TOOL = [
    (r"(^|[/_])(Armory_Showcase|Showcase_Theatre)([_./]|$)", "the showcase world"),
    (r"^Server/Drops/Prefabs/[^/]+\.patch$", "the list of chests your loot goes in"),
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


# ---- the gates' lines, as Lady reads them ----------------------------------------------------------
#
# Each rule: the gate, a pattern for the line after its subject, and what she reads. "says" is what is
# wrong in her words and what a player sees, "do" is the one thing to change. {name} fields come
# from the pattern. A rule with do=SEND is something she cannot fix herself.

ITEM_FIELD = {"Model": "model", "Texture": "texture", "Icon": "icon"}
FIELD_FIX = {"Model": "Pick an existing model in its Model field.",
             "Texture": "Pick an existing texture in its Texture field.",
             "Icon": "Click Generate icon on its page."}
SHOWS = {"sound": "plays nothing", "particle": "shows nothing", "camera effect": "does nothing"}


def typo_line(m) -> str | None:
    """The line number a gate's JSON error names, if it names one."""
    found = re.search(r"line (\d+)", m.groupdict().get("detail") or "")
    return found.group(1) if found else None


# HyCreator cannot open a file that does not parse: it shows a red notice naming the line and opens
# nothing, so the fix goes through Notepad.
TYPO = (lambda m: f"Has a typing mistake{' at line ' + typo_line(m) if typo_line(m) else ''}, so Hytale skips this file.",
        lambda m: ("HyCreator cannot open it. Right-click it in the file tree, click Reveal in Explorer, "
                   f"then fix {'line ' + typo_line(m) if typo_line(m) else 'it'} in Notepad."))
# A gate's own words for a file that does not parse, with the parser's message, which a gate that
# stopped on the same file repeats in its output.
TYPO_LINE = re.compile(r"(?:cannot be read as JSON, so the game never loads it \((.*)\)|does not parse, (.*))$")
# The Colors page makes these two fixes for every color of a set in one press (armory-studio.js:
# categoryFault, and wrongFamily for a recipe made from the wrong resource type).
COLORS_TOO = "For a set of colors, open one and click Colors to fix them all at once."
# HyCreator's recipe editor, a button at the top of every item page: ingredients, bench and tier.
RECIPE = "Click Recipe at the top of its page"
# HyCreator's form shows a field written twice only once, so the fix is in the file's text, which
# the More file actions button opens.
RAW = "Click the three dots at the top of its page, then Open JSON. "

RULES = [
    ("itemcheck", r"cannot be read as JSON, so the game never loads it \((?P<detail>.*)\)", *TYPO),
    ("itemcheck", r"is not a JSON object, so the game never loads it", *TYPO),
    ("itemcheck", r"(?P<field>Model|Texture|Icon) (?P<value>.+) exists in neither the mod nor the game",
     lambda m: f"Its {ITEM_FIELD[m['field']]} {m['value']} does not exist.", lambda m: FIELD_FIX[m["field"]]),
    ("itemcheck", r"CustomModel (?P<value>.+) exists in neither the mod nor the game",
     "Its block model {value} does not exist.", "Pick an existing model in its CustomModel field."),
    ("itemcheck", r"CustomModelTexture (?P<value>.+) exists in neither the mod nor the game",
     "Its block texture {value} does not exist.", "Pick an existing texture in its CustomModelTexture field."),
    ("itemcheck", r"its recipe asks for the item (?P<value>\S+), which does not exist",
     "Its recipe needs the item {value}, which does not exist.", RECIPE + " and fix that name."),
    ("itemcheck", r"its recipe asks for the family (?P<value>\S+), which has no family file",
     "Its recipe takes the resource type {value}, which does not exist.", RECIPE + " and fix that name."),
    ("itemcheck", r"it belongs to the family (?P<value>\S+), which has no family file",
     "Its ResourceTypes names {value}, which does not exist.", "Fix that name in its ResourceTypes."),
    ("itemcheck", r"it plays the (?P<kind>sound|particle|camera effect) (?P<value>.+), which exists in neither the mod nor the game",
     lambda m: f"Its {m['kind']} {m['value']} does not exist, so it {SHOWS[m['kind']]}.", "Pick a name HyCreator suggests."),
    ("itemcheck", r"its item sound set (?P<value>\S+) does not exist",
     "Its ItemSoundSetId {value} does not exist, so it makes no sound.", "Pick a name HyCreator suggests."),
    ("itemcheck", r"its quality (?P<value>\S+) does not exist",
     "Its quality {value} does not exist.", "Pick a quality HyCreator suggests."),
    ("itemcheck", r"its (?P<block>Armor|Weapon|Utility) changes the stat (?P<value>\S+), which does not exist",
     "Uses the stat {value}, which does not exist, so it does nothing in game.",
     "Remove {value} from its StatModifiers, or ask Larsonix for that stat."),
    ("itemcheck", r"it is in (?P<armory>\S+) but not (?P<value>\S+), so other mods cannot find it",
     "Missing the category {value}, so other mods cannot find it.", "Add {value} to its Categories. " + COLORS_TOO),
    ("itemcheck", r"its Categories is not a list", "Its Categories is not a list.",
     RAW + "Put its Categories in square brackets, click Apply, then Save."),
    ("itemcheck", r"its texture (?P<value>.+) is not a PNG picture",
     "Its texture {value} is not a PNG.", "Save that texture again as a PNG."),
    ("itemcheck", r"its model (?P<value>.+) cannot be read \((?P<detail>.*)\)",
     "Its model {value} cannot be read.", "Export that model again from Blockbench."),

    ("recipecheck", r"no inputs", "Its recipe takes no ingredient, so no bench shows it.", RECIPE + " and add an ingredient."),
    ("recipecheck", r"input item (?P<value>\S+) does not exist",
     "Its recipe needs the item {value}, which does not exist.", RECIPE + " and fix that name."),
    ("recipecheck", r"input family (?P<value>\S+) has no English name, the bench shows (?P<key>\S+)",
     "Its recipe takes {value}, which has no English name, so the bench shows {key}.",
     "Open Languages, en-US, server. In the empty row at the bottom, type resourceType.{value}.name "
     "and its English name, click +, then Save."),
    ("recipecheck", r"input family (?P<value>\S+) has no member item( and no family file)?",
     "Its recipe takes {value}, but no item has that resource type, so nobody can craft it.",
     RECIPE + " and fix that name."),
    ("recipecheck", r"input family (?P<value>\S+) holds only (?P<have>\S+) armour, the recipe makes (?P<makes>\S+) armour",
     "Its recipe takes {have} armor ({value}), but this is {makes} armor.",
     RECIPE + " and take a resource type of {makes} armor. " + COLORS_TOO),
    ("recipecheck", r"input (?P<value>.+) names no item, family or tag",
     "Its recipe has an ingredient with no item, resource type or tag.", RECIPE + " and fix that ingredient."),
    ("recipecheck", r"output item (?P<value>\S+) does not exist",
     "Its recipe makes the item {value}, which does not exist.", RECIPE + " and fix that name."),
    ("recipecheck", r"bench '(?P<value>[^']*)' is declared by no item",
     "Its recipe needs the bench {value}, which does not exist.", RECIPE + " and fix the bench's name."),
    ("recipecheck", r"bench (?P<value>\S+) is a (?P<have>\S+) bench, the recipe asks for (?P<want>\S+)",
     "Its recipe asks for {value} as a {want} bench, but it is a {have} bench, so no bench shows it.",
     RECIPE + " and set that bench's type to {have}."),
    ("recipecheck", r"bench (?P<value>\S+) has (?P<tiers>\d+) tier\(s\), the recipe needs tier (?P<tier>\d+)",
     "Its recipe needs tier {tier} of {value}, which has {tiers}.", RECIPE + " and lower the tier."),
    ("recipecheck", r"no block grants the augment tag (?P<value>\S+)",
     "Its recipe needs the bench upgrade {value}, which nothing gives.", RECIPE + " and fix that name."),
    ("recipecheck", r"a StructuralCrafting recipe must have exactly one input, it has (?P<count>\d+)",
     "Its recipe takes {count} ingredients, but this bench takes exactly one.", RECIPE + " and keep one ingredient."),
    ("recipecheck", r"stale accepted entry, .*", "This check needs updating.", SEND),

    ("familycheck", r"STAT CLOSURE: (?P<count>\d+) distinct profiles across .*",
     "Items of {subject} have {count} different armor stats, so the Alteration Table changes stats as it changes color.",
     "Give every item of {subject} the same armor stats."),
    ("familycheck", r"SLOT CLOSURE: (?P<count>\d+) distinct slots across .*",
     "Items of {subject} are worn on {count} different slots, so the Alteration Table can turn one into another.",
     "Give each slot its own resource type."),
    ("familycheck", r"REACHABILITY: .*",
     "No player can get these items: none is crafted at a bench or found in a chest.",
     "Give one of them a recipe at a bench, or add one to a chest loot table."),
    ("familycheck", r"stale accepted violation: .*", "This check needs updating.", SEND),

    ("lootcheck", r"cannot be read as JSON, so the game never loads it \((?P<detail>.*)\)", *TYPO),
    ("lootcheck", r"it drops the item (?P<value>\S+), which does not exist",
     "Its loot names the item {value}, which does not exist, so it never drops.",
     "Open it and pick an existing item in place of {value}."),
    ("lootcheck", r"it puts the drop list (?P<value>\S+) in chests, but \S+ does not exist",
     "Chests look for your loot table {value}, which is gone, so they hold no Armory loot.",
     "Name one of your chest loot tables {value} again."),
    ("lootcheck", r"it names the drop list (?P<value>\S+), which does not exist",
     "Its loot names the table {value}, which does not exist, so that part drops nothing.",
     "Open it and fix that name."),
    ("lootcheck", r"it drops nothing at all, so the game refuses the whole file",
     "Its loot holds nothing, so Hytale refuses the whole file.", "Open it and add an item."),

    ("dupkeycheck", r"duplicate key (?P<value>.+) \(REPEATS DIFFER, a value is being dropped\)",
     "The field {value} is written twice, with different values. Hytale uses only the second one.",
     RAW + "Delete the one you do not want, click Apply, then Save."),
    ("dupkeycheck", r"duplicate key (?P<value>.+) \(identical repeat, silent edit trap\)",
     "The field {value} is written twice. Hytale uses only the second one, so an edit to the first does nothing.",
     RAW + "Delete the first one, click Apply, then Save."),
    ("dupkeycheck", r"does not parse, (?P<detail>.*)", *TYPO),
    ("dupkeycheck", r"unreadable, (?P<detail>.*)", "This file cannot be opened.", SEND),

    ("assetcheck", r"stat (?P<value>\S+) is defined neither by us nor by the game",
     "The stat {value} does not exist, so its tooltip line shows a raw name.",
     "Remove {value} from the items using it, or ask Larsonix for that stat."),
    ("assetcheck", r"(?P<lang>\S+) client\.lang lacks (?P<key>\S+), needed by (?P<value>\S+) and others",
     "A tooltip line has no {lang} name: {key}.", SEND),
    ("assetcheck", r"(?P<lang>\S+) client\.lang lacks (?P<key>\S+), which en-US has",
     "A tooltip line has no {lang} name: {key}.", SEND),
    ("assetcheck", r"(?P<lang>\S+) client\.lang: (?P<key>\S+) has no \{value\} slot, so its amount never shows",
     "A tooltip line never shows its amount: {key}.", SEND),
    ("assetcheck", r"(?P<value>\S+) references (?P<key>\S+) '(?P<id>[^']*)' which we do not ship",
     "Its block names the hitbox {id}, which does not exist.", SEND),
    ("assetcheck", r".+", "A bench or tooltip of the mod is broken.", SEND),
]
RULES = [(gate, re.compile(pattern), says, do) for gate, pattern, says, do in RULES]


def subject_of(gate: str, line: str) -> tuple[dict | None, str]:
    """What a gate's line is about, and the rest of the line.

    Items come as a file path (itemcheck, dupkeycheck) or as "item X" (recipecheck), recipes as
    "recipe X", and the Alteration Table check names a resource type first.
    """
    text = line.replace("\\", "/") if gate == "dupkeycheck" else line
    m = re.match(r"^(Server/([^:]+\.(?:json|patch))):\s*(.*)$", text, re.S)
    if m:
        name = os.path.splitext(os.path.basename(m.group(2)))[0]
        return {"label": name, "open": m.group(2)}, m.group(3)
    m = re.match(r"^(?:patched )?(item|recipe) (\S+):\s*(.*)$", line, re.S)
    if m:
        folder = "Item/Items/" if m.group(1) == "item" else "Item/Recipes/"
        return {"label": m.group(2), "find": folder}, m.group(3)
    m = re.match(r"^(Resource_\S+|\S+):\s*((?:STAT CLOSURE|SLOT CLOSURE|REACHABILITY):.*)$", line, re.S)
    if gate == "familycheck" and m:
        return {"label": m.group(1), "kind": "resource type"}, m.group(2)
    return None, line


def items_named(text: str) -> list[str]:
    """The item ids the Alteration Table check lists after "items:" or "owning items:"."""
    out = []
    for m in re.finditer(r"(?:owning )?items: ([^:]*?)(?=\s+(?:profile \d+:|owning items:|items:)|$)", text):
        out += [i.strip() for i in m.group(1).split(",") if i.strip() and i.strip() != "<none>"]
    return list(dict.fromkeys(out))


def for_lady(gate: str, line: str) -> dict:
    """One line of a gate as Lady reads it: says, do, the items it is about, and the line itself."""
    subject, rest = subject_of(gate, line)
    for rule_gate, pattern, says, do in RULES:
        if rule_gate != gate:
            continue
        m = pattern.fullmatch(rest.strip())
        if not m:
            continue
        fields = {k: v for k, v in m.groupdict().items() if v is not None}
        fields["subject"] = subject["label"] if subject else ""
        render = lambda t: t(m) if callable(t) else t.format(**fields)
        entry = {"says": render(says), "do": render(do), "items": [], "line": line, "known": True}
        if subject and subject.get("kind") == "resource type":
            named = items_named(rest)
            entry["items"] = [{"label": i, "find": "Item/Items/"} for i in named]
        elif subject and subject.get("open") and made_by("Server/" + subject["open"]):
            pass  # a file our tools write is never hers to open, the message says what to do instead
        elif subject:
            entry["items"] = [subject]
        return entry
    return {"says": "One of the checks found something it cannot explain.", "do": SEND, "items": [], "line": line,
            "known": False, "detail": line}


def one_per_field(entries: list[dict]) -> list[dict]:
    """One message per field written more than once in a file.

    The gate names each repeat it meets, so a field written three times is two lines, one about
    different values and one about the same value. She reads it once, as written that many times.
    """
    out, seen = [], {}
    for e in entries:
        m = re.search(r"duplicate key (.+) \((identical repeat|REPEATS DIFFER)", e["line"])
        if not m or not e["items"]:
            out.append(e)
            continue
        key = (e["items"][0].get("open"), m.group(1))
        if key not in seen:
            seen[key] = {"entry": e, "times": 2, "differ": m.group(2) == "REPEATS DIFFER"}
            out.append(e)
            continue
        merged = seen[key]
        merged["times"] += 1
        merged["differ"] = merged["differ"] or m.group(2) == "REPEATS DIFFER"
        times = "twice" if merged["times"] == 2 else f"{merged['times']} times"
        merged["entry"]["says"] = (f"The field {m.group(1)} is written {times}"
                                   + (", with different values. Hytale uses only the last one." if merged["differ"]
                                      else ". Hytale uses only the last one, so an edit to the others does nothing."))
        merged["entry"]["do"] = RAW + "Keep one and delete the others, click Apply, then Save."
    return out


def lady_view(checks: list[dict]) -> None:
    """Adds each check's problems as Lady reads them, under "lady".

    A stat that does not exist is named by two gates, the item one per item and the tooltip one once.
    The item one already says which items and what to do, so the tooltip one is left out.
    """
    stats = set()
    mixed = set()
    typos = set()
    waiting = False
    for c in checks:
        for p in c["problems"]:
            m = TYPO_LINE.search(p)
            if m and (m.group(1) or m.group(2)):
                typos.add(m.group(1) or m.group(2))
            if c["id"] == "itemcheck":
                m = re.search(r"changes the stat (\S+), which does not exist$", p)
                if m:
                    stats.add(m.group(1))
            if c["id"] == "recipecheck":
                m = re.search(r"input family (\S+) holds only \S+ armour, the recipe makes \S+ armour$", p)
                if m:
                    mixed.add(m.group(1))
    for c in checks:
        if "lady" in c:
            continue
        entries = []
        for p in c["problems"]:
            if c["id"] == "assetcheck":
                m = re.fullmatch(r"stat (\S+) is defined neither by us nor by the game", p)
                if m and m.group(1) in stats:
                    continue
            # A recipe making one slot from another slot's resource type puts both slots in it. Its
            # slots and stats then differ because of that recipe alone, and asking for equal stats
            # would give a helmet the stats of gloves. Fixing the recipe settles both, and the
            # next run judges whatever is left.
            if c["id"] == "familycheck":
                m = re.match(r"(\S+): (?:SLOT|STAT) CLOSURE:", p)
                if m and m.group(1) in mixed:
                    continue
            entries.append(for_lady(c["id"], p))
        if c["id"] == "dupkeycheck":
            entries = one_per_field(entries)
        if c["status"] == "error":
            tail = "\n".join(c.get("output_tail") or [])
            # A gate that stopped on a file another gate already names as a typing mistake is that
            # same mistake, not something for Larsonix. It runs again once the file is fixed.
            if any(t in tail for t in typos):
                waiting = True
            else:
                entries.append({"says": f"The check \"{c['title']}\" could not run, so it says nothing about your work.",
                                "do": SEND, "items": [], "line": tail, "known": True, "detail": tail})
        c["lady"] = entries
    if waiting:
        for c in checks:
            for e in c["lady"]:
                if TYPO_LINE.search(e["line"]):
                    e["says"] += " Some checks wait until it is fixed."


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
        problem = "Your copy is on main, not on your branch." if branch == "main" else "Your copy is on no branch."
        # HyCreator's git button, at the top, shows the branch the copy is on: main, or HEAD for none.
        switch = f"In HyCreator, click {branch or 'HEAD'} at the top, then Branches..., then lady."
        return {"status": "fail", "output_tail": [], "problems": [problem], "lady": [
            {"says": problem, "do": switch, "items": [], "line": problem, "known": True}]}
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
    lady = []
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
            # A file deleted in HyCreator waits in its trash, which puts it back byte for byte.
            lady.append({"says": f"The language file {rel} is gone, so players see raw names.",
                         "do": "If you deleted it in HyCreator, click Workspace at the top left, then Trash (restore deleted)..., "
                               "and click Restore. If not, send Larsonix a screenshot.",
                         "items": [], "line": problems[-1], "known": True})
            continue
        with open(here, encoding="utf-8-sig") as fh:
            gone = sorted(expected - lang_keys(fh.read()))
        if gone:
            shown = ", ".join(gone[:12]) + (f" and {len(gone) - 12} more" if len(gone) > 12 else "")
            problems.append(f"{rel} lost {len(gone)} name(s) the mod has: {shown}")
            lady.append({"says": f"This language file lost {len(gone)} name{'s' if len(gone) != 1 else ''}, so players see raw names: {shown}.",
                         "do": SEND, "items": [{"label": rel.rsplit("/", 2)[-2] + " " + rel.rsplit("/", 1)[-1],
                                                "open": rel[len("Server/"):] if rel.startswith("Server/") else None}],
                         "line": problems[-1], "known": True})
    return {"status": "fail" if problems else "pass", "problems": problems, "lady": lady, "output_tail": []}


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
    lady = []
    for path in sorted(p for p in changed if p.startswith("src/main/resources/")):
        rel = path[len("src/main/resources/"):]
        tool = made_by(rel)
        if tool:
            problems.append(f"{rel} is made by a tool from {tool}, and this branch changed it. "
                            "The change is erased the next time the tool runs.")
            lady.append({"says": f"You changed a file our tools rewrite from {tool}, so the change will be lost.",
                         "do": "Send Larsonix a screenshot, and say what should change.",
                         "items": [{"label": os.path.splitext(os.path.basename(rel))[0],
                                    "open": rel[len("Server/"):] if rel.startswith("Server/") else None}],
                         "line": problems[-1], "known": True})
    return {"status": "fail" if problems else "pass", "problems": problems, "lady": lady, "output_tail": []}


# ---- the game check: her work as the game itself loads it ------------------------------------------
#
# The game's own server is booted over her work exactly as her game loads it (the code from
# Studio/Tools/TheArmory-code.jar, her files from src/main/resources), and every item, effect and recipe
# is written down as the game holds it. The rules in game/rules.py compare that with vanilla and with
# the other items of each set. The same is done once for the commit her branch started from, and she is
# shown only what her own work changed. A result is kept per starting commit, so it is computed once.

GAME_DIR = os.path.join(HERE, "game")
CODE_JAR = os.path.join(REPO, "Studio", "Tools", "TheArmory-code.jar")
WORK = os.path.join(os.environ.get("LOCALAPPDATA", ""), "TheArmory", "Checks")

# What she reads for each kind of finding: what is wrong in her words, then the one thing to do. A
# field name is the one she types in HyCreator. {name}, {field} and the rest come from the finding.
LANGUAGES = ("Open Languages, en-US, server. In the empty row at the bottom, type its name key and its English name, "
             "click +, then Save.")
GAME_WORDS = {
    ("names-resolve", "value"): ("Its {field} names {name}, which does not exist, so that part does nothing in game.",
                                 "Open it and pick a name HyCreator suggests for {name}."),
    ("names-resolve", "key"): ("Its {field} names {name}, which does not exist, so that part does nothing in game.",
                               "Open it and pick a name HyCreator suggests for {name}."),
    ("like-its-kind", "unset"): ("It has no {field}, so {effect}.", "Open it and fill in {field}."),
    ("like-its-kind", "default"): ("It keeps the base {field}, so {effect}.",
                                   "Open it and set {field} to a value of its own."),
    ("like-its-siblings", None): ("It has no {field}, which the other items of its set have.",
                                  "Open it and set {field} like the others of its set."),
    ("like-its-siblings", "value"): ("Its {field} is {value}, where the other items of its set all have {others}.",
                                     "Open it and check its {field}."),
    ("reachable", "item"): ("No player can get it: nothing crafts it, drops it or sells it.",
                            "Give it a recipe at a bench, or add it to a chest loot table."),
    ("reachable", "recipe"): ("Its recipe can never be crafted, so nobody can make it at a bench.",
                              RECIPE + " and check each ingredient and the bench."),
    ("names-and-pictures", "no text"): ("It has no English name, so players see a raw name.", LANGUAGES),
    ("names-and-pictures", "slot word"): ("It is worn on the {slot}, but its name says {word}.",
                                          "Fix its English name in Languages, en-US, server."),
    ("names-and-pictures", "shared", "name"): ("It shows the same name as items that look different.",
                                               "Give it a name of its own."),
    ("names-and-pictures", "shared", "icon"): ("It shares its icon with items of another look, so the icon is right for one of them at most.",
                                               FIELD_FIX["Icon"]),
    ("common-files", "different"): ("This file has the same name as a vanilla file, so it replaces that file "
                                    "everywhere in the game.", "Rename your file, unless that is what you want."),
    ("common-files", "identical"): ("This file is a copy of a vanilla file with the same name.",
                                    "Delete your copy, the vanilla file is used anyway."),
    ("all-loaded", "not loaded"): ("Hytale did not load this file, so it is missing in game.",
                                   "Read the other messages about this file: they say why."),
    ("all-loaded", "none loaded"): ("Hytale loaded none of your work, so the whole mod is missing in game.", SEND),
    ("server-says", "refused"): ("Hytale refuses to start with your work, because some files cannot load.",
                                 "Fix each file listed here. Each has its own message in this panel."),
    ("server-says", "failed to load"): ("Hytale cannot load some of your files, so they are missing in game.",
                                        "Fix each file listed here. Each has its own message in this panel."),
}
SERVER_SAYS = [
    (re.compile(r"^Unused key: (?P<key>\S+)$"), "Hytale ignores its field {key}, so it does nothing.",
     "Check the spelling of {key}, or remove it."),
    (re.compile(r"^Asset '(?P<name>[^']+)' of type \S+ doesn't exist!$"),
     "It names {name}, which does not exist, so Hytale cannot load this file.",
     "Open it and pick a name HyCreator suggests in place of {name}."),
    (re.compile(r"^\[LOC\] Key '(?P<key>[^']+)' does not exist in server\.lang!$"),
     "It uses the name {key}, which has no English text, so players see a raw name.", LANGUAGES),
]


def game_words(f: dict) -> dict:
    """One finding of the game check as Lady reads it, in the same shape as the gates' lines."""
    d = f.get("detail") or {}
    fields = {k: (", ".join(map(str, v)) if isinstance(v, list) else v) for k, v in d.items()}
    form = d.get("form")
    words = GAME_WORDS.get((f["rule"], form, d.get("field"))) or GAME_WORDS.get((f["rule"], form)) \
        or GAME_WORDS.get((f["rule"], None))
    if f["rule"] == "server-says" and form in ("warning", "error"):
        for pattern, says, do in SERVER_SAYS:
            m = pattern.match(d.get("field") or "")
            if m:
                words = (says, do)
                fields.update(m.groupdict())
                break
        else:
            words = None
    path = f.get("file") or ""
    items = [{"label": f["id"], "open": path[len("/Server/"):]}] if path.startswith("/Server/") and path.endswith(".json") \
        else ([{"label": f["id"]}] if f["kind"] not in ("Server", "ServerLog", "ServerFinding") else [])
    if f["rule"] == "server-says" and form in ("refused", "failed to load"):
        items = [{"label": i} for i in d.get("files") or []]
    if not words:
        return {"says": "Hytale found something the checks cannot explain yet.", "do": SEND, "items": items,
                "line": f["fact"], "known": False, "detail": f["fact"]}
    try:
        says, do = (w.format(**fields) for w in words)
    except (KeyError, IndexError):
        return {"says": "Hytale found something the checks cannot explain yet.", "do": SEND, "items": items,
                "line": f["fact"], "known": False, "detail": f["fact"]}
    return {"says": says, "do": do, "items": items, "line": f["fact"], "known": True}


def finding_key(f: dict) -> str:
    d = f.get("detail") or {}
    return "|".join(str(x) for x in (f["rule"], f["kind"], f["id"], d.get("field"), d.get("form"), d.get("name"),
                                      d.get("word")))


def run_snapshot(out: str, game: str, packs: list[str], jar: str | None) -> tuple[bool, str]:
    cmd = [sys.executable, os.path.join(GAME_DIR, "snapshot.py"), "--out", out, "--game", game]
    if jar:
        cmd += ["--mod", jar]
    for p in packs:
        cmd += ["--pack", p]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=1200,
                           stdin=subprocess.DEVNULL, creationflags=NO_WINDOW,
                           env=dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONDONTWRITEBYTECODE="1"))
    except (OSError, subprocess.TimeoutExpired) as exc:
        return False, str(exc)
    return r.returncode == 0, (r.stdout + r.stderr).strip()


def game_findings(vanilla: str, modded: str) -> list[dict]:
    if GAME_DIR not in sys.path:
        sys.path.insert(0, GAME_DIR)
    import model
    import rules
    pair = model.Pair(model.Snapshot(vanilla), model.Snapshot(modded))
    out = []
    for rule in rules.RULES:
        for f in rule.run(pair):
            d = dict(f.detail)
            # Only her mod's own: the engine's own loading order and the showcase world are not hers. And only what a
            # player meets: a file that differs from the game's own with no proven effect, or a plain copy of another
            # file, is not a problem to fix.
            if d.get("owner") or any(re.search(p, f.id) or re.search(p, f.file or "") for p, _ in MADE_BY_TOOL):
                continue
            if not rules.effect_proven(f):
                continue
            out.append({"rule": f.rule, "kind": f.kind, "id": f.id, "file": f.file, "fact": f.fact, "detail": d})
    return out


def check_game(own: list[str] | None, starts: list[str], why: str, game: str) -> dict:
    """What her work changed, as the game loads it: findings present now and absent where her branch started."""
    if own is None:
        return {"status": "error", "problems": [], "output_tail": [why]}
    changed = bool(git("status", "--porcelain", "--", "src/main/resources"))
    if not own and not changed:
        return {"status": "pass", "problems": [], "output_tail": []}
    if not os.path.isfile(os.path.join(GAME_DIR, "snapshot.py")) or not os.path.isfile(CODE_JAR):
        return {"status": "error", "problems": [], "output_tail": ["the game check's files are missing"]}
    sys.path.insert(0, GAME_DIR)
    import snapshot
    version = snapshot.read_version(os.path.join(game, "Server", "HytaleServer.jar")) or "unknown"
    os.makedirs(WORK, exist_ok=True)
    vanilla = os.path.join(WORK, f"vanilla-{version}")
    if not os.path.isfile(os.path.join(vanilla, "run.json")):
        ok, text = run_snapshot(vanilla, game, [], None)
        if not ok:
            return {"status": "error", "problems": [], "output_tail": text.splitlines()[-6:]}
    # What her branch started from, computed once per starting commit, game version and rules.
    rules_id = git("hash-object", os.path.join(GAME_DIR, "rules.py")) or "?"
    base_keys: set[str] = set()
    for start in starts:
        cache = os.path.join(WORK, f"base-{start[:12]}-{version}-{rules_id[:12]}.json")
        if not os.path.isfile(cache):
            keys = base_findings(start, game, vanilla)
            if keys is None:
                return {"status": "error", "problems": [], "output_tail": [f"the start {start[:12]} could not be read"]}
            write_json(cache, {"keys": sorted(keys)})
        with open(cache, encoding="utf-8") as fh:
            base_keys |= set(json.load(fh)["keys"])
    now = os.path.join(WORK, "now")
    ok, text = run_snapshot(now, game, [RESOURCES], CODE_JAR)
    if not ok:
        return {"status": "error", "problems": [], "output_tail": text.splitlines()[-6:]}
    new = [f for f in game_findings(vanilla, now) if finding_key(f) not in base_keys]
    lady = [game_words(f) for f in new]
    return {"status": "fail" if new else "pass", "problems": [f["fact"] for f in new], "lady": lady, "output_tail": []}


def base_findings(start: str, game: str, vanilla: str) -> set[str] | None:
    """The findings at a commit: its files and its code jar, booted like her own work."""
    import io
    import tarfile
    import tempfile
    with tempfile.TemporaryDirectory(dir=WORK) as tmp:
        # Read from git's output, never from a file git writes: the Python a Windows store installs keeps a folder
        # it creates under AppData to itself, and git cannot see it.
        r = subprocess.run(["git", "-C", REPO, "archive", "--format=tar", start, "src/main/resources",
                            "Studio/Tools/TheArmory-code.jar"], capture_output=True, stdin=subprocess.DEVNULL,
                           creationflags=NO_WINDOW)
        if r.returncode != 0:
            return None
        with tarfile.open(fileobj=io.BytesIO(r.stdout)) as tar:
            tar.extractall(tmp, filter="data")
        snap = os.path.join(tmp, "snapshot")
        ok, _ = run_snapshot(snap, game, [os.path.join(tmp, "src", "main", "resources")],
                             os.path.join(tmp, "Studio", "Tools", "TheArmory-code.jar"))
        if not ok:
            return None
        return {finding_key(f) for f in game_findings(vanilla, snap)}


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
        result["error"] = "The mod's files were not found. " + SEND
        result["error_detail"] = RESOURCES
    elif not game:
        result["error"] = "Hytale's files were not found. Start Hytale once, then commit again."
    else:
        for name, title in GATES:
            entry = {"id": name, "title": title}
            entry.update(run_gate(name, game))
            result["checks"].append(entry)
        own, starts, why = own_work()
        result["own_commits"] = None if own is None else len(own)
        for name, title, outcome in (
            ("branch", "Your branch", check_branch()),
            ("languages", "Language files", check_languages(own, starts, why)),
            ("generated", "Files our tools make", check_generated(own, starts, why)),
            ("game", "Your work in the game", check_game(own, starts, why, game)),
        ):
            entry = {"id": name, "title": title, "seconds": None}
            entry.update(outcome)
            result["checks"].append(entry)
        lady_view(result["checks"])
    result["finished"] = now()
    result["seconds"] = round(time.monotonic() - clock, 1)
    return result


def write_json(path: str, value: dict) -> None:
    temp = path + ".tmp"
    with open(temp, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(value, fh, indent=1, ensure_ascii=False)
        fh.write("\n")
    os.replace(temp, path)


def groups(result: dict) -> list[dict]:
    """Every problem of every check, the ones that say the same thing together, the largest first."""
    out: dict[tuple[str, str], dict] = {}
    for c in result["checks"]:
        for e in c.get("lady") or []:
            g = out.setdefault((e["says"], e["do"]), {"says": e["says"], "do": e["do"], "items": [], "count": 0, "details": []})
            g["count"] += 1
            g["items"] += [i["label"] for i in e["items"] if i["label"] not in g["items"]]
            if e.get("detail"):
                g["details"].append(e["detail"])
    return sorted(out.values(), key=lambda g: -max(g["count"], len(g["items"])))


def when(stamp: str | None) -> str:
    try:
        return datetime.datetime.fromisoformat(stamp).strftime("%d %b, %H:%M").lstrip("0")
    except (TypeError, ValueError):
        return str(stamp or "")


def summary(result: dict) -> tuple[str, bool]:
    head = "The Armory checks, " + when(result["finished"])
    if result["error"]:
        detail = result.get("error_detail")
        return head + "\n" + result["error"] + (f"\nFor Larsonix: {detail}" if detail else ""), False
    found = groups(result)
    if not found:
        return head + ": everything is fine.", True
    total = len(found)
    lines = [head, f"{total} thing{'s' if total != 1 else ''} to fix.", ""]
    for g in found:
        many = len(g["items"]) > 1
        lines.append((f"{len(g['items'])} items: " if many else (g["items"][0] + ": " if g["items"] else "")) + g["says"])
        lines.append("  To fix: " + g["do"])
        if many:
            shown = ", ".join(g["items"][:10]) + (f" and {len(g['items']) - 10} more" if len(g["items"]) > 10 else "")
            lines.append("  " + shown)
        for detail in g["details"]:
            lines.append("  For Larsonix: " + detail.replace("\n", "\n    "))
        lines.append("")
    lines.append("Everything else is fine.")
    return "\n".join(lines).rstrip(), False


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
                      "error": "The checks stopped on something unexpected. " + SEND,
                      "error_detail": f"{type(exc).__name__}: {exc}"}
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
