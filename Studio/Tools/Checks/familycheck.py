# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# Checks that every Alteration Table family has one set of stats, one slot and a way in.

from __future__ import annotations
import argparse
import copy
import json
import os
from pathlib import PurePosixPath
import sys
import zipfile
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODRES = os.environ.get('ARMORY_RESOURCES') or os.path.join(ROOT, 'The-Armory-Mod', 'src', 'main', 'resources')
GAME = os.environ.get('ARMORY_GAME') or os.path.join(os.environ.get('APPDATA', ''), 'Hytale', 'install', 'release', 'package', 'game', 'latest')
ASSETS = os.path.join(GAME, 'Assets.zip')
STAT_CLOSURE = 'STAT CLOSURE'
SLOT_CLOSURE = 'SLOT CLOSURE'
REACHABILITY = 'REACHABILITY'
CHECKS = (STAT_CLOSURE, SLOT_CLOSURE, REACHABILITY)
ACCEPTED_VIOLATIONS = {('DemonHelm', STAT_CLOSURE): 'Cyclops, Demon and Glutton are deliberate stat identities; splitting would orphan 12 helms until the mod owner supplies recipes.', ('GrandLichHood', SLOT_CLOSURE): 'The same robe is worn hood-up as Head or hood-down as Chest; splitting would orphan 34 items until the mod owner supplies recipes.', ('Amulet', SLOT_CLOSURE): 'The same amulet model is worn at the neck or on the head; splitting would orphan 4 items until the mod owner supplies recipes.'}

class ResolutionError(ValueError):
    pass

def canonical(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))

def read_json(path: str):
    with open(path, encoding='utf-8-sig') as fh:
        return json.load(fh)

def shipped_items() -> dict[str, dict]:
    if not os.path.isfile(ASSETS):
        raise OSError(f'shipped Assets.zip not found at {ASSETS}')
    items: dict[str, dict] = {}
    with zipfile.ZipFile(ASSETS) as archive:
        for name in archive.namelist():
            if '/Item/Items/' not in f'/{name}' or not name.endswith('.json'):
                continue
            item_id = PurePosixPath(name).stem
            document = json.loads(archive.read(name).decode('utf-8-sig'))
            if item_id in items:
                raise ValueError(f'duplicate vanilla item id {item_id}')
            items[item_id] = document
    return items

def overlay(base: dict, patch: dict) -> dict:
    result = copy.deepcopy(base)
    for key, value in patch.items():
        if key.endswith('+'):
            plain = key[:-1]
            current = result.get(plain, [])
            if not isinstance(current, list) or not isinstance(value, list):
                raise ValueError(f'cannot append non-list patch field {key}')
            result[plain] = copy.deepcopy(current) + copy.deepcopy(value)
        else:
            result[key] = copy.deepcopy(value)
    return result

def load_items(item_root: str) -> tuple[dict[str, dict], set[str], int]:
    effective = shipped_items()
    local: dict[str, dict] = {}
    patches: dict[str, dict] = {}
    paths: dict[tuple[str, str], str] = {}
    for dirpath, _dirnames, filenames in os.walk(item_root):
        for name in sorted(filenames):
            kind = '.json' if name.endswith('.json') else '.patch' if name.endswith('.patch') else None
            if kind is None:
                continue
            item_id = os.path.splitext(name)[0]
            path = os.path.join(dirpath, name)
            key = (kind, item_id)
            if key in paths:
                raise ValueError(f'duplicate mod item id {item_id} in {paths[key]} and {path}')
            paths[key] = path
            document = read_json(path)
            if not isinstance(document, dict):
                raise ValueError(f'item document is not an object: {path}')
            (local if kind == '.json' else patches)[item_id] = document
    effective.update(local)
    for item_id, patch in patches.items():
        if item_id not in effective:
            raise ValueError(f'item patch target does not exist: {item_id}')
        effective[item_id] = overlay(effective[item_id], patch)
    return (effective, set(local), len(patches))

