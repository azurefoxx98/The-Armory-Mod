# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# The rules that compare the mod's assets with vanilla and with each other.

from __future__ import annotations
import json
import re
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable
from model import Asset, Pair, leaves, map_keys, normal_path, present_paths, short, values_at
sys.path.append(str(Path(__file__).resolve().parent.parent))
from pairedmods import paired_mod_of_stat

@dataclass
class Finding:
    rule: str
    kind: str
    id: str
    file: str
    fact: str
    family: str = ''
    detail: dict = field(default_factory=dict)

    def key(self) -> tuple[str, str, str]:
        return (self.rule, self.kind, self.id)

@dataclass
class Rule:
    id: str
    title: str
    defect_class: str
    truth: str
    not_modelled: list[str]
    run: Callable[[Pair], list[Finding]]

    def __post_init__(self) -> None:
        rule = self.run

        def run(pair: Pair) -> list[Finding]:
            return defining_files(rule(pair), pair)
        self.run = run

def effect_proven(f: Finding) -> bool:
    return not ('effect' in f.detail and f.detail['effect'] is None)

def defining_files(findings: list[Finding], pair: Pair) -> list[Finding]:
    items = pair.modded.store('Item')
    for f in findings:
        if f.file or f.kind != 'BlockType':
            continue
        item = items.get(f.id.lstrip('*').split('_State_Definitions_')[0])
        if item is not None and getattr(item, 'path', ''):
            f.file = item.path
    return findings
KIND_MIN_GROUP = 20
KIND_PRESENT_SHARE = 0.95
KIND_DEFAULT_SHARE = 0.05
KIND_FAMILY_MIN = 5

def _family(ident: str) -> str | None:
    head, sep, _ = ident.lstrip('*').partition('_')
    return head if sep and head else None
KIND_EFFECTS = {('BlockType', 'ParticleColor', 'unset'): 'the world map draws it white', ('BlockType', 'BlockParticleSetId', 'unset'): 'nothing stepping on it or hitting it raises particles', ('BlockType', 'BlockSoundSetId', 'default'): 'it makes no sound when placed or broken', ('Item', 'Categories', 'unset'): 'it shows in no tab of the creative inventory', ('Item', 'PlayerAnimationsId', 'default'): "a player holds it in a pose the game's own items of that use never have", ('ResourceType', 'Icon', 'unset'): 'the crafting window shows an empty square for it'}

def _shown_ingredient(pair: Pair) -> set[str]:
    out: set[str] = set()
    for r in pair.modded.store('CraftingRecipe').values():
        if any((isinstance(b, dict) and b.get('Type') == 'Crafting' for b in r.asset.get('BenchRequirement') or [])):
            out |= {i['ResourceTypeId'] for i in r.asset.get('Input') or [] if isinstance(i, dict) and i.get('ResourceTypeId')}
    return out

def top_present(asset: dict) -> set[str]:

    def holds(v) -> bool:
        if v is None:
            return False
        if isinstance(v, dict):
            return not v or any((holds(x) for x in v.values()))
        if isinstance(v, list):
            return not v or any((holds(x) for x in v))
        return True
    return {k for k, v in asset.items() if holds(v)}

def _contexts(refs: dict, kind: str, ident: str) -> set[tuple]:
    out: set[tuple] = set()
    for rk, rid, path in refs.get((kind, ident), []):
        ups = {(uk, upath) for uk, _, upath in refs.get((rk, rid), [])}
        if ups:
            out |= {(rk, path, uk, upath) for uk, upath in ups}
        else:
            out.add((rk, path))
    return out

def _context_text(ctx: tuple) -> str:
    if len(ctx) == 4:
        return f'used by a {ctx[0]} ({ctx[1]}) that a {ctx[2]} uses ({ctx[3]})'
    return f'used by a {ctx[0]} ({ctx[1]})'

