"""Test check.py the way Lady meets it, in a throwaway clone of this repository.

  python check_test.py            everything, needs the installed game
  python check_test.py --words    only what Lady reads: the gates' strings and their translation

First, what Lady reads (the words test below). Then the clone gets a branch of its own made from this one, the way her branch is made, and must pass
every check. Then one mistake per check is committed there, each the kind the mod has really
received, and every check must name its own. Then the branch is switched to main, which must be
named too. Last, a run started the way the git hooks start one must leave its result behind.
Nothing outside the throwaway folder is touched. Needs the installed game.
"""
from __future__ import annotations

import json
import os
import re
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


# ---- what Lady reads ------------------------------------------------------------------------------
#
# The gates are byte copies of ours and print for us. check.py translates every line they print into
# what she reads. These two lists keep that true: every string the gates hold, as it stood when the
# translator was written, and one real line of each kind they print as a problem. A gate that changes
# fails here until its new lines have a rule in check.py and a sample below.

def gate_strings() -> list[str]:
    """Every string of three words or more in the copied gates, each value written as {}."""
    import ast
    out = []
    for gate, _title in check.GATES:
        tree = ast.parse(open(os.path.join(HERE, gate + ".py"), encoding="utf-8").read())
        docs, inner = set(), set()
        for n in ast.walk(tree):
            if isinstance(n, (ast.Module, ast.FunctionDef, ast.ClassDef)) and n.body and isinstance(n.body[0], ast.Expr):
                docs.add(id(n.body[0].value))
            if isinstance(n, ast.JoinedStr):
                inner.update(id(v) for v in n.values)
            if isinstance(n, ast.BinOp) and isinstance(n.op, ast.Mod):
                inner.add(id(n.left))
        for n in ast.walk(tree):
            if id(n) in docs or id(n) in inner:
                continue
            if isinstance(n, ast.Constant) and isinstance(n.value, str):
                s = n.value
            elif isinstance(n, ast.JoinedStr):
                s = "".join(v.value if isinstance(v, ast.Constant) else "{}" for v in n.values)
            elif isinstance(n, ast.BinOp) and isinstance(n.op, ast.Mod) and isinstance(n.left, ast.Constant) and isinstance(n.left.value, str):
                s = re.sub(r"%[-0-9.]*[sdfr]", "{}", n.left.value)
            else:
                continue
            if len(s.split()) >= 3 and f"{gate}\t{s}" not in out:
                out.append(f"{gate}\t{s}")
    return sorted(out)


