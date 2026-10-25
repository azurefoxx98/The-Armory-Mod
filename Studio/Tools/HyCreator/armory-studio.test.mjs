// Tests for the Colours page's analysis, run against the mod's real files with Node:
//
//   node Studio/Tools/HyCreator/armory-studio.test.mjs [--sweep]
//
// Faults are planted in memory only, nothing on disk is written. --sweep also runs the analysis
// on every default piece of the mod and prints the faults it finds, which is a report and never a
// change. Vanilla items are read from the installed game's Assets.zip when one is present.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { makeReader, analyse, plan, defaultChoice, isAlterationRecipe, colourKey } from './armory-studio.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const resources = path.resolve(here, '..', '..', '..', 'src', 'main', 'resources');
const assetsZip = path.join(process.env.APPDATA || '', 'Hytale', 'install', 'release', 'package', 'game', 'latest', 'Assets.zip');

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    e.isDirectory() ? walk(p, out) : out.push(p);
  }
  return out;
}

const diskFiles = walk(resources).map(p => path.relative(resources, p).split(path.sep).join('/'));

let vanilla = null;
function vanillaIndex() {
  if (vanilla) return vanilla;
  vanilla = new Map();
  if (!fs.existsSync(assetsZip)) return vanilla;
  const names = execFileSync('python', ['-c', 'import sys,zipfile;print("\\n".join(n for n in zipfile.ZipFile(sys.argv[1]).namelist() if n.startswith("Server/Item/Items/") and n.endswith(".json")))', assetsZip], { maxBuffer: 1 << 26 }).toString().split(/\r?\n/).filter(Boolean);
  for (const n of names) vanilla.set(n.slice('Server/'.length), n);
  return vanilla;
}
function readVanilla(rel) {
  const entry = vanillaIndex().get(rel);
  if (!entry) return null;
  return execFileSync('python', ['-c', 'import sys,zipfile;sys.stdout.buffer.write(zipfile.ZipFile(sys.argv[1]).read(sys.argv[2]))', assetsZip, entry]).toString('utf8');
}

// A file access like HyCreator's: Server paths, "../Common/" for Common, an in-memory overlay.
function io(overlay = new Map(), removed = new Set()) {
  const toDisk = rel => rel.startsWith('../Common/') ? 'Common/' + rel.slice('../Common/'.length) : 'Server/' + rel;
  const fromDisk = p => p.startsWith('Common/') ? '../Common/' + p.slice('Common/'.length) : p.slice('Server/'.length);
  return {
    async list(prefix, exts) {
      const base = toDisk(prefix);
      const all = new Set([...diskFiles, ...[...overlay.keys()].map(toDisk)]);
      return [...all].filter(p => p.startsWith(base) && exts.includes(p.slice(p.lastIndexOf('.') + 1))
        && (overlay.has(fromDisk(p)) || !removed.has(fromDisk(p)))).map(fromDisk);
    },
    async listVanilla(prefix, exts) {
      return [...vanillaIndex().keys()].filter(r => r.startsWith(prefix) && exts.includes(r.slice(r.lastIndexOf('.') + 1)));
    },
    async read(rel) {
      if (overlay.has(rel)) return overlay.get(rel);
      if (removed.has(rel)) return readVanilla(rel);
      const p = path.join(resources, ...toDisk(rel).split('/'));
      return fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : readVanilla(rel);
    },
  };
}

let failures = 0;
function check(label, ok, detail = '') {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (detail ? '  ' + detail : ''));
  if (!ok) failures++;
}
const faultsOf = a => a.cells.flatMap(c => c.items.flatMap(i => i.faults.map(f => ({ id: i.id, ...f }))));
const chest = 'Item/Items/AtlanteanSet/Atlantean_Chest_Default.json';
const red = 'Item/Items/AtlanteanSet/Atlantean_Chest_Red.json';
const readJson = rel => JSON.parse(fs.readFileSync(path.join(resources, 'Server', ...rel.split('/')), 'utf8'));

// 1. The Atlantean chest as it ships.
{
  const a = await analyse(makeReader(io()), chest);
  check('Atlantean chest: default found', a.set && a.set.defaultId === 'Atlantean_Chest_Default');
  check('Atlantean chest: piece family', a.set.pieceFamily === 'Resource_Atlantean_Chest', a.set.pieceFamily);
  check('Atlantean chest: 20 colour sheets, 2 shapes', a.set.sheets.length === 20 && a.set.shapes.length === 2, a.set.sheets.length + ' x ' + a.set.shapes.length);
  check('Atlantean chest: ids spelt colour then shape', a.set.order === 'A');
  const made = a.cells.filter(c => !c.missing).length;
  check('Atlantean chest: all 40 cells already made', made === 40, made + ' made');
  check('Atlantean chest: 39 colour versions found', a.memberCount === 39, String(a.memberCount));
  const faults = faultsOf(a);
  check('Atlantean chest: no fault in the shipped set', faults.length === 0, faults.slice(0, 3).map(f => f.id + ' ' + f.kind).join('; '));
  const p = plan(a, defaultChoice(a));
  check('Atlantean chest: pressing the button writes nothing', p.create.length + p.edit.length + p.icons.length === 0);
}

