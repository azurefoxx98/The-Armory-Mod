# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# Checks that every recipe of the mod can really be crafted.

from __future__ import annotations
import argparse
import copy
import json
import os
from pathlib import PurePosixPath
import sys
import zipfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from familycheck import ASSETS, MODRES, read_json, resolve_armors
BUILTIN_BENCHES = {'Fieldcraft'}
ACCEPTED = {'item Weapon_Dualswords_Iron': "Developer quality test item since 1.15.0, borrowing the iron sword's name and model. Its recipe names no bench, so nobody can craft it, which is right for a test item."}
OPERATORS = ('++', '--', '+', '-', '?', '~')

def split_operator(key: str) -> tuple[str, str]:
    for op in OPERATORS:
        if key.endswith(op) and len(key) > len(op):
            return (key[:-len(op)], op)
    return (key, '')

def overlay(base: dict, patch: dict, where: str) -> dict:
    result = copy.deepcopy(base)
    for key, value in patch.items():
        plain, op = split_operator(key)
        if op == '':
            result[plain] = copy.deepcopy(value)
        elif op == '?':
            result.setdefault(plain, copy.deepcopy(value))
        elif op in ('+', '++', '-', '--'):
            current = result.get(plain) or []
            if not isinstance(current, list) or not isinstance(value, list):
                raise ValueError(f'{where}: operator {op} on a non-list field {plain}')
            result[plain] = current + value if op in ('+', '++') else value + current
        else:
            raise ValueError(f'{where}: operator {op} on {plain} is not modelled by this check')
    return result

def walk(root: str, suffix: str):
    for dirpath, _dirs, files in os.walk(root):
        for name in sorted(files):
            if name.endswith(suffix):
                yield os.path.join(dirpath, name)

def load(resources: str):
    item_root = os.path.join(resources, 'Server', 'Item', 'Items')
    recipe_root = os.path.join(resources, 'Server', 'Item', 'Recipes')
    type_root = os.path.join(resources, 'Server', 'Item', 'ResourceTypes')
    for path in (item_root, recipe_root, type_root):
        if not os.path.isdir(path):
            raise OSError(f'directory not found: {path}')
    items: dict[str, dict] = {}
    vanilla_recipes: dict[str, dict] = {}
    vanilla_types: set[str] = set()
    with zipfile.ZipFile(ASSETS) as archive:
        for name in archive.namelist():
            if not name.endswith('.json'):
                continue
            stem = PurePosixPath(name).stem
            if name.startswith('Server/Item/Items/'):
                if stem in items:
                    raise ValueError(f'duplicate vanilla item id {stem}')
                items[stem] = json.loads(archive.read(name).decode('utf-8-sig'))
            elif name.startswith('Server/Item/Recipes/'):
                vanilla_recipes[stem] = json.loads(archive.read(name).decode('utf-8-sig'))
            elif name.startswith('Server/Item/ResourceTypes/'):
                vanilla_types.add(stem)
    vanilla_ids = set(items)
    local: set[str] = set()
    for path in walk(item_root, '.json'):
        stem = os.path.splitext(os.path.basename(path))[0]
        if stem in local:
            raise ValueError(f'duplicate mod item id {stem}')
        local.add(stem)
        items[stem] = read_json(path)
    for path in walk(item_root, '.patch'):
        stem = os.path.splitext(os.path.basename(path))[0]
        if stem not in items:
            raise ValueError(f'item patch target does not exist: {path}')
        items[stem] = overlay(items[stem], read_json(path), path)
    recipes: list[tuple[str, dict, str | None]] = []
    for stem in sorted(local):
        recipe = items[stem].get('Recipe')
        if isinstance(recipe, dict) and recipe:
            recipes.append((f'item {stem}', recipe, stem))
    for path in walk(recipe_root, '.json'):
        stem = os.path.splitext(os.path.basename(path))[0]
        recipe = read_json(path)
        out = (recipe.get('PrimaryOutput') or {}).get('ItemId')
        recipes.append((f'recipe {stem}', recipe, out))
    for path in walk(recipe_root, '.patch'):
        stem = os.path.splitext(os.path.basename(path))[0]
        if stem not in vanilla_recipes:
            raise ValueError(f'recipe patch target does not exist: {path}')
        recipe = overlay(vanilla_recipes[stem], read_json(path), path)
        out = (recipe.get('PrimaryOutput') or {}).get('ItemId')
        recipes.append((f'patched recipe {stem}', recipe, out))
    mod_types = {os.path.splitext(os.path.basename(p))[0] for p in walk(type_root, '.json')}
    LANG_KEYS.clear()
    LANG_KEYS.update(lang_keys(os.path.join(resources, 'Server', 'Languages', 'en-US', 'server.lang')))
    vanilla_item_recipes = [(f'vanilla item {i}', items[i]['Recipe'], i) for i in sorted(vanilla_ids) if i not in local and isinstance(items[i].get('Recipe'), dict) and items[i]['Recipe']] + [(f'vanilla recipe {k}', v, (v.get('PrimaryOutput') or {}).get('ItemId')) for k, v in sorted(vanilla_recipes.items())]
    return (items, local, recipes, vanilla_item_recipes, mod_types | vanilla_types)
