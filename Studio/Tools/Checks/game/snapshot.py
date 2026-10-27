# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# Boots the game's own server over the mod and writes down every asset as the game holds it.

from __future__ import annotations
import argparse
import ctypes
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from ctypes import wintypes
from pathlib import Path
HERE = Path(__file__).resolve().parent
READER = HERE / 'reader'
READER_SOURCE = READER / 'src' / 'checks' / 'SnapshotReader.java'
READER_CLASSES = READER / 'classes'
READER_MANIFEST = READER / 'manifest.json'
APPDATA = Path(os.environ.get('APPDATA', ''))
DEFAULT_GAME = APPDATA / 'Hytale' / 'install' / 'release' / 'package' / 'game' / 'latest'
DEFAULT_JAVA = APPDATA / 'Hytale' / 'install' / 'release' / 'package' / 'jre' / 'latest' / 'bin' / 'java.exe'
DEFAULT_JDK = Path('C:\\Program Files\\Java\\jdk-25\\bin\\javac.exe')
VERDICT = re.compile('CHECKS snapshot complete=(true|false)(.*)')
CI_LINE = re.compile('::(error|warning) file=(.*?)(?:,line=(\\d+))?(?:,col=(\\d+))?::(.*)')
NO_WINDOW = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
REFUSED = re.compile('Asset validation FAILED with \\d+ reason\\(s\\):\\s*\\n(.*?)(?=\\n\\[\\d{4}/)', re.S)
FAILED_ASSET = re.compile('\\[AssetStore\\|(\\w+)\\] Failed to decode asset: ([^,]+), ([^,]+), ([^\\n]*)')

def fail(msg: str) -> None:
    print(f'snapshot: {msg}', file=sys.stderr)
    sys.exit(1)

def build_reader(game_jar: Path, javac: Path) -> None:
    cls = READER_CLASSES / 'checks' / 'SnapshotReader.class'
    if cls.exists() and (not READER_SOURCE.exists() or cls.stat().st_mtime >= READER_SOURCE.stat().st_mtime):
        return
    if not javac.exists():
        fail(f'the reader needs compiling and no JDK was found at {javac}')
    shutil.rmtree(READER_CLASSES, ignore_errors=True)
    READER_CLASSES.mkdir(parents=True)
    r = subprocess.run([str(javac), '--release', '25', '-Xlint:-deprecation', '-cp', str(game_jar), '-d', str(READER_CLASSES), str(READER_SOURCE)], capture_output=True, text=True, creationflags=NO_WINDOW)
    if r.returncode != 0:
        fail('the reader does not compile:\n' + r.stdout + r.stderr)

def plugin_id(mod: Path) -> str:
    if mod.is_dir():
        m = json.loads((mod / 'manifest.json').read_text(encoding='utf-8-sig'))
    else:
        with zipfile.ZipFile(mod) as z:
            m = json.loads(z.read('manifest.json').decode('utf-8-sig'))
    return f"{m['Group']}:{m['Name']}"

def link_folder(target: Path, link: Path) -> None:
    if os.name != 'nt':
        os.symlink(target, link, target_is_directory=True)
        return
    import _winapi
    _winapi.CreateJunction(str(target), str(link))

def is_link(p: Path) -> bool:
    return os.path.islink(p) or (hasattr(os.path, 'isjunction') and os.path.isjunction(p))

def remove_tree(root: Path) -> None:
    if not root.exists():
        return
    for dirpath, dirnames, _ in os.walk(root):
        for d in list(dirnames):
            p = Path(dirpath) / d
            if is_link(p):
                os.rmdir(p) if os.name == 'nt' else os.unlink(p)
                dirnames.remove(d)
    shutil.rmtree(root, ignore_errors=True)