GATE_STRINGS = [
    'assetcheck\t\n== does every modelled prop actually have collision ==',
    'assetcheck\t\n== every hitbox id referenced by our items resolves ==',
    'assetcheck\t\n== every tooltip line our items produce has a name ==',
    'assetcheck\t\n== hitboxes we added ==',
    'assetcheck\t   {} texture {}x{}',
    'assetcheck\t== models and their textures ==',
    'assetcheck\tFAILED, {} problem(s):',
    'assetcheck\tScribe_Lock (held, shipped)',
    'assetcheck\tall checks passed',
    'assetcheck\tnot a png',
    'assetcheck\tper-part coverage skipped, hitbox-fit not importable ({})',
    'assetcheck\tstat {} is defined neither by us nor by the game',
    "assetcheck\ttooltip names: the installed game's client.lang or Assets.zip is unreadable",
    'assetcheck\ttooltip names: we ship no en-US client.lang',
    'assetcheck\ttooltip names: {} item files, {} distinct tooltip lines, {} languages',
    'assetcheck\t{} client.lang lacks {}, needed by {} and others',
    'assetcheck\t{} client.lang lacks {}, which en-US has',
    'assetcheck\t{} client.lang: {} has no {value} slot, so its amount never shows',
    "assetcheck\t{} references {} '{}' which we do not ship",
    'assetcheck\t{}: Ymin is {}, it dips below its own floor and the block will not place',
    'assetcheck\t{}: all {} parts at 95%% collision coverage or better',
    'assetcheck\t{}: box {} has a non-positive {} extent, Box.CODEC will reject it',
    'assetcheck\t{}: model or texture missing',
    'assetcheck\t{}: no boxes, the codec requires a non-empty array',
    'assetcheck\t{}: {} boxes, footprint {}x{}x{} = {} cells, Y from {}',
    'assetcheck\t{}: {} face(s) fall outside the {}x{} sheet (worst {} px). REPORT ONLY, this formula still disagrees with 14 of 64 shipped pairs',
    'assetcheck\t{}: {} of {} parts under 95%% collision coverage: {}',
    'dupkeycheck\tDelete the EARLIER block. The engine keeps the last one, so the survivor is the',
    'dupkeycheck\tREPEATS DIFFER, a value is being dropped',
    'dupkeycheck\tcontrol failed: a clean document was reported as duplicated',
    'dupkeycheck\tcontrol failed: a planted duplicate was not detected, results are worthless',
    'dupkeycheck\tcontrol failed: identical repeats were reported as differing',
    'dupkeycheck\tdupkeycheck: no duplicate keys',
    'dupkeycheck\tdupkeycheck: {} json and patch files scanned under {}',
    'dupkeycheck\tdupkeycheck: {} problem(s)',
    'dupkeycheck\tidentical repeat, silent edit trap',
    'dupkeycheck\tno such directory: {}',
    'dupkeycheck\tone already in effect, and the parsed result of the file does not change.',
    'dupkeycheck\t{"Armor": {"StatModifiers": {"Mana": [{"Amount": 25}], "Health": [{"Amount": 1}]}}}',
    'dupkeycheck\t{"Armor": {"StatModifiers": {"Mana": [{"Amount": 25}], "Mana": [{"Amount": 25}]}}}',
    'dupkeycheck\t{}: does not parse, {}',
    'dupkeycheck\t{}: duplicate key {} ({})',
    'dupkeycheck\t{}: unreadable, {}',
    'familycheck\t    owning items: {}',
    'familycheck\t    profile {}: {}',
    'familycheck\tACCEPTED: {}: {}: {}',
    'familycheck\tCyclops, Demon and Glutton are deliberate stat identities; splitting would orphan 12 helms until the mod owner supplies recipes.',
    'familycheck\tFAIL: stale accepted violation: {}: {}: {}',
    'familycheck\tFAIL: {}: {}: {}',
    'familycheck\tThe same amulet model is worn at the neck or on the head; splitting would orphan 4 items until the mod owner supplies recipes.',
    'familycheck\tThe same robe is worn hood-up as Head or hood-down as Chest; splitting would orphan 34 items until the mod owner supplies recipes.',
    'familycheck\tcannot append non-list patch field {}',
    'familycheck\tdrop directory not found at {}',
    'familycheck\tduplicate mod item id {} in {} and {}',
    'familycheck\tduplicate vanilla item id {}',
    'familycheck\tfamilycheck: FAILED: {} failure(s), {} live accepted violation(s)',
    'familycheck\tfamilycheck: PASS: all families closed and reachable; {} live accepted violation(s)',
    'familycheck\tfamilycheck: cannot complete scan: {}',
    'familycheck\tfamilycheck: {} alteration families, {} reachable item links, {} dropped item ids',
    'familycheck\tfamilycheck: {} vanilla items, {} mod items, {} item patches',
    'familycheck\titem directory not found at {}',
    'familycheck\titem document is not an object: {}',
    'familycheck\titem patch target does not exist: {}',
    'familycheck\tmissing parent item {} in chain {}',
    'familycheck\tno owning item has a non-alteration recipe or appears in Server/Drops',
    'familycheck\tparent cycle: {}',
    'familycheck\tresource tree to scan (defaults to the mod resource tree)',
    'familycheck\tshipped Assets.zip not found at {}',
    'familycheck\tthe family no longer violates this check',
    'familycheck\tunknown check name',
    'familycheck\t{} distinct profiles across {} reachable items',
    'familycheck\t{} distinct slots across {} reachable items',
    'familycheck\t{}: Armor is not an object',
    'familycheck\t{}: Parent is not a string',
    'itemcheck\tCustomModel {} exists in neither the mod nor the game',
    'itemcheck\tCustomModelTexture {} exists in neither the mod nor the game',
    'itemcheck\tit belongs to the family {}, which has no family file',
    'itemcheck\tit is in {} but not {}, so other mods cannot find it',
    'itemcheck\tit plays the {} {}, which exists in neither the mod nor the game',
    'itemcheck\titem directory not found at {}',
    'itemcheck\titem sound set',
    'itemcheck\titemcheck: FAILED: {} problem(s), {} note(s)',
    'itemcheck\titemcheck: PASS: everything every item names exists, {} note(s), {} model(s) with faces partly off their picture',
    'itemcheck\titemcheck: cannot complete scan: {}',
    'itemcheck\titemcheck: {} item files, {} model and picture pairs',
    'itemcheck\tits Categories is not a list',
    'itemcheck\tits item sound set {} does not exist',
    'itemcheck\tits quality {} does not exist',
    'itemcheck\tits recipe asks for the family {}, which has no family file',
    'itemcheck\tits recipe asks for the item {}, which does not exist',
    'itemcheck\tits {} changes the stat {}, which does not exist',
    'itemcheck\tnot a png',
    "itemcheck\tthe game's Assets.zip not found at {}",
    'itemcheck\t{} {} exists in neither the mod nor the game',
    'itemcheck\t{}: cannot be read as JSON, so the game never loads it ({})',
    'itemcheck\t{}: is not a JSON object, so the game never loads it',
    'itemcheck\t{}: its model {} cannot be read ({})',
    'itemcheck\t{}: its texture {} is not a PNG picture',
    'itemcheck\t{}: {} face(s) partly outside {} ({}x{})',
    'lootcheck\tdrop directory not found at {}',
    'lootcheck\tlootcheck: FAILED: {} problem(s)',
    'lootcheck\tlootcheck: PASS: every item and drop list a loot table names exists',
    'lootcheck\tlootcheck: cannot complete scan: {}',
    'lootcheck\tlootcheck: {} drop files, against {} items and {} drop lists',
    "lootcheck\tthe game's Assets.zip not found at {}",
    'lootcheck\t{}: cannot be read as JSON, so the game never loads it ({})',
    'lootcheck\t{}: it drops nothing at all, so the game refuses the whole file',
    'lootcheck\t{}: it drops the item {}, which does not exist',
    'lootcheck\t{}: it names the drop list {}, which does not exist',
    'lootcheck\t{}: it puts the drop list {} in chests, but {} does not exist',
    'recipecheck\t (another bench route works)',
    'recipecheck\t and no family file',
    'recipecheck\t vanilla recipes, for scale: ',
    "recipecheck\tDeveloper quality test item since 1.15.0, borrowing the iron sword's name and model. Its recipe names no bench, so nobody can craft it, which is right for a test item.",
    'recipecheck\ta StructuralCrafting recipe must have exactly one input, it has {}',
    "recipecheck\tbench '{}' is declared by no item",
    'recipecheck\tbench {} declares no category {}',
    'recipecheck\tbench {} has {} tier(s), the recipe needs tier {}',
    'recipecheck\tbench {} is a {} bench, the recipe asks for {}',
    'recipecheck\tbench, the recipe asks',
    'recipecheck\tdeclared by no item',
    'recipecheck\tdeclares no category',
    'recipecheck\tdirectory not found: {}',
    'recipecheck\tduplicate mod item id {}',
    'recipecheck\tduplicate vanilla item id {}',
    'recipecheck\texactly one input',
    'recipecheck\tinput family {} has no English name, the bench shows server.resourceType.{}.name',
    'recipecheck\tinput family {} has no member item',
    'recipecheck\tinput family {} holds only {} armour, the recipe makes {} armour',
    'recipecheck\tinput item {} does not exist',
    'recipecheck\tinput {} names no item, family or tag',
    'recipecheck\titem patch target does not exist: {}',
    'recipecheck\tno block grants the augment tag {}',
    'recipecheck\toutput item {} does not exist',
    'recipecheck\tpatched recipe {}',
    'recipecheck\trecipe patch target does not exist: {}',
    'recipecheck\trecipecheck: FAILED: {} problem(s), {} note(s)',
    'recipecheck\trecipecheck: PASS: every mod recipe resolves, {} note(s)',
    'recipecheck\trecipecheck: bench rules over ',
    'recipecheck\trecipecheck: cannot complete scan: {}',
    'recipecheck\trecipecheck: {} mod recipes, {} items ({} mod), {} families with members, {} benches',
    'recipecheck\tresource tree to scan (defaults to the mod resource tree)',
    'recipecheck\tvanilla item {}',
    'recipecheck\tvanilla recipe {}',
    'recipecheck\t{}: ACCEPTED: {}',
    'recipecheck\t{}: operator {} on a non-list field {}',
    'recipecheck\t{}: operator {} on {} is not modelled by this check',
    'recipecheck\t{}: stale accepted entry, the recipe no longer fails (reason was: {})',
]