def alteration_families(items: dict[str, dict]) -> dict[str, set[str]]:
    families: dict[str, set[str]] = {}
    for item_id, document in items.items():
        recipe = document.get('Recipe')
        if not isinstance(recipe, dict):
            continue
        benches = {row.get('Id') for row in recipe.get('BenchRequirement', []) or [] if isinstance(row, dict)}
        if 'ArmoryAlteration' not in benches:
            continue
        for row in recipe.get('Input', []) or []:
            family = row.get('ResourceTypeId') if isinstance(row, dict) else None
            if isinstance(family, str):
                families.setdefault(family, set()).add(item_id)
    return families

def family_owners(items: dict[str, dict]) -> dict[str, set[str]]:
    owners: dict[str, set[str]] = {}
    for item_id, document in items.items():
        for row in document.get('ResourceTypes', []) or []:
            family = row.get('Id') if isinstance(row, dict) else None
            if isinstance(family, str):
                owners.setdefault(family, set()).add(item_id)
    return owners

def dropped_items(drop_root: str) -> set[str]:
    found: set[str] = set()
    for dirpath, _dirnames, filenames in os.walk(drop_root):
        for name in sorted(filenames):
            if not name.endswith(('.json', '.patch')):
                continue
            document = read_json(os.path.join(dirpath, name))
            pending = [document]
            while pending:
                node = pending.pop()
                if isinstance(node, dict):
                    item_id = node.get('ItemId')
                    if isinstance(item_id, str):
                        found.add(item_id)
                    pending.extend(node.values())
                elif isinstance(node, list):
                    pending.extend(node)
    return found

def resolve_armors(items: dict[str, dict]):
    memo: dict[str, dict | None] = {}

    def resolve(item_id: str, chain: tuple[str, ...]=()) -> dict | None:
        if item_id in memo:
            return memo[item_id]
        if item_id in chain:
            raise ResolutionError(f"parent cycle: {' -> '.join(chain + (item_id,))}")
        if item_id not in items:
            raise ResolutionError(f"missing parent item {item_id} in chain {' -> '.join(chain) or '<root>'}")
        document = items[item_id]
        parent_id = document.get('Parent')
        if parent_id is not None and (not isinstance(parent_id, str)):
            raise ResolutionError(f'{item_id}: Parent is not a string')
        if 'Armor' not in document:
            resolved = resolve(parent_id, chain + (item_id,)) if parent_id else None
        else:
            declared = document['Armor']
            if not isinstance(declared, dict):
                raise ResolutionError(f'{item_id}: Armor is not an object')
            resolved = copy.deepcopy(declared)
            if 'DamageClassEnhancement' not in resolved and parent_id:
                parent_armor = resolve(parent_id, chain + (item_id,))
                if parent_armor is not None and 'DamageClassEnhancement' in parent_armor:
                    resolved['DamageClassEnhancement'] = copy.deepcopy(parent_armor['DamageClassEnhancement'])
        memo[item_id] = resolved
        return resolved
    return resolve

def has_non_alteration_recipe(document: dict) -> bool:
    recipe = document.get('Recipe')
    if not isinstance(recipe, dict) or not recipe:
        return False
    benches = {row.get('Id') for row in recipe.get('BenchRequirement', []) or [] if isinstance(row, dict)}
    return 'ArmoryAlteration' not in benches

def profile_lines(groups: dict[str, list[str]], labels: dict[str, str]) -> list[str]:
    lines: list[str] = []
    for number, key in enumerate(sorted(groups), 1):
        lines.append(f'    profile {number}: {labels[key]}')
        lines.append(f"      items: {', '.join(sorted(groups[key]))}")
    return lines