// 2. Opening a colour version finds the same default.
{
  const a = await analyse(makeReader(io()), red);
  check('open a colour version: same default', a.set && a.set.defaultId === 'Atlantean_Chest_Default', a.error || a.set.defaultId);
}

// 3. The October faults, planted back in memory.
{
  const overlay = new Map();
  const removed = new Set();
  const redDoc = readJson(red);
  redDoc.Recipe.BenchRequirement = [{ Type: 'StructuralCrafting' }];
  overlay.set(red, JSON.stringify(redDoc));
  const gb = 'Item/Items/AtlanteanSet/Atlantean_Chest_Gold_Blue.json';
  const gbDoc = readJson(gb); gbDoc.Texture = 'Items/Armors/Atlantean/Gold Green.png'; overlay.set(gb, JSON.stringify(gbDoc));
  const green = 'Item/Items/AtlanteanSet/Atlantean_Chest_Green.json';
  const greenDoc = readJson(green); greenDoc.Icon = 'Icons/ItemsGenerated/Atlantean_Chest_Default.png'; overlay.set(green, JSON.stringify(greenDoc));
  const purple = 'Item/Items/AtlanteanSet/Atlantean_Chest_Purple.json';
  const purpleDoc = readJson(purple); purpleDoc.Armor.StatModifiers.Health[0].Amount = 30; overlay.set(purple, JSON.stringify(purpleDoc));
  const yellow = 'Item/Items/AtlanteanSet/Atlantean_Chest_Yellow.json';
  const yellowDoc = readJson(yellow); yellowDoc.Recipe = { Input: [{ ItemId: 'Ingredient_Bar_Gold', Quantity: 2 }], BenchRequirement: [{ Id: 'Armor_Bench', Type: 'Crafting', Categories: ['Armor_Chest'] }] }; overlay.set(yellow, JSON.stringify(yellowDoc));
  removed.add('Item/ResourceTypes/Resource_Atlantean_Chest.json');
  const a = await analyse(makeReader(io(overlay, removed)), chest);
  const f = faultsOf(a);
  const has = (id, kind) => f.find(x => x.id === id && x.kind === kind);
  check('planted: recipe with no bench is caught and ticked', !!has('Atlantean_Chest_Red', 'noTable') && has('Atlantean_Chest_Red', 'noTable').byDefault);
  check('planted: a crafted colour is caught and left unticked', !!has('Atlantean_Chest_Yellow', 'noTable') && !has('Atlantean_Chest_Yellow', 'noTable').byDefault);
  check('planted: wrong sheet is caught, left unticked', !!has('Atlantean_Chest_Gold_Blue', 'sheetMismatch') && !has('Atlantean_Chest_Gold_Blue', 'sheetMismatch').byDefault);
  check('planted: two versions in one cell are named', !!has('Atlantean_Chest_Gold_Blue', 'shared') && !!has('Atlantean_Chest_Gold_Green', 'shared'));
  check('planted: the empty cell left by the wrong sheet shows as missing', a.cells.some(c => c.missing && c.id === 'Atlantean_Chest_Gold_Blue' && c.clash));
  check('planted: a shared icon is caught', !!has('Atlantean_Chest_Green', 'iconField'));
  check('planted: different stats are caught, left unticked', !!has('Atlantean_Chest_Purple', 'stats') && !has('Atlantean_Chest_Purple', 'stats').byDefault);
  check('planted: a missing family file is caught', a.set.familyFileMissing === true);
  const p = plan(a, defaultChoice(a));
  const redEdit = p.edit.find(e => e.rel === red);
  check('planted: the plan fixes the bench recipe', !!redEdit && isAlterationRecipe(redEdit.ops[0][1]));
  check('planted: the plan makes the family file', p.create.some(c => c.rel === 'Item/ResourceTypes/Resource_Atlantean_Chest.json' && c.text === '{}\n'));
  check('planted: the plan rebakes the shared icon', p.icons.includes('Atlantean_Chest_Green'));
  check('planted: the plan leaves the unticked faults alone', !p.edit.some(e => e.rel.endsWith('Gold_Blue.json') || e.rel.endsWith('Purple.json') || e.rel.endsWith('Yellow.json')));
  // Shipped control: the same checks on the files as they are find nothing (section 1).
}