# One line of each kind the gates print as a problem, as problems_in hands it over, and what Lady
# reads for it. Most come from her branch's real result of 2026-10-26.
SAMPLES = [
    ("itemcheck", "Server/Item/Items/A/B.json: cannot be read as JSON, so the game never loads it (Expecting value: line 1 column 1 (char 0))", "typing mistake"),
    ("itemcheck", "Server/Item/Items/A/B.json: is not a JSON object, so the game never loads it", "typing mistake"),
    ("itemcheck", "Server/Item/Items/A/B.json: Model Items/Armors/X/Chest.blockymodel exists in neither the mod nor the game", "Its model Items/Armors/X/Chest.blockymodel does not exist."),
    ("itemcheck", "Server/Item/Items/A/B.json: Texture Items/Armors/X/Red.png exists in neither the mod nor the game", "Its texture Items/Armors/X/Red.png does not exist."),
    ("itemcheck", "Server/Item/Items/A/B.json: Icon Icons/ItemsGenerated/B.png exists in neither the mod nor the game", "Generate icon"),
    ("itemcheck", "Server/Item/Items/A/B.json: CustomModel Blocks/X.blockymodel exists in neither the mod nor the game", "block model"),
    ("itemcheck", "Server/Item/Items/A/B.json: CustomModelTexture Blocks/X.png exists in neither the mod nor the game", "block texture"),
    ("itemcheck", "Server/Item/Items/A/B.json: its recipe asks for the item Ingredient_Bar_Ghost, which does not exist", "needs the item Ingredient_Bar_Ghost"),
    ("itemcheck", "Server/Item/Items/A/B.json: its recipe asks for the family Resource_Ghost, which has no family file", "resource type Resource_Ghost"),
    ("itemcheck", "Server/Item/Items/A/B.json: it belongs to the family Resource_Ghost, which has no family file", "ResourceTypes names Resource_Ghost"),
    ("itemcheck", "Server/Item/Items/A/B.json: it plays the sound SFX_Ghost, which exists in neither the mod nor the game", "so it plays nothing"),
    ("itemcheck", "Server/Item/Items/A/B.json: it plays the particle Ghost_Puff, which exists in neither the mod nor the game", "so it shows nothing"),
    ("itemcheck", "Server/Item/Items/A/B.json: it plays the camera effect Ghost_Shake, which exists in neither the mod nor the game", "so it does nothing"),
    ("itemcheck", "Server/Item/Items/A/B.json: its item sound set ISS_Ghost does not exist", "ISS_Ghost does not exist, so it makes no sound"),
    ("itemcheck", "Server/Item/Items/A/B.json: its quality Ghostly does not exist", "quality Ghostly"),
    ("itemcheck", "Server/Item/Items/Praetorian_Boots.json: its Armor changes the stat Volatility, which does not exist", "Uses the stat Volatility"),
    ("itemcheck", "Server/Item/Items/Praetorian_Boots.json: it is in ArmoryCategory.ArmoryArmors but not Items.Armors, so other mods cannot find it", "Missing the category Items.Armors"),
    ("itemcheck", "Server/Item/Items/A/B.json: its Categories is not a list", "not a list"),
    ("itemcheck", "Server/Item/Items/A/B.json: its texture Items/X.png is not a PNG picture", "not a PNG"),
    ("itemcheck", "Server/Item/Items/A/B.json: its model Items/X.blockymodel cannot be read (Expecting value)", "cannot be read"),
    ("recipecheck", "item X: no inputs", "takes no ingredient"),
    ("recipecheck", "item X: input item Y does not exist", "needs the item Y"),
    ("recipecheck", "item X: input family Resource_Y has no English name, the bench shows server.resourceType.Resource_Y.name", "no English name"),
    ("recipecheck", "item X: input family Resource_Y has no member item", "no item has that resource type"),
    ("recipecheck", "item X: input family Resource_Y has no member item and no family file", "no item has that resource type"),
    ("recipecheck", "item Armor_Thorium_Head_Black: input family Resource_Thorium_Hands holds only Hands armour, the recipe makes Head armour", "Its recipe takes Hands armor (Resource_Thorium_Hands), but this is Head armor."),
    ("recipecheck", "item X: input {'Quantity': 1} names no item, family or tag", "an ingredient with no item"),
    ("recipecheck", "recipe X: output item Y does not exist", "makes the item Y"),
    ("recipecheck", "item X: bench 'Ghost_Bench' is declared by no item", "the bench Ghost_Bench"),
    ("recipecheck", "item X: bench Workbench is a Crafting bench, the recipe asks for StructuralCrafting", "but it is a Crafting bench"),
    ("recipecheck", "item X: bench Workbench has 3 tier(s), the recipe needs tier 5", "tier 5 of Workbench"),
    ("recipecheck", "item X: no block grants the augment tag Ghost", "bench upgrade Ghost"),
    ("recipecheck", "item X: a StructuralCrafting recipe must have exactly one input, it has 2", "takes 2 ingredients"),
    ("recipecheck", "item Weapon_Dualswords_Iron: stale accepted entry, the recipe no longer fails (reason was: test)", "needs updating"),
    ("recipecheck", "patched recipe X: input item Y does not exist", "needs the item Y"),
    ("familycheck", "Resource_Thorium_Hands: STAT CLOSURE: 2 distinct profiles across 18 reachable items profile 1: {\"BaseDamageResistance\":0} items: A_1, A_2 profile 2: {\"BaseDamageResistance\":1} items: B_1", "2 different armor stats"),
    ("familycheck", "Resource_Thorium_Hands: SLOT CLOSURE: 2 distinct slots across 18 reachable items profile 1: \"Hands\" items: A_1, A_2 profile 2: \"Head\" items: B_1", "2 different slots"),
    ("familycheck", "Resource_PraetorianBoots: REACHABILITY: no owning item has a non-alteration recipe or appears in Server/Drops owning items: Praetorian_Boots, Praetorian_Boots_Gray", "No player can get these items"),
    ("familycheck", "stale accepted violation: DemonHelm: STAT CLOSURE: the family no longer violates this check reason: x", "needs updating"),
    ("dupkeycheck", "Server\\Item\\Items\\A\\B.json: duplicate key Armor.StatModifiers.Mana (identical repeat, silent edit trap)", "an edit to the first does nothing"),
    ("dupkeycheck", "Server\\Item\\Items\\A\\B.json: duplicate key Quality (REPEATS DIFFER, a value is being dropped)", "with different values"),
    ("dupkeycheck", "Server\\Item\\Items\\A\\B.json: does not parse, Expecting ',' delimiter", "typing mistake"),
    ("dupkeycheck", "Server\\Item\\Items\\A\\B.json: unreadable, [Errno 13] Permission denied", "cannot be opened"),
    ("assetcheck", "stat Volatility is defined neither by us nor by the game", "raw name"),
    ("assetcheck", "es-ES client.lang lacks itemTooltip.stats.TA_Swim_Speed, needed by X.json and others", "no es-ES name"),
    ("assetcheck", "es-ES client.lang lacks itemTooltip.stats.X, which en-US has", "no es-ES name"),
    ("assetcheck", "en-US client.lang: itemTooltip.stats.X has no {value} slot, so its amount never shows", "never shows its amount"),
    ("assetcheck", "Bench_X.json references HitboxType 'ArmoryGhost' which we do not ship", "hitbox ArmoryGhost"),
    ("assetcheck", "AlterationTable: model or texture missing", "A bench or tooltip of the mod is broken."),
    ("lootcheck", "Server/Drops/TheArmory/Armory_Zone1_Loot.json: cannot be read as JSON, so the game never loads it (Expecting ',' delimiter: line 12 column 5 (char 210))", "fix line 12 in Notepad"),
    ("lootcheck", "Server/Drops/TheArmory/Armory_Zone1_Loot.json: it drops the item Mace_Iron_Rustyy, which does not exist", "pick an existing item in place of Mace_Iron_Rustyy"),
    ("lootcheck", "Server/Drops/Prefabs/Zone1_Goblin_Tier1.patch: it puts the drop list Armory_Zone1_Loot in chests, but Armory_Zone1_Loot does not exist", "Name one of your chest loot tables Armory_Zone1_Loot again."),
    ("lootcheck", "Server/Drops/ScrapArmorDrops.json: it names the drop list Ghost_Drops, which does not exist", "names the table Ghost_Drops"),
    ("lootcheck", "Server/Drops/TheArmory/Armory_Zone1_Loot.json: it drops nothing at all, so the game refuses the whole file", "add an item"),
]


