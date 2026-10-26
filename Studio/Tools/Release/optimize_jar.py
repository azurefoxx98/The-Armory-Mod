# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# Optimises the model textures inside a release jar, proves every face unchanged, and stamps it.

import json, os, shutil, subprocess, sys, tempfile, time, zipfile
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import copy
import texopt_stamp
STAMP_KEYS = ('applied_groups', 'pairs_proved', 'faces_identical', 'texels_before', 'texels_after', 'share', 'defects')
args = sys.argv[1:]

def copy_without_stamp(src, dst):
    with zipfile.ZipFile(src) as z, zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as o:
        for info in z.infolist():
            if info.filename != texopt_stamp.NAME:
                o.writestr(copy.copy(info), z.read(info.filename))

def stamp(summary):
    texopt_stamp.write(OUT, {k: summary.get(k) for k in STAMP_KEYS})

def take(flag, default=None):
    if flag in args:
        i = args.index(flag)
        v = args[i + 1]
        del args[i:i + 2]
        return v
    return default
cache, vanilla, keep = (take('--cache'), take('--vanilla'), take('--keep'))
flags = [a for a in args if a.startswith('--')]
args = [a for a in args if not a.startswith('--')]
border, strict = ('--border' in flags, '--strict' in flags)
if len(args) != 2:
    print(__doc__)
    print('EXIT=2')
    sys.exit(2)
IN, OUT = (os.path.abspath(args[0]), os.path.abspath(args[1]))
work = os.path.abspath(keep) if keep else tempfile.mkdtemp(prefix='optimize-jar-')
started = time.time()

def step(name, cmd):
    p = subprocess.run([sys.executable] + cmd, capture_output=True, text=True)
    if p.returncode != 0:
        print(f'{name} failed:\n' + (p.stdout + p.stderr)[-3000:])
        fail()
    return p.stdout

def fail():
    if os.path.exists(OUT + '.tmp'):
        os.remove(OUT + '.tmp')
    print('EXIT=2')
    sys.exit(2)

def art_key():
    import hashlib
    h = hashlib.sha256()
    with zipfile.ZipFile(IN) as z:
        for n in sorted((n for n in z.namelist() if n.startswith(('Common/', 'Server/')) and (not n.endswith('/')))):
            h.update(n.encode() + hashlib.sha256(z.read(n)).digest())
    for f in sorted(os.listdir(HERE)):
        if f.endswith('.py'):
            h.update(f.encode() + hashlib.sha256(open(os.path.join(HERE, f), 'rb').read()).digest())
    h.update(f'{border}|{vanilla}'.encode())
    return h.hexdigest()
if cache:
    stored = os.path.join(os.path.abspath(cache), 'jar-' + art_key())
    if os.path.isfile(os.path.join(stored, 'summary.json')):
        summary = json.load(open(os.path.join(stored, 'summary.json'), encoding='utf-8'))
        if summary['applied_groups']:
            step('jar_overlay', [os.path.join(HERE, 'jar_overlay.py'), IN, os.path.join(stored, 'files'), stored, OUT + '.tmp'])
            os.replace(OUT + '.tmp', OUT)
        else:
            copy_without_stamp(IN, OUT)
        stamp(summary)
        summary.update({'jar_bytes_before': os.path.getsize(IN), 'jar_bytes_after': os.path.getsize(OUT), 'art_unchanged_reused': True, 'seconds': round(time.time() - started, 1)})
        print(json.dumps(summary))
        defects = summary['defects']
        if not keep:
            shutil.rmtree(work, ignore_errors=True)
        code = 1 if strict and defects else 0
        print(f'EXIT={code}')
        sys.exit(code)
try:
    pack = os.path.join(work, 'pack')
    if os.path.exists(pack):
        shutil.rmtree(pack)
    with zipfile.ZipFile(IN) as z:
        assets = [n for n in z.namelist() if n.startswith(('Common/', 'Server/')) and (not n.endswith('/'))]
        for n in assets + [n for n in ('manifest.json',) if n in z.namelist()]:
            z.extract(n, pack)
    opt = os.path.join(work, 'opt')
    if os.path.exists(opt):
        shutil.rmtree(opt)
    extra = (['--border'] if border else []) + (['--vanilla', vanilla] if vanilla else []) + (['--cache', cache] if cache else [])
    step('optimize_mod', [os.path.join(HERE, 'optimize_mod.py'), pack, opt] + extra)
    rows = json.load(open(os.path.join(opt, 'mod_report.json'), encoding='utf-8'))
    defects = [(r['family'], ' '.join(w).replace('STOP: ', '').split(';')[0]) for r in rows for w in list((r.get('refused') or {}).values())[:1] if 'reads off its picture' in ' '.join(w)]
    chosen = [r for r in rows if r['chosen'] in ('shared', 'pooled')]
    before, best = (sum((r['before'] for r in rows)), sum((r['best'] for r in rows)))
    if not chosen:
        copy_without_stamp(IN, OUT)
        summary = {'applied_groups': 0}
    else:
        backup = os.path.join(work, 'backup')
        if os.path.exists(backup):
            shutil.rmtree(backup)
        step('apply', [os.path.join(HERE, 'apply_to_source.py'), 'apply', pack, opt, backup] + (['--vanilla', vanilla] if vanilla else []))
        proof = json.load(open(os.path.join(backup, 'proof.json'), encoding='utf-8'))
        if not proof['proved']:
            print('the proof failed: ' + json.dumps(proof['problems'][:5]))
            fail()
        step('jar_overlay', [os.path.join(HERE, 'jar_overlay.py'), IN, pack, backup, OUT + '.tmp'])
        os.replace(OUT + '.tmp', OUT)
        summary = {'applied_groups': len(chosen), 'pairs_proved': proof['pairs'], 'faces_identical': proof['faces_identical']}
    summary.update({'texels_before': before, 'texels_after': best, 'share': round(best / max(before, 1), 4), 'jar_bytes_before': os.path.getsize(IN), 'jar_bytes_after': os.path.getsize(OUT), 'groups_from_cache': sum((1 for r in rows if r.get('cached'))), 'defects': [f'{fam}: {why}' for fam, why in defects], 'seconds': round(time.time() - started, 1)})
    stamp(summary)
    summary['jar_bytes_after'] = os.path.getsize(OUT)
    if cache:
        tmp_store = stored + '.tmp'
        shutil.rmtree(tmp_store, ignore_errors=True)
        os.makedirs(tmp_store)
        if chosen:
            shutil.copyfile(os.path.join(backup, 'manifest.json'), os.path.join(tmp_store, 'manifest.json'))
            for e in json.load(open(os.path.join(backup, 'manifest.json'), encoding='utf-8'))['files']:
                if e['after']:
                    dst = os.path.join(tmp_store, 'files', e['path'])
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    shutil.copyfile(os.path.join(pack, e['path']), dst)
        json.dump({k: v for k, v in summary.items() if k not in ('seconds', 'jar_bytes_before', 'jar_bytes_after')}, open(os.path.join(tmp_store, 'summary.json'), 'w', encoding='utf-8'))
        shutil.rmtree(stored, ignore_errors=True)
        os.replace(tmp_store, stored)
    print(json.dumps(summary))
finally:
    if not keep:
        shutil.rmtree(work, ignore_errors=True)
code = 1 if strict and defects else 0
print(f'EXIT={code}')
sys.exit(code)
