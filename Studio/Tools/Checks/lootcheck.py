# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# Checks that every item and loot table a chest loot table names exists.

from __future__ import annotations
import json
import os
import re
import sys
import zipfile
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODRES = os.environ.get('ARMORY_RESOURCES') or os.path.join(ROOT, 'The-Armory-Mod', 'src', 'main', 'resources')
GAME = os.environ.get('ARMORY_GAME') or os.path.join(os.environ.get('APPDATA', ''), 'Hytale', 'install', 'release', 'package', 'game', 'latest')
OPERATOR = re.compile('(\\+\\+|--|\\+|-|\\?|~)$')

def stems(names, prefix: str, ext: str) -> set[str]:
    return {n.rsplit('/', 1)[1][:-len(ext)] for n in names if n.startswith(prefix) and n.endswith(ext)}

def local_stems(root: str, ext: str) -> set[str]:
    out = set()
    for _d, _dirs, files in os.walk(root):
        out.update((f[:-len(ext)] for f in files if f.endswith(ext)))
    return out

def references(node, found: dict[str, list[str]]) -> None:
    if isinstance(node, dict):
        for key, value in node.items():
            plain = OPERATOR.sub('', key)
            if plain in ('ItemId', 'DroplistId') and isinstance(value, str):
                found[plain].append(value)
            else:
                references(value, found)
    elif isinstance(node, list):
        for value in node:
            references(value, found)

def check(resources: str, game: str) -> tuple[list[str], dict]:
    drops_root = os.path.join(resources, 'Server', 'Drops')
    assets = os.path.join(game, 'Assets.zip')
    if not os.path.isdir(drops_root):
        raise OSError(f'drop directory not found at {drops_root}')
    if not os.path.isfile(assets):
        raise OSError(f"the game's Assets.zip not found at {assets}")
    with zipfile.ZipFile(assets) as archive:
        names = archive.namelist()
    items = stems(names, 'Server/Item/Items/', '.json') | local_stems(os.path.join(resources, 'Server', 'Item', 'Items'), '.json')
    lists = stems(names, 'Server/Drops/', '.json') | local_stems(drops_root, '.json')
    problems: list[str] = []
    files = 0
    for dirpath, _dirs, fnames in os.walk(drops_root):
        for fname in sorted(fnames):
            if not fname.endswith(('.json', '.patch')):
                continue
            files += 1
            path = os.path.join(dirpath, fname)
            rel = os.path.relpath(path, resources).replace(os.sep, '/')
            try:
                with open(path, encoding='utf-8-sig') as fh:
                    doc = json.load(fh)
            except (OSError, ValueError) as exc:
                problems.append(f'{rel}: cannot be read as JSON, so the game never loads it ({exc})')
                continue
            found: dict[str, list[str]] = {'ItemId': [], 'DroplistId': []}
            references(doc, found)
            for item in dict.fromkeys(found['ItemId']):
                if item not in items:
                    problems.append(f'{rel}: it drops the item {item}, which does not exist')
            for name in dict.fromkeys(found['DroplistId']):
                if name not in lists and fname.endswith('.patch'):
                    problems.append(f'{rel}: it puts the drop list {name} in chests, but {name} does not exist')
                elif name not in lists:
                    problems.append(f'{rel}: it names the drop list {name}, which does not exist')
            if fname.endswith('.json') and (not found['ItemId']) and (not found['DroplistId']):
                problems.append(f'{rel}: it drops nothing at all, so the game refuses the whole file')
    return (problems, {'files': files, 'items': len(items), 'lists': len(lists)})

def main(argv=None) -> int:
    args = sys.argv[1:] if argv is None else argv
    resources = os.path.abspath(args[0]) if args else MODRES
    try:
        problems, stats = check(resources, GAME)
    except (OSError, zipfile.BadZipFile) as exc:
        print(f'lootcheck: cannot complete scan: {exc}')
        return 2
    print(f"lootcheck: {stats['files']} drop files, against {stats['items']} items and {stats['lists']} drop lists")
    for line in problems:
        print(f'FAIL: {line}')
    if problems:
        print(f'lootcheck: FAILED: {len(problems)} problem(s)')
        return 1
    print('lootcheck: PASS: every item and drop list a loot table names exists')
    return 0
if __name__ == '__main__':
    sys.exit(main())
