# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# Lists every place a mod draws a model with a picture, as the game resolves it.

import collections, json, os, re, sys, zipfile
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from jsonspan import parse, JsonSpanError
DEFAULT_VANILLA = os.path.expandvars('%APPDATA%/Hytale/install/release/package/game/latest/Assets.zip')
MODEL_KEYS = ('Model', 'CustomModel')
SCOPES = ('Item/Items', 'Item/Block/Blocks', 'Models')
BIG = 4 * 1024 * 1024
TOKEN = re.compile('[^\\"\'\\r\\n\\t:,\\[\\]{}()<>=|*?]*?\\.(?:png|blockymodel)\\b', re.I)
BINARY = ('.png', '.jpg', '.jpeg', '.ogg', '.wav', '.bson', '.lpf', '.zip', '.jar', '.class', '.ttf', '.otf', '.webm')

def norm(path):
    return path.replace('\\', '/').lower()

def scope_of(server_rel):
    for s in SCOPES:
        if server_rel.startswith(s + '/'):
            return s
    return server_rel.split('/', 1)[0]

def suffixes(token):
    yield token
    for i, c in enumerate(token):
        if c in '/ \\':
            yield token[i + 1:]

class Pack:

    def __init__(self, root, vanilla=DEFAULT_VANILLA, overrides=None, removed=(), added=()):
        self.root = os.path.abspath(root)
        self.vanilla_path = vanilla
        self.overrides = dict(overrides or {})
        removed = set(removed)
        self.common = {}
        self.text_files = []
        rels = []
        for dp, _, fs in os.walk(self.root):
            for f in fs:
                rels.append(os.path.relpath(os.path.join(dp, f), self.root).replace(os.sep, '/'))
        for rel in [r for r in rels if r not in removed] + [a for a in added if a not in rels]:
            if rel.startswith('Common/'):
                self.common[norm(rel[7:])] = rel[7:]
            if not rel.lower().endswith(BINARY):
                self.text_files.append(rel)
        z = zipfile.ZipFile(vanilla)
        self.zip = z
        self.vanilla_common = {norm(n[7:]) for n in z.namelist() if n.startswith('Common/')}
        self.vanilla_ids = {}
        for n in z.namelist():
            if n.startswith('Server/') and n.endswith('.json'):
                srel = n[7:]
                self.vanilla_ids.setdefault((scope_of(srel), os.path.basename(n)[:-5]), n)
        self.docs = {}
        self.ids = {}
        self.unparsed = []
        for rel in self.text_files:
            if not rel.lower().endswith('.json') or not rel.startswith('Server/'):
                continue
            if rel not in self.overrides and os.path.getsize(os.path.join(self.root, rel)) > BIG:
                continue
            try:
                self.docs[rel] = parse(self.read_text(rel))
            except (UnicodeDecodeError, JsonSpanError) as e:
                self.unparsed.append((rel, str(e)))
                continue
            self.ids[scope_of(rel[7:]), os.path.basename(rel)[:-5]] = rel
        self._vdocs = {}

    def read_text(self, rel):
        if rel in self.overrides:
            return self.overrides[rel].lstrip('\ufeff')
        with open(os.path.join(self.root, rel), encoding='utf-8-sig', newline='') as f:
            return f.read()

    def vdoc(self, name):
        if name not in self._vdocs:
            try:
                self._vdocs[name] = (json.loads(self.zip.read(name).decode('utf-8-sig')), None)
            except Exception:
                self._vdocs[name] = ({}, None)
        return self._vdocs[name]

    def parent_frame(self, doc_key, scope):
        value = self.get_doc(doc_key)[0]
        parent = value.get('Parent') if isinstance(value, dict) else None
        if not isinstance(parent, str):
            return None
        if (scope, parent) in self.ids:
            return ('pack', self.ids[scope, parent])
        if (scope, parent) in self.vanilla_ids:
            return ('vanilla', self.vanilla_ids[scope, parent])
        return None

    def get_doc(self, doc_key):
        kind, name = doc_key
        return self.docs[name] if kind == 'pack' else self.vdoc(name)

    def node(self, doc_key, ptr):
        v = self.get_doc(doc_key)[0]
        for k in ptr:
            v = v[k]
        return v

    def frames(self, doc_key, ptr, scope, depth=0):
        if depth > 24:
            return [(doc_key, ptr)]
        out = [(doc_key, ptr)]
        if ptr == ():
            p = self.parent_frame(doc_key, scope)
            if p:
                out += self.frames(p, (), scope, depth + 1)
        elif len(ptr) >= 2 and ptr[-2] == 'State' and (scope == 'Item/Items'):
            out += self.frames(doc_key, ptr[:-2], scope, depth + 1)
        elif len(ptr) >= 3 and ptr[-2] == 'Definitions' and (ptr[-3] == 'State'):
            out += self.frames(doc_key, ptr[:-3], scope, depth + 1)
        return out

    def effective(self, doc_key, ptr, scope):
        model = mplace = None
        textures, tplaces = (None, None)
        for fk, fp in self.frames(doc_key, ptr, scope):
            obj = self.node(fk, fp)
            if not isinstance(obj, dict):
                continue
            if model is None:
                for k in MODEL_KEYS:
                    if isinstance(obj.get(k), str):
                        model, mplace = (obj[k], (fk, fp + (k,)))
                        break
            if textures is None:
                vals, places = ([], [])
                if isinstance(obj.get('Texture'), str):
                    vals.append(obj['Texture'])
                    places.append((fk, fp + ('Texture',)))
                cmt = obj.get('CustomModelTexture')
                if isinstance(cmt, list):
                    for i, e in enumerate(cmt):
                        if isinstance(e, dict) and isinstance(e.get('Texture'), str):
                            vals.append(e['Texture'])
                            places.append((fk, fp + ('CustomModelTexture', i, 'Texture')))
                if vals:
                    textures, tplaces = (vals, places)
            if model is not None and textures is not None:
                break
        return ((model, mplace), (textures, tplaces))

    def inventory(self):
        pairs = collections.defaultdict(list)
        explained = set()
        undrawn = []
        for rel, (value, spans) in self.docs.items():
            scope = scope_of(rel[7:])
            key = ('pack', rel)
            for ptr in [k[0] for k in spans if len(k) == 2 and k[1] == '{members}' and isinstance(k[0], tuple)]:
                if len(ptr) >= 2 and ptr[-2] == 'CustomModelTexture':
                    continue
                obj = self.node(key, ptr)
                own_model = any((isinstance(obj.get(k), str) and obj[k].lower().endswith('.blockymodel') for k in MODEL_KEYS))
                own_tex = isinstance(obj.get('Texture'), str) or isinstance(obj.get('CustomModelTexture'), list)
                if not (own_model or own_tex):
                    continue
                (m, mplace), (ts, tplaces) = self.effective(key, ptr, scope)
                if m is not None and ts is None and (scope == 'Item/Items'):
                    ts, tplaces = (['Items/Unknown.png'], [None])
                if m is None or not ts:
                    undrawn.append({'file': rel, 'at': list(ptr), 'model': m, 'textures': ts})
                    continue
                for t, tp in zip(ts, tplaces):
                    pairs[norm(m), norm(t)].append({'file': rel, 'at': list(ptr)})
                    for place in (mplace, tp):
                        if place and place[0][0] == 'pack':
                            explained.add((place[0][1], tuple(place[1])))
        mentions = collections.defaultdict(lambda: collections.Counter())
        exact = collections.defaultdict(lambda: collections.Counter())
        for rel in self.text_files:
            try:
                low = self.read_text(rel).lower()
            except (UnicodeDecodeError, OSError):
                continue
            if '\x00' in low:
                continue
            for mt in TOKEN.finditer(low):
                for s in suffixes(mt.group(0).strip()):
                    mentions[s.replace('\\', '/')][rel] += 1
        for rel, ptr in explained:
            exact[norm(self.node(('pack', rel), ptr))][rel] += 1
        return (pairs, mentions, exact, undrawn)

    def components(self):
        pairs, mentions, exact, undrawn = self.inventory()
        parent = {}

        def find(x):
            parent.setdefault(x, x)
            while parent[x] != x:
                parent[x] = parent[parent[x]]
                x = parent[x]
            return x
        for m, p in pairs:
            parent[find(('m', m))] = find(('p', p))
        groups = collections.defaultdict(lambda: {'models': set(), 'pictures': set(), 'pairs': []})
        for (m, p), where in pairs.items():
            g = groups[find(('m', m))]
            g['models'].add(m)
            g['pictures'].add(p)
            g['pairs'].append((m, p))
        out = []
        for g in groups.values():
            reasons = []
            members = [('model', m) for m in sorted(g['models'])] + [('picture', p) for p in sorted(g['pictures'])]
            for kind, path in members:
                if re.search('[\\"\'\\r\\n\\t:,\\[\\]{}()<>=|*?]', path):
                    reasons.append(f'{kind} {path} holds a character the mention scan cannot follow')
                if path not in self.common:
                    reasons.append(f'{kind} {path} is not a file of the pack' + (" (vanilla's)" if path in self.vanilla_common else ''))
                elif path in self.vanilla_common:
                    reasons.append(f"{kind} {path} overrides vanilla's file of the same path")
                extra = {rel: n - exact[path].get(rel, 0) for rel, n in mentions[path].items() if n > exact[path].get(rel, 0)}
                for rel, n in sorted(extra.items())[:5]:
                    reasons.append(f'{kind} {path} is named {n} more time(s) in {rel} than this reader resolved')
            real = lambda path: self.common.get(path, path)
            pics = sorted((real(p) for p in g['pictures']))
            out.append({'models': sorted((real(m) for m in g['models'])), 'pictures': pics, 'pairs': sorted(([real(m), real(p)] for m, p in g['pairs'])), 'roots': sorted({p.split('/', 1)[0] for p in pics}), 'optimisable': not reasons, 'reasons': reasons})
        out.sort(key=lambda c: c['pictures'][0])
        return (out, undrawn)

def main():
    args = sys.argv[1:]
    vanilla = DEFAULT_VANILLA
    if '--vanilla' in args:
        i = args.index('--vanilla')
        vanilla = args[i + 1]
        del args[i:i + 2]
    root, out = (args[0], args[1])
    pack = Pack(root, vanilla)
    comps, undrawn = pack.components()
    ok = [c for c in comps if c['optimisable']]
    json.dump({'pack': pack.root, 'vanilla': vanilla, 'components': comps, 'undrawn': undrawn, 'unparsed': pack.unparsed}, open(out, 'w', encoding='utf-8'), indent=1)
    print(f"groups {len(comps)}: optimisable {len(ok)}, left alone {len(comps) - len(ok)}; pairs {sum((len(c['pairs']) for c in comps))}; objects drawing nothing {len(undrawn)}; unparsed files {len(pack.unparsed)}")
    print('EXIT=0')
if __name__ == '__main__':
    main()