// 4. A new set: only the default piece exists.
{
  const removed = new Set();
  for (const p of diskFiles) {
    const m = p.match(/^Server\/(Item\/Items\/AtlanteanSet\/Atlantean_Chest_.*\.json)$/);
    if (m && !m[1].endsWith('_Default.json')) removed.add(m[1]);
  }
  const a = await analyse(makeReader(io(new Map(), removed)), chest);
  const missing = a.cells.filter(c => c.missing).length;
  check('new set: 39 missing cells, all ticked', missing === 39 && defaultChoice(a).make.size === 39, missing + ' missing');
  const p = plan(a, defaultChoice(a));
  check('new set: 39 files and 39 icons planned', p.create.length === 39 && p.icons.length === 39, p.create.length + ' / ' + p.icons.length);
  const redAlt = JSON.parse(p.create.find(c => c.rel.endsWith('/Atlantean_Chest_Red_Alt.json')).text);
  check('new set: a version names the default as Parent', redAlt.Parent === 'Atlantean_Chest_Default');
  check('new set: it restates quality and recipe, which are not inherited', redAlt.Quality === 'Rare' && isAlterationRecipe(redAlt.Recipe) && redAlt.Recipe.Input[0].ResourceTypeId === 'Resource_Atlantean_Chest');
  check('new set: it wears its sheet and alternate shape', redAlt.Texture === 'Items/Armors/Atlantean/Red.png' && redAlt.Model === 'Items/Armors/Atlantean/Chest_Alt.blockymodel');
  check('new set: it names its own icon', redAlt.Icon === 'Icons/ItemsGenerated/Atlantean_Chest_Red_Alt.png');
  check('new set: no stats are copied', !('Armor' in redAlt) && !('ResourceTypes' in redAlt));
  const plainAlt = JSON.parse(p.create.find(c => c.rel.endsWith('/Atlantean_Chest_Alt.json')).text);
  check('new set: the default colour in the alternate shape keeps the default texture', !('Texture' in plainAlt) && plainAlt.Model.endsWith('Chest_Alt.blockymodel'));
  // The made version, resolved through its Parent, equals the shipped full copy on every field
  // the engine reads, except the light (a new set has no colours yet to take one from).
  const reader = makeReader(io(new Map(p.create.map(c => [c.rel, c.text])), removed));
  const made = await reader.effective('Atlantean_Chest_Red_Alt');
  const shipped = readJson('Item/Items/AtlanteanSet/Atlantean_Chest_Red_Alt.json');
  const differ = Object.keys({ ...made, ...shipped }).filter(k => k !== 'Parent' && k !== 'Light' && JSON.stringify(made[k]) !== JSON.stringify(shipped[k]));
  check('new set: the made version equals the shipped one field for field', differ.length === 0, differ.join(', '));
}

check('colour key spelling', colourKey('Deep orange.png') === 'Deep_Orange' && colourKey('Gold Blue.png') === 'Gold_Blue');

// 5. Every default piece of the mod, reported, never changed.
if (process.argv.includes('--sweep')) {
  const reader = makeReader(io());
  const index = await reader.itemIndex();
  const seen = new Set();
  const byKind = new Map();
  let sets = 0;
  let unasked = 0;
  const lines = [];
  for (const rel of index.values()) {
    const own = await reader.docAt(rel);
    if (!own || typeof own.Model !== 'string' || typeof own.Texture !== 'string' || typeof own.Parent === 'string') continue;
    if (isAlterationRecipe(own.Recipe) || (own.Recipe && (own.Recipe.BenchRequirement || []).some(r => r && r.Id === 'ArmoryAlteration'))) continue;
    const a = await analyse(reader, rel);
    if (a.error || seen.has(a.set.pieceFamily)) continue;
    seen.add(a.set.pieceFamily);
    if (a.memberCount === 0) continue;
    sets++;
    const p = plan(a, defaultChoice(a));
    unasked += p.create.length + p.edit.length + p.icons.length;
    for (const f of faultsOf(a)) {
      byKind.set(f.kind, (byKind.get(f.kind) || 0) + 1);
      lines.push(a.set.defaultId + '  ' + f.id + '  ' + f.kind + '  ' + f.text);
    }
  }
  console.log('SWEEP  ' + sets + ' colour sets with versions, faults by kind: ' + JSON.stringify(Object.fromEntries(byKind)));
  check('sweep: on the mod as it ships, the button writes nothing nobody ticked', unasked === 0, unasked + ' writes');
  fs.writeFileSync(path.join(process.env.TEMP || here, 'armory-colours-sweep.txt'), lines.join('\n') + '\n');
  console.log('SWEEP  details: ' + path.join(process.env.TEMP || here, 'armory-colours-sweep.txt'));
}

console.log(failures ? 'FAILED ' + failures : 'ALL PASS');
process.exit(failures ? 1 : 0);