LANG_KEYS: set[str] = set()

def lang_keys(mod_lang: str) -> set[str]:
    keys: set[str] = set()
    texts = []
    if os.path.isfile(mod_lang):
        texts.append(open(mod_lang, encoding='utf-8-sig').read())
    with zipfile.ZipFile(ASSETS) as archive:
        texts.append(archive.read('Server/Languages/en-US/server.lang').decode('utf-8-sig'))
    for text in texts:
        for line in text.splitlines():
            if '=' in line and (not line.lstrip().startswith('#')):
                keys.add(line.split('=', 1)[0].strip())
    return keys

def resource_members(items: dict[str, dict]) -> dict[str, set[str]]:
    memo: dict[str, list] = {}

    def resolve(item_id: str, chain: tuple[str, ...]=()) -> list:
        if item_id in memo:
            return memo[item_id]
        if item_id in chain or item_id not in items:
            return []
        doc = items[item_id]
        if 'ResourceTypes' in doc:
            value = doc['ResourceTypes'] or []
        elif isinstance(doc.get('Parent'), str):
            value = resolve(doc['Parent'], chain + (item_id,))
        else:
            value = []
        memo[item_id] = value
        return value
    members: dict[str, set[str]] = {}
    for item_id in items:
        for row in resolve(item_id):
            if isinstance(row, dict) and isinstance(row.get('Id'), str):
                members.setdefault(row['Id'], set()).add(item_id)
    return members

def benches(items: dict[str, dict]) -> dict[str, list[dict]]:
    found: dict[str, list[dict]] = {}
    for item_id, doc in items.items():
        block = doc.get('BlockType')
        bench = block.get('Bench') if isinstance(block, dict) else None
        if isinstance(bench, dict) and isinstance(bench.get('Id'), str):
            found.setdefault(bench['Id'], []).append(dict(bench, _item=item_id))
    return found

def bench_problems(recipe: dict, bench_map: dict[str, list[dict]]) -> tuple[list[str], list[str]]:
    rows = recipe.get('BenchRequirement') or []
    routes = [route_problems(row, bench_map) for row in rows]
    working = [r for r in routes if not r[0]]
    notes = [n for r in routes for n in r[1]]
    if working or not rows:
        problems = []
        notes += [p + ' (another bench route works)' for r in routes for p in r[0]]
    else:
        problems = [p for r in routes for p in r[0]]
    if any((r.get('Type') == 'StructuralCrafting' for r in rows)) and len(recipe.get('Input') or []) != 1:
        problems.append(f"a StructuralCrafting recipe must have exactly one input, it has {len(recipe.get('Input') or [])}")
    return (problems, notes)

def granted_augments(items: dict[str, dict]) -> set[str]:
    found: set[str] = set()
    pending = list(items.values())
    while pending:
        node = pending.pop()
        if isinstance(node, dict):
            tags = node.get('GrantsAugmentTags')
            if isinstance(tags, list):
                found.update((t for t in tags if isinstance(t, str)))
            pending.extend(node.values())
        elif isinstance(node, list):
            pending.extend(node)
    return found

def route_problems(row: dict, bench_map: dict[str, list[dict]]) -> tuple[list[str], list[str]]:
    problems, notes = ([], [])
    bid, btype = (row.get('Id', ''), row.get('Type', 'Crafting'))
    if bid in BUILTIN_BENCHES:
        return (problems, notes)
    declared = bench_map.get(bid)
    if not declared:
        return ([f"bench '{bid}' is declared by no item"], notes)
    typed = [b for b in declared if b.get('Type') == btype]
    if not typed:
        have = sorted({str(b.get('Type')) for b in declared})
        return ([f"bench {bid} is a {'/'.join(have)} bench, the recipe asks for {btype}"], notes)
    cats = {c.get('Id') if isinstance(c, dict) else c for b in typed for c in b.get('Categories') or []}
    if btype != 'DiagramCrafting':
        for cat in row.get('Categories') or []:
            if cat not in cats:
                notes.append(f'bench {bid} declares no category {cat}')
    tier = row.get('RequiredTierLevel') or 0
    top = max((len(b.get('TierLevels') or []) for b in typed))
    if tier > max(top, 1):
        problems.append(f'bench {bid} has {top} tier(s), the recipe needs tier {tier}')
    for tag in row.get('RequiredAugmentTags') or []:
        if tag not in AUGMENTS:
            problems.append(f'no block grants the augment tag {tag}')
    return (problems, notes)