def find_violations(resources: str):
    item_root = os.path.join(resources, 'Server', 'Item', 'Items')
    drop_root = os.path.join(resources, 'Server', 'Drops')
    if not os.path.isdir(item_root):
        raise OSError(f'item directory not found at {item_root}')
    if not os.path.isdir(drop_root):
        raise OSError(f'drop directory not found at {drop_root}')
    items, local_ids, patch_count = load_items(item_root)
    families = alteration_families(items)
    owners = family_owners(items)
    drops = dropped_items(drop_root)
    resolve = resolve_armors(items)
    violations: dict[tuple[str, str], list[str]] = {}
    for family in sorted(families):
        members = sorted(families[family])
        stat_groups: dict[str, list[str]] = {}
        stat_labels: dict[str, str] = {}
        slot_groups: dict[str, list[str]] = {}
        slot_labels: dict[str, str] = {}
        for item_id in members:
            armor = resolve(item_id)
            if armor is None:
                stat_key = 'absent Armor'
                stat_label = 'absent Armor'
                slot_key = 'absent Armor'
                slot_label = 'absent Armor'
            else:
                stats = {key: value for key, value in armor.items() if key not in ('ArmorSlot', 'CosmeticsToHide')}
                stat_key = canonical(stats)
                stat_label = stat_key
                if 'ArmorSlot' not in armor:
                    slot_key = 'unset'
                    slot_label = 'unset'
                else:
                    slot_key = 'value:' + canonical(armor['ArmorSlot'])
                    slot_label = canonical(armor['ArmorSlot'])
            stat_groups.setdefault(stat_key, []).append(item_id)
            stat_labels[stat_key] = stat_label
            slot_groups.setdefault(slot_key, []).append(item_id)
            slot_labels[slot_key] = slot_label
        if len(stat_groups) > 1:
            violations[family, STAT_CLOSURE] = [f'{len(stat_groups)} distinct profiles across {len(members)} reachable items', *profile_lines(stat_groups, stat_labels)]
        if len(slot_groups) > 1:
            violations[family, SLOT_CLOSURE] = [f'{len(slot_groups)} distinct slots across {len(members)} reachable items', *profile_lines(slot_groups, slot_labels)]
        entry_points = sorted((item_id for item_id in owners.get(family, set()) if has_non_alteration_recipe(items[item_id]) or item_id in drops))
        if not entry_points:
            owned = sorted(owners.get(family, set()))
            violations[family, REACHABILITY] = ['no owning item has a non-alteration recipe or appears in Server/Drops', f"    owning items: {(', '.join(owned) if owned else '<none>')}"]
    stats = {'vanilla_items': len(items) - len(local_ids), 'local_items': len(local_ids), 'patches': patch_count, 'families': len(families), 'reachable': sum((len(members) for members in families.values())), 'drops': len(drops)}
    return (violations, stats)

def report(resources: str, accepted=None) -> bool:
    if accepted is None:
        accepted = ACCEPTED_VIOLATIONS
    violations, stats = find_violations(resources)
    failures = 0
    accepted_live = 0
    print(f"familycheck: {stats['vanilla_items']} vanilla items, {stats['local_items']} mod items, {stats['patches']} item patches")
    print(f"familycheck: {stats['families']} alteration families, {stats['reachable']} reachable item links, {stats['drops']} dropped item ids")
    for key in sorted(violations):
        family, check = key
        detail = violations[key]
        if key in accepted:
            accepted_live += 1
            print(f'ACCEPTED: {family}: {check}: {detail[0]}')
            for line in detail[1:]:
                print(line)
            print(f'    reason: {accepted[key]}')
        else:
            failures += 1
            print(f'FAIL: {family}: {check}: {detail[0]}')
            for line in detail[1:]:
                print(line)
    for key in sorted(set(accepted) - set(violations)):
        family, check = key
        failures += 1
        if check not in CHECKS:
            extra = 'unknown check name'
        else:
            extra = 'the family no longer violates this check'
        print(f'FAIL: stale accepted violation: {family}: {check}: {extra}')
        print(f'    reason: {accepted[key]}')
    if failures:
        print(f'familycheck: FAILED: {failures} failure(s), {accepted_live} live accepted violation(s)')
        return False
    print(f'familycheck: PASS: all families closed and reachable; {accepted_live} live accepted violation(s)')
    return True

def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('resources', nargs='?', default=MODRES, help='resource tree to scan (defaults to the mod resource tree)')
    args = ap.parse_args(argv)
    try:
        return 0 if report(os.path.abspath(args.resources)) else 1
    except (OSError, UnicodeError, ValueError, json.JSONDecodeError, zipfile.BadZipFile) as exc:
        print(f'familycheck: cannot complete scan: {exc}')
        return 2
if __name__ == '__main__':
    sys.exit(main())
