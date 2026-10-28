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


# One finding of each kind the game check reports, as game/rules.py makes it, and what Lady reads.
GAME_SAMPLES = [
    ({"rule": "names-resolve", "kind": "Item", "id": "Praetorian_Boots", "file": "/Server/Item/Items/Praetorian_Boots.json",
      "fact": "x", "detail": {"field": "Armor.StatModifiers", "name": "Volatility", "form": "key"}}, "names Volatility"),
    ({"rule": "like-its-kind", "kind": "BlockType", "id": "Scribing_Table", "file": "/Server/Item/Items/Scribing_Table.json",
      "fact": "x", "detail": {"field": "ParticleColor", "form": "unset", "effect": "the world map draws it white"}},
     "It has no ParticleColor (under BlockType), so the world map draws it white"),
    ({"rule": "like-its-kind", "kind": "BlockType", "id": "Dice_D6", "file": "",
      "fact": "x", "detail": {"field": "BlockSoundSetId", "form": "default", "effect": "it makes no sound when placed or broken"}},
     "keeps the base BlockSoundSetId (under BlockType), so it makes no sound"),
    ({"rule": "like-its-kind", "kind": "ResourceType", "id": "RookEliteHelm", "file": "/Server/Item/ResourceTypes/R.json",
      "fact": "x", "detail": {"field": "Icon", "form": "unset", "effect": "the crafting window shows an empty square for it"}},
     "icon of the most basic item"),
    ({"rule": "like-its-siblings", "kind": "Item", "id": "Praetorian_Boots_Gray", "file": "/Server/Item/Items/B.json",
      "fact": "x", "detail": {"field": "SoundEventId", "set": "Praetorian"}}, "the other items of its set have"),
    ({"rule": "like-its-siblings", "kind": "Item", "id": "RookEliteBootsCharred", "file": "",
      "fact": "x", "detail": {"field": "Armor.X", "form": "value", "value": 19, "others": 15}}, "is 19, where"),
    ({"rule": "foreign-family", "kind": "Item", "id": "BramblekinBracers", "file": "/Server/Item/Items/B.json", "fact": "x",
      "detail": {"field": "ResourceTypes", "form": "foreign", "name": "Resource_Armor_Iron_Hands", "of": "Armor_Iron_Hands"}},
     "beside Resource_Armor_Iron_Hands, click Remove element"),
    ({"rule": "reachable", "kind": "Item", "id": "Praetorian_Helm", "file": "", "fact": "x",
      "detail": {"form": "item", "makers": []}}, "No player can get it"),
    ({"rule": "reachable", "kind": "Item", "id": "Armor_Thorium_Chest_Black", "file": "", "fact": "x",
      "detail": {"form": "base outside", "makers": [], "base": "Armor_Thorium_Chest", "name": "Resource_Thorium_Chest"}},
     "the vanilla Armor_Thorium_Chest, does not have its resource type yet"),
    ({"rule": "reachable", "kind": "CraftingRecipe", "id": "R", "file": "", "fact": "x",
      "detail": {"form": "recipe", "output": "A"}}, "can never be crafted"),
    ({"rule": "names-and-pictures", "kind": "Item", "id": "Goblin_Skirt", "file": "", "fact": "x",
      "detail": {"field": "name", "form": "no text"}}, "no English name"),
    ({"rule": "names-and-pictures", "kind": "Item", "id": "A", "file": "", "fact": "x",
      "detail": {"field": "name", "form": "slot word", "word": "chestplate", "slot": "Head"}}, "its name says chestplate"),
    ({"rule": "names-and-pictures", "kind": "Item", "id": "A", "file": "", "fact": "x",
      "detail": {"field": "icon", "form": "shared", "with": ["B"]}}, "shares its icon"),
    ({"rule": "names-and-pictures", "kind": "Item", "id": "A", "file": "", "fact": "x",
      "detail": {"field": "name", "form": "shared", "with": ["B"]}}, "the same name"),
    ({"rule": "common-files", "kind": "CommonFile", "id": "Icons/X.png", "file": "Icons/X.png", "fact": "x",
      "detail": {"field": "replace", "form": "different"}}, "replaces that file"),
    ({"rule": "all-loaded", "kind": "Item", "id": "Mirror_Shield", "file": "/Server/Item/Items/M.json", "fact": "x",
      "detail": {"field": "file", "form": "not loaded"}}, "did not load this file"),
    ({"rule": "all-loaded", "kind": "Mod", "id": "x.jar", "file": "", "fact": "x",
      "detail": {"field": "files", "form": "none loaded", "count": 9}}, "loaded none of your work"),
    ({"rule": "server-says", "kind": "Server", "id": "refuses to start", "file": "", "fact": "x",
      "detail": {"field": "boot", "form": "refused", "files": ["Mirror_Shield"]}}, "refuses to start"),
    ({"rule": "server-says", "kind": "ServerFinding", "id": "Mirror_Shield", "file": "/Server/Item/Items/M.json", "fact": "x",
      "detail": {"field": "Asset 'Mirror_Distortion_System_Glow' of type com.x.ParticleSystem doesn't exist!",
                 "form": "error"}}, "names Mirror_Distortion_System_Glow"),
    ({"rule": "server-says", "kind": "ServerFinding", "id": "S", "file": "/Server/Item/Interactions/S.json", "fact": "x",
      "detail": {"field": "Unused key: Config.Particles.0.ParticleColor", "form": "warning"}}, "ignores its field"),
    ({"rule": "server-says", "kind": "ServerFinding", "id": "M", "file": "/Server/Item/Items/M.json", "fact": "x",
      "detail": {"field": "[LOC] Key 'server.items.Wyrm_Shield.name' does not exist in server.lang!", "form": "warning"}},
     "no English text"),
    # The language file spells its keys without server. in front, so that is what she types.
    ({"rule": "server-says", "kind": "ServerFinding", "id": "M", "file": "/Server/Item/Items/M.json", "fact": "x",
      "detail": {"field": "[LOC] Key 'server.items.Wyrm_Shield.name' does not exist in server.lang!", "form": "warning"}},
     "type items.Wyrm_Shield.name and its English text"),
    ({"rule": "names-and-pictures", "kind": "Item", "id": "Goblin_Skirt", "file": "", "fact": "x",
      "detail": {"field": "name", "form": "no text"}}, "click add translation beside its Name"),
    ({"rule": "names-and-pictures", "kind": "Item", "id": "Relentless", "file": "", "fact": "x",
      "detail": {"field": "name", "form": "shared", "with": ["B"]}}, "then the Use button that appears"),
    ({"rule": "like-its-siblings", "kind": "Item", "id": "StandingArmorVerdan", "file": "", "fact": "x",
      "detail": {"field": "Interactions.Secondary.Interactions.*.{Next, Type}"}}, "Its right click works differently"),
    ({"rule": "like-its-siblings", "kind": "Item", "id": "Voidhood_Goblin", "file": "", "fact": "x",
      "detail": {"field": "Armor.StatModifiers.Mana.*.Amount", "form": "value", "value": 18.0, "others": 5.0}},
     "check its Amount (under Armor, StatModifiers, Mana)"),
    ({"rule": "like-its-kind", "kind": "Item", "id": "KanohiHau", "file": "", "fact": "x",
      "detail": {"field": "PlayerAnimationsId", "form": "default", "effect": "x"}}, "If the row shows Reference existing, click it first"),
    ({"rule": "like-its-kind", "kind": "BlockType", "id": "Dice_D6", "file": "", "fact": "x",
      "detail": {"field": "BlockParticleSetId", "form": "unset", "effect": "x"}}, "the button that shows the unset fields"),
    ({"rule": "names-resolve", "kind": "Item", "id": "X", "file": "", "fact": "x",
      "detail": {"field": "Interactions.Primary.Interactions.*.Effects.WorldSoundEventId", "name": "SFX_Ghost", "form": "value"}},
     "Open JSON. Change SFX_Ghost to a name that exists"),
]