AUGMENTS: set[str] = set()

def check(resources: str) -> tuple[list[str], list[str], dict]:
    items, local, recipes, vanilla_recipes, type_files = load(resources)
    AUGMENTS.clear()
    AUGMENTS.update(granted_augments(items))
    members = resource_members(items)
    bench_map = benches(items)
    armor_of = resolve_armors(items)

    def slot(item_id: str):
        try:
            armor = armor_of(item_id)
        except ValueError:
            return None
        return armor.get('ArmorSlot') if isinstance(armor, dict) else None
    vanilla_counts: dict[str, int] = {}
    for _label, recipe, _out in vanilla_recipes:
        problems, notes = bench_problems(recipe, bench_map)
        for p in problems + notes:
            kind = rule_kind(p)
            vanilla_counts[kind] = vanilla_counts.get(kind, 0) + 1
    failures, all_notes, accepted_live = ([], [], set())
    for label, recipe, out in recipes:
        problems = []
        inputs = recipe.get('Input') or []
        if not inputs:
            problems.append('no inputs')
        for row in inputs:
            if 'ItemId' in row:
                if row['ItemId'] not in items:
                    problems.append(f"input item {row['ItemId']} does not exist")
            elif 'ResourceTypeId' in row:
                fam = row['ResourceTypeId']
                bench_types = {b.get('Type', 'Crafting') for b in recipe.get('BenchRequirement') or []}
                if bench_types != {'StructuralCrafting'} and f'resourceType.{fam}.name' not in LANG_KEYS:
                    problems.append(f'input family {fam} has no English name, the bench shows server.resourceType.{fam}.name')
                if not members.get(fam):
                    problems.append(f'input family {fam} has no member item' + ('' if fam in type_files else ' and no family file'))
                elif out and slot(out):
                    slots = {slot(m) for m in members[fam]}
                    if None not in slots and slot(out) not in slots:
                        problems.append(f"input family {fam} holds only {'/'.join(sorted(slots))} armour, the recipe makes {slot(out)} armour")
            elif 'ItemTag' not in row:
                problems.append(f'input {row} names no item, family or tag')
        outs = [recipe.get('PrimaryOutput')] + list(recipe.get('Output') or [])
        for row in outs:
            if isinstance(row, dict) and 'ItemId' in row and (row['ItemId'] not in items):
                problems.append(f"output item {row['ItemId']} does not exist")
        bench_fail, bench_notes = bench_problems(recipe, bench_map)
        all_notes += [f'{label}: {n}' for n in bench_notes]
        if problems + bench_fail:
            if label in ACCEPTED:
                accepted_live.add(label)
                all_notes += [f'{label}: ACCEPTED: {p}' for p in problems + bench_fail]
            else:
                failures += [f'{label}: {p}' for p in problems + bench_fail]
    for label in sorted(set(ACCEPTED) - accepted_live):
        failures.append(f'{label}: stale accepted entry, the recipe no longer fails (reason was: {ACCEPTED[label]})')
    stats = {'items': len(items), 'mod_items': len(local), 'recipes': len(recipes), 'vanilla_recipes': len(vanilla_recipes), 'families': len(members), 'benches': len(bench_map)}
    return (failures, all_notes, vanilla_counts, stats)

def rule_kind(message: str) -> str:
    for marker, kind in (('declared by no item', 'missing bench'), ('bench, the recipe asks', 'bench type'), ('declares no category', 'category (note)'), ('tier(s)', 'tier'), ('exactly one input', 'structural inputs'), ('augment tag', 'augment')):
        if marker in message:
            return kind
    return 'other'

def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('resources', nargs='?', default=MODRES, help='resource tree to scan (defaults to the mod resource tree)')
    args = ap.parse_args(argv)
    try:
        failures, notes, vanilla_counts, stats = check(os.path.abspath(args.resources))
    except (OSError, UnicodeError, ValueError, json.JSONDecodeError, zipfile.BadZipFile) as exc:
        print(f'recipecheck: cannot complete scan: {exc}')
        return 2
    print(f"recipecheck: {stats['recipes']} mod recipes, {stats['items']} items ({stats['mod_items']} mod), {stats['families']} families with members, {stats['benches']} benches")
    print('recipecheck: bench rules over ' + str(stats['vanilla_recipes']) + ' vanilla recipes, for scale: ' + (', '.join((f'{k} {v}' for k, v in sorted(vanilla_counts.items()))) or 'none fire'))
    for line in notes:
        print(f'NOTE: {line}')
    for line in failures:
        print(f'FAIL: {line}')
    if failures:
        print(f'recipecheck: FAILED: {len(failures)} problem(s), {len(notes)} note(s)')
        return 1
    print(f'recipecheck: PASS: every mod recipe resolves, {len(notes)} note(s)')
    return 0
if __name__ == '__main__':
    sys.exit(main())
