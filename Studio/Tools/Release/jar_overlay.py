# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# Copies a jar and lays exactly the changed files over it.

import hashlib, json, os, sys, zipfile
JAR, ROOT, BACKUP, NEW = sys.argv[1:5]
manifest = json.load(open(os.path.join(BACKUP, 'manifest.json'), encoding='utf-8'))
z = zipfile.ZipFile(JAR)
names = {n.lower(): n for n in z.namelist()}
entries = {e['path']: e for e in manifest['files']}
for path, e in entries.items():
    if e['before']:
        n = names.get(path.lower())
        if n is None or hashlib.sha256(z.read(n)).hexdigest() != e['before']:
            sys.exit(f"STOP: the jar's {path} is not the source's original: build the jar from that source first")
    elif path.lower() in names:
        sys.exit(f'STOP: the jar already holds {path}, which the apply added')
    if e['after']:
        full = os.path.join(ROOT, path)
        if not os.path.isfile(full) or hashlib.sha256(open(full, 'rb').read()).hexdigest() != e['after']:
            sys.exit(f"STOP: the source's {path} is not what the apply wrote (undone, or changed since)")
replaced = {names[p.lower()]: p for p, e in entries.items() if e['before'] and e['after']}
deleted = {names[p.lower()] for p, e in entries.items() if e['before'] and (not e['after'])}
with zipfile.ZipFile(NEW, 'w', zipfile.ZIP_DEFLATED) as out:
    for info in z.infolist():
        if info.filename in deleted or info.filename == 'META-INF/texopt.json':
            continue
        if info.filename in replaced:
            out.writestr(info, open(os.path.join(ROOT, replaced[info.filename]), 'rb').read())
        else:
            out.writestr(info, z.read(info))
    stamp = max((i.date_time for i in z.infolist()))
    for p, e in sorted(entries.items()):
        if not e['before']:
            info = zipfile.ZipInfo(p, date_time=stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 420 << 16
            out.writestr(info, open(os.path.join(ROOT, p), 'rb').read())
print(json.dumps({'replaced_or_rewritten': len(replaced), 'deleted': len(deleted), 'added': sum((1 for e in entries.values() if not e['before'])), 'jar_bytes_before': os.path.getsize(JAR), 'jar_bytes_after': os.path.getsize(NEW)}))
print('EXIT=0')
