# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# Finds every linked group of models and pictures in a mod, and optimises each one.

import concurrent.futures, hashlib, json, os, shutil, subprocess, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pack_inventory
args = sys.argv[1:]

def take(flag, default=None):
    if flag in args:
        i = args.index(flag)
        v = args[i + 1]
        del args[i:i + 2]
        return v
    return default
vanilla = take('--vanilla', pack_inventory.DEFAULT_VANILLA)
jobs = int(take('--jobs', '4'))
cache = take('--cache')
if cache:
    cache = os.path.abspath(cache)
    os.makedirs(cache, exist_ok=True)
border = ['--border'] if '--border' in args else []
args = [a for a in args if a != '--border']
ROOT, OUT = (os.path.abspath(args[0]), os.path.abspath(args[1]))
COMMON = os.path.join(ROOT, 'Common')
os.makedirs(OUT, exist_ok=True)
pack = pack_inventory.Pack(ROOT, vanilla)
comps, undrawn = pack.components()
json.dump({'pack': ROOT, 'vanilla': vanilla, 'components': comps, 'undrawn': undrawn, 'unparsed': pack.unparsed}, open(os.path.join(OUT, 'inventory.json'), 'w', encoding='utf-8'), indent=1)
from PIL import Image

def texels(rel):
    with Image.open(os.path.join(COMMON, rel)) as im:
        return im.size[0] * im.size[1]

def file_sha(path):
    return hashlib.sha256(open(path, 'rb').read()).hexdigest()
import faces
CODE_KEY = hashlib.sha256(''.join((file_sha(p) for p in (os.path.join(HERE, 'optimize_family.py'), faces.__file__))).encode()).hexdigest()

def run(i, comp, mode):
    out = os.path.join(OUT, f'group_{i:03d}_{mode}')
    spec_path = os.path.join(OUT, f'group_{i:03d}.json')
    key = None
    if cache:
        h = hashlib.sha256()
        h.update(open(spec_path, 'rb').read())
        for rel in comp['models'] + comp['pictures']:
            h.update(rel.encode() + file_sha(os.path.join(COMMON, rel)).encode())
        h.update((mode + str(border) + CODE_KEY).encode())
        key = os.path.join(cache, h.hexdigest())
        if os.path.isfile(os.path.join(key, 'refused.json')):
            return (mode, json.load(open(os.path.join(key, 'refused.json'), encoding='utf-8')) | {'cached': True})
        if os.path.isfile(os.path.join(key, 'report.json')):
            shutil.copytree(key, out, dirs_exist_ok=True)
            return (mode, json.load(open(os.path.join(out, 'report.json'), encoding='utf-8')) | {'cached': True})
    p = subprocess.run([sys.executable, os.path.join(HERE, 'optimize_family.py'), COMMON, out, mode, spec_path] + border, capture_output=True, text=True)
    rep = os.path.join(out, 'report.json')
    if p.returncode == 0 and os.path.isfile(rep):
        result = json.load(open(rep, encoding='utf-8'))
        if key:
            shutil.copytree(out, key + '.tmp', dirs_exist_ok=True)
            os.replace(key + '.tmp', key)
        return (mode, result)
    result = {'refused': (p.stdout + p.stderr).strip().splitlines()[-1:]}
    if key and result['refused'] and result['refused'][0].startswith('STOP:'):
        os.makedirs(key, exist_ok=True)
        json.dump(result, open(os.path.join(key, 'refused.json'), 'w', encoding='utf-8'))
    return (mode, result)
rows = [None] * len(comps)
work = []
for i, c in enumerate(comps):
    in_pack = [p for p in c['pictures'] if p.lower() in pack.common]
    before = sum((texels(p) for p in in_pack))
    rows[i] = dict(group=i, family=c['pictures'][0], models=len(c['models']), pictures=len(c['pictures']), pairs=len(c['pairs']), before=before, best=before, chosen='kept')
    if not c['optimisable']:
        rows[i]['chosen'] = 'kept: left alone'
        rows[i]['reasons'] = c['reasons'][:6]
        continue
    json.dump({'models': c['models'], 'pictures': c['pictures'], 'pairs': c['pairs']}, open(os.path.join(OUT, f'group_{i:03d}.json'), 'w', encoding='utf-8'), indent=1)
    work += [(i, c, 'shared'), (i, c, 'pooled')]
with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as ex:
    results = list(ex.map(lambda w: (w[0],) + run(*w), work))
for i, mode, r in results:
    row = rows[i]
    if r.get('cached'):
        row['cached'] = row.get('cached', 0) + 1
    if 'texels_after' in r:
        row[mode] = r['texels_after']
        if r['texels_after'] < row['best']:
            row['best'], row['chosen'] = (r['texels_after'], mode)
    else:
        row.setdefault('refused', {})[mode] = r['refused']
json.dump(rows, open(os.path.join(OUT, 'mod_report.json'), 'w', encoding='utf-8'), indent=1)
before = sum((r['before'] for r in rows))
best = sum((r['best'] for r in rows))
by = {}
for r in rows:
    by[r['chosen']] = by.get(r['chosen'], 0) + 1
alone = sum((r['before'] for r in rows if r['chosen'].startswith('kept: left')))
refused = [r for r in rows if r.get('refused')]
print(f'groups {len(rows)}: chosen {by}')
print(f'model pictures before {before:,} texels, best lossless {best:,} ({100 * best / max(before, 1):.1f}%)')
print(f"left alone (Hytale's files, overrides, unresolved mentions): {alone:,} texels")
print(f'groups with a refused mode: {len(refused)}')
for r in refused[:10]:
    print('  ', r['family'], r['refused'])
print('EXIT=0')