def words(expect) -> None:
    now = gate_strings()
    added = sorted(set(now) - set(GATE_STRINGS))
    gone = sorted(set(GATE_STRINGS) - set(now))
    expect(f"the gates print the {len(GATE_STRINGS)} strings the translation was written against", not added and not gone,
           "new: " + " | ".join(added[:5]) + "  gone: " + " | ".join(gone[:5]))
    wrong = []
    for gate, line, part in SAMPLES:
        e = check.for_lady(gate, line)
        if not e["known"] or part not in e["says"] + " " + e["do"]:
            wrong.append(f"{gate}: {line[:70]} -> {e['says']} / {e['do']}")
    expect(f"each of the {len(SAMPLES)} kinds of problem line reads as Lady's words", not wrong, "\n      ".join(wrong))
    planted = check.for_lady("itemcheck", "Server/Item/Items/A/B.json: a sentence no rule knows")
    expect("a line no rule knows is kept whole for Larsonix, never dropped",
           not planted["known"] and planted["detail"].endswith("a sentence no rule knows") and planted["do"] == check.SEND, planted)
    item = check.for_lady("itemcheck", SAMPLES[13][1])
    expect("an item named by its file opens it", item["items"] == [{"label": "B", "open": "Item/Items/A/B.json"}], item["items"])
    dup = check.for_lady("dupkeycheck", SAMPLES[40][1])
    expect("a file named with Windows separators opens it", dup["items"] == [{"label": "B", "open": "Item/Items/A/B.json"}], dup["items"])
    reach = check.for_lady("familycheck", SAMPLES[37][1])
    expect("an unreachable resource type lists its items, not itself",
           [i["label"] for i in reach["items"]] == ["Praetorian_Boots", "Praetorian_Boots_Gray"], reach["items"])
    stat = check.for_lady("familycheck", SAMPLES[35][1])
    expect("a resource type with two stat profiles lists the items of both", [i["label"] for i in stat["items"]] == ["A_1", "A_2", "B_1"], stat["items"])
    chests = check.for_lady("lootcheck", SAMPLES[51][1])
    expect("a chest file our tools write is never offered to open", chests["items"] == [], chests["items"])
    table = check.for_lady("lootcheck", SAMPLES[50][1])
    expect("her loot table opens", table["items"] == [{"label": "Armory_Zone1_Loot", "open": "Drops/TheArmory/Armory_Zone1_Loot.json"}],
           table["items"])

    # One typing mistake stops three gates, which repeat the parser's message. She reads one problem.
    parse = "Expecting ',' delimiter: line 7 column 3 (char 146)"
    typo = [
        {"id": "itemcheck", "title": "Item files", "status": "fail",
         "problems": [f"Server/Item/Items/A/B.json: cannot be read as JSON, so the game never loads it ({parse})"]},
        {"id": "recipecheck", "title": "Recipes", "status": "error", "problems": [],
         "output_tail": [f"recipecheck: cannot complete scan: {parse}"]},
        {"id": "assetcheck", "title": "Benches and tooltips", "status": "error", "problems": [],
         "output_tail": ["Traceback (most recent call last):", f"json.decoder.JSONDecodeError: {parse}"]},
        {"id": "dupkeycheck", "title": "Fields written twice", "status": "fail",
         "problems": [f"Server\\Item\\Items\\A\\B.json: does not parse, {parse}"]},
        {"id": "familycheck", "title": "Alteration Table", "status": "error", "problems": [],
         "output_tail": ["familycheck: cannot complete scan: something else entirely"]},
    ]
    check.lady_view(typo)
    found = check.groups({"checks": typo})
    says = [g["says"] for g in found]
    expect("a typing mistake that stops other checks is one problem, and a check stopped by something else still shows",
           len(found) == 2 and says[0] == "Has a typing mistake at line 7, so Hytale skips this file. Some checks wait until it is fixed."
           and "Alteration Table" in says[1], says)

    # Her branch's shape: a stat named by two gates, and a recipe making helmets from gloves.
    checks = [
        {"id": "itemcheck", "title": "Item files", "status": "fail", "problems": [SAMPLES[15][1]]},
        {"id": "recipecheck", "title": "Recipes", "status": "fail", "problems": [SAMPLES[25][1]]},
        {"id": "familycheck", "title": "Alteration Table", "status": "fail", "problems": [SAMPLES[35][1], SAMPLES[36][1]]},
        {"id": "dupkeycheck", "title": "Fields written twice", "status": "pass", "problems": []},
        {"id": "assetcheck", "title": "Benches and tooltips", "status": "fail", "problems": [SAMPLES[43][1]]},
        {"id": "branch", "title": "Your branch", "status": "error", "problems": [], "output_tail": ["boom"]},
    ]
    check.lady_view(checks)
    by = {c["id"]: c["lady"] for c in checks}
    expect("a stat the item check names is not named again by the tooltip check", by["assetcheck"] == [], by["assetcheck"])
    expect("a resource type mixed up by a recipe asks only for the recipe fix, never for equal stats",
           by["familycheck"] == [] and len(by["recipecheck"]) == 1, by["familycheck"])
    expect("a check that could not run says so and hands off", len(by["branch"]) == 1 and by["branch"][0]["do"] == check.SEND
           and by["branch"][0]["detail"] == "boom", by["branch"])
    thrice = [{"id": "dupkeycheck", "title": "Fields written twice", "status": "fail", "problems": [
        "Server\\Item\\Items\\A\\B.json: duplicate key ItemSoundSetId (REPEATS DIFFER, a value is being dropped)",
        "Server\\Item\\Items\\A\\B.json: duplicate key ItemSoundSetId (identical repeat, silent edit trap)",
        "Server\\Item\\Items\\A\\C.json: duplicate key Quality (identical repeat, silent edit trap)"]}]
    check.lady_view(thrice)
    says = [e["says"] for e in thrice[0]["lady"]]
    expect("a field written three times is one message, not two", len(says) == 2 and "written 3 times, with different values" in says[0]
           and "written twice" in says[1], says)
    text, ok = check.summary({"finished": "2026-10-26T12:40:00+01:00", "error": None, "checks": checks})
    expect("the window counts things to fix, not lines", not ok and "3 things to fix." in text, text)
    expect("the window never shows a gate's own words", not any(w in text for w in ("CLOSURE", "REACHABILITY", "family", "armour")), text)


