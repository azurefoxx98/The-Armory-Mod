# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# Writes an optimised group into a copy of the mod, and proves the result.

import datetime, hashlib, json, os, shutil, subprocess, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import numpy as np
from PIL import Image
import pack_inventory
from pack_inventory import MODEL_KEYS, norm, scope_of
from jsonspan import set_string_member, splice
from faces import compare_pair

class Refused(Exception):
    pass

def sha(path):
    return hashlib.sha256(open(path, 'rb').read()).hexdigest()

def sha_bytes(b):
    return hashlib.sha256(b).hexdigest()

def tools_fingerprint():
    return {f: sha(os.path.join(HERE, f)) for f in sorted(os.listdir(HERE)) if f.endswith('.py')}

def drawing_objects(pack):
    for rel, (value, spans) in pack.docs.items():
        scope = scope_of(rel[7:])
        for ptr in [k[0] for k in spans if len(k) == 2 and k[1] == '{members}' and isinstance(k[0], tuple)]:
            if len(ptr) >= 2 and ptr[-2] == 'CustomModelTexture':
                continue
            obj = pack.node(('pack', rel), ptr)
            own_model = any((isinstance(obj.get(k), str) and obj[k].lower().endswith('.blockymodel') for k in MODEL_KEYS))
            own_tex = isinstance(obj.get('Texture'), str) or isinstance(obj.get('CustomModelTexture'), list)
            if own_model or own_tex:
                yield (rel, ptr, scope)

def resolved(pack, rel, ptr, scope):
    (m, mplace), (ts, tplaces) = pack.effective(('pack', rel), ptr, scope)
    return ((m, mplace), (ts, tplaces))

def pairs_of(pack, rel, ptr, scope):
    (m, _), (ts, _) = resolved(pack, rel, ptr, scope)
    if m is not None and ts is None and (scope == 'Item/Items'):
        ts = ['Items/Unknown.png']
    if m is None or not ts:
        return ()
    return tuple(sorted(((norm(m), norm(t)) for t in ts)))

def git_dirty(root, rels):
    top = subprocess.run(['git', '-C', root, 'rev-parse', '--show-toplevel'], capture_output=True, text=True)
    if top.returncode != 0:
        return None
    prefix = os.path.relpath(root, top.stdout.strip()).replace(os.sep, '/')
    prefix = '' if prefix == '.' else prefix + '/'
    p = subprocess.run(['git', '-C', root, 'status', '--porcelain=v1', '-z', '--untracked-files=all', '--', '.'], capture_output=True)
    if p.returncode != 0:
        raise Refused('git status failed: ' + p.stderr.decode(errors='replace').strip())
    wanted = {(prefix + r).lower() for r in rels}
    out, items = ([], p.stdout.decode('utf-8', errors='replace').split('\x00'))
    i = 0
    while i < len(items):
        entry = items[i]
        i += 1
        if len(entry) < 4:
            continue
        status, path = (entry[:2], entry[3:])
        if status[0] in 'RC':
            i += 1
        if path.lower() in wanted:
            out.append(entry)
    return out

