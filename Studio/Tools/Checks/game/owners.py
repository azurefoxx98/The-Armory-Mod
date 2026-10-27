# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# Says whose each finding is: hers to fix in HyCreator, or ours.

from __future__ import annotations
import re
SHOWCASE = '(^|[/_*])(Armory_Showcase|Showcase_Theatre|Showcase)([_./]|$)'
LOOT_PATCHES = '(^|/)Server/Drops/Prefabs/[^/]+\\.patch$'
OWNERS = [('us', 'our showcase world', {'id': SHOWCASE}), ('us', 'our showcase world', {'file': SHOWCASE}), ('us', 'the chest patches our tools write', {'file': LOOT_PATCHES}), ('us', 'a stat only code can add', {'rule': '^names-resolve$', 'form': '^key$', 'field': '(^|\\.)StatModifiers$'}), ('us', 'a stat only code can add', {'gate': '^itemcheck$', 'line': 'changes the stat \\S+, which does not exist$'}), ('us', 'a stat only code can add', {'gate': '^assetcheck$', 'line': '^stat \\S+ is defined neither by us nor by the game$'}), ('us', 'tooltip names in other languages, a hitbox, a broken bench: only we can fix these', {'gate': '^assetcheck$'}), ('us', 'the check itself needs updating', {'line': 'stale accepted (entry|violation)'}), ('us', 'the Scribing Table is ours', {'id': '^Scribing_Table($|_)'}), ('us', "the Alteration Kit's interactions are ours", {'id': '^Alteration_Kit_(Feed|Use)$'}), ('us', 'the Mirror glow is ours', {'id': '^Mirror_Glow_Spawner$'})]

def _part(key: str, rule: str, ident: str, file: str, detail: dict, gate: str, line: str) -> str:
    return str({'rule': rule, 'id': ident, 'file': file, 'gate': gate, 'line': line}.get(key, detail.get(key)) or '')

def owner_of(rule: str='', ident: str='', file: str='', detail: dict | None=None, gate: str='', line: str='', used: set | None=None) -> tuple[str, str]:
    detail = detail or {}
    if detail.get('owner'):
        return (detail['owner'], "the game's own")
    for n, (owner, why, match) in enumerate(OWNERS):
        if all((re.search(p, _part(k, rule, ident, file or '', detail, gate, line)) for k, p in match.items())):
            if used is not None:
                used.add(n)
            return (owner, why)
    return ('mod', '')

def unmatched(used: set) -> list[str]:
    return [f"{why} ({', '.join((f'{k} {p}' for k, p in match.items()))})" for n, (owner, why, match) in enumerate(OWNERS) if n not in used and 'id' in match and (match['id'] != SHOWCASE)]
