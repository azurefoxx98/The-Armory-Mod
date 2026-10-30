# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# Checks that every model, picture, icon, sound, effect, stat and family an item names exists, and that gear keeps the game's own category.

from __future__ import annotations
import json
import os
import re
import struct
import sys
import tempfile
import zipfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from assetcheck import model_faces
from pairedmods import paired_mod_of_stat
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODRES = os.environ.get('ARMORY_RESOURCES') or os.path.join(ROOT, 'The-Armory-Mod', 'src', 'main', 'resources')
GAME = os.environ.get('ARMORY_GAME') or os.path.join(os.environ.get('APPDATA', ''), 'Hytale', 'install', 'release', 'package', 'game', 'latest')
SOUND_KEY = re.compile('"(?:WorldSoundEventId|LocalSoundEventId|SoundEventId)": "([^"]+)"')
PARTICLE_KEY = re.compile('"SystemId": "([^"]+)"')
CAMERA_KEY = re.compile('"CameraEffect": "([^"]+)"')
VANILLA_CATEGORY = {'ArmoryCategory.ArmoryArmors': 'Items.Armors', 'ArmoryCategory.ArmoryShields': 'Items.Weapons', 'ArmoryCategory.ArmoryWeapons': 'Items.Weapons'}

def png_size(data: bytes) -> tuple[int, int]:
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('not a png')
    return struct.unpack('>II', data[16:24])

class Tree:

    def __init__(self, resources: str, archive: zipfile.ZipFile):
        self.res = resources
        self.zip = archive
        self.vanilla = set(archive.namelist())

    def exists(self, rel: str) -> bool:
        return os.path.isfile(os.path.join(self.res, *rel.split('/'))) or rel in self.vanilla

    def read(self, rel: str) -> bytes:
        path = os.path.join(self.res, *rel.split('/'))
        if os.path.isfile(path):
            with open(path, 'rb') as fh:
                return fh.read()
        return self.zip.read(rel)

    def ids(self, prefix: str, ext: str) -> set[str]:
        out = {n.rsplit('/', 1)[1][:-len(ext)] for n in self.vanilla if n.startswith(prefix) and n.endswith(ext)}
        for _d, _dirs, files in os.walk(os.path.join(self.res, *prefix.split('/'))):
            out.update((f[:-len(ext)] for f in files if f.endswith(ext)))
        return out

def check(resources: str, game: str) -> tuple[list[str], list[str], dict]:
    items_root = os.path.join(resources, 'Server', 'Item', 'Items')
    assets = os.path.join(game, 'Assets.zip')
    if not os.path.isdir(items_root):
        raise OSError(f'item directory not found at {items_root}')
    if not os.path.isfile(assets):
        raise OSError(f"the game's Assets.zip not found at {assets}")
    with zipfile.ZipFile(assets) as archive:
        tree = Tree(resources, archive)
        known = {'item': tree.ids('Server/Item/Items/', '.json'), 'family': tree.ids('Server/Item/ResourceTypes/', '.json'), 'sound': tree.ids('Server/Audio/SoundEvents/', '.json'), 'particle': tree.ids('Server/Particles/', '.particlesystem'), 'camera effect': tree.ids('Server/Camera/CameraEffect/', '.json'), 'item sound set': tree.ids('Server/Audio/ItemSounds/', '.json'), 'stat': tree.ids('Server/Entity/Stats/', '.json'), 'quality': tree.ids('Server/Item/Qualities/', '.json')}
        problems: list[str] = []
        notes: list[str] = []
        pairs: dict[tuple[str, str], str] = {}
        files = 0
        for dirpath, _dirs, names in os.walk(items_root):
            for fname in sorted(names):
                if not fname.endswith('.json'):
                    continue
                files += 1
                path = os.path.join(dirpath, fname)
                rel = os.path.relpath(path, resources).replace(os.sep, '/')
                name = fname[:-5]
                try:
                    with open(path, encoding='utf-8-sig') as fh:
                        doc = json.load(fh)
                except (OSError, ValueError) as exc:
                    problems.append(f'{rel}: cannot be read as JSON, so the game never loads it ({exc})')
                    continue
                if not isinstance(doc, dict):
                    problems.append(f'{rel}: is not a JSON object, so the game never loads it')
                    continue
                problems += [f'{rel}: {p}' for p in item_problems(doc, tree, known)]
                if isinstance(doc.get('Model'), str) and isinstance(doc.get('Texture'), str):
                    pairs.setdefault((doc['Model'], doc['Texture']), rel)
                block = doc.get('BlockType') if isinstance(doc.get('BlockType'), dict) else {}
                if isinstance(block.get('CustomModel'), str):
                    for row in block.get('CustomModelTexture') or []:
                        if isinstance(row, dict) and isinstance(row.get('Texture'), str):
                            pairs.setdefault((block['CustomModel'], row['Texture']), rel)
        off = 0
        for (model, texture), rel in sorted(pairs.items()):
            if not (tree.exists('Common/' + model) and tree.exists('Common/' + texture)):
                continue
            try:
                w, h = png_size(tree.read('Common/' + texture))
            except ValueError:
                problems.append(f'{rel}: its texture {texture} is not a PNG picture')
                continue
            with tempfile.TemporaryDirectory() as tmp:
                local = os.path.join(tmp, 'model.json')
                with open(local, 'wb') as fh:
                    fh.write(tree.read('Common/' + model))
                try:
                    faces = list(model_faces(local))
                except ValueError as exc:
                    problems.append(f'{rel}: its model {model} cannot be read ({exc})')
                    continue
            outside = [f for f in faces if f[2] < 0 or f[3] < 0 or f[2] + f[4] > w + 1e-06 or (f[3] + f[5] > h + 1e-06)]
            if outside:
                off += 1
                notes.append(f'{model}: {len(outside)} face(s) partly outside {texture} ({w}x{h})')
    return (problems, notes, {'files': files, 'pairs': len(pairs), 'models_off': off})