def like_its_kind(pair: Pair) -> list[Finding]:
    findings: list[Finding] = []
    left: list[str] = []
    vrefs = pair.referrers(pair.vanilla)
    mrefs = pair.referrers(pair.modded)
    for kind_full in pair.modded.store_names.values():
        kind = short(kind_full)
        mod = pair.mod_assets(kind)
        if not mod:
            continue
        vanilla = pair.vanilla_assets(kind)
        groups: dict[tuple, list[Asset]] = {}
        for a in vanilla.values():
            for c in _contexts(vrefs, kind, a.id):
                groups.setdefault(c, []).append(a)
        shown = _shown_ingredient(pair) if kind == 'ResourceType' else set()
        effect = lambda p, form, ident: None if (kind, p) == ('ResourceType', 'Icon') and ident not in shown else KIND_EFFECTS.get((kind, p, form))
        defaults = pair.vanilla.defaults(kind)
        default_leaves = {p: v for p, v in defaults.items() if not isinstance(v, (dict, list))}
        stats_cache: dict[tuple, tuple[dict, dict]] = {}
        families: dict[str, list[Asset]] = {}
        for v in vanilla.values():
            if _family(v.id):
                families.setdefault(_family(v.id), []).append(v)

        def family_agrees(a: Asset, does) -> list[Asset] | None:
            fam = families.get(_family(a.id) or '', [])
            if len(fam) < KIND_FAMILY_MIN:
                return None
            return fam if sum((1 for v in fam if does(v))) / len(fam) >= KIND_PRESENT_SHARE else None

        def stats(c: tuple) -> tuple[dict, dict]:
            if c not in stats_cache:
                present: dict[str, int] = {}
                values: dict[str, dict[str, int]] = {}
                for a in groups[c]:
                    for p in top_present(a.asset):
                        present[p] = present.get(p, 0) + 1
                    for p in default_leaves:
                        v = a.asset.get(p)
                        if v is not None and (not isinstance(v, (dict, list))):
                            key = repr(v)
                            values.setdefault(p, {})
                            values[p][key] = values[p].get(key, 0) + 1
                stats_cache[c] = (present, values)
            return stats_cache[c]
        for a in mod.values():
            ctxs = sorted((c for c in _contexts(mrefs, kind, a.id) if len(groups.get(c, [])) >= KIND_MIN_GROUP))
            if not ctxs:
                continue
            mine = top_present(a.asset)
            missing: dict[str, list[str]] = {}
            defaulted: dict[str, list[str]] = {}
            for c in ctxs:
                n = len(groups[c])
                present, values = stats(c)
                for p, cnt in present.items():
                    if cnt / n >= KIND_PRESENT_SHARE and p not in mine:
                        missing.setdefault(p, []).append(f'{cnt} of {n} game assets {_context_text(c)}')
                for p, dv in default_leaves.items():
                    if a.asset.get(p) != dv:
                        continue
                    same = values.get(p, {}).get(repr(dv), 0)
                    if same / n <= KIND_DEFAULT_SHARE:
                        defaulted.setdefault(p, []).append(f'{same} of {n} game assets {_context_text(c)} hold {dv!r}')
            for p, why in sorted(missing.items()):
                if len(why) != len(ctxs):
                    continue
                fam = family_agrees(a, lambda v: p not in top_present(v.asset))
                if fam:
                    left.append(f"{a.id} sets no {p}, as {len(fam)} of the game's {_family(a.id)}_ {kind} assets do")
                    continue
                findings.append(Finding('like-its-kind', kind, a.id, a.path, f"{a.id} sets no {p}, which the game's own {kind} assets of the same use set: " + '; '.join(why) + '.', family=f'{kind}:{p}', detail={'field': p, 'form': 'unset', 'effect': effect(p, 'unset', a.id)}))
            for p, why in sorted(defaulted.items()):
                if len(why) != len(ctxs):
                    continue
                fam = family_agrees(a, lambda v: v.asset.get(p) == default_leaves[p])
                if fam:
                    left.append(f"{a.id} leaves {p} at {default_leaves[p]!r}, as {len(fam)} of the game's {_family(a.id)}_ {kind} assets do")
                    continue
                findings.append(Finding('like-its-kind', kind, a.id, a.path, f"{a.id} leaves {p} at the engine's default {default_leaves[p]!r}, which the game's own {kind} assets of the same use almost never hold: " + '; '.join(why) + '.', family=f'{kind}:{p}', detail={'field': p, 'form': 'default', 'effect': effect(p, 'default', a.id)}))
    pair.notes['like-its-kind'] = left
    return findings
SIBLING_MIN_FAMILY = 4
SIBLING_PRESENT_SHARE = 0.9

def _item_families(pair: Pair) -> dict[tuple, list[Asset]]:
    fam: dict[tuple, list[Asset]] = {}
    for a in pair.mod_assets('Item').values():
        sub = a.asset.get('SubCategory')
        if not (isinstance(sub, str) and sub):
            continue
        armor = a.asset.get('Armor')
        what = ('slot', armor.get('ArmorSlot')) if isinstance(armor, dict) else ('built on', a.parent or '-')
        fam.setdefault((sub, *what), []).append(a)
    return fam

def _lacks(members: list[Asset]) -> dict[str, dict[str, int]]:
    n = len(members)
    sets = {a.id: present_paths(a.asset) for a in members}
    counts: dict[str, int] = {}
    for s in sets.values():
        for p in s:
            counts[p] = counts.get(p, 0) + 1
    out: dict[str, dict[str, int]] = {}
    for p, cnt in counts.items():
        if cnt == n or not (cnt == n - 1 or cnt / n >= SIBLING_PRESENT_SHARE):
            continue
        if p == 'Recipe' or p.startswith('Recipe.'):
            continue
        parent = p.rsplit('.', 1)[0] if '.' in p else None
        if parent and counts.get(parent) == cnt:
            continue
        for a in members:
            if p not in sets[a.id]:
                out.setdefault(a.id, {})[p] = cnt
    return out

def like_its_siblings(pair: Pair) -> list[Finding]:
    families = _item_families(pair)
    whole_sets: dict[str, list[Asset]] = {}
    for (name, _, _), members in families.items():
        whole_sets.setdefault(name, []).extend(members)
    findings: list[Finding] = []

    def report(members: list[Asset], name: str, group: str, only: set[str] | None=None) -> None:
        n = len(members)
        by_id = {a.id: a for a in members}
        for lone, missing in sorted(_lacks(members).items()):
            if only is not None and lone not in only:
                continue
            groups: list[tuple[str, list[str]]] = []
            by_parent: dict[str, list[str]] = {}
            for p in sorted(missing):
                if '.' in p:
                    by_parent.setdefault(p.rsplit('.', 1)[0], []).append(p)
                else:
                    groups.append((p, [p]))
            for parent, paths in groups + sorted(by_parent.items()):
                cnt = min((missing[p] for p in paths))
                if len(paths) == 1:
                    p = paths[0]
                    example = next((a for a in members if a.id != lone and values_at(a.asset, p)), None)
                    shown = values_at(example.asset, p) if example else []
                    what = p + (f' ({example.id}: {shown[0]!r})' if len(shown) == 1 and (not isinstance(shown[0], (dict, list))) else '')
                else:
                    what = f"{parent}.{{{', '.join((x.rsplit('.', 1)[1] for x in paths))}}}"
                findings.append(Finding('like-its-siblings', 'Item', lone, by_id[lone].path, f'{lone} sets no {what}. {cnt} of the {n - 1} other {group} do.', family=f'set:{name}', detail={'field': paths[0] if len(paths) == 1 else parent, 'set': name}))
    small: dict[str, set[str]] = {}
    for (name, how, what), members in sorted(families.items(), key=lambda kv: tuple(map(str, kv[0]))):
        if len(members) >= SIBLING_MIN_FAMILY:
            group = f"{what} pieces of the set '{name}'" if how == 'slot' else f"items of the set '{name}' built on {what}"
            report(members, name, group)
        else:
            small.setdefault(name, set()).update((a.id for a in members))
    for name, ids in sorted(small.items()):
        if len(whole_sets[name]) >= SIBLING_MIN_FAMILY:
            report(whole_sets[name], name, f"items of the set '{name}'", only=ids)
    return findings