# A whole snapshot's log: one server, one verdict. And the real damage of 2026-10-27: two servers wrote one log, and a
# record starts inside another.
WHOLE_LOG = ("[2026/10/27 19:09:26   INFO] [Hytale] Loading NPC assets phase...\n"
             "[2026/10/27 19:09:40   INFO] CHECKS snapshot complete=true assets=1\n")
WRITTEN_OVER = ("[2026/10/27 19:09:29   WARN] [NPC|P] Replacing asset 'Test[2026/10/27 19:09:29   WARN] [NPC|P] Replacing\n"
                "[2026/10/27 19:09:40   INFO] CHECKS snapshot complete=true assets=1\n")


def snapshot_at(folder: str, made: str, log: str = WHOLE_LOG) -> None:
    os.makedirs(os.path.join(folder, "stores"), exist_ok=True)
    with open(os.path.join(folder, "run.json"), "w", encoding="utf-8") as fh:
        json.dump({"complete": True, "made": made}, fh)
    with open(os.path.join(folder, "boot.log"), "w", encoding="utf-8") as fh:
        fh.write(log)


def made_by(folder: str) -> str:
    with open(os.path.join(folder, "run.json"), encoding="utf-8") as fh:
        return json.load(fh).get("made")


def own_folders(expect) -> None:
    """Two game check runs at once, or one cut off halfway, never stop the next one (the shared snapshot folder did)."""
    work = tempfile.mkdtemp(prefix="armory-work-")
    saved = check.WORK
    check.WORK = work
    try:
        # What a run cut off halfway left behind: a shared folder with no run.json, and its own folder, long stale.
        os.makedirs(os.path.join(work, "now", "server"))
        stale = os.path.join(work, "now.4242")
        os.makedirs(os.path.join(stale, "server"))
        os.utime(stale, (time.time() - check.STALE_SECONDS - 60,) * 2)
        live = os.path.join(work, "now.4343")
        os.makedirs(live)
        mine = check.own_folder("now")
        expect("a run snapshots into a folder of its own", os.path.basename(mine) == f"now.{os.getpid()}")
        expect("a folder left by a run cut off long ago is removed", not os.path.exists(stale))
        expect("a folder of a run still going is left alone", os.path.isdir(live))
        snapshot_at(mine, "now")
        kept = check.keep_as(mine, os.path.join(work, "now"), replace=True)
        expect("the finished snapshot replaces the half-written shared folder", kept == os.path.join(work, "now")
               and os.path.isfile(os.path.join(work, "now", "run.json")) and not os.path.exists(mine))
        # The game alone, already made by another run: this run's own copy is dropped and theirs is read.
        van = os.path.join(work, "vanilla-x")
        snapshot_at(van, "first")
        other = check.own_folder("vanilla-x")
        snapshot_at(other, "second")
        kept = check.keep_as(other, van, replace=False)
        expect("a finished game-alone snapshot made first by another run is kept, this run's dropped",
               kept == van and made_by(van) == "first" and not os.path.exists(other))
        # The game alone, written by two servers at once: its log is cut, so this run's whole one takes its place.
        snapshot_at(van, "written over", log=WRITTEN_OVER)
        other = check.own_folder("vanilla-x")
        snapshot_at(other, "whole")
        kept = check.keep_as(other, van, replace=False)
        expect("a game-alone snapshot whose log two servers wrote gives way to this run's whole one",
               kept == van and made_by(van) == "whole" and not os.path.exists(other))
    finally:
        check.WORK = saved
        shutil.rmtree(work, ignore_errors=True)