def item_problems(doc: dict, tree: Tree, known: dict[str, set[str]]) -> list[str]:
    out: list[str] = []
    for key in ('Model', 'Texture', 'Icon'):
        value = doc.get(key)
        if isinstance(value, str) and value and (not tree.exists('Common/' + value)):
            out.append(f'{key} {value} exists in neither the mod nor the game')
    block = doc.get('BlockType') if isinstance(doc.get('BlockType'), dict) else {}
    if isinstance(block.get('CustomModel'), str) and (not tree.exists('Common/' + block['CustomModel'])):
        out.append(f"CustomModel {block['CustomModel']} exists in neither the mod nor the game")
    for row in block.get('CustomModelTexture') or []:
        texture = row.get('Texture') if isinstance(row, dict) else None
        if isinstance(texture, str) and (not tree.exists('Common/' + texture)):
            out.append(f'CustomModelTexture {texture} exists in neither the mod nor the game')
    recipe = doc.get('Recipe') if isinstance(doc.get('Recipe'), dict) else {}
    for row in recipe.get('Input') or []:
        if not isinstance(row, dict):
            continue
        if isinstance(row.get('ItemId'), str) and row['ItemId'] not in known['item']:
            out.append(f"its recipe asks for the item {row['ItemId']}, which does not exist")
        if isinstance(row.get('ResourceTypeId'), str) and row['ResourceTypeId'] not in known['family']:
            out.append(f"its recipe asks for the family {row['ResourceTypeId']}, which has no family file")
    for row in doc.get('ResourceTypes') or []:
        family = row.get('Id') if isinstance(row, dict) else None
        if isinstance(family, str) and family not in known['family']:
            out.append(f'it belongs to the family {family}, which has no family file')
    text = json.dumps(doc)
    for pattern, kind in ((SOUND_KEY, 'sound'), (PARTICLE_KEY, 'particle'), (CAMERA_KEY, 'camera effect')):
        for value in pattern.findall(text):
            if value not in known[kind]:
                out.append(f'it plays the {kind} {value}, which exists in neither the mod nor the game')
    if isinstance(doc.get('ItemSoundSetId'), str) and doc['ItemSoundSetId'] not in known['item sound set']:
        out.append(f"its item sound set {doc['ItemSoundSetId']} does not exist")
    if isinstance(doc.get('Quality'), str) and doc['Quality'] not in known['quality']:
        out.append(f"its quality {doc['Quality']} does not exist")
    for block_name in ('Armor', 'Weapon', 'Utility'):
        part = doc.get(block_name)
        mods = part.get('StatModifiers') if isinstance(part, dict) else None
        for stat in mods or {} if isinstance(mods, dict) else {}:
            if stat not in known['stat'] and (not paired_mod_of_stat(stat)):
                out.append(f'its {block_name} changes the stat {stat}, which does not exist')
    categories = doc.get('Categories')
    if isinstance(categories, list):
        present = {c for c in categories if isinstance(c, str)}
        for armory, vanilla in VANILLA_CATEGORY.items():
            if armory in present and vanilla not in present:
                out.append(f'it is in {armory} but not {vanilla}, so other mods cannot find it')
    elif categories is not None:
        out.append('its Categories is not a list')
    return out

def main(argv=None) -> int:
    args = sys.argv[1:] if argv is None else argv
    resources = os.path.abspath(args[0]) if args else MODRES
    try:
        problems, notes, stats = check(resources, GAME)
    except (OSError, zipfile.BadZipFile) as exc:
        print(f'itemcheck: cannot complete scan: {exc}')
        return 2
    print(f"itemcheck: {stats['files']} item files, {stats['pairs']} model and picture pairs")
    for line in notes:
        print(f'NOTE: {line}')
    for line in problems:
        print(f'FAIL: {line}')
    if problems:
        print(f'itemcheck: FAILED: {len(problems)} problem(s), {len(notes)} note(s)')
        return 1
    print(f"itemcheck: PASS: everything every item names exists, {len(notes)} note(s), {stats['models_off']} model(s) with faces partly off their picture")
    return 0
if __name__ == '__main__':
    sys.exit(main())