SIBLING_VALUE_FIELDS = ('Armor.', 'MaxDurability', 'DurabilityLossOnHit', 'ItemLevel')

def sibling_values(pair: Pair) -> list[Finding]:
    groups: dict[tuple, list[Asset]] = {}
    for a in pair.mod_assets('Item').values():
        armor = a.asset.get('Armor')
        sub = a.asset.get('SubCategory')
        if isinstance(armor, dict) and isinstance(sub, str) and sub:
            groups.setdefault((sub, armor.get('ArmorSlot')), []).append(a)
    findings: list[Finding] = []
    for (sub, slot), members in sorted(groups.items(), key=lambda kv: (kv[0][0], str(kv[0][1]))):
        if len(members) < SIBLING_MIN_FAMILY:
            continue
        ids = {a.id for a in members}
        values: dict[str, dict[str, Any]] = {}
        for a in members:
            for p, v in leaves(a.asset):
                if isinstance(v, (int, float)) and (not isinstance(v, bool)) and p.startswith(SIBLING_VALUE_FIELDS):
                    values.setdefault(p, {})[a.id] = v
        by_id = {a.id: a for a in members}
        for p, vals in sorted(values.items()):
            voices = {i: v for i, v in vals.items() if not (by_id[i].parent in ids and vals.get(by_id[i].parent) == v)}
            if len(voices) < SIBLING_MIN_FAMILY:
                continue
            counts = Counter(voices.values())
            if len(counts) != 2:
                continue
            (common, n_common), (odd, n_odd) = counts.most_common()
            if n_odd != 1 or n_common < SIBLING_MIN_FAMILY - 1:
                continue
            lone = next((i for i, v in voices.items() if v == odd))
            heirs = sorted((i for i, v in vals.items() if i not in voices and v == odd))
            findings.append(Finding('like-its-siblings', 'Item', lone, by_id[lone].path, f"{lone} has {p} = {odd!r}, where the other {n_common} {slot} pieces of the set '{sub}' all have {common!r}" + (f" ({', '.join(heirs)} inherits it)" if heirs else '') + '.', family=f'set:{sub}', detail={'field': p, 'form': 'value', 'value': odd, 'others': common}))
    return findings

def _language(snap) -> dict[str, str]:
    path = snap.folder / 'language-en-US.json'
    return json.loads(path.read_text(encoding='utf-8')) if path.exists() else {}

def name_key(a: Asset) -> str:
    props = a.asset.get('TranslationProperties')
    key = props.get('Name') if isinstance(props, dict) else None
    return key if isinstance(key, str) and key else f'server.items.{a.id}.name'

def names_and_pictures(pair: Pair) -> list[Finding]:
    snap = pair.modded
    text = _language(snap)
    items = snap.store('Item')
    mod = pair.mod_assets('Item')
    findings: list[Finding] = []
    by_text: dict[str, list[str]] = {}
    by_icon: dict[str, list[str]] = {}
    for i, a in items.items():
        key = name_key(a)
        if key not in text:
            if i in mod:
                findings.append(Finding('names-and-pictures', 'Item', i, a.path, f"{i}'s name {key!r} has no English text in the game as loaded with the mod, so a player reads the key itself.", family='name with no text', detail={'field': 'name', 'form': 'no text'}))
        else:
            by_text.setdefault(text[key].strip().lower(), []).append(i)
        icon = a.asset.get('Icon')
        if isinstance(icon, str) and icon:
            by_icon.setdefault(icon, []).append(i)
    look = lambda i: (items[i].asset.get('Model'), items[i].asset.get('Texture'))
    slot = lambda i: (items[i].asset.get('Armor') or {}).get('ArmorSlot') if isinstance(items[i].asset.get('Armor'), dict) else None
    sub = lambda i: items[i].asset.get('SubCategory') or ''
    name_of = lambda i: text.get(name_key(items[i]), '')
    words = lambda i: set(re.findall('[a-z]+', name_of(i).lower()))
    use: dict[str, Counter] = {}
    for i in items:
        if slot(i):
            for w in words(i):
                use.setdefault(w, Counter())[slot(i), sub(i)] += 1

    def slot_of_word(w: str, own_set: str):
        outside: Counter = Counter()
        sets: set[str] = set()
        for (sl, sb), n in use.get(w, {}).items():
            if sb != own_set or not sb:
                outside[sl] += n
                sets.add(sb)
        if sum(outside.values()) < 3 or len(outside) != 1 or len(sets) < 2:
            return (None, 0)
        return next(iter(outside.items()))
    for i in sorted(mod):
        if not slot(i):
            continue
        said = {w: slot_of_word(w, sub(i)) for w in words(i)}
        if any((sl == slot(i) for sl, _ in said.values())):
            continue
        for w in sorted(said):
            other_slot, n = said[w]
            if other_slot and other_slot != slot(i):
                findings.append(Finding('names-and-pictures', 'Item', i, items[i].path, f'{i} is worn on the {slot(i)} and its name "{name_of(i)}" says "{w}", a word the {n} other items using it are all worn on the {other_slot}.', family=f'name says another slot: {w}', detail={'field': 'name', 'form': 'slot word', 'word': w}))
    game = pair.vanilla_assets('Item')
    kind = lambda i: 'armor' if slot(i) else 'weapon' if items[i].asset.get('Weapon') is not None else None
    model = lambda i: items[i].asset.get('Model')
    family = lambda i: {r for r in values_at(items[i].asset, 'ResourceTypes.*.Id') if isinstance(r, str)}
    for shown, ids in sorted(by_text.items()):
        for i in sorted((x for x in ids if x in mod)):
            if not kind(i):
                continue
            holders = [x for x in ids if x in game and kind(x) == kind(i)]
            if any((model(x) == model(i) for x in holders)):
                continue
            others = sorted((x for x in ids if x != i and kind(x) == kind(i) and (sub(x) != sub(i) or not sub(i)) and (model(x) != model(i)) and (x in game or not family(x) & family(i))))
            if others:
                findings.append(Finding('names-and-pictures', 'Item', i, items[i].path, f'''{i} shows the name "{name_of(i)}", which {len(others)} {kind(i)} item(s) of another set or of the game, with another model, also show: {', '.join(others[:6])}''' + (' ...' if len(others) > 6 else '') + '.', family=f'same name across sets: {shown}', detail={'field': 'name', 'form': 'shared', 'with': others}))
    own = lambda i: _stem(items[i].asset.get('Icon')) == i
    for icon, ids in sorted(by_icon.items()):
        mine = sorted((x for x in ids if x in mod))
        if len({look(x) for x in ids}) < 2 or not mine:
            continue
        for i in mine:
            others = sorted((x for x in ids if look(x) != look(i)))
            if not others or own(i):
                continue
            findings.append(Finding('names-and-pictures', 'Item', i, items[i].path, f"{i} shows the icon {icon}, and so do {len(others)} item(s) with another model or texture: {', '.join(others[:6])}" + (' ...' if len(others) > 6 else '') + '. One icon can be right for one look at most.', family=f'same icon, other look: {icon}', detail={'field': 'icon', 'form': 'shared', 'with': others}))
    return findings

