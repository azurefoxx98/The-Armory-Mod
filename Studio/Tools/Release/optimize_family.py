# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# Lays one group's pictures out at their smallest, proving every face reads the same texels.

import copy, hashlib, json, math, os, sys
import numpy as np
from PIL import Image
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from faces import FACES, face_corners_on_atlas, face_dims, sample, shapes
ROOT, OUT, MODE, SPEC = (sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
BORDER = 1 if '--border' in sys.argv[5:] else 0
spec = json.load(open(SPEC, encoding='utf-8'))

def canon(stack):
    best = None
    for k in range(4):
        r = np.rot90(stack, k, axes=(1, 2))
        for m in (r, r[:, :, ::-1]):
            key = (m.shape, m.tobytes())
            if best is None or key < best:
                best = key
    return best

def pack(rects):
    order = sorted(range(len(rects)), key=lambda i: (-rects[i][1], -rects[i][0]))
    area = sum((w * h for w, h in rects))
    widest = max((w for w, _ in rects))
    best = None
    for W in range(max(32, -(-widest // 32) * 32), 4097, 32):
        if W * W > 64 * area and best:
            break
        sky = [(0, 0, W)]
        pos = [None] * len(rects)
        ok = True
        for i in order:
            w, h = rects[i]
            cand = None
            for s in range(len(sky)):
                x = sky[s][0]
                if x + w > W:
                    break
                y, span, t = (0, 0, s)
                while span < w and t < len(sky):
                    y = max(y, sky[t][1])
                    span += sky[t][2]
                    t += 1
                if span < w:
                    continue
                if cand is None or (y, x) < (cand[0], cand[1]):
                    cand = (y, x)
            if cand is None:
                ok = False
                break
            y, x = cand
            pos[i] = (x, y)
            new, placed = ([], False)
            for sx, sy, sw in sky:
                ex = sx + sw
                if ex <= x or sx >= x + w:
                    new.append((sx, sy, sw))
                    continue
                if sx < x:
                    new.append((sx, sy, x - sx))
                if not placed:
                    new.append((x, y + h, w))
                    placed = True
                if ex > x + w:
                    new.append((x + w, sy, ex - (x + w)))
            merged = []
            for seg in sorted(new):
                if merged and merged[-1][1] == seg[1] and (merged[-1][0] + merged[-1][2] == seg[0]):
                    merged[-1] = (merged[-1][0], seg[1], merged[-1][2] + seg[2])
                else:
                    merged.append(seg)
            sky = merged
        if not ok:
            continue
        H = max((p[1] + rects[i][1] for i, p in enumerate(pos)))
        H = max(32, -(-H // 32) * 32)
        if best is None or W * H < best[0] * best[1]:
            best = (W, H, pos)
    return best

def turns():
    for angle in (0, 90, 180, 270):
        for mx in (False, True):
            for my in (False, True):
                yield (angle, mx, my)
uv_nodes = set()
for dp, _, fs in os.walk(ROOT):
    for fn in fs:
        if fn.endswith('.blockyanim'):
            try:
                a = json.load(open(os.path.join(dp, fn), encoding='utf-8'))
            except Exception:
                continue
            for node, tr in (a.get('nodeAnimations') or {}).items():
                for k in tr.get('shapeUvOffset') or []:
                    d = k.get('delta') or {}
                    if any((abs(float(d.get(c, 0) or 0)) > 0 for c in ('x', 'y'))):
                        uv_nodes.add(node)

def names(model):
    out = set()

    def rec(n):
        out.add(n.get('name'))
        for c in n.get('children', []):
            rec(c)
    for n in model['nodes']:
        rec(n)
    return out
pics = {rel: np.asarray(Image.open(os.path.join(ROOT, rel)).convert('RGBA')) for rel in spec['pictures']}
models = {rel: json.load(open(os.path.join(ROOT, rel), encoding='utf-8')) for rel in spec['models']}
before_texels = sum((p.shape[0] * p.shape[1] for p in pics.values()))
inputs = {rel: hashlib.sha256(open(os.path.join(ROOT, rel), 'rb').read()).hexdigest() for rel in spec['models'] + spec['pictures']}
for mrel, model in models.items():
    hit = names(model) & uv_nodes
    if hit:
        sys.exit(f'STOP: {mrel} has nodes a clip slides the texture of ({sorted(hit)[:3]}); this tool leaves it alone')
pairs = [tuple(p) for p in spec.get('pairs') or [[m, p] for m in spec['models'] for p in spec['pictures']]]
pair_set = set(pairs)
if any((m not in models or p not in pics for m, p in pairs)):
    sys.exit("STOP: a pair names a model or picture outside the spec's lists")
dressed = {m: [p for p in spec['pictures'] if (m, p) in pair_set] for m in spec['models']}
if MODE == 'pooled' and len({p.split('/', 1)[0] for p in spec['pictures']}) > 1:
    sys.exit(f"STOP: pooled needs one sheet per root folder, and these pictures span several ({sorted({p.split('/', 1)[0] for p in spec['pictures']})})")
if MODE == 'shared':
    units = [(m, dressed[m]) for m in spec['models'] if dressed[m]]
else:
    units = [(m, [p]) for m, p in pairs]
collapsed_axes = 0
records = []
work = []
for u, (mrel, prels) in enumerate(units):
    model = copy.deepcopy(models[mrel])
    work.append(model)
    for sh in shapes(model):
        layout = sh.get('textureLayout') or {}
        faces = [f for f in layout if (f in FACES if sh['type'] == 'box' else f == 'front')]
        grids = {}
        for f in faces:
            ua, va, fw, fh, _ = face_dims(sh, f)
            g = {p: sample(pics[p], layout[f], fw, fh) for p in prels}
            if any((v is None for v in g.values())):
                sys.exit(f'STOP: {mrel} face {f} reads off its picture; this tool leaves such models alone')
            grids[f] = (ua, va, fw, fh, g)
        if not grids:
            continue
        collapse = {}
        for axis in range(3):
            spans = [(ua, va, g) for ua, va, fw, fh, g in grids.values() if axis in (ua, va)]
            sz = float((sh.get('settings') or {}).get('size', {}).get('xyz'[axis], 0) or 0)
            collapse[axis] = sz > 0 and bool(spans) and all((bool((gr == gr[:, :1]).all()) if ua == axis else bool((gr == gr[:1, :]).all()) for ua, va, g in spans for gr in g.values()))
        settings = sh.setdefault('settings', {})
        size = settings.setdefault('size', {})
        stretch = sh.setdefault('stretch', {'x': 1.0, 'y': 1.0, 'z': 1.0})
        collapsed_axes += sum(collapse.values())
        for axis, k in enumerate('xyz'):
            if collapse[axis]:
                old = float(size[k])
                stretch[k] = float(stretch.get(k, 1.0) or 1.0) * old
                size[k] = 1
        for f, (ua, va, fw, fh, g) in grids.items():
            nfw = 1 if collapse[ua] else fw
            nfh = 1 if collapse[va] else fh
            ng = {p: gr[:nfh, :nfw] for p, gr in g.items()}
            records.append((u, sh, f, nfw, nfh, g, ng))
regions, region_of = ([], [])
index = {}
for u, sh, f, nfw, nfh, g, ng in records:
    prels = units[u][1]
    stack = np.stack([ng[p] for p in prels]) if MODE == 'shared' else ng[prels[0]][None]
    key = canon(stack)
    if MODE == 'shared':
        key = ('shared', tuple(prels)) + key
    if key not in index:
        index[key] = len(regions)
        regions.append((stack, list(prels)))
    region_of.append(index[key])
rects = [(r.shape[2] + 2 * BORDER, r.shape[1] + 2 * BORDER) for r, _ in regions]
W, H, pos = pack(rects)
pos = [(x + BORDER, y + BORDER) for x, y in pos]

def bordered(g):
    return np.pad(g, ((BORDER, BORDER), (BORDER, BORDER), (0, 0)), mode='edge')
if MODE == 'shared':
    sheets = {p: np.zeros((H, W, 4), np.uint8) for p in pics}
    for (r, rp), (x, y) in zip(regions, pos):
        for vi, p in enumerate(rp):
            sheets[p][y - BORDER:y + r.shape[1] + BORDER, x - BORDER:x + r.shape[2] + BORDER] = bordered(r[vi])
else:
    atlas = np.zeros((H, W, 4), np.uint8)
    for (r, _), (x, y) in zip(regions, pos):
        atlas[y - BORDER:y + r.shape[1] + BORDER, x - BORDER:x + r.shape[2] + BORDER] = bordered(r[0])
for (u, sh, f, nfw, nfh, g, ng), ri in zip(records, region_of):
    prels = units[u][1]
    x, y = pos[ri]
    found = None
    for angle, mx, my in turns():
        fd = {'offset': {'x': 0, 'y': 0}, 'mirror': {'x': mx, 'y': my}, 'angle': angle}
        _, (x0, y0, w2, h2) = face_corners_on_atlas(fd, nfw, nfh)
        if (w2, h2) != (regions[ri][0].shape[2], regions[ri][0].shape[1]):
            continue
        fd['offset'] = {'x': x - x0, 'y': y - y0}
        good = True
        for p in prels:
            sheet = sheets[p] if MODE == 'shared' else atlas
            got = sample(sheet, fd, nfw, nfh)
            if got is None or not np.array_equal(got, ng[p]):
                good = False
                break
        if good:
            found = fd
            break
    if found is None:
        sys.exit(f'STOP: no turn reproduces a face of {units[u][0]} ({f}); nothing written')
    sh['textureLayout'][f] = found
checked = 0
for u, sh, f, nfw, nfh, g, ng in records:
    for p in units[u][1]:
        sheet = sheets[p] if MODE == 'shared' else atlas
        got = sample(sheet, sh['textureLayout'][f], nfw, nfh)
        want = g[p]
        if not np.array_equal(np.broadcast_to(got, want.shape) if got.shape != want.shape else got, want):
            sys.exit(f'STOP: proof failed on {units[u][0]} {f} for {p}')
        checked += 1
outc = os.path.join(OUT, 'Common')
written = []
if MODE == 'shared':
    for (mrel, _), model in zip(units, work):
        d = os.path.join(outc, mrel)
        os.makedirs(os.path.dirname(d), exist_ok=True)
        json.dump(model, open(d, 'w', encoding='utf-8'), separators=(',', ':'))
        written.append(mrel)
    for p, s in sheets.items():
        d = os.path.join(outc, p)
        os.makedirs(os.path.dirname(d), exist_ok=True)
        Image.fromarray(s, 'RGBA').save(d, optimize=True)
        written.append(p)
    after_texels = W * H * len(pics)
copies = []
arel = None
if MODE == 'pooled':
    base = os.path.dirname(spec['pictures'][0])
    group_id = hashlib.sha1('\n'.join(sorted(spec['pictures'])).encode()).hexdigest()[:8]
    arel = base + f'/_Atlas_{group_id}.png'
    lower_pack = {p.lower() for p in spec['models'] + spec['pictures']}

    def copy_name(mrel, prel, long):
        stem = os.path.splitext(os.path.basename(prel))[0]
        if long:
            stem = os.path.basename(os.path.dirname(prel)) + '_' + stem
        return os.path.splitext(mrel)[0] + '__' + stem.replace(' ', '_') + '.blockymodel'
    names_short = [copy_name(m, p[0], False) for m, p in units]
    long = len({n.lower() for n in names_short}) < len(names_short)
    names_all = [copy_name(m, p[0], long) for m, p in units]
    if len({n.lower() for n in names_all}) < len(names_all) or lower_pack & {n.lower() for n in names_all + [arel]}:
        sys.exit("STOP: the copies' or the sheet's names clash with each other or with the group's own files")
    copies = [[m, p[0], n] for (m, p), n in zip(units, names_all)]
if MODE == 'shared':
    after_texels = W * H * len(pics)
else:
    os.makedirs(os.path.join(outc, base), exist_ok=True)
    Image.fromarray(atlas, 'RGBA').save(os.path.join(outc, arel), optimize=True)
    written.append(arel)
    for (m, p, rel), model in zip(copies, work):
        d = os.path.join(outc, rel)
        os.makedirs(os.path.dirname(d), exist_ok=True)
        json.dump(model, open(d, 'w', encoding='utf-8'), separators=(',', ':'))
        written.append(rel)
    after_texels = W * H
report = {'mode': MODE, 'border': BORDER, 'models': len(models), 'pictures': len(pics), 'pairs': len(pairs), 'faces': len(records), 'faces_checked_texel_for_texel': checked, 'regions': len(regions), 'region_texels': int(sum((r.shape[1] * r.shape[2] for r, _ in regions))), 'sheet': [W, H], 'texels_before': before_texels, 'texels_after': after_texels, 'share_of_before': round(after_texels / before_texels, 4), 'axes_collapsed': collapsed_axes, 'inputs': inputs, 'atlas': arel, 'copies': copies, 'written': written}
json.dump(report, open(os.path.join(OUT, 'report.json'), 'w', encoding='utf-8'), indent=1)
print(json.dumps({k: v for k, v in report.items() if k not in ('written', 'inputs', 'copies')}))
print('EXIT=0')
