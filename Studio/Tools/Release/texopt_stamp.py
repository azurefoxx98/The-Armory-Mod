# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# Writes and checks the stamp that says a jar was optimised by these tools.

import hashlib, json, os, sys, zipfile
HERE = os.path.dirname(os.path.abspath(__file__))
NAME = 'META-INF/texopt.json'

def tools_fingerprint():
    h = hashlib.sha256()
    for f in sorted(os.listdir(HERE)):
        if f.endswith('.py'):
            h.update(f.encode() + hashlib.sha256(open(os.path.join(HERE, f), 'rb').read()).digest())
    return h.hexdigest()

def art_fingerprint(z):
    h = hashlib.sha256()
    names = sorted((n for n in z.namelist() if n.startswith(('Common/', 'Server/')) and (not n.endswith('/'))))
    for n in names:
        h.update(n.encode() + hashlib.sha256(z.read(n)).digest())
    return (h.hexdigest(), len(names))

def write(jar, summary):
    with zipfile.ZipFile(jar) as z:
        art, _ = art_fingerprint(z)
        if NAME in z.namelist():
            raise RuntimeError('the jar already holds a stamp')
        stamp = max((i.date_time for i in z.infolist()))
    body = json.dumps({'tools': tools_fingerprint(), 'art': art, 'summary': summary}, indent=1, sort_keys=True)
    with zipfile.ZipFile(jar, 'a', zipfile.ZIP_DEFLATED) as z:
        info = zipfile.ZipInfo(NAME, date_time=stamp)
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 420 << 16
        z.writestr(info, body)

def check(jar):
    with zipfile.ZipFile(jar) as z:
        art, n = art_fingerprint(z)
        if n == 0:
            return (True, 'no model art in this jar')
        if NAME not in z.namelist():
            return (False, 'not optimised: the jar has no texopt stamp (build it with optimize_jar.py)')
        s = json.loads(z.read(NAME))
    if s['art'] != art:
        return (False, "the jar's art changed after it was optimised")
    if s['tools'] != tools_fingerprint():
        return (False, 'optimised by older tools: build it again')
    sm = s.get('summary', {})
    return (True, f"optimised: {sm.get('share', 1):.1%} of the texels, {len(sm.get('defects', []))} defect(s) reported")
if __name__ == '__main__':
    if len(sys.argv) < 3 or sys.argv[1] != 'check':
        print(__doc__)
        sys.exit(2)
    bad = 0
    for jar in sys.argv[2:]:
        try:
            ok, why = check(jar)
        except Exception as e:
            ok, why = (False, f'unreadable: {e}')
        bad += not ok
        print(('OK   ' if ok else 'FAIL ') + os.path.basename(jar) + ': ' + why)
    sys.exit(1 if bad else 0)