def _stem(path) -> str:
    return path.rsplit('/', 1)[-1].rsplit('.', 1)[0] if isinstance(path, str) else ''

def _drop_items(value: Any) -> set[str]:
    out = set()
    for path, v in leaves(value):
        if path.endswith('ItemId') and isinstance(v, str):
            out.add(v)
    return out

def reachable(pair: Pair) -> list[Finding]:
    snap = pair.modded
    items = snap.store('Item')
    mod_items = pair.mod_assets('Item')
    vanilla_items = pair.vanilla_assets('Item')
    reach: dict[str, str] = {i: 'a game item' for i in vanilla_items if i in items}
    for d in snap.store('ItemDropList').values():
        for i in _drop_items(d.asset):
            reach.setdefault(i, f'drop list {d.id}')
    for kind in ('BarterShopAsset', 'ShopAsset'):
        for s in snap.store(kind).values():
            for i in _drop_items(s.asset):
                reach.setdefault(i, f'{kind} {s.id}')
    recipes = list(snap.store('CraftingRecipe').values())
    carriers: dict[str, set[str]] = {}
    for i, a in items.items():
        for rt in values_at(a.asset, 'ResourceTypes.*.Id'):
            carriers.setdefault(rt, set()).add(i)
    provided = {b for a in snap.store('BlockType').values() for b in values_at(a.asset, 'Bench.Id') if isinstance(b, str)}
    game_used = {b for r in pair.vanilla.store('CraftingRecipe').values() for b in values_at(r.asset, 'BenchRequirement.*.Id') if isinstance(b, str)}

    def outputs(r) -> list[str]:
        return [o for o in values_at(r.asset, 'Output.*.ItemId') + values_at(r.asset, 'PrimaryOutput.ItemId') if isinstance(o, str)]

    def blockers(r) -> list[str]:
        out = []
        for b in values_at(r.asset, 'BenchRequirement.*.Id'):
            if isinstance(b, str) and b not in provided and (b not in game_used):
                out.append(f'it asks for the bench {b!r}, which no block provides')
        for inp in r.asset.get('Input') or []:
            if inp.get('ItemId') and inp['ItemId'] not in reach:
                out.append(f"it takes {inp['ItemId']}, which has no way in")
            elif inp.get('ResourceTypeId') and (not any((c in reach for c in carriers.get(inp['ResourceTypeId'], ())))):
                who = sorted(carriers.get(inp['ResourceTypeId'], ()))
                out.append(f"it takes the resource type {inp['ResourceTypeId']}, " + (f"carried only by {', '.join(who)}, none of which has a way in" if who else 'which no item carries'))
        return out
    changed = True
    while changed:
        changed = False
        for r in recipes:
            outs = outputs(r)
            if all((o in reach for o in outs)) or blockers(r):
                continue
            for o in outs:
                if o not in reach:
                    reach[o] = f'recipe {r.id}'
                    changed = True
    findings: list[Finding] = []
    states = 0
    game_ways = {o for r in pair.vanilla.store('CraftingRecipe').values() for o in outputs(r)}
    for kind in ('ItemDropList', 'BarterShopAsset', 'ShopAsset'):
        for d in pair.vanilla.store(kind).values():
            game_ways |= set(_drop_items(d.asset))
    wearers: dict[str, set[str]] = {}
    for g, v in vanilla_items.items():
        if g in items and isinstance(items[g].asset.get('Model'), str):
            wearers.setdefault(items[g].asset['Model'], set()).add(g)
    for i, a in sorted(mod_items.items()):
        if a.asset.get('Armor') is None and a.asset.get('Weapon') is None:
            continue
        if i in reach:
            continue
        if i.startswith('*') and a.parent in items:
            states += 1
            continue
        makers = [r for r in recipes if i in outputs(r)]
        if not makers:
            fact = f'{i} has no way in: no recipe makes it, no drop list names it and no shop sells it, in the game as loaded with the mod.'
        else:
            fact = f'{i} has no way in: ' + '; '.join((f'{r.id}: ' + '; '.join(blockers(r)) for r in makers)) + '.'
        detail = {'form': 'item', 'makers': [r.id for r in makers]}
        roots = sorted((g for g in wearers.get(a.asset.get('Model'), ()) if not re.search('npc', g, re.I) and vanilla_items[g].parent not in wearers.get(a.asset.get('Model'), ())))
        base = roots[0] if len(roots) == 1 and roots[0] in game_ways else None
        wanted = {inp['ResourceTypeId'] for r in makers for inp in r.asset.get('Input') or [] if inp.get('ResourceTypeId')}
        if base and len(wanted) == 1 and makers and all((b.startswith('it takes the resource type ') for r in makers for b in blockers(r))):
            family = wanted.pop()
            if family not in values_at(items[base].asset, 'ResourceTypes.*.Id'):
                detail = {'form': 'base outside', 'makers': [r.id for r in makers], 'base': base, 'name': family}
                fact += f" Its base, the game's {base}, has a way in but does not carry {family}."
        findings.append(Finding('reachable', 'Item', i, a.path, fact, family=f"set:{a.asset.get('SubCategory') or '-'}", detail=detail))
    unreachable_items = {f.id for f in findings}
    for r in recipes:
        outs = outputs(r)
        if not any((o in mod_items for o in outs)) or any((o in unreachable_items for o in outs)):
            continue
        blocked = blockers(r)
        if blocked:
            out = outs[0]
            findings.append(Finding('reachable', 'CraftingRecipe', r.id, items[out].path if out in items else '', f'{r.id} can never be made, so {out} cannot be crafted (it has another way in: {reach.get(out)}): ' + '; '.join(blocked) + '.', family=f"set:{items[out].asset.get('SubCategory') or '-'}" if out in items else '', detail={'form': 'recipe', 'output': out}))
    pair.notes['reachable'] = [f"{states} item state(s) left to their item: a stack becomes a state only when an interaction or the mod's code switches it"] if states else []
    return findings