def make_plan(root, opt, groups_arg, vanilla):
    rows = json.load(open(os.path.join(opt, 'mod_report.json'), encoding='utf-8'))
    if groups_arg:
        wanted = [int(g) for g in groups_arg.split(',')]
    else:
        wanted = [r['group'] for r in rows if r['chosen'] in ('shared', 'pooled')]
    by_group = {r['group']: r for r in rows}
    pack = pack_inventory.Pack(root, vanilla)
    comps, _ = pack.components()
    now = {tuple(c['pictures']): c for c in comps}
    common = lambda rel: os.path.join(root, 'Common', rel)
    files = {}
    mapping = []
    rewrite_targets = {}
    deleted_norm = set()
    for g in wanted:
        row = by_group.get(g)
        if not row or row['chosen'] not in ('shared', 'pooled'):
            raise Refused(f"group {g} has no optimised layout to apply (chosen: {row and row['chosen']})")
        mode = row['chosen']
        spec = json.load(open(os.path.join(opt, f'group_{g:03d}.json'), encoding='utf-8'))
        out = os.path.join(opt, f'group_{g:03d}_{mode}')
        rep = json.load(open(os.path.join(out, 'report.json'), encoding='utf-8'))
        c = now.get(tuple(spec['pictures']))
        if c is None or not c['optimisable'] or c['pairs'] != sorted(spec['pairs']) or (c['models'] != sorted(spec['models'])):
            raise Refused(f"group {g} ({spec['pictures'][0]}) is no longer the same optimisable group in the mod: optimise again")
        for rel, h in rep['inputs'].items():
            if not os.path.isfile(common(rel)) or sha(common(rel)) != h:
                raise Refused(f'group {g}: {rel} changed since the optimisation read it: optimise again')
        new_common = os.path.join(out, 'Common')
        if mode == 'shared':
            for rel in spec['models'] + spec['pictures']:
                files['Common/' + rel] = {'action': 'replaced', 'new': open(os.path.join(new_common, rel), 'rb').read()}
            mapping.append({'group': g, 'mode': mode, 'pairs': [[m, p, m, p] for m, p in spec['pairs']]})
        else:
            for m, p, cp in rep['copies']:
                rewrite_targets[norm(m), norm(p)] = (cp, rep['atlas'])
            for rel in [cp for _, _, cp in rep['copies']] + [rep['atlas']]:
                if norm(rel) in pack.common:
                    raise Refused(f'group {g}: {rel} already exists in the mod')
                files['Common/' + rel] = {'action': 'added', 'new': open(os.path.join(new_common, rel), 'rb').read()}
            for rel in spec['models'] + spec['pictures']:
                files['Common/' + rel] = {'action': 'deleted', 'new': None}
                deleted_norm.add(norm(rel))
            mapping.append({'group': g, 'mode': mode, 'pairs': [[m, p, cp, rep['atlas']] for m, p, cp in rep['copies']]})
    edits = {}
    before = {}
    for rel, ptr, scope in drawing_objects(pack):
        prs = pairs_of(pack, rel, ptr, scope)
        before[rel, ptr] = prs
        hit = [pr for pr in prs if pr in rewrite_targets]
        if not hit:
            continue
        if len(prs) > 1:
            raise Refused(f'{rel} {list(ptr)} draws several pictures with one model; one copy per picture cannot be written there')
        new_model, new_pic = rewrite_targets[hit[0]]
        (m, mplace), (ts, tplaces) = resolved(pack, rel, ptr, scope)
        value, spans = pack.docs[rel]
        mkey = mplace[1][-1]
        e = [set_string_member(pack.read_text(rel), spans, ptr, mkey, new_model)]
        tplace = tplaces[0]
        if tplace[1][-1] == 'Texture' and len(tplace[1]) >= 1 and (len(tplace[1]) < 3 or tplace[1][-3] != 'CustomModelTexture'):
            e.append(set_string_member(pack.read_text(rel), spans, ptr, 'Texture', new_pic))
        else:
            if tplace[0] != ('pack', rel) or tuple(tplace[1][:-3]) != ptr:
                raise Refused(f'{rel} {list(ptr)} inherits its block texture list; it cannot be rewritten in place')
            s, en = spans[tuple(tplace[1])]
            e.append((s, en, json.dumps(new_pic, ensure_ascii=False)))
        edits.setdefault(rel, []).extend(e)
    texts = {}
    for rel, e in edits.items():
        texts[rel] = splice(pack.read_text(rel), e)
    added = [r for r, f in files.items() if f['action'] == 'added']
    removed = [r for r, f in files.items() if f['action'] == 'deleted']
    after = pack_inventory.Pack(root, vanilla, overrides=texts, removed=removed, added=added)
    wrong = []
    for (rel, ptr), prs in before.items():
        scope = scope_of(rel[7:])
        want = tuple(sorted(((norm(rewrite_targets[pr][0]), norm(rewrite_targets[pr][1])) if pr in rewrite_targets else pr for pr in prs)))
        got = pairs_of(after, rel, ptr, scope)
        if got != want:
            wrong.append(f'{rel} {list(ptr)}: would draw {got}, should draw {want}')
    if wrong:
        raise Refused(f'{len(wrong)} object(s) would not draw what they should after the rewrite, e.g. {wrong[:3]}')
    if deleted_norm:
        _, mentions, _, _ = after.inventory()
        left = {p: dict(mentions[p]) for p in deleted_norm if mentions.get(p)}
        if left:
            raise Refused(f'{len(left)} deleted file(s) would still be named, e.g. {list(left.items())[:3]}')
    for rel, text in texts.items():
        raw = open(os.path.join(root, rel), 'rb').read()
        bom = b'\xef\xbb\xbf' if raw.startswith(b'\xef\xbb\xbf') else b''
        files[rel] = {'action': 'rewritten', 'new': bom + text.encode('utf-8')}
    dirty = git_dirty(root, [r for r in files])
    if dirty:
        raise Refused(f'{len(dirty)} file(s) to touch have uncommitted changes in git, e.g. {dirty[:3]}')
    return {'files': files, 'mapping': mapping, 'git': dirty is not None, 'groups': wanted}

