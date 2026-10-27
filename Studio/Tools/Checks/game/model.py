# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# Reads those written assets, vanilla and with the mod, side by side.

from __future__ import annotations
import json
import re
from dataclasses import dataclass, field
from functools import cached_property
from pathlib import Path
from typing import Any, Iterator
PACKAGE = 'com.hypixel.hytale.'
_FOLDER_PACK = re.compile('^(?:<server>|.*/server)/mods/[^/]+(/(?:Server|Common)/.*)$')

def normal_path(path: str) -> str:
    m = _FOLDER_PACK.match((path or '').replace('\\', '/'))
    return m.group(1) if m else path

def short(store: str) -> str:
    return store.rsplit('.', 1)[-1]

def leaves(value: Any, prefix: str='') -> Iterator[tuple[str, Any]]:
    if isinstance(value, dict):
        if not value:
            yield (prefix, {})
        for k, v in value.items():
            yield from leaves(v, f'{prefix}.{k}' if prefix else k)
    elif isinstance(value, list):
        if not value:
            yield (prefix, [])
        for v in value:
            yield from leaves(v, f'{prefix}.*' if prefix else '*')
    else:
        yield (prefix, value)

def _fold_key(k: str) -> str:
    return '#' if k.isdigit() else k

def map_keys(value: Any, prefix: str='') -> Iterator[tuple[str, list[str]]]:
    if isinstance(value, dict):
        if value and prefix:
            yield (prefix, list(value.keys()))
        for k, v in value.items():
            fk = _fold_key(k)
            yield from map_keys(v, f'{prefix}.{fk}' if prefix else fk)
    elif isinstance(value, list):
        for v in value:
            yield from map_keys(v, f'{prefix}.*' if prefix else '*')

def present_paths(asset: dict) -> set[str]:
    out: set[str] = set()
    for path, v in leaves(asset):
        if v is None:
            continue
        parts = path.split('.')
        for i in range(1, len(parts) + 1):
            out.add('.'.join(parts[:i]))
    return out

def values_at(asset: dict, path: str) -> list[Any]:
    cur = [asset]
    for part in path.split('.'):
        nxt = []
        for c in cur:
            if part == '*' and isinstance(c, list):
                nxt.extend(c)
            elif isinstance(c, dict) and part in c:
                nxt.append(c[part])
        cur = nxt
    return cur

@dataclass
class Asset:
    store: str
    id: str
    pack: str
    path: str
    parent: str | None
    tags: dict
    asset: dict
    cycles: list = field(default_factory=list)

    @property
    def kind(self) -> str:
        return short(self.store)

class Snapshot:

    def __init__(self, folder: Path):
        self.folder = Path(folder)
        run = self.folder / 'run.json'
        if not run.exists():
            raise SystemExit(f'no snapshot at {self.folder} (run snapshot.py or cases.py)')
        self.run = json.loads(run.read_text(encoding='utf-8'))
        if not self.run.get('complete'):
            raise SystemExit(f'the snapshot at {self.folder} is not complete; take it again')
        self._stores: dict[str, dict[str, Asset]] = {}

    @cached_property
    def store_names(self) -> dict[str, str]:
        return {short(p.stem): p.stem for p in (self.folder / 'stores').glob('*.jsonl')}

    def store(self, kind: str) -> dict[str, Asset]:
        name = self.store_names.get(kind, kind)
        if name not in self._stores:
            out: dict[str, Asset] = {}
            path = self.folder / 'stores' / f'{name}.jsonl'
            if path.exists():
                with open(path, encoding='utf-8') as f:
                    for line in f:
                        d = json.loads(line)
                        out[d['id']] = Asset(name, d['id'], d.get('pack', ''), normal_path(d.get('path', '')), d.get('parent'), d.get('tags', {}), d.get('asset') or {}, d.get('cycles', []))
            self._stores[name] = out
        return self._stores[name]

    def store_folders(self) -> dict[str, tuple[str, str]]:
        path = self.folder / 'meta.json'
        if not path.exists():
            return {}
        return {short(s['store']): (s['path'], s.get('extension', '.json')) for s in json.loads(path.read_text(encoding='utf-8')).get('stores', []) if 'path' in s}

    def unwritten(self) -> dict[str, list[str]]:
        path = self.folder / 'meta.json'
        if not path.exists():
            return {}
        out = {}
        for s in json.loads(path.read_text(encoding='utf-8')).get('stores', []):
            if s.get('refusals'):
                out[short(s['store'])] = sorted((r['id'] for r in s['refusals']))
        return out

    def defaults(self, kind: str) -> dict:
        name = self.store_names.get(kind, kind)
        path = self.folder / 'defaults' / f'{name}.json'
        return json.loads(path.read_text(encoding='utf-8')) if path.exists() else {}

    @cached_property
    def engine_lines(self) -> list[dict]:
        path = self.folder / 'engine-lines.jsonl'
        if not path.exists():
            return []
        lines = [json.loads(l) for l in path.read_text(encoding='utf-8').splitlines() if l.strip()]
        for l in lines:
            l['file'] = normal_path(l.get('file') or '')
        return lines