ALTERATION_BENCH = 'ArmoryAlteration'

def foreign_family(pair: Pair) -> list[Finding]:
    items = pair.modded.store('Item')
    game = pair.vanilla_assets('Item')
    mod = pair.mod_assets('Item')
    colours: dict[str, set[str]] = {}
    for r in pair.modded.store('CraftingRecipe').values():
        if not any((isinstance(b, dict) and b.get('Id') == ALTERATION_BENCH for b in r.asset.get('BenchRequirement') or [])):
            continue
        outs = [o for o in values_at(r.asset, 'Output.*.ItemId') + values_at(r.asset, 'PrimaryOutput.ItemId') if isinstance(o, str)]
        for inp in r.asset.get('Input') or []:
            if isinstance(inp, dict) and inp.get('ResourceTypeId'):
                colours.setdefault(inp['ResourceTypeId'], set()).update(outs)
    own = lambda a: {r for r in values_at(a.asset, 'ResourceTypes.*.Id') if isinstance(r, str)}
    findings: list[Finding] = []
    for fam, made in sorted(colours.items()):
        looks = {items[c].asset.get('Model') for c in made if c in items}
        wearers = sorted((g for g, a in game.items() if a.asset.get('Model') in looks))
        owners = [g for g in wearers if fam in own(game[g])] or wearers
        if not owners:
            continue
        for i, a in sorted(mod.items()):
            parent = items.get(a.parent) if a.parent else None
            if fam not in own(a) or (parent is not None and fam in own(parent)):
                continue
            if i in made or a.asset.get('Model') in looks:
                continue
            findings.append(Finding('foreign-family', 'Item', i, a.path, f"{i} names the colour family {fam}, the colours of the game's {', '.join(owners[:3])}, but wears another model ({a.asset.get('Model')}): the Alteration Table turns it into one of those colours, and no recipe turns it back.", family=f'foreign family: {fam}', detail={'field': 'ResourceTypes', 'form': 'foreign', 'name': fam, 'of': owners[0]}))
    return findings

def names_resolve(pair: Pair) -> list[Finding]:
    snap = pair.modded
    ids = {short(n): set(snap.store(n)) for n in snap.store_names.values()}
    by_kind: dict[str, list[tuple[str, str, dict]]] = {}
    for (form, kind, path), spec in pair.reference_paths.items():
        by_kind.setdefault(kind, []).append((form, path, spec))
    findings: list[Finding] = []
    paired: dict[str, set[str]] = {}
    for kind, specs in sorted(by_kind.items()):
        base = pair.vanilla.store(kind)
        for a in snap.store(kind).values():
            old = base.get(a.id)
            if old is not None and old.asset == a.asset:
                continue
            keys = dict(map_keys(a.asset)) if any((f == 'key' for f, _, _ in specs)) else {}
            for form, path, spec in specs:
                names = [v for v in values_at(a.asset, path) if isinstance(v, str) and v] if form == 'value' else keys.get(path, [])
                for n in names:
                    if n in spec['seen'] or any((n in ids[t] for t in spec['targets'])):
                        continue
                    if 'EntityStatType' in spec['targets'] and paired_mod_of_stat(n):
                        paired.setdefault(f"{paired_mod_of_stat(n)}'s {n}", set()).add(a.id)
                        continue
                    where = ' or '.join(sorted(spec['targets']))
                    findings.append(Finding('names-resolve', kind, a.id, a.path, f"{a.id} names {n!r} {('as a key of' if form == 'key' else 'at')} {path}, where the game names a {where}; no {where} called {n!r} exists in the game as loaded with the mod.", family=f'{kind}:{path}', detail={'field': path, 'name': n, 'form': form}))
    pair.notes['names-resolve'] = [f'{name}, named by {len(users)} asset(s): a stat of a mod this one is made to run beside (pairedmods.py)' for name, users in sorted(paired.items())]
    return findings
_LOG_LINE = re.compile('\\[[0-9/ :]+\\s+(WARN|SEVERE|WARNING)\\]\\s+(.*)')
_SHUTDOWN = re.compile('\\[HytaleServer\\] Shutdown triggered')
_PATCHLY_WIPE = re.compile("\\[AssetMonitor\\] The asset file '[^']*[\\\\/]mods[\\\\/][^\\\\/']+_Patchly(?:[\\\\/][^']*)?' was deleted before we could load/update it!$")

def _folded(message: str) -> str:
    return re.sub('[A-Za-z]:\\\\[^ ]*?\\\\snapshots\\\\[^\\\\ ]+', '<run>', message).strip()