def show(plan):
    by = {}
    for rel, f in sorted(plan['files'].items()):
        by.setdefault(f['action'], []).append(rel)
    for action in ('added', 'replaced', 'rewritten', 'deleted'):
        rels = by.get(action, [])
        print(f'{action}: {len(rels)}')
        for r in rels[:6]:
            print('   ', r)
        if len(rels) > 6:
            print(f'    ... {len(rels) - 6} more')
    print(f"groups: {plan['groups']}; pairs: {sum((len(m['pairs']) for m in plan['mapping']))}; git: {('checked clean' if plan['git'] else 'not a git repository: the backup is the only undo')}")

def apply(root, opt, backup, groups_arg, vanilla):
    root, backup = (os.path.abspath(root), os.path.abspath(backup))
    if os.path.exists(backup):
        raise Refused('the backup dir exists already: give a new one')
    if os.path.commonpath([root, backup]) == root:
        raise Refused('the backup dir sits inside the pack: it would ship with the mod')
    plan = make_plan(root, opt, groups_arg, vanilla)
    show(plan)
    os.makedirs(os.path.join(backup, 'files'))
    entries = []
    for rel, f in sorted(plan['files'].items()):
        full = os.path.join(root, rel)
        e = {'path': rel, 'action': f['action'], 'before': None, 'after': None}
        if f['action'] != 'added':
            dst = os.path.join(backup, 'files', rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(full, dst)
            e['before'] = sha(full)
            if sha(dst) != e['before']:
                raise Refused(f'the backup of {rel} does not read back')
        if f['new'] is not None:
            e['after'] = sha_bytes(f['new'])
        entries.append(e)
    manifest = {'pack': root, 'created': datetime.datetime.now().isoformat(timespec='seconds'), 'optimisation': os.path.abspath(opt), 'groups': plan['groups'], 'mapping': plan['mapping'], 'git': plan['git'], 'vanilla': vanilla, 'tools': tools_fingerprint(), 'files': entries}
    json.dump(manifest, open(os.path.join(backup, 'manifest.json'), 'w', encoding='utf-8'), indent=1)
    made_dirs = []
    try:
        for e in entries:
            f = plan['files'][e['path']]
            full = os.path.join(root, e['path'])
            if f['new'] is not None:
                d = os.path.dirname(full)
                if not os.path.isdir(d):
                    os.makedirs(d)
                    made_dirs.append(d)
                tmp = full + '.texopt-tmp'
                open(tmp, 'wb').write(f['new'])
                os.replace(tmp, full)
        for e in entries:
            if e['action'] == 'deleted':
                os.remove(os.path.join(root, e['path']))
        for e in entries:
            full = os.path.join(root, e['path'])
            if e['after'] is not None and sha(full) != e['after']:
                raise Refused(f"{e['path']} did not read back after the write")
            if e['action'] == 'deleted' and os.path.exists(full):
                raise Refused(f"{e['path']} is still there")
    except Exception as ex:
        restore(root, backup, entries, check=False)
        raise Refused(f'apply failed part-way and every file was put back: {ex}')
    manifest['made_dirs'] = made_dirs
    json.dump(manifest, open(os.path.join(backup, 'manifest.json'), 'w', encoding='utf-8'), indent=1)
    print(f'applied: {len(entries)} files; backup and manifest: {backup}')
    return prove(root, backup, vanilla)

def prove(root, backup, vanilla=None):
    manifest = json.load(open(os.path.join(backup, 'manifest.json'), encoding='utf-8'))
    vanilla = vanilla or manifest.get('vanilla') or pack_inventory.DEFAULT_VANILLA
    backed = {e['path'] for e in manifest['files'] if e['before']}

    def old(rel):
        p = 'Common/' + rel
        return os.path.join(backup, 'files', p) if p in backed else os.path.join(root, p)

    def load(path, picture):
        return np.asarray(Image.open(path).convert('RGBA')) if picture else json.load(open(path, encoding='utf-8'))
    faces, problems = (0, [])
    for grp in manifest['mapping']:
        for om, op, nm, np_ in grp['pairs']:
            n, why = compare_pair(load(old(om), False), load(old(op), True), load(os.path.join(root, 'Common', nm), False), load(os.path.join(root, 'Common', np_), True))
            faces += n
            if why:
                problems.append(f'{om} on {op}: {why}')
    pack = pack_inventory.Pack(root, vanilla)
    pairs, mentions, _, _ = pack.inventory()
    for grp in manifest['mapping']:
        for om, op, nm, np_ in grp['pairs']:
            if (norm(nm), norm(np_)) not in pairs:
                problems.append(f'nothing in the mod draws {nm} on {np_}')
            if grp['mode'] == 'pooled' and (norm(om), norm(op)) in pairs:
                problems.append(f'something in the mod still draws the old {om} on {op}')
    for e in manifest['files']:
        if e['action'] == 'deleted' and mentions.get(norm(e['path'][7:])):
            problems.append(f"{e['path']} is deleted and still named in {dict(mentions[norm(e['path'][7:])])}")
    verdict = {'pairs': sum((len(g['pairs']) for g in manifest['mapping'])), 'faces_identical': faces, 'problems': problems, 'proved': not problems}
    print(json.dumps(verdict)[:2000])
    json.dump(verdict, open(os.path.join(backup, 'proof.json'), 'w', encoding='utf-8'), indent=1)
    return 0 if not problems else 1

def restore(root, backup, entries, check=True):
    if check:
        changed = []
        for e in entries:
            full = os.path.join(root, e['path'])
            if e['after'] is None:
                if os.path.exists(full):
                    changed.append(f"{e['path']} exists again")
            elif not os.path.isfile(full) or sha(full) != e['after']:
                changed.append(f"{e['path']} changed since apply")
        if changed:
            raise Refused(f'{len(changed)} file(s) changed since apply; nothing undone: {changed[:5]}')
    for e in entries:
        full = os.path.join(root, e['path'])
        if e['action'] == 'added':
            if os.path.exists(full):
                os.remove(full)
        else:
            os.makedirs(os.path.dirname(full), exist_ok=True)
            shutil.copyfile(os.path.join(backup, 'files', e['path']), full)
    bad = []
    for e in entries:
        full = os.path.join(root, e['path'])
        if e['before'] is None:
            if os.path.exists(full):
                bad.append(e['path'])
        elif sha(full) != e['before']:
            bad.append(e['path'])
    if bad:
        raise Refused(f'undo left {len(bad)} file(s) different from before: {bad[:5]}')

def undo(root, backup):
    manifest = json.load(open(os.path.join(backup, 'manifest.json'), encoding='utf-8'))
    if os.path.exists(os.path.join(backup, 'undone.json')):
        raise Refused('this backup was undone already')
    if os.path.abspath(root) != manifest['pack']:
        raise Refused(f"this backup belongs to {manifest['pack']}")
    restore(root, backup, manifest['files'])
    for d in sorted(manifest.get('made_dirs', []), key=len, reverse=True):
        if os.path.isdir(d) and (not os.listdir(d)):
            os.rmdir(d)
    json.dump({'undone': datetime.datetime.now().isoformat(timespec='seconds'), 'files': len(manifest['files'])}, open(os.path.join(backup, 'undone.json'), 'w', encoding='utf-8'))
    print(f"undone: {len(manifest['files'])} files back exactly as before (fingerprints checked)")
    return 0

def main():
    args = sys.argv[1:]

    def take(flag):
        if flag in args:
            i = args.index(flag)
            v = args[i + 1]
            del args[i:i + 2]
            return v
        return None
    take_vanilla = take('--vanilla')
    vanilla = take_vanilla or pack_inventory.DEFAULT_VANILLA
    groups = take('--groups')
    cmd = args[0]
    try:
        if cmd == 'plan':
            show(make_plan(os.path.abspath(args[1]), args[2], groups, vanilla))
            code = 0
        elif cmd == 'apply':
            code = apply(args[1], args[2], args[3], groups, vanilla)
        elif cmd == 'prove':
            code = prove(os.path.abspath(args[1]), args[2], take_vanilla)
        elif cmd == 'undo':
            code = undo(os.path.abspath(args[1]), args[2])
        else:
            raise Refused(f'unknown command {cmd}')
    except Refused as r:
        print(f'REFUSED: {r}')
        code = 2
    print(f'EXIT={code}')
    sys.exit(code)
if __name__ == '__main__':
    main()