def package_reader(dest: Path, mods: list[Path], packs: list[Path]=()) -> None:
    manifest = json.loads(READER_MANIFEST.read_text(encoding='utf-8'))
    manifest['OptionalDependencies'] = {plugin_id(m): '*' for m in [*mods, *packs]}
    with zipfile.ZipFile(dest, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('manifest.json', json.dumps(manifest, indent=2))
        for f in sorted(READER_CLASSES.rglob('*.class')):
            z.write(f, f.relative_to(READER_CLASSES).as_posix())

class _Counters(ctypes.Structure):
    _fields_ = [('cb', wintypes.DWORD), ('PageFaultCount', wintypes.DWORD), ('PeakWorkingSetSize', ctypes.c_size_t), ('WorkingSetSize', ctypes.c_size_t), ('QuotaPeakPagedPoolUsage', ctypes.c_size_t), ('QuotaPagedPoolUsage', ctypes.c_size_t), ('QuotaPeakNonPagedPoolUsage', ctypes.c_size_t), ('QuotaNonPagedPoolUsage', ctypes.c_size_t), ('PagefileUsage', ctypes.c_size_t), ('PeakPagefileUsage', ctypes.c_size_t)]

def peak_memory(proc: subprocess.Popen) -> int | None:
    if os.name != 'nt':
        return None
    c = _Counters()
    c.cb = ctypes.sizeof(c)
    ok = ctypes.WinDLL('psapi').GetProcessMemoryInfo(wintypes.HANDLE(int(proc._handle)), ctypes.byref(c), c.cb)
    return int(c.PeakWorkingSetSize) if ok else None

def main() -> int:
    ap = argparse.ArgumentParser(description=(__doc__ or "Boot the game's own server over some mods and write out every asset.").splitlines()[0])
    ap.add_argument('--out', required=True, type=Path, help='folder to write (emptied first)')
    ap.add_argument('--mod', action='append', default=[], type=Path, help='a mod jar to load (repeatable)')
    ap.add_argument('--pack', action='append', default=[], type=Path, help="a folder of mod files holding a manifest.json, linked as the game's mods folder links it")
    ap.add_argument('--game', type=Path, default=DEFAULT_GAME, help='game folder holding Server/ and Assets.zip')
    ap.add_argument('--java', type=Path, default=DEFAULT_JAVA, help='java.exe that runs the server')
    ap.add_argument('--javac', type=Path, default=DEFAULT_JDK, help='javac.exe, only to build the reader')
    ap.add_argument('--memory', default='2G', help="the server's maximum heap")
    ap.add_argument('--timeout', type=int, default=600, help='seconds before the boot is stopped')
    ap.add_argument('--no-shortcut', action='store_true', help='control run: retry every endless field every time (slow; proves the shortcut changes nothing)')
    a = ap.parse_args()
    game_jar = a.game / 'Server' / 'HytaleServer.jar'
    assets = a.game / 'Assets.zip'
    for p, what in ((game_jar, 'the server'), (assets, "the game's assets"), (a.java, 'java')):
        if not p.exists():
            fail(f'{what} not found at {p}')
    for m in a.mod:
        if not m.is_file():
            fail(f'no mod jar at {m}')
    for p in a.pack:
        if not (p / 'manifest.json').is_file():
            fail(f'no manifest.json in the mod folder {p}')
    build_reader(game_jar, a.javac)
    out = a.out.resolve()
    if out.exists():
        if not (out / 'run.json').exists() and any(out.iterdir()):
            fail(f'{out} exists and was not written by this tool; refusing to empty it')
        remove_tree(out)
    work = out / 'server'
    (work / 'mods').mkdir(parents=True)
    for m in a.mod:
        shutil.copy2(m, work / 'mods' / m.name)
    for p in a.pack:
        link_folder(p.resolve(), work / 'mods' / p.resolve().name)
    package_reader(work / 'mods' / 'ChecksSnapshotReader.jar', a.mod, a.pack)
    env = dict(os.environ, CI='1')
    cmd = [str(a.java), f'-Xmx{a.memory}', f'-Dchecks.out={out}'] + (['-Dchecks.noShortcut=true'] if a.no_shortcut else []) + ['-jar', str(game_jar), '--bare', '--disable-sentry', '--auth-mode', 'offline', '--assets', str(assets)]
    log_path = out / 'boot.log'

    def boot(args: list[str]) -> tuple:
        started = time.monotonic()
        with open(log_path, 'wb') as log:
            proc = subprocess.Popen(args, cwd=work, stdout=log, stderr=subprocess.STDOUT, env=env, creationflags=NO_WINDOW)
            try:
                code = proc.wait(timeout=a.timeout)
                timed_out = False
            except subprocess.TimeoutExpired:
                proc.kill()
                code = proc.wait()
                timed_out = True
            seconds = round(time.monotonic() - started, 1)
            peak = peak_memory(proc)
        text = re.sub('\\x1b\\[[0-9;]*m', '', log_path.read_text(encoding='utf-8', errors='replace'))
        return (code, timed_out, seconds, peak, text)
    code, timed_out, seconds, peak, text = boot(cmd)
    verdicts = VERDICT.findall(text)
    complete = bool(verdicts) and verdicts[-1][0] == 'true'
    refused = None
    stop = REFUSED.search(text)
    if not complete and stop:
        log_path.replace(out / 'boot-refused.log')
        refused = {'reason': stop.group(1).strip(), 'log': 'boot-refused.log', 'files': [{'store': s, 'id': i, 'file': f, 'message': m.strip()} for s, i, f, m in FAILED_ASSET.findall(text)]}
        cmd = cmd + ['--ignore-broken-mods']
        code, timed_out, more, peak2, text = boot(cmd)
        seconds = round(seconds + more, 1)
        peak = max((p for p in (peak, peak2) if p)) if peak or peak2 else None
        verdicts = VERDICT.findall(text)
        complete = bool(verdicts) and verdicts[-1][0] == 'true'
    else:
        failed = FAILED_ASSET.findall(text)
        if failed:
            refused = {'reason': 'these files failed to load, and the server started without them', 'log': 'boot.log', 'started': True, 'files': [{'store': s, 'id': i, 'file': f, 'message': m.strip()} for s, i, f, m in failed]}
    lines = []
    for kind, file, line, col, msg in CI_LINE.findall(text):
        lines.append({'kind': kind, 'file': file, 'line': int(line) if line else None, 'col': int(col) if col else None, 'message': msg.strip()})
    with open(out / 'engine-lines.jsonl', 'w', encoding='utf-8') as f:
        for l in lines:
            f.write(json.dumps(l, ensure_ascii=False) + '\n')
    meta = {}
    if (out / 'meta.json').exists():
        meta = json.loads((out / 'meta.json').read_text(encoding='utf-8'))
    on_disk = 0
    for f in (out / 'stores').glob('*.jsonl') if (out / 'stores').exists() else []:
        with open(f, 'rb') as fh:
            on_disk += sum((1 for _ in fh))
    whole = complete and meta.get('written') == on_disk
    run = {'command': cmd, 'mods': [str(m.resolve()) for m in a.mod] + [str(p.resolve()) for p in a.pack], 'game': str(a.game), 'server_version': read_version(game_jar), 'exit_code': code, 'timed_out': timed_out, 'seconds': seconds, 'peak_memory_mb': round(peak / 2 ** 20) if peak else None, 'verdict': verdicts[-1][1].strip() if verdicts else None, 'complete': whole, 'assets_on_disk': on_disk, 'engine_lines': len(lines), 'refused': refused}
    (out / 'run.json').write_text(json.dumps(run, indent=2), encoding='utf-8')
    remove_tree(work)
    if refused:
        print(f"snapshot: the server REFUSED to start with these mods ({len(refused['files'])} file(s) failed to load); read again past them with --ignore-broken-mods")
    print(f"snapshot: {('complete' if whole else 'NOT complete')}: {on_disk} assets, {meta.get('refused', '?')} refused by their codec, {len(lines)} engine lines, {seconds} s, peak {run['peak_memory_mb']} MB, server {run['server_version']}")
    return 0 if whole else 1

def read_version(game_jar: Path) -> str | None:
    with zipfile.ZipFile(game_jar) as z:
        for line in z.read('META-INF/MANIFEST.MF').decode('utf-8').splitlines():
            if line.startswith('Implementation-Version:'):
                return line.split(':', 1)[1].strip()
    return None
if __name__ == '__main__':
    sys.exit(main())
