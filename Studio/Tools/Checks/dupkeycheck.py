# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# Finds a field written twice in one file. The game keeps only the last one.

from __future__ import annotations
import json
import os
import sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MOD = os.environ.get('ARMORY_RESOURCES') or os.path.join(ROOT, 'The-Armory-Mod', 'src', 'main', 'resources')
SCANNED_SUFFIXES = ('.json', '.patch')
CONTROL_DIRTY = '{"Armor": {"StatModifiers": {"Mana": [{"Amount": 25}], "Mana": [{"Amount": 25}]}}}'
CONTROL_CLEAN = '{"Armor": {"StatModifiers": {"Mana": [{"Amount": 25}], "Health": [{"Amount": 1}]}}}'

def duplicate_keys(text: str) -> list[tuple[str, bool]]:
    found: list[tuple[str, bool]] = []

    def hook(pairs):
        seen: dict = {}
        for key, value in pairs:
            if key in seen:
                same = json.dumps(seen[key], sort_keys=True) == json.dumps(value, sort_keys=True)
                found.append((key, same))
            seen[key] = value
        return seen
    json.loads(text, object_pairs_hook=hook)
    return found

def paths_to(node, key: str, prefix: str='') -> list[str]:
    out: list[str] = []
    if isinstance(node, dict):
        for k, v in node.items():
            here = f'{prefix}.{k}' if prefix else k
            if k == key:
                out.append(here)
            out.extend(paths_to(v, key, here))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            out.extend(paths_to(v, key, f'{prefix}[{i}]'))
    return out

def control_check() -> None:
    dirty = duplicate_keys(CONTROL_DIRTY)
    if not dirty:
        sys.exit('control failed: a planted duplicate was not detected, results are worthless')
    if not dirty[0][1]:
        sys.exit('control failed: identical repeats were reported as differing')
    if duplicate_keys(CONTROL_CLEAN):
        sys.exit('control failed: a clean document was reported as duplicated')

def scan(root: str) -> tuple[int, list[str], int]:
    problems: list[str] = []
    scanned = 0
    dupes = 0
    for dirpath, _dirnames, filenames in os.walk(root):
        for name in sorted(filenames):
            if not name.endswith(SCANNED_SUFFIXES):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            scanned += 1
            try:
                with open(path, 'rb') as fh:
                    text = fh.read().decode('utf-8-sig')
            except OSError as exc:
                problems.append(f'{rel}: unreadable, {exc}')
                continue
            try:
                dups = duplicate_keys(text)
            except ValueError as exc:
                problems.append(f'{rel}: does not parse, {exc}')
                continue
            if not dups:
                continue
            parsed = json.loads(text)
            for key, identical in dups:
                where = paths_to(parsed, key) or [key]
                kind = 'identical repeat, silent edit trap' if identical else 'REPEATS DIFFER, a value is being dropped'
                problems.append(f"{rel}: duplicate key {' or '.join(where)} ({kind})")
                dupes += 1
    return (scanned, problems, dupes)

def main() -> int:
    control_check()
    roots = sys.argv[1:] or [MOD]
    total = 0
    dupes = 0
    problems: list[str] = []
    for root in roots:
        if not os.path.isdir(root):
            print(f'no such directory: {root}')
            return 2
        scanned, found, found_dupes = scan(root)
        total += scanned
        problems.extend(found)
        dupes += found_dupes
    print(f"dupkeycheck: {total} json and patch files scanned under {', '.join(roots)}")
    if not problems:
        print('dupkeycheck: no duplicate keys')
        return 0
    for line in problems:
        print(f'  {line}')
    print(f'dupkeycheck: {len(problems)} problem(s)')
    if dupes:
        print('Delete the EARLIER block. The engine keeps the last one, so the survivor is the')
        print('one already in effect, and the parsed result of the file does not change.')
    return 1
if __name__ == '__main__':
    sys.exit(main())