def words(expect) -> None:
    wrong = []
    for finding, part in GAME_SAMPLES:
        e = check.game_words(finding)
        if not e["known"] or part not in e["says"] + " " + e["do"]:
            wrong.append(f"{finding['rule']} {finding['detail'].get('form')} -> {e['says']} / {e['do']}")
    expect(f"each of the {len(GAME_SAMPLES)} kinds of game finding reads as Lady's words", not wrong, "\n      ".join(wrong))
    odd = check.game_words({"rule": "server-says", "kind": "ServerLog", "id": "x", "file": "", "fact": "a new line",
                            "detail": {"field": "a new line", "form": "log"}})
    expect("a game finding no text knows is kept whole for Larsonix", not odd["known"] and odd["do"] == check.SEND
           and odd["detail"] == "a new line", odd)
    sys.path.insert(0, check.GAME_DIR)
    import owners
    tool_files = ["Server/Item/Items/Armory_Showcase/X.json", "Server/Models/Showcase_Theatre/Y.json",
                  "Server/Drops/Prefabs/Zone1_Goblin_Tier1.patch"]
    wrong = [p for p in tool_files if check.made_by(p) and owners.owner_of(file=p)[0] != "us"]
    expect("every file our tools rewrite is ours in the owner table", not wrong and all(check.made_by(p) for p in tool_files),
           str(wrong))
    stat = check.for_lady("itemcheck", "Server/Item/Items/Praetorian_Boots.json: its Armor changes the stat Volatility, which does not exist")
    quality = check.for_lady("itemcheck", "Server/Item/Items/A/B.json: its quality Ghostly does not exist")
    expect("a stat only code adds is Larsonix's, a missing quality is hers",
           check.owner_of_card("itemcheck", stat) == "us" and check.owner_of_card("itemcheck", quality) == "mod",
           f"{check.owner_of_card('itemcheck', stat)} {check.owner_of_card('itemcheck', quality)}")
    hitbox = check.for_lady("assetcheck", "Bench_X.json references HitboxType 'ArmoryGhost' which we do not ship")
    expect("a card that hands the work to Larsonix is his", check.owner_of_card("assetcheck", hitbox) == "us")
    game_stat = check.game_words({"rule": "names-resolve", "kind": "Item", "id": "Praetorian_Boots", "file": "", "fact": "x",
                                  "owner": "us", "detail": {"field": "Armor.StatModifiers", "name": "Volatility", "form": "key"}})
    expect("a game finding keeps the owner the table gave it", check.owner_of_card("game", game_stat) == "us")
    # One card per fact: the item check's missing stat and the family check's unreachable items fold into the game
    # check's card when it states the same thing, and stay whole when it does not.
    stat_line = "Server/Item/Items/Praetorian_Boots.json: its Armor changes the stat Volatility, which does not exist"
    other_stat = "Server/Item/Items/Praetorian_Boots.json: its Armor changes the stat Ghost_Stat, which does not exist"
    reach_line = ("Resource_PraetorianBoots: REACHABILITY: no owning item has a non-alteration recipe or appears in Server/Drops "
                  "owning items: Praetorian_Boots, Praetorian_Boots_Gray")
    checks = [{"id": "itemcheck", "lady": [check.for_lady("itemcheck", stat_line), check.for_lady("itemcheck", other_stat)]},
              {"id": "familycheck", "lady": [check.for_lady("familycheck", reach_line)]},
              {"id": "game", "lady": [
                  {"about": {"rule": "names-resolve", "id": "Praetorian_Boots", "form": "key", "name": "Volatility"}},
                  {"about": {"rule": "reachable", "id": "Praetorian_Boots", "form": "item", "name": None}},
                  {"about": {"rule": "reachable", "id": "Praetorian_Boots_Gray", "form": "item", "name": None}}]}]
    check.fold_game(checks)
    expect("a stat the game check names is one card, a stat it does not name stays",
           [e["line"] for e in checks[0]["lady"]] == [other_stat], [e["line"] for e in checks[0]["lady"]])
    expect("unreachable items the game check names are one card", checks[1]["lady"] == [], checks[1]["lady"])
    checks[2]["lady"].pop()
    checks[1]["lady"] = [check.for_lady("familycheck", reach_line)]
    check.fold_game(checks)
    expect("a family line naming an item the game check does not name stays whole", len(checks[1]["lady"]) == 1)
    order = check.groups({"checks": [{"lady": [
        {"says": "old and big", "do": "x", "items": [{"label": str(i)} for i in range(9)], "new": False},
        {"says": "new and small", "do": "x", "items": [{"label": "a"}], "new": True}]}]})
    expect("what her work added comes first, whatever its size", [g["says"] for g in order] == ["new and small", "old and big"],
           [g["says"] for g in order])
    # "Normal for me": one entry hides one problem on one item, never the same problem on another item.
    reach = check.for_lady("familycheck", reach_line)
    reach["key"], reach["owner"] = "familycheck|" + reach_line, "mod"
    stat = {"says": "Uses a stat", "do": "x", "items": [{"label": "Praetorian_Boots"}], "line": "s", "key": "game|stat",
            "owner": "us"}
    result = {"finished": "2026-10-27T18:00:00", "error": None,
              "checks": [{"id": "familycheck", "status": "fail", "lady": [reach]}, {"id": "game", "status": "fail", "lady": [stat]}]}
    marks = [{"key": reach["key"], "item": "Praetorian_Boots", "by": "Lady"},
             {"key": "familycheck|gone", "item": "Old_Item", "by": "Lady"}]
    result["normal"] = check.apply_normal(result["checks"], marks)
    mine = check.groups(result, "mod")
    expect("a problem judged normal on one item still shows on the other item",
           len(mine) == 1 and mine[0]["items"] == ["Praetorian_Boots_Gray"], mine)
    expect("a mark matching nothing any more is named, and the hidden ones are counted",
           [e["item"] for e in result["normal"]["stale"]] == ["Old_Item"] and result["normal"]["hidden"] == 1, result["normal"])
    _, ok = check.summary(result)
    expect("her own open problem fails the run", not ok)
    marks.append({"key": reach["key"], "item": "Praetorian_Boots_Gray", "by": "Lady"})
    result["normal"] = check.apply_normal(result["checks"], marks)
    text, ok = check.summary(result)
    expect("Larsonix's problems never fail her run, and the window names them as his",
           ok and "Larsonix's problems" in text and "Uses a stat" in text and "Hidden as normal for you: 2" in text, text)
    result["checks"].append({"id": "recipecheck", "status": "error", "lady": []})
    expect("a check that could not run still fails the run", not check.summary(result)[1])
    # Problems meant to be (accepted.json): taken off her cards for good, and a judgment matching nothing any more is a
    # card for us. A file that replaces a game file is judged by its hash, so a changed file is a problem again.
    picture = {"rule": "common-files", "kind": "CommonFile", "id": "Icons/A.png", "file": "Icons/A.png", "fact": "f",
               "detail": {"field": "replace", "form": "different", "hash": "h1"}}
    key = "game|" + check.finding_key(picture)
    expect("a replacement's key ends with its file's hash, and other keys are as before",
           key.endswith("|h1") and check.finding_key(dict(picture, detail={"field": "x"})).count("|") == 6, key)
    unreached = {"says": "No player can get it", "do": "x", "items": [{"label": "RookHelmGhost"}, {"label": "Ruined_Sword_Gold"}],
                 "line": "r", "key": "game|reach", "owner": "mod"}
    replaced = {"says": "Replaces a game file", "do": "x", "items": [{"label": "Icons/A.png"}], "line": "p", "key": key,
                "owner": "mod"}
    result = {"finished": "2026-10-28T18:00:00", "error": None,
              "checks": [{"id": "game", "status": "fail", "lady": [unreached, replaced]}]}
    accepted = [{"key": "game|reach", "item": "RookHelmGhost", "by": "Lady"}, {"key": key, "item": "Icons/A.png", "by": "Larsonix"}]
    result["accepted"] = check.apply_accepted(result["checks"], accepted)
    mine = check.groups(result, "mod")
    expect("an accepted problem leaves her cards, the same problem on another item stays",
           [g["items"] for g in mine] == [["Ruined_Sword_Gold"]] and result["accepted"]["accepted"] == 2, mine)
    text, _ = check.summary(result)
    expect("the window counts what is meant to be", "Meant to be, so not shown: 2." in text, text)
    changed = "game|" + check.finding_key(dict(picture, detail=dict(picture["detail"], hash="h2")))
    result = {"finished": "2026-10-28T18:00:00", "error": None, "checks": [{"id": "game", "status": "fail", "lady": [
        dict(replaced, key=changed)]}]}
    result["accepted"] = check.apply_accepted(result["checks"], accepted)
    expect("a changed file is her problem again, and the judgment that no longer matches is Larsonix's",
           [g["items"] for g in check.groups(result, "mod")] == [["Icons/A.png"]]
           and [g["items"] for g in check.groups(result, "us")] == [["RookHelmGhost", "Icons/A.png"]]
           and check.summary(result)[1] is False, check.groups(result))
    opened = check.game_words(GAME_SAMPLES[0][0])
    expect("a game finding about an item opens its file",
           opened["items"] == [{"label": "Praetorian_Boots", "open": "Item/Items/Praetorian_Boots.json"}], opened["items"])
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
    own_folders(expect)
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
        shutil.copytree(os.path.join(HERE, "game"), os.path.join(target, "game"), dirs_exist_ok=True,
                        ignore=shutil.ignore_patterns("__pycache__"))
        git(clone, "add", "Studio/Tools/Checks")
        git(clone, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-q", "--allow-empty",
            "-m", "The checks as they stand")
        # That commit is the test's setup, not her work, so it is pushed where her branch goes.
        git(clone, "update-ref", "refs/remotes/origin/feature/test-setup", "HEAD")

        code, result = run(clone)
        checks = by_id(result)
        # The checks that read the whole mod report what the branch already holds (since her October import, the
        # Praetorian stats and families), so a branch of her own is judged on what it adds, never on that.
        before = {k: set(v["problems"]) for k, v in checks.items()}
        own = ("branch", "languages", "generated", "showcase")
        expect("a branch made from ours shows no problem of its own, and no check stops",
               not result["error"] and all(checks[k]["status"] == "pass" for k in own)
               and all(v["status"] != "error" for v in checks.values()),
               json.dumps({k: (v["status"], v["problems"][:2], v["output_tail"][-3:]) for k, v in checks.items()}))
        start_game = checks.get("game", {})
        expect("the game check runs on a branch with nothing of its own, shows what the game finds, and marks nothing new",
               start_game.get("status") in ("pass", "fail") and start_game.get("new") == 0
               and not any(e.get("new") for e in start_game.get("lady", [])),
               json.dumps({k: start_game.get(k) for k in ("status", "new", "output_tail")})[:800])
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
        # A language file saved from an older copy: its last name is lost. The last one at most one file uses, so the
        # planted fault stays one fault whatever her file ends with (a description shared by 13 items once made it 13).
        lang = os.path.join(res, "Server", "Languages", "en-US", "server.lang")
        lost = None
        users: dict[str, int] = {}
        for dirpath, _dirs, files in os.walk(os.path.join(res, "Server")):
            for f in files:
                if f.endswith(".json"):
                    with open(os.path.join(dirpath, f), encoding="utf-8-sig", errors="replace") as fh:
                        for key in set(re.findall(r'"server\.([^"]+)"', fh.read())):
                            users[key] = users.get(key, 0) + 1

        def drop_last(text: str) -> str:
            nonlocal lost
            lines = text.splitlines(keepends=True)
            for i in range(len(lines) - 1, -1, -1):
                if "=" in lines[i] and not lines[i].lstrip().startswith("#") \
                        and users.get(lines[i].split("=", 1)[0].strip(), 0) <= 1:
                    lost = lines[i].split("=", 1)[0].strip()
                    return "".join(lines[:i] + lines[i + 1:])
            raise SystemExit("server.lang holds no name used by at most one file")
        edit(lang, drop_last)
        # A hand edit to a patch the loot tool writes, which puts her loot tables in chests.
        drops = os.path.join(res, "Server", "Drops", "Prefabs")
        loot = os.path.join(drops, sorted(n for n in os.listdir(drops) if n.endswith(".patch"))[0])
        edit(loot, lambda t: t + "\n")
        # A piece that stood on one figure of the showcase alone now stands nowhere: its stand wears another piece.
        stands: dict[str, list[str]] = {}
        for dirpath, _dirs, files in os.walk(os.path.join(res, "Server", "Prefabs", "Armory_Showcase")):
            for f in files:
                if f.endswith(".prefab.json"):
                    with open(os.path.join(dirpath, f), encoding="utf-8") as fh:
                        for block in json.load(fh)["blocks"]:
                            worn = ((block.get("components") or {}).get("Components") or {}).get(check.SHOWCASE_STAND) or {}
                            for v in worn.values():
                                stands.setdefault(v, []).append(os.path.join(dirpath, f))
        # Never the item the sound set was planted in: that one is rightly among her problems for the sound.
        planted = os.path.splitext(os.path.basename(rel_item))[0]
        unshown = sorted(i for i, at in stands.items() if len(at) == 1 and i.startswith("Atlantean") and i != planted)[0]
        other = sorted(i for i, at in stands.items() if len(at) > 1)[0]
        edit(stands[unshown][0], lambda t: t.replace(f'"{unshown}"', f'"{other}"', 1))
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
        showcase = checks["showcase"]
        expect("the piece no figure wears any more is named, as ours alone",
               showcase["status"] == "fail" and [e["items"][0]["label"] for e in showcase["lady"]] == [unshown]
               and all(e["owner"] == "us" for e in showcase["lady"]), showcase)
        expect("and it is not among her problems", not any(unshown in g["items"] for g in check.groups(result, "mod")),
               [g["says"] for g in check.groups(result, "mod") if unshown in g["items"]])
        expect("the misspelled chest item is named", checks["lootcheck"]["status"] == "fail"
               and any("Mace_Iron_Rustyy" in p for p in checks["lootcheck"]["problems"]), checks["lootcheck"])
        game = checks.get("game", {})
        expect("the game check names the item whose sound set does not exist, in her words",
               game.get("status") == "fail" and any(os.path.splitext(os.path.basename(rel_item))[0] in
                                                    [i["label"] for i in e["items"]] for e in game.get("lady", [])),
               json.dumps({k: game.get(k) for k in ("status", "problems", "output_tail")})[:1500])
        # Four changes were planted, and the branch's start already holds hundreds of findings. All of them show, and
        # only the lines her commit caused are new: a handful, listed first.
        new = [e for e in game.get("lady", []) if e.get("new")]
        expect("the game check marks only what her commit changed as new",
               0 < len(new) <= 12 and game.get("new") == len(new), [e["says"] for e in new])
        expect("and still shows every finding the start already held",
               len(game.get("problems", [])) >= len(start_game.get("problems", [])), (len(game.get("problems", [])),
                                                                                       len(start_game.get("problems", []))))
        expect("what is new comes first", all(e.get("new") for e in game.get("lady", [])[:len(new)]))
        # The comparison below can fail: the item check, where a sound set was planted, holds new lines.
        expect("the comparison with the branch's start sees what was planted",
               any("ISS_Ghost" in p for p in set(checks["itemcheck"]["problems"]) - before["itemcheck"]),
               sorted(set(checks["itemcheck"]["problems"]) - before["itemcheck"])[:3])
        quiet = ("recipecheck", "familycheck", "assetcheck", "branch")
        expect("the checks with nothing planted find nothing new and still run",
               all(checks[k]["status"] != "error" and set(checks[k]["problems"]) <= before[k] for k in quiet),
               {k: (checks[k]["status"], sorted(set(checks[k]["problems"]) - before[k])[:3]) for k in quiet})

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