def _log_lines(snap) -> set[str]:
    text = re.sub('\\x1b\\[[0-9;]*m', '', (snap.folder / 'boot.log').read_text(encoding='utf-8', errors='replace'))
    out: set[str] = set()
    for line in text.splitlines():
        m = _LOG_LINE.match(line)
        if m and '[SERR]' not in m.group(2):
            out.add(_folded(m.group(2)))
    return out
_MISSING_INTERACTION = re.compile('\\[Hytale\\] Missing (root )?interaction (\\S+)$')
_DUPLICATE_COUNT = re.compile('\\[CommonAssetModule\\|P\\] Duplicated Asset Count: (\\d+)$')
_FOR_FILE = re.compile("for file '([^']+)'")
_LOST_PARENT = re.compile('\\[AssetStore\\|\\w+\\] Failed to find inherited parent asset (\\S+) \\((\\w+)\\) for (\\S+)$')

def _shutdown_wipes(snap) -> set[str]:
    text = re.sub('\\x1b\\[[0-9;]*m', '', (snap.folder / 'boot.log').read_text(encoding='utf-8', errors='replace'))
    out: set[str] = set()
    stopping = False
    for line in text.splitlines():
        if _SHUTDOWN.search(line):
            stopping = True
        m = _LOG_LINE.match(line)
        if stopping and m and _PATCHLY_WIPE.search(m.group(2)):
            out.add(_folded(m.group(2)))
    return out

def _left_nothing(snap, root: bool, ident: str) -> bool:
    a = snap.store('RootInteraction' if root else 'Interaction').get(ident)
    if a is None:
        return False
    if root:
        return bool(a.asset.get('Interactions'))
    return a.asset.get('Message') != f'Missing interaction: {ident}'

def _lost_fields(pair: Pair, kind: str, ident: str) -> list[str]:
    base = pair.vanilla.store(kind).get(ident)
    now = pair.modded.store(kind).get(ident)
    if base is None or now is None:
        return []
    return sorted((k for k, v in base.asset.items() if v is not None and now.asset.get(k) is None))

def server_says(pair: Pair) -> list[Finding]:
    findings: list[Finding] = []
    refused = pair.modded.run.get('refused') if isinstance(getattr(pair.modded, 'run', None), dict) else None
    if refused:
        files = refused.get('files') or []
        named = '; '.join((f"{x['id']} ({normal_path(x['file'])}): {x['message']}" for x in files))
        if refused.get('started'):
            fact = f"These files fail to load, and the game's own server started without them: {named}."
        else:
            fact = f'''The game's own server refuses to start with this build: "{refused['reason']}". ''' + (f'The file(s) it could not load: {named}. ' if named else 'The files it dropped are named by the all-loaded rule. ') + 'Only a server started with --ignore-broken-mods runs, and then without them.'
        findings.append(Finding('server-says', 'Server', 'refuses to start', '', fact + " The reason for each is in this rule's lines for that file.", family='files that fail to load' if refused.get('started') else 'the server refuses to start', detail={'field': 'boot', 'form': 'failed to load' if refused.get('started') else 'refused', 'files': [x['id'] for x in files]}))
    key = lambda l: (l['kind'], l['file'], l['message'])
    extra = Counter((key(l) for l in pair.modded.engine_lines)) - Counter((key(l) for l in pair.vanilla.engine_lines))
    for (kind, file, message), n in sorted(extra.items()):
        ident = file.rsplit('/', 1)[-1].removesuffix('.json') if file else '?'
        findings.append(Finding('server-says', 'ServerFinding', ident, file, f"The game's own server reports for {file}: {message} ({kind}" + (f', {n} times' if n > 1 else '') + '). It does not for the game alone.', family=f"server:{message.split(':', 1)[0]}", detail={'field': message, 'form': kind}))
    left_nothing = 0
    explained = []
    vanilla_lines = _log_lines(pair.vanilla)
    wipes = _shutdown_wipes(pair.modded)
    wiped = 0
    for message in sorted(_log_lines(pair.modded) - vanilla_lines):
        if message in wipes:
            wiped += 1
            continue
        m = _DUPLICATE_COUNT.match(message)
        if m:
            base = next((int(x.group(1)) for x in map(_DUPLICATE_COUNT.match, vanilla_lines) if x), 0)
            cf = common_files(pair)
            added = duplicates_added(cf)
            if int(m.group(1)) - base == added:
                explained.append(f"""the server's "Duplicated Asset Count: {m.group(1)}" is {added} more than the game alone's {base}, exactly what the {len(cf)} files the common files rule names add""")
                continue
        m = _MISSING_INTERACTION.match(message)
        if m and _left_nothing(pair.modded, bool(m.group(1)), m.group(2)):
            left_nothing += 1
            continue
        m = _LOST_PARENT.match(message)
        if m:
            parent, kind, child = m.groups()
            lost = _lost_fields(pair, kind, child)
            findings.append(Finding('server-says', kind, child, '', f"The game's own {kind} {child} loads without its parent {parent} in the game with the mod, and with it in the game alone: the engine's known loading issue (its own ticket HT-1539) drops the parent when the state loads first. " + (f"It loses {', '.join(lost)} that the game alone gives it." if lost else 'Its loaded value is the same as in the game alone.') + ' Which build trips it depends on how the files are grouped, not on what any one file says.', family="engine drops a game block's parent (HT-1539)", detail={'field': 'parent', 'form': 'lost parent', 'parent': parent, 'lost': lost, 'owner': 'game'}))
            continue
        named = _FOR_FILE.search(message)
        file = normal_path(named.group(1)) if named else ''
        ident = file.rsplit('/', 1)[-1].rsplit('.', 1)[0] if file.startswith('/') else re.sub('\\d+', 'N', message)[:120]
        findings.append(Finding('server-says', 'ServerLog', ident, file, f'''The game's own server prints "{message}" with the mod, and never without it.''' + ('' if file.startswith('/') else ' The line names no file.'), family='log:' + re.sub("'[^']*'", "'..'", re.sub('[0-9]+', 'N', message))[:90], detail={'field': message, 'form': 'log'}))
    pair.notes['server-says'] = explained + ([f'{left_nothing} "Missing interaction" line(s) the game alone never prints, each proven to leave nothing behind: the loaded game holds the real interaction'] if left_nothing else []) + ([f'{wiped} file watcher line(s) printed as the server shut down, about Patchly clearing its own generated folder: nothing a player meets'] if wiped else [])
    return findings