def main(argv: list[str]) -> int:
    failures = []

    def expect(label: str, ok: bool, detail="") -> None:
        print(("ok    " if ok else "WRONG ") + label + ("" if ok else f"\n      {detail}"))
        if not ok:
            failures.append(label)

    words(expect)
    if argv == ["--words"]:
        print(f"check_test: {len(failures)} failure(s)")
        return 1 if failures else 0
    if not check.find_game():
        print("check_test: cannot run, the installed game was not found")
        return 2

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
        # A hand edit to a patch the loot tool writes, which puts her loot tables in chests.
        drops = os.path.join(res, "Server", "Drops", "Prefabs")
        loot = os.path.join(drops, sorted(n for n in os.listdir(drops) if n.endswith(".patch"))[0])
        edit(loot, lambda t: t + "\n")
        # Her own loot table, edited the way she now may, with one item misspelled.
        table = os.path.join(res, "Server", "Drops", "TheArmory", "Armory_Zone1_Loot.json")
        edit(table, lambda t: t.replace('"ItemId": "Mace_Iron_Rusty"', '"ItemId": "Mace_Iron_Rustyy"', 1))
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
        expect("the edited chest patch is named, and her edited loot table is not", checks["generated"]["status"] == "fail"
               and any(os.path.basename(loot) in p for p in checks["generated"]["problems"])
               and not any("Armory_Zone1_Loot" in p for p in checks["generated"]["problems"]), checks["generated"])
        expect("the misspelled chest item is named", checks["lootcheck"]["status"] == "fail"
               and any("Mace_Iron_Rustyy" in p for p in checks["lootcheck"]["problems"]), checks["lootcheck"])
        expect("the checks with nothing planted still pass",
               all(checks[k]["status"] == "pass" for k in ("recipecheck", "familycheck", "assetcheck", "branch")),
               {k: checks[k]["status"] for k in checks})

        # Something no check expects, a language file that is not text, must still leave a result
        # saying the run stopped, never the last one standing in for it.
        with open(lang, "ab") as fh:
            fh.write(b"\xff\xfe broken\n")
        code, result = run(clone)
        expect("an unexpected failure still writes a result that says so",
               code == 1 and bool(result["error"]) and "unexpected" in result["error"], result.get("error"))
        git(clone, "checkout", "-q", "--", "src/main/resources/Server/Languages")

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
    sys.exit(main(sys.argv[1:]))