class Pair:

    def __init__(self, vanilla: Snapshot, modded: Snapshot):
        self.vanilla = vanilla
        self.modded = modded
        self.notes: dict[str, list[str]] = {}
        if vanilla.run.get('server_version') != modded.run.get('server_version'):
            raise SystemExit(f"the two snapshots were taken on different game versions: {vanilla.run.get('server_version')} and {modded.run.get('server_version')}")

    def mod_assets(self, kind: str) -> dict[str, Asset]:
        base = self.vanilla.store(kind)
        return {k: v for k, v in self.modded.store(kind).items() if k not in base}

    def vanilla_assets(self, kind: str) -> dict[str, Asset]:
        return self.vanilla.store(kind)

    @cached_property
    def reference_targets(self) -> dict[tuple[str, str], str]:
        ids_by_store = {short(n): set(self.vanilla.store(n)) for n in self.vanilla.store_names.values()}
        targets: dict[tuple[str, str], str] = {}
        for kind in ids_by_store:
            strings: dict[str, set[str]] = {}
            for a in self.vanilla.store(kind).values():
                for path, v in leaves(a.asset):
                    if isinstance(v, str) and v:
                        strings.setdefault(path, set()).add(v)
            for path, vals in strings.items():
                if len(vals) < 3:
                    continue
                best, hits = (None, 0)
                for target, ids in ids_by_store.items():
                    h = sum((1 for v in vals if v in ids))
                    if h > hits:
                        best, hits = (target, h)
                if best and hits / len(vals) >= 0.95:
                    targets[kind, path] = best
        return targets

    @cached_property
    def reference_paths(self) -> dict[tuple[str, str, str], dict]:
        ids_by_store = {short(n): set(self.vanilla.store(n)) for n in self.vanilla.store_names.values()}
        out: dict[tuple[str, str, str], dict] = {}
        for kind in ids_by_store:
            seen: dict[tuple[str, str], set[str]] = {}
            for a in self.vanilla.store(kind).values():
                for path, v in leaves(a.asset):
                    if isinstance(v, str) and v:
                        seen.setdefault(('value', path), set()).add(v)
                for path, keys in map_keys(a.asset):
                    seen.setdefault(('key', path), set()).update(keys)
            for (form, path), names in seen.items():
                if len(names) < 3:
                    continue
                targets = {t for t, ids in ids_by_store.items() if sum((1 for n in names if n in ids)) / len(names) >= 0.95}
                if targets:
                    out[form, kind, path] = {'targets': targets, 'seen': names}
        return out

    def referrers(self, snap: Snapshot) -> dict[tuple[str, str], list[tuple[str, str, str]]]:
        out: dict[tuple[str, str], list[tuple[str, str, str]]] = {}
        by_kind: dict[str, list[str]] = {}
        for (kind, path), target in self.reference_targets.items():
            by_kind.setdefault(kind, []).append(path)
        for kind, paths in by_kind.items():
            for a in snap.store(kind).values():
                for path in paths:
                    target = self.reference_targets[kind, path]
                    for v in values_at(a.asset, path):
                        if isinstance(v, str):
                            out.setdefault((target, v), []).append((kind, a.id, path))
        return out