def common_files(pair: Pair) -> list[Finding]:
    path = pair.modded.folder / 'common-files.json'
    if not path.exists():
        return []
    data = json.loads(path.read_text(encoding='utf-8'))
    game_packs = {f['pack'] for g in json.loads((pair.vanilla.folder / 'common-files.json').read_text(encoding='utf-8'))['sameBytes'] for f in g['files']} if (pair.vanilla.folder / 'common-files.json').exists() else set()
    is_game = lambda f: f['pack'] in game_packs or f['pack'].startswith('Hytale:')
    hash_of = {(f['pack'], f['name']): g['hash'] for g in data['sameBytes'] for f in g['files']}
    findings: list[Finding] = []
    replaced = set()
    for group in data['sameName']:
        game = [f for f in group if is_game(f)]
        for f in group:
            if is_game(f) or not game:
                continue
            replaced.add((f['pack'], f['name']))
            if f.get('hash') and game[0].get('hash'):
                same = f['hash'] == game[0]['hash']
            else:
                same = hash_of.get((f['pack'], f['name'])) is not None and hash_of.get((f['pack'], f['name'])) == hash_of.get((game[0]['pack'], game[0]['name']))
            picture = not same and bool(f.get('picture')) and (f.get('picture') == game[0].get('picture'))
            form = 'identical' if same else 'same picture' if picture else 'different'
            findings.append(Finding('common-files', 'CommonFile', f['name'], f['name'], f"The mod ships {f['name']} under the game's own file name, so it replaces the game's file everywhere the game uses it" + {'identical': ', with identical bytes (no visible change)', 'same picture': ', with other bytes that hold the same picture (no visible change)', 'different': ", with different bytes (the game's own items or pages change too)"}[form] + '.', family='replaces a game file' + ('' if form == 'different' else f' ({form})'), detail={'field': 'replace', 'form': form, 'pack': f['pack'], 'effect': "the game's own items and pages using it change too" if form == 'different' else None}))
    for group in data['sameBytes']:
        files = group['files']
        mine = [f for f in files if not is_game(f) and (f['pack'], f['name']) not in replaced]
        if not mine:
            continue
        others = [f for f in files if f not in mine]
        for f in mine:
            twin = (others or [x for x in mine if x is not f])[0]
            findings.append(Finding('common-files', 'CommonFile', f['name'], f['name'], f"{f['name']} has the same bytes as {twin['name']} ({('the game' if is_game(twin) else 'the mod')}): {len(files) - 1} other file(s) hold the same bytes.", family='same bytes as another file', detail={'field': 'copy', 'form': 'game' if any((is_game(x) for x in files)) else 'mod', 'twin': twin['name'], 'group': sorted((x['name'] for x in mine)), 'effect': None}))
    return findings

def duplicates_added(findings: list[Finding]) -> int:
    replaced = sum((1 for f in findings if f.detail.get('field') == 'replace'))
    groups: dict[frozenset, int] = {}
    with_game: dict[frozenset, bool] = {}
    for f in findings:
        if f.detail.get('field') == 'copy':
            key = frozenset(f.detail['group'])
            groups[key] = groups.get(key, 0) + 1
            with_game[key] = f.detail.get('form') == 'game'
    return replaced + sum((n if with_game[k] else n - 1 for k, n in groups.items()))

def density_imports(snap) -> tuple[dict[str, set[tuple[str, str, str]]], set[str]]:
    imports: dict[str, set] = {}
    exports: set[str] = set()

    def walk(v, kind, ident, path):
        if isinstance(v, dict):
            if 'Inputs' in v and isinstance(v.get('ExportAs'), str) and v['ExportAs']:
                exports.add(v['ExportAs'])
            if v.get('Type') == 'Imported' and 'Inputs' in v and isinstance(v.get('Name'), str):
                imports.setdefault(v['Name'], set()).add((kind, ident, path))
            for k, x in v.items():
                walk(x, kind, ident, f'{path}.{k}' if path else k)
        elif isinstance(v, list):
            for x in v:
                walk(x, kind, ident, f'{path}.*' if path else '*')
    for full in snap.store_names.values():
        if 'hytalegenerator' in full:
            for a in snap.store(full).values():
                walk(a.asset, short(full), a.id, '')
    return (imports, exports)

def density_imports_resolve(pair: Pair) -> list[Finding]:
    imports, exports = density_imports(pair.modded)
    findings: list[Finding] = []
    for name, users in sorted(imports.items()):
        if name in exports:
            continue
        for kind, ident, path in sorted(users):
            base = pair.vanilla.store(kind).get(ident)
            mine = pair.modded.store(kind).get(ident)
            if base is not None and mine is not None and (base.asset == mine.asset):
                continue
            findings.append(Finding('density-imports', kind, ident, mine.path if mine else '', f'{ident} imports the density {name!r} (at {path}), which no density node exports in the game as loaded with the mod, so the world generator uses 0 in its place.', family=f'density import with no export: {name}', detail={'field': path, 'name': name, 'form': 'import'}))
    game_imports, game_exports = density_imports(pair.vanilla)
    own = sorted((n for n in game_imports if n not in game_exports))
    pair.notes['density-imports'] = [f"the game itself imports {len(own)} density name(s) nothing exports: {', '.join(own)}"] if own else []
    return findings

def _mod_files(mod: str) -> list[str] | None:
    import zipfile
    from pathlib import Path as _P
    p = _P(mod)
    try:
        if p.is_dir():
            return sorted(('/' + f.relative_to(p).as_posix() for f in p.rglob('*') if f.is_file() and f.relative_to(p).as_posix().startswith('Server/')))
        with zipfile.ZipFile(p) as z:
            return sorted(('/' + n for n in z.namelist() if n.startswith('Server/') and (not n.endswith('/'))))
    except (OSError, zipfile.BadZipFile):
        return None

