# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# Checks the mod's benches and hitboxes, and that every tooltip line has a name.

from __future__ import annotations
import json
import math
import os
import struct
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pairedmods import paired_mod_of_stat
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MOD = os.environ.get('ARMORY_RESOURCES') or os.path.join(ROOT, 'The-Armory-Mod', 'src', 'main', 'resources')
ITEMS = os.path.join(MOD, 'Server', 'Item', 'Items')
HITBOXES = os.path.join(MOD, 'Server', 'Item', 'Block', 'Hitboxes')
LANGS = os.path.join(MOD, 'Server', 'Languages')
GAME = os.environ.get('ARMORY_GAME') or os.path.join(os.environ.get('APPDATA', ''), 'Hytale', 'install', 'release', 'package', 'game', 'latest')
problems: list[str] = []
notes: list[str] = []

def fail(msg):
    problems.append(msg)

def png_size(path):
    with open(path, 'rb') as f:
        head = f.read(33)
    if head[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('not a png')
    return struct.unpack('>II', head[16:24])

def _q_mul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return (aw * bx + ax * bw + ay * bz - az * by, aw * by - ax * bz + ay * bw + az * bx, aw * bz + ax * by - ay * bx + az * bw, aw * bw - ax * bx - ay * by - az * bz)

def _q_rot(q, v):
    x, y, z, w = q
    vx, vy, vz = v
    tx = 2 * (y * vz - z * vy)
    ty = 2 * (z * vx - x * vz)
    tz = 2 * (x * vy - y * vx)
    return (vx + w * tx + (y * tz - z * ty), vy + w * ty + (z * tx - x * tz), vz + w * tz + (x * ty - y * tx))
FACE_AXES = {'front': (0, 1), 'back': (0, 1), 'left': (2, 1), 'right': (2, 1), 'top': (0, 2), 'bottom': (0, 2)}

def model_faces(path):
    doc = json.load(open(path, encoding='utf-8-sig'))

    def visit(nodes):
        for n in nodes:
            sh = n.get('shape')
            if sh and sh.get('type') == 'box' and (sh.get('visible') is not False):
                size = (sh.get('settings', {}) or {}).get('size', {}) or {}
                dims = [size.get(k, 0.0) for k in 'xyz']
                for face, (a, b) in FACE_AXES.items():
                    f = (sh.get('textureLayout') or {}).get(face)
                    if not f:
                        continue
                    w, h = (dims[a], dims[b])
                    if f.get('angle', 0) % 360 in (90, 270):
                        w, h = (h, w)
                    off = f.get('offset', {})
                    mirror = f.get('mirror', {}) or {}
                    x, y = (off.get('x', 0), off.get('y', 0))
                    if mirror.get('x'):
                        x -= w
                    if mirror.get('y'):
                        y -= h
                    yield (n.get('name'), face, x, y, w, h)
            yield from visit(n.get('children', []) or [])
    yield from visit(doc.get('nodes', []))

def check_model_texture(model, texture, label):
    tw, th = png_size(texture)
    out = []
    for node, face, x, y, w, h in model_faces(model):
        if w <= 0 or h <= 0:
            continue
        if x < 0 or y < 0 or x + w > tw or (y + h > th):
            out.append((node, face, x, y, w, h))
    if out:
        worst = max((max(-o[2], 0) + max(-o[3], 0) + max(o[2] + o[4] - tw, 0) + max(o[3] + o[5] - th, 0) for o in out))
        notes.append('%s: %d face(s) fall outside the %dx%d sheet (worst %d px). REPORT ONLY, this formula still disagrees with 14 of 64 shipped pairs' % (label, len(out), tw, th, worst))
    return (tw, th)

def footprint(boxes):
    lo = [min((b['Min'].get(k, 0.0) for b in boxes)) for k in 'XYZ']
    hi = [max((b['Max'].get(k, 0.0) for b in boxes)) for k in 'XYZ']
    dims = []
    for i in range(3):
        mn = math.floor(lo[i] + 1e-09)
        mx = math.ceil(hi[i] - 1e-09)
        dims.append(max(mx - mn, 1))
    return (dims, dims[0] * dims[1] * dims[2], lo, hi)

def check_hitbox(path, label):
    boxes = json.load(open(path, encoding='utf-8-sig'))['Boxes']
    if not boxes:
        fail('%s: no boxes, the codec requires a non-empty array' % label)
        return
    for i, b in enumerate(boxes):
        for k in 'XYZ':
            if b['Max'][k] - b['Min'][k] <= 0:
                fail('%s: box %d has a non-positive %s extent, Box.CODEC will reject it' % (label, i, k))
    dims, cells, lo, hi = footprint(boxes)
    if lo[1] < 0:
        fail('%s: Ymin is %.3f, it dips below its own floor and the block will not place' % (label, lo[1]))
    notes.append('%s: %d boxes, footprint %dx%dx%d = %d cells, Y from %.3f' % (label, len(boxes), dims[0], dims[1], dims[2], cells, lo[1]))
    return cells

def lang_keys(path):
    keys = {}
    with open(path, encoding='utf-8-sig') as f:
        for line in f:
            if '=' in line and (not line.lstrip().startswith('#')):
                k, v = line.split('=', 1)
                keys[k.strip()] = v.strip()
    return keys

def stat_hidden(stat_id, zf):
    ours = os.path.join(MOD, 'Server', 'Entity', 'Stats', stat_id + '.json')
    if os.path.isfile(ours):
        return bool(json.load(open(ours, encoding='utf-8-sig')).get('HideFromTooltip', False))
    try:
        return bool(json.loads(zf.read('Server/Entity/Stats/%s.json' % stat_id).decode('utf-8-sig')).get('HideFromTooltip', False))
    except KeyError:
        fail('stat %s is defined neither by us nor by the game' % stat_id)
        return True

def check_tooltip_names():
    import zipfile
    vanilla_lang = os.path.join(GAME, 'Client', 'Data', 'Shared', 'Language', 'en-US', 'client.lang')
    assets = os.path.join(GAME, 'Assets.zip')
    if not (os.path.isfile(vanilla_lang) and os.path.isfile(assets)):
        fail("tooltip names: the installed game's client.lang or Assets.zip is unreadable")
        return
    vanilla = lang_keys(vanilla_lang)
    ours = {loc: lang_keys(os.path.join(LANGS, loc, 'client.lang')) for loc in sorted(os.listdir(LANGS)) if os.path.isfile(os.path.join(LANGS, loc, 'client.lang'))}
    if 'en-US' not in ours:
        fail('tooltip names: we ship no en-US client.lang')
        return
    for loc, keys in ours.items():
        for k, v in keys.items():
            if k.startswith('itemTooltip.') and '{value}' not in v:
                fail('%s client.lang: %s has no {value} slot, so its amount never shows' % (loc, k))
        for k in set(ours['en-US']) - set(keys):
            fail('%s client.lang lacks %s, which en-US has' % (loc, k))
    needed = {}
    zf = zipfile.ZipFile(assets)
    files = 0
    for dirpath, _, names in os.walk(ITEMS):
        for f in names:
            if not f.endswith('.json'):
                continue
            files += 1
            doc = json.load(open(os.path.join(dirpath, f), encoding='utf-8-sig'))
            for block in ('Armor', 'Weapon', 'Utility'):
                b = doc.get(block)
                if not isinstance(b, dict):
                    continue
                for stat in b.get('StatModifiers') or {}:
                    needed.setdefault(('stat', stat), f)
                for cause in b.get('DamageResistance') or {}:
                    needed.setdefault(('res', cause.lower()), f)
                for cause in b.get('DamageEnhancement') or {}:
                    needed.setdefault(('enh', cause.lower()), f)
                for cls in b.get('DamageClassEnhancement') or {}:
                    needed.setdefault(('cls', cls.lower()), f)
    hidden = {}
    for (kind, name), example in sorted(needed.items()):
        if kind == 'stat':
            keys = ['itemTooltip.stats.' + name]
        elif kind == 'res':
            keys = ['itemTooltip.damageCauseResistance.' + name]
        else:
            family = 'damageEnhancementCause' if kind == 'enh' else 'damageClassEnhancement'
            keys = ['itemTooltip.%s.%s.%s' % (family, way, name) for way in ('increase', 'decrease', 'modify')]
        for key in keys:
            if key in vanilla:
                continue
            if kind == 'stat':
                if paired_mod_of_stat(name):
                    continue
                if name not in hidden:
                    hidden[name] = stat_hidden(name, zf)
                if hidden[name]:
                    continue
            for loc, mine in ours.items():
                if key not in mine:
                    fail('%s client.lang lacks %s, needed by %s and others' % (loc, key, example))
    notes.append('tooltip names: %d item files, %d distinct tooltip lines, %d languages' % (files, len(needed), len(ours)))

def main():
    print('== models and their textures ==')
    pairs = [(os.path.join(MOD, 'Common/Items/Blocks/Workbenches/Scribing/Scribing_Bench.blockymodel'), os.path.join(MOD, 'Common/Items/Blocks/Workbenches/Scribing/Scribing_Bench.png'), 'Scribing_Bench'), (os.path.join(MOD, 'Common/Items/AlterationKit/AlterationKit.blockymodel'), os.path.join(MOD, 'Common/Items/AlterationKit/AlterationKit_Texture.png'), 'AlterationKit'), (os.path.join(MOD, 'Common/Items/Blocks/Workbenches/Alteration/AlterationTable.blockymodel'), os.path.join(MOD, 'Common/Items/Blocks/Workbenches/Alteration/AlterationTable.png'), 'AlterationTable'), (os.path.join(MOD, 'Common/Items/ScribeLock/Scribe_Lock.blockymodel'), os.path.join(MOD, 'Common/Items/ScribeLock/Scribe_Lock.png'), 'Scribe_Lock (held, shipped)')]
    for model, tex, label in pairs:
        if not (os.path.isfile(model) and os.path.isfile(tex)):
            fail('%s: model or texture missing' % label)
            continue
        tw, th = check_model_texture(model, tex, label)
        print('   %-32s texture %dx%d' % (label, tw, th))
    print('\n== hitboxes we added ==')
    if os.path.isdir(HITBOXES):
        for dirpath, _, files in os.walk(HITBOXES):
            for f in sorted(files):
                if f.endswith('.json'):
                    check_hitbox(os.path.join(dirpath, f), f[:-5])
    print('\n== does every modelled prop actually have collision ==')
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'hitbox-fit'))
    try:
        import boxes as bx
        from blockymodel import evaluate_parts
    except Exception as e:
        notes.append('per-part coverage skipped, hitbox-fit not importable (%s)' % e)
    else:
        for label, model, asset in [('AlterationTable', os.path.join(MOD, 'Common/Items/Blocks/Workbenches/Alteration/AlterationTable.blockymodel'), 'ArmoryAlterationTable'), ('Scribing_Bench', os.path.join(MOD, 'Common/Items/Blocks/Workbenches/Scribing/Scribing_Bench.blockymodel'), 'ArmoryScribingTable')]:
            hb = os.path.join(HITBOXES, 'Bench', asset + '.json')
            if not (os.path.isfile(model) and os.path.isfile(hb)):
                continue
            parts, _ = evaluate_parts(model)
            raw = json.load(open(hb, encoding='utf-8-sig'))['Boxes']
            hull = [bx.Box((b['Min']['X'], b['Min']['Y'], b['Min']['Z']), (b['Max']['X'], b['Max']['Y'], b['Max']['Z'])) for b in raw]
            weak = []
            for p in parts:
                clipped = []
                for h in hull:
                    lo = tuple((max(p.box.lo[a], h.lo[a]) for a in range(3)))
                    hi = tuple((min(p.box.hi[a], h.hi[a]) for a in range(3)))
                    if all((hi[a] - lo[a] > 1e-12 for a in range(3))):
                        clipped.append(bx.Box(lo, hi))
                frac = bx.union_volume(clipped) / max(p.box.volume(), 1e-12) if clipped else 0.0
                if frac < 0.95:
                    weak.append((frac, p.node_path))
            if weak:
                weak.sort()
                notes.append('%s: %d of %d parts under 95%% collision coverage: %s' % (label, len(weak), len(parts), ', '.join(('%s %.0f%%' % (path.split('/')[-2], frac * 100) for frac, path in weak))))
            else:
                notes.append('%s: all %d parts at 95%% collision coverage or better' % (label, len(parts)))
    print('\n== every hitbox id referenced by our items resolves ==')
    have = set()
    for dirpath, _, files in os.walk(HITBOXES):
        have.update((f[:-5] for f in files if f.endswith('.json')))
    vanilla = os.environ.get('VANILLA_HITBOX_IDS', '')
    known = have | set(filter(None, vanilla.split(',')))
    for f in sorted(os.listdir(ITEMS)):
        if not f.endswith('.json'):
            continue
        doc = json.load(open(os.path.join(ITEMS, f), encoding='utf-8-sig'))
        bt = doc.get('BlockType') or {}
        for key in ('HitboxType', 'InteractionHitboxType'):
            val = bt.get(key)
            if val and val.startswith('Armory') and (val not in have):
                fail("%s references %s '%s' which we do not ship" % (f, key, val))
    print('\n== every tooltip line our items produce has a name ==')
    check_tooltip_names()
    for n in notes:
        print('   ' + n)
    print()
    if problems:
        print('FAILED, %d problem(s):' % len(problems))
        for p in problems:
            print('   ' + p)
        return 1
    print('all checks passed')
    return 0
if __name__ == '__main__':
    sys.exit(main())