def all_loaded(pair: Pair) -> list[Finding]:
    stores = pair.modded.store_folders()
    if not stores:
        return []
    game_packs = {a.pack for name in pair.vanilla.store_names.values() for a in pair.vanilla.store(name).values()}
    loaded: set[str] = set()
    for name in pair.modded.store_names.values():
        loaded |= {a.path for a in pair.modded.store(name).values() if a.path.startswith('/Server/') and a.pack not in game_packs}
    findings: list[Finding] = []
    unread = []
    for mod in pair.modded.run.get('mods', []):
        files = _mod_files(mod)
        if files is None:
            unread.append(mod)
            continue
        expected = [(f, kind) for f in files for kind, (folder, ext) in stores.items() if folder and f.startswith(f'/Server/{folder}/') and f.endswith(ext)]
        missing = sorted({f: kind for f, kind in expected if f not in loaded}.items())
        name = mod.replace('\\', '/').rsplit('/', 1)[-1]
        if expected and len(missing) == len({f for f, _ in expected}):
            findings.append(Finding('all-loaded', 'Mod', name, '', f"The game loaded none of the {len(missing)} files {name} ships where it loads files: the whole mod's content is missing in game.", family='the game loaded none of the mod', detail={'field': 'files', 'form': 'none loaded', 'count': len(missing)}))
            continue
        for f, kind in missing:
            findings.append(Finding('all-loaded', kind, f.rsplit('/', 1)[-1].rsplit('.', 1)[0], f, f'The game did not load {f}: no {kind} came from it, so it is missing in game.', family='files the game did not load', detail={'field': 'file', 'form': 'not loaded'}))
    pair.notes['all-loaded'] = [f"could not read the mod's own files to compare: {', '.join(unread)}"] if unread else []
    return findings

def fold_generated(findings: list[Finding], pair: Pair) -> list[Finding]:
    by_key = {(f.rule, f.kind, f.id, f.detail.get('field'), f.detail.get('form')): f for f in findings}
    kept: list[Finding] = []
    for f in findings:
        if not f.id.startswith('*'):
            kept.append(f)
            continue
        bare = f.id.lstrip('*')
        container = None
        cut = bare
        while '_' in cut and container is None:
            cut = cut.rsplit('_', 1)[0]
            for cand in (cut, '*' + cut, '**' + cut):
                if (f.rule, f.kind, cand, f.detail.get('field'), f.detail.get('form')) in by_key:
                    container = by_key[f.rule, f.kind, cand, f.detail.get('field'), f.detail.get('form')]
                    break
        if container is None:
            kept.append(f)
        else:
            container.detail.setdefault('states', []).append(f.id)
    for f in kept:
        n = len(f.detail.get('states', []))
        if n:
            f.fact += f' The same holds for {n} of its generated states.'
    return kept
RULES = [Rule('all-loaded', 'Every file the mod ships where the game loads files was loaded', 'A', 'G', ['files outside the folders an asset store loads (prefabs, worlds, language files, common files)', "a file loaded but then replaced by another pack's file with the same id"], all_loaded), Rule('density-imports', 'Every density a world file imports is exported somewhere', 'A', 'R + engine source', ['pattern, prop assignment and position imports (each resolves by its own rule, not yet read)', 'whether the generator ever reaches the import (a biome no world uses)'], density_imports_resolve), Rule('names-resolve', 'Every name a file gives leads to something in the game', 'A', 'R', ['a name in a place the game itself never uses for names', 'names inside Java code', 'language keys (the text shown for a name)', 'files the engine reads outside its asset stores (prefabs, world gen output, NPC roles)'], names_resolve), Rule('server-says', "What the game's own server reports with the mod and not without it", 'A, E', 'G', ['anything the server does not print in a bare boot (worlds, players, prefab and world gen validation, which need --validate-assets)', 'which mod file causes a line that names none'], server_says), Rule('common-files', "A mod file that replaces a game file by name, or repeats another file's bytes", 'G', 'G', ['whether a replacement is wanted', 'files the engine does not register as common (Server data)'], common_files), Rule('names-and-pictures', 'An item whose name has no text, or that shows the same name or icon as another', 'A, B', 'S', ['names built at run time by Java (tooltips, the Scribing Table)', 'languages other than English', 'pictures that differ in file but look alike'], names_and_pictures), Rule('like-its-kind', "A field the game's own assets of the same use always set", 'C', 'V', ['what the default does in play', 'assets nothing points at (no use, so no kind)', 'kinds with fewer than 20 game assets of that use', 'a value the kind uses rarely but not by default'], lambda pair: fold_generated(like_its_kind(pair), pair)), Rule('like-its-siblings', 'One item of a set differs from all the others', 'B, C', 'S', ['sets of fewer than 4 items', 'items with no SubCategory', 'a field that differs in value, not presence', 'a field more than a tenth of the group lacks (beyond one member)', "recipes (the reachable rule's question)", 'numbers of weapons and of pieces outside a set and slot', 'a number two or more pieces differ on'], lambda pair: like_its_siblings(pair) + sibling_values(pair)), Rule('reachable', 'A wearable or wieldable item a player can obtain, and a recipe that can be made', 'D', 'R + loaded game', ['whether a drop list is ever rolled', 'items handed out by Java code or commands', 'creatures wearing an item (NPC roles are not in the snapshot)', "whether a recipe's category is offered by its bench", 'a bench a block provides but no player can build', 'blocks and other items that are not armour or weapons'], reachable), Rule('foreign-family', 'A mod item naming the colour family of a game item it is not', 'B', 'R + loaded game', ["a family the mod's own pieces make (no game item wears its colours' model)", 'an item that only inherits the family (the table refuses it since 2026-10-27)'], foreign_family)]
