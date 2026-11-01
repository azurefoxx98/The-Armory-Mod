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
import { makeReader, analyse, plan, defaultChoice, isAlterationRecipe, colourKey, guideMarkdown, checkView, noticeFor, fileIn, ladyGroups, ladyItems, sortItems, matchesFilter, ITEM_ORDERS, hyCreatorTooOld, NEEDS_HYCREATOR, withMark, withoutMark, sheetColour,
  parseGitConfig, setupState, runView, releaseProblem, compareVersions, taskLaunch, checkLaunch, languageChanges, GET_SCRIPT, ARMORY_SOURCE, RUNS, statusLine, hiddenStyle, HIDDEN_TITLES } from './armory-studio.js';

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

// The game's item and family files, read from Assets.zip in one pass: the page may scan every
// game item to find the one a colour set starts from.
let vanilla = null;
function vanillaIndex() {
  if (vanilla) return vanilla;
  vanilla = new Map();
  if (!fs.existsSync(assetsZip)) return vanilla;
  const dump = execFileSync('python', ['-c', 'import sys,zipfile,json;z=zipfile.ZipFile(sys.argv[1]);sys.stdout.write(json.dumps({n[len("Server/"):]: z.read(n).decode("utf-8","replace") for n in z.namelist() if n.startswith(("Server/Item/Items/", "Server/Item/ResourceTypes/")) and n.endswith(".json")}))', assetsZip], { maxBuffer: 1 << 28 }).toString('utf8');
  for (const [rel, text] of Object.entries(JSON.parse(dump))) vanilla.set(rel, text);
  return vanilla;
}
function readVanilla(rel) {
  return vanillaIndex().get(rel) ?? null;
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
const canonicalText = text => JSON.stringify(JSON.parse(text));
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

// 2b. A piece inheriting from a game item never takes one of the game's families as its colour
// family. The signs inherit Wood_Trunk: taking it once offered to put all three signs into the
// game's wood family and to write an empty Wood_Trunk file over the game's own.
{
  const a = await analyse(makeReader(io()), 'Item/Items/EmptySign.json');
  check('signs: the game family Wood_Trunk is not their colour family', !a.error && a.set.pieceFamily !== 'Wood_Trunk', a.error || a.set.pieceFamily);
  const p = plan(a, defaultChoice(a));
  check('signs: the button touches no family', !p.create.some(c => c.rel.startsWith('Item/ResourceTypes/') && c.rel !== 'Item/ResourceTypes/' + a.set.pieceFamily + '.json')
    && !p.edit.some(e => e.ops.some(([field]) => field[0] === 'ResourceTypes' && JSON.stringify(e.ops).includes('Wood_Trunk'))),
    JSON.stringify(p).slice(0, 200));
}

// 2c. A set whose default is a game piece: the Iron chest recolours. The mod's patch puts the
// game's Iron chest in their family, and 26 game chests inherit from the Iron chest with no family
// of their own, so the page names them and ticks nothing.
{
  const fromVersion = await analyse(makeReader(io()), 'Item/Items/Armor_Iron_Chest_Black.json');
  check('Iron chest colour: its default is the game piece', !fromVersion.error && fromVersion.set.defaultId === 'Armor_Iron_Chest' && fromVersion.set.gameDefault, fromVersion.error || fromVersion.set.defaultId);
  check('Iron chest colour: the family comes from the mod\'s patch', fromVersion.set.pieceFamily === 'Resource_Armor_Iron_Chest', fromVersion.set.pieceFamily);
  check('Iron chest colour: every colour is found', fromVersion.memberCount >= 20, String(fromVersion.memberCount));
  check('Iron chest colour: the 26 game chests inheriting it are named', fromVersion.set.inheritors.length === 26, String(fromVersion.set.inheritors.length));
  const p = plan(fromVersion, defaultChoice(fromVersion));
  check('Iron chest colour: nothing is ticked while game items inherit its family', p.create.length + p.edit.length + p.icons.length === 0, JSON.stringify(p).slice(0, 200));
  const fromGame = await analyse(makeReader(io()), 'Item/Items/Armor/Iron/Armor_Iron_Chest.json');
  check('the game Iron chest opened itself: the same set', !fromGame.error && fromGame.set.defaultId === 'Armor_Iron_Chest' && fromGame.memberCount === fromVersion.memberCount, fromGame.error || String(fromGame.memberCount));
  const sword = await analyse(makeReader(io()), 'Item/Items/VanillaRecolors/Long_Sword_Cobalt/Longsword_Cobalt_Red.json');
  check('Cobalt longsword colour: its default is the game\'s Cobalt longsword', !sword.error && sword.set.defaultId === 'Weapon_Longsword_Cobalt', sword.error || sword.set.defaultId);
}

// 2d. A new set of colours for a game piece, planted in memory: two colour sheets of the Mithril
// chest in the folder the mod uses by convention, and a helmet's sheet beside them. Opening the
// game's Mithril chest offers both colours and not the helmet; the button writes the family, the
// patch putting the game piece in it, and two small versions; pressing again writes nothing.
// The Mithril chest has no colours in the mod, so the set starts empty on every branch. The
// Thorium chest did until Lady's own Thorium colours arrived.
{
  const overlay = new Map();
  const dir = '../Common/Items/Armors/VanillaRecolors/Mithril/';
  for (const name of ['Chest_Black.png', 'Chest_Red.png', 'Helm_Red.png']) overlay.set(dir + name, '');
  const mithril = 'Item/Items/Armor/Mithril/Armor_Mithril_Chest.json';
  const a = await analyse(makeReader(io(overlay)), mithril);
  check('Mithril chest: a game piece is a default', !a.error && a.set.gameDefault && a.set.defaultId === 'Armor_Mithril_Chest', a.error || JSON.stringify(a.set && a.set.defaultId));
  check('Mithril chest: it does not take the Iron chest\'s family it inherits', a.set.pieceFamily !== 'Resource_Armor_Iron_Chest', a.set.pieceFamily);
  check('Mithril chest: its two colours, not the helmet\'s sheet', a.set.sheets.length === 3 && !a.set.sheets.some(s => s.endsWith('Helm_Red.png')), a.set.sheets.join(', '));
  const p = plan(a, defaultChoice(a));
  const versions = p.create.filter(c => c.rel.endsWith('.json') && c.rel.startsWith('Item/Items/'));
  const patch = p.create.find(c => c.rel === 'Item/Items/Armor/Mithril/Armor_Mithril_Chest.patch');
  check('Mithril chest: the family file is made', p.create.some(c => c.rel === 'Item/ResourceTypes/' + a.set.pieceFamily + '.json'));
  check('Mithril chest: a patch adds the game piece to the family with Patchly\'s +', !!patch && canonicalText(patch.text) === canonicalText(JSON.stringify({ 'ResourceTypes+': [{ Id: a.set.pieceFamily }] })), patch && patch.text);
  const red = versions.map(v => JSON.parse(v.text)).find(v => v.Texture === 'Items/Armors/VanillaRecolors/Mithril/Chest_Red.png');
  check('Mithril chest: two colours are made, named Black and Red', versions.length === 2 && versions.some(v => v.rel.endsWith('/Armor_Mithril_Chest_Red.json')), versions.map(v => v.rel).join(', '));
  check('Mithril chest: a colour names the game piece as its parent and sits in both tabs', !!red && red.Parent === 'Armor_Mithril_Chest'
    && JSON.stringify(red.Categories) === JSON.stringify(['ArmoryCategory.ArmoryArmors', 'Items.Armors']) && isAlterationRecipe(red.Recipe), red && JSON.stringify(red));
  for (const c of p.create) overlay.set(c.rel, c.text);
  const again = await analyse(makeReader(io(overlay)), mithril);
  const p2 = plan(again, defaultChoice(again));
  check('Mithril chest: the made colours are found as its colours', again.memberCount === 2, String(again.memberCount));
  check('Mithril chest: pressing again writes nothing but the icons not yet baked', p2.create.length === 0 && p2.edit.length === 0, JSON.stringify(p2).slice(0, 300));
  const fromColour = await analyse(makeReader(io(overlay)), versions[0].rel);
  check('Mithril chest: opening a made colour finds the same set', !fromColour.error && fromColour.set.defaultId === 'Armor_Mithril_Chest', fromColour.error || fromColour.set.defaultId);
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

// The oldest HyCreator the plugin works in. 1.3.8 is the last one with the empty vanilla listing and
// the empty file picker, so it alone of these is refused.
{
  const old = ['1.3.8', '1.3.0', '1.2.9', '0.9.9'];
  const fine = ['1.3.9', '1.3.10', '1.4.0', '1.10.0', '2.0.0', '1.4.0-beta.1'];
  check('HyCreator too old: ' + old.join(', '), old.every(hyCreatorTooOld), old.filter(v => !hyCreatorTooOld(v)).join(', '));
  check('HyCreator new enough: ' + fine.join(', '), !fine.some(hyCreatorTooOld), fine.filter(hyCreatorTooOld).join(', '));
  check('HyCreator whose version cannot be read is let through', ![undefined, null, '', 'dev', '1.3'].some(hyCreatorTooOld));
  check('the floor is 1.3.9', NEEDS_HYCREATOR === '1.3.9');
}

// A texture named for no colour never gives a colour name (the gray Praetorian helm wears Head_Texture.png).
{
  check('colour name: Helm_Blue.png is Blue', sheetColour('Items/X/Helm_Blue.png') === 'Blue');
  check('colour name: Head_Texture.png names no colour', sheetColour('Items/X/Head_Texture.png') === null);
  check('colour name: a plain colour file is its colour', sheetColour('Items/X/Deep orange.png') === 'Deep_Orange');
  const a = await analyse(makeReader(io()), 'Item/Items/Praetorian_Helm.json');
  check('colour name: the Praetorian helm set offers no Praetorian_Helm_Texture', a.set && !a.cells.some(c => c.id === 'Praetorian_Helm_Texture'),
    a.cells ? a.cells.map(c => c.id).join(', ') : JSON.stringify(a).slice(0, 200));
  const gray = a.cells && a.cells.find(c => c.items.some(i => i.id === 'Praetorian_Helm_Gray'));
  check('colour name: the gray helm stays in its own cell, unnamed, with nothing to make', gray && gray.unnamed && !gray.missing);
  const removed = new Set(['Item/Items/Praetorian_Helm_Gray.json']);
  const b = await analyse(makeReader(io(new Map(), removed)), 'Item/Items/Praetorian_Helm.json');
  const empty = b.cells && b.cells.find(c => c.unnamed);
  check('colour name: without the gray helm its texture is offered as nothing to make', empty && empty.missing
    && !defaultChoice(b).make.has(null) && plan(b, { make: new Set([null]), fix: new Set() }).create.length === 0);
}

// The checks panel and its notices, from results shaped as check.py writes them.
{
  const pass = { finished: '2026-10-25T21:00:00+01:00', commit: 'abcdef1234', branch: 'lady', error: null,
    checks: [{ id: 'itemcheck', title: 'T', status: 'pass', problems: [], lady: [] }, { id: 'languages', title: 'L', status: 'pass', problems: [], lady: [] }] };
  const quality = { says: 'Its quality Ghostly does not exist.', do: 'Pick a quality HyCreator suggests.', known: true };
  const fail = { ...pass, finished: '2026-10-25T21:05:00+01:00', checks: [
    { id: 'itemcheck', title: 'T', status: 'fail', problems: ['x', 'y'], lady: [
      { ...quality, items: [{ label: 'B', open: 'Item/Items/A/B.json' }] },
      { ...quality, items: [{ label: 'C', open: 'Item/Items/A/C.json' }] }] },
    { id: 'recipecheck', title: 'R', status: 'error', problems: [], output_tail: ['boom'], lady: [
      { says: 'The check "R" could not run, so it says nothing about your work.', do: 'This one is ours to fix, and you can keep working.', items: [], known: true, detail: 'boom' }] }] };
  const broke = { ...pass, finished: '2026-10-25T21:06:00+01:00', error: 'Hytale\'s files were not found. Start Hytale once, then commit again.', checks: [] };
  check('panel: a clean result is a pass', checkView(pass, null).state === 'pass' && checkView(pass, null).problems === 0);
  const v = checkView(fail, null);
  check('panel: one fix on two items and a check that could not run are two things to fix', v.state === 'problems' && v.problems === 2, String(v.problems));
  const q = v.groups.find(g => g.says === quality.says);
  check('panel: the same fix on two items is one card with both items', q && q.items.length === 2 && q.do === quality.do, JSON.stringify(q));
  check('panel: an item opens from its card', q && q.items[0].open === 'Item/Items/A/B.json');
  check('panel: the largest group comes first', v.groups[0] === q);
  check('panel: what only Larsonix needs is kept apart', v.groups[1].details[0] === 'boom');
  check('panel: an error result says so', checkView(broke, null).state === 'error' && checkView(broke, null).error === broke.error);
  check('panel: a newer run in progress shows as busy', checkView(pass, { started: '2026-10-25T21:01:00+01:00' }).busy === true
    && checkView(pass, { started: '2026-10-25T20:59:00+01:00' }).busy === false);
  check('panel: no result yet, with a run going', checkView(null, { started: 'x' }).state === 'running' && checkView(null, null).state === 'none');
  // A result written before the translation shows its lines whole, so an update never blanks the panel.
  const old = { ...pass, finished: 'old', checks: [{ id: 'itemcheck', title: 'T', status: 'fail', problems: ['Server/Item/Items/A/B.json: its quality Ghostly does not exist'] }] };
  const ov = checkView(old, null);
  check('panel: a result from before the translation still shows each line and opens its file', ov.problems === 1 && ov.groups[0].items[0].open === 'Item/Items/A/B.json');
  check('panel: a problem naming no file opens nothing', fileIn('item X: input family Y has no member item') === null);
  check('notice: a first result with problems is announced', /found 2 things to fix/.test(noticeFor(null, fail) || ''));
  check('notice: the same problems in a later run stay quiet', noticeFor(fail, { ...fail, finished: 'later' }) === null);
  const another = { ...fail, finished: 'later', checks: [{ ...fail.checks[0], lady: [...fail.checks[0].lady, { says: 'New.', do: 'Fix it.', items: [], known: true }] }, fail.checks[1]] };
  check('notice: a changed list of problems is announced', /found 3 things to fix/.test(noticeFor(fail, another) || ''));
  check('notice: a first clean result stays quiet', noticeFor(null, pass) === null);
  check('notice: the same result twice is announced once', noticeFor(fail, fail) === null);
  check('notice: a pass after problems is announced as a success', (noticeFor(fail, { ...pass, finished: 'z' }) || '').startsWith('✓ '));
  check('notice: a pass after a pass stays quiet', noticeFor(pass, { ...pass, finished: 'z' }) === null);
  check('notice: a run that could not complete is announced', /could not run/.test(noticeFor(pass, broke) || ''));
  check('notice: one thing is spelt singular', /found 1 thing to fix\./.test(noticeFor(null, { ...fail, checks: [fail.checks[0]] }) || ''));
  check('ladyGroups: entries with the same words and fix merge, others stay apart',
    ladyGroups([{ says: 'a', do: 'b', items: [{ label: 'X' }] }, { says: 'a', do: 'b', items: [{ label: 'X' }, { label: 'Y' }] }, { says: 'a', do: 'c', items: [] }]).map(g => g.items.length).join() === '2,0');

  // "Normal for me": one problem on one item, never the same problem on another item, and each can come back.
  const key = 'itemcheck|' + quality.says;
  const one = checkView(fail, null, [{ key, item: 'B' }]);
  const q1 = one.groups.find(g => g.says === quality.says);
  check('normal: a problem judged normal on one item still shows on the other', q1 && q1.items.map(i => i.label).join() === 'C', JSON.stringify(q1));
  check('normal: the hidden one is listed to be shown again', one.hidden.length === 1 && one.hidden[0].item === 'B' && one.hidden[0].key === key);
  const both = checkView(fail, null, [{ key, item: 'B' }, { key, item: 'C' }]);
  check('normal: a card whose every item is normal leaves her list', !both.groups.some(g => g.says === quality.says) && both.problems === 1);
  const gone = checkView(fail, null, [{ key: 'itemcheck|gone', item: 'Old' }]);
  check('normal: a mark matching nothing any more is listed apart', gone.stale.length === 1 && gone.stale[0].item === 'Old' && gone.hidden.length === 0);
  check('normal: the notice counts only what is still hers', /found 1 thing to fix/.test(noticeFor(null, fail, [{ key, item: 'B' }, { key, item: 'C' }]) || ''));
  const marks = withMark(withMark([], { key, item: 'B' }), { key, item: 'B', date: 'x' });
  check('normal: marking twice keeps one mark, and Show again removes it', marks.length === 1 && marks[0].date === 'x'
    && withoutMark(marks, { key, item: 'B' }).length === 0 && withoutMark(marks, { key, item: 'C' }).length === 1);
  check('normal: the button carries the key of the item\'s own problem',
    checkView({ ...fail, checks: [{ id: 'game', status: 'fail', lady: [{ ...quality, key: 'game|k', items: [{ label: 'B' }] }] }] }, null, [])
      .groups[0].items[0].problem === 'game|k');

  // Larsonix's problems: apart, read only, never counted as hers.
  const ours = { ...fail, checks: [...fail.checks, { id: 'game', title: 'G', status: 'fail', lady: [
    { says: 'Uses the stat Volatility.', do: 'x', owner: 'us', items: [{ label: 'Praetorian_Boots' }] }] }] };
  const ov2 = checkView(ours, null, []);
  check("ours: Larsonix's problems are kept apart and not counted as hers", ov2.ours.length === 1 && ov2.problems === 2
    && !ov2.groups.some(g => g.says === 'Uses the stat Volatility.'));
  const onlyOurs = { ...pass, checks: [{ id: 'game', title: 'G', status: 'fail', lady: [{ says: 'Ours.', do: 'x', owner: 'us', items: [] }] }] };
  check("ours: with only Larsonix's problems her panel says everything of hers is fine", checkView(onlyOurs, null, []).state === 'pass');

  // What her work added comes first, whatever its size.
  const order = ladyGroups([{ says: 'big', do: 'x', items: [{ label: '1' }, { label: '2' }, { label: '3' }] }, { says: 'small', do: 'x', items: [{ label: 'a' }], new: true }]);
  check('new: what her work added comes first', order[0].says === 'small' && order[0].new === true);

  // The view by item: the same problems, each item once with every problem on it, and the same marks.
  const two = { ...pass, checks: [{ id: 'game', title: 'G', status: 'fail', lady: [
    { says: 'No icon.', do: 'Generate one.', key: 'game|icon|B', items: [{ label: 'B', open: 'Item/Items/A/B.json' }] },
    { says: 'No icon.', do: 'Generate one.', key: 'game|icon|C', items: [{ label: 'C', open: 'Item/Items/Z/C.json' }] },
    { says: 'No name.', do: 'Add one.', key: 'game|name|B', items: [{ label: 'B', open: 'Item/Items/A/B.json' }], new: true },
    { says: 'Its stat X does not exist.', do: 'x', key: 'game|stat|B', owner: 'us', items: [{ label: 'B' }] },
    { says: 'The check could not run.', do: 'This one is ours to fix, and you can keep working.', key: 'branch|run', items: [] }] }] };
  const bv = checkView(two, null, []);
  const li = ladyItems(bv.entries);
  const b = li.items.find(i => i.label === 'B');
  check('by item: each item once, with every problem on it', li.items.length === 2 && b && b.problems.length === 2 && li.items.find(i => i.label === 'C').problems.length === 1,
    JSON.stringify(li.items.map(i => [i.label, i.problems.length])));
  check('by item: an item keeps the file it opens', b.open === 'Item/Items/A/B.json');
  check('by item: a problem on no item stays a card of its own', li.loose.length === 1 && li.loose[0].says === 'The check could not run.');
  check('by item: what her work added comes first on the item, and marks the item new', b.new && b.problems[0].says === 'No name.');
  check('by item: each problem keeps its own key for Normal for me', b.problems.map(p => p.key).sort().join() === 'game|icon|B,game|name|B');
  check("by item: Larsonix's problems stay apart", !li.items.some(i => i.problems.some(p => p.says.startsWith('Its stat')))
    && ladyItems(bv.ourEntries).items.length === 1 && ladyItems(bv.ourEntries).items[0].problems[0].says === 'Its stat X does not exist.');
  const marked = checkView(two, null, [{ key: 'game|icon|B', item: 'B' }]);
  const mb = ladyItems(marked.entries).items.find(i => i.label === 'B');
  check('by item: a mark made in either view hides that one problem on that item in both', mb.problems.length === 1 && mb.problems[0].key === 'game|name|B'
    && marked.groups.find(g => g.says === 'No icon.').items.map(i => i.label).join() === 'C');
  const allMarked = checkView(two, null, [{ key: 'game|icon|B', item: 'B' }, { key: 'game|name|B', item: 'B' }]);
  check('by item: an item whose every problem is normal leaves the list', !ladyItems(allMarked.entries).items.some(i => i.label === 'B'));

  // The orders, each breaking its ties by name.
  const it = (label, n, isNew = false, open) => ({ label, open, new: isNew, problems: Array(n).fill({}) });
  const list = [it('b', 1), it('A', 2), it('c', 3, true), it('a10', 1), it('a9', 1), it('d', 2, false, 'Item/Items/Z/d.json'), it('e', 2, false, 'Item/Items/B/e.json')];
  const names = order => sortItems(list, order).map(i => i.label).join();
  check('sort: by name, A to Z, any case, numbers as numbers', names('name') === 'A,a9,a10,b,c,d,e', names('name'));
  check('sort: most problems first, ties by name', names('count') === 'c,A,d,e,a9,a10,b', names('count'));
  check('sort: new first, then most problems, then name', names('new') === 'c,A,d,e,a9,a10,b', names('new'));
  check('sort: by folder, then name', names('folder').startsWith('A,a9,a10,b,c,e,d'), names('folder'));
  check('sort: an unknown order falls back to new first', names('nonsense') === names('new'));
  check('sort: the list given is never reordered', list.map(i => i.label).join() === 'b,A,c,a10,a9,d,e');
  check('sort: every order offered exists', ITEM_ORDERS.every(o => sortItems(list, o.id).length === list.length) && ITEM_ORDERS.map(o => o.id).join() === 'new,count,name,folder');

  // The filter: every word typed, any case, any order, a space for an underscore.
  check('filter: words in any order and case', matchesFilter('Praetorian_Boots_Gray', 'gray PRAETORIAN') && matchesFilter('Praetorian_Boots_Gray', 'praetorian boots'));
  check('filter: an underscore typed counts as a space', matchesFilter('Praetorian_Boots_Gray', 'praetorian_gray'));
  check('filter: every word must be there',!matchesFilter('Praetorian_Boots_Gray', 'praetorian red'));
  check('filter: empty keeps everything', matchesFilter('X', '') && matchesFilter('X', '   ') && matchesFilter('X', null));
  check('filter: a path is found by its file name', matchesFilter('UI/Custom/Common/Buttons/Disabled@2x.png', 'disabled'));
}

// The panel of buttons: what it reads from her copy, what it starts, and what it shows.
{
  const config = '[core]\n\trepositoryformatversion = 0\n\thooksPath = Studio/Tools/hooks\n[remote "origin"]\n\turl = https://example.invalid/x.git\n'
    + '[armory]\n\tmods = C:\\\\Users\\\\L\\\\AppData\\\\Roaming\\\\Hytale\\\\UserData\\\\Mods\n\tpython = "C:/Users/L/AppData/Local/TheArmory/Python-3.15.1/python.exe"\n';
  const c = parseGitConfig(config);
  check('panel: her checks\' program is read from .git/config, quotes removed', c.python === 'C:/Users/L/AppData/Local/TheArmory/Python-3.15.1/python.exe', JSON.stringify(c));
  check('panel: setup ran when the copy points git at the Studio\'s own steps', c.hooksPath === 'Studio/Tools/hooks');
  check('panel: a setting of another section is never taken for hers', parseGitConfig('[remote "armory"]\n\tpython = x\n').python === undefined);
  check('panel: setup state, never run, checks missing, ready', setupState(parseGitConfig('[core]\n\tbare = false\n')) === 'none'
    && setupState(parseGitConfig('[core]\n\thooksPath = Studio/Tools/hooks\n[armory]\n\tmods = C:/x/Mods\n')) === 'nochecks' && setupState(c) === 'ready' && setupState(null) === 'none');
  // The USB key's copy carries the hooks and the checks from the start, and the game's place only while it plays this computer's game.
  check('panel: on the key, Set up shows until this computer\'s Hytale plays the key', setupState(parseGitConfig('[core]\n\thooksPath = Studio/Tools/hooks\n[armory]\n\tpython = L:/Programs/Python/python.exe\n')) === 'none');

  const t0 = 1_000_000;
  check('run: no result and nothing started is idle', runView(null, null, null, t0).state === 'idle');
  check('run: started, its result not there yet, is starting', runView(null, 'u-1', t0, t0 + 2000).state === 'starting');
  check('run: an older result does not count for a new start', runView({ id: 'u-0', state: 'done', lines: [] }, 'u-1', t0, t0 + 2000).state === 'starting');
  check('run: no result of its own after 30 seconds is a start that never happened', runView(null, 'u-1', t0, t0 + 31000).state === 'lost');
  const done = runView({ id: 'u-1', state: 'done', finished: 'x', lines: ['Updating your copy of The Armory.', '', 'For Larsonix: fatal: boom', 'Done.'] }, 'u-1', t0, t0 + 5000);
  check('run: its own result shows its state and its lines, blanks left out', done.state === 'done' && done.lines.length === 3, JSON.stringify(done.lines));
  check('run: a line for Larsonix is marked so the panel shows it small', done.lines[1].larsonix && done.lines[1].text === 'fatal: boom' && !done.lines[0].larsonix);
  check('run: a result read with no start waiting shows as it is', runView({ id: 'x', state: 'running', lines: ['a'] }, null, null).state === 'running');
  check('run: an unknown state is a failure, never a pass', runView({ id: 'x', state: 'weird', lines: [] }, null, null).state === 'failed');

  const info = { last: '1.24.2', suggested: '1.24.3', released: ['1.24.0', '1.24.1', '1.24.2'] };
  const form = { version: '', choice: 'draft', notes: 'Fixed things.', message: '' };
  check('release form: an empty version takes the suggested one', releaseProblem(info, form) === null);
  check('release form: a version that is not one is named', /is not a version number/.test(releaseProblem(info, { ...form, version: '1.25' }) || ''));
  check('release form: a version not newer than the last release is named', /not newer than the last release, 1\.24\.2/.test(releaseProblem(info, { ...form, version: '1.24.1' }) || ''));
  check('release form: v in front is allowed', releaseProblem(info, { ...form, version: 'v1.25.0' }) === null);
  check('release form: an empty changelog is named', /changelog/.test(releaseProblem(info, { ...form, notes: '  ' }) || ''));
  check('release form: a draft or a publish, nothing else', /Choose/.test(releaseProblem(info, { ...form, choice: 'message' }) || '') && releaseProblem(info, { ...form, choice: 'publish' }) === null);
  check('release form: no choice is named', /Choose/.test(releaseProblem(info, { ...form, choice: '' }) || ''));
check('release form: work not sent needs her line', /what you did/.test(releaseProblem({ ...info, uncommitted: true }, form) || '')
  && releaseProblem({ ...info, uncommitted: true }, { ...form, work: 'New gray set' }) === null);
check('status: files changed', statusLine({ repo: true, ahead: 0 }, 3) === '3 files changed, not sent yet.', statusLine({ repo: true, ahead: 0 }, 3));
check('status: one file', statusLine({ repo: true, ahead: 2 }, 1) === '1 file changed, not sent yet.', statusLine({ repo: true, ahead: 2 }, 1));
check('status: committed, not sent', /not on GitHub yet/.test(statusLine({ repo: true, ahead: 2 }, 0)));
check('status: all sent', statusLine({ repo: true, ahead: 0, behind: 3 }, 0) === 'All your work is on GitHub.');
check('status: no copy says nothing', statusLine(null, 0) === '');
check('hidden: every title is in the style', HIDDEN_TITLES.every(t => hiddenStyle().includes('[title^="' + t + '"]')));
check('hidden: her branch button in its three wordings', /commit\(s\) to push/.test(hiddenStyle()) && /first push sets it/.test(hiddenStyle()) && /named origin yet/.test(hiddenStyle()));
  check('release form: versions compare as numbers', compareVersions('1.10.0', '1.9.9') === 1 && compareVersions('1.2.3', '1.2.3') === 0 && compareVersions('0.9.0', '1.0.0') === -1);

  const l = taskLaunch('C:\\Windows', 'C:/Users/L/Documents/The-Armory-Mod/', 'release', 'release-1', { Version: '1.25.0', Choice: 'draft', NotesFile: 'C:\\n.txt', MessageFile: '' });
  check('launch: through the console host with no window', l.exe === 'C:\\Windows\\System32\\conhost.exe' && l.args[0] === '--headless' && l.args[1].endsWith('\\WindowsPowerShell\\v1.0\\powershell.exe'));
  check('launch: the copy\'s own runner, its task and its start', l.args.includes('C:\\Users\\L\\Documents\\The-Armory-Mod\\Studio\\Tools\\run.ps1')
    && l.args[l.args.indexOf('-Task') + 1] === 'release' && l.args[l.args.indexOf('-Id') + 1] === 'release-1', l.args.join(' '));
  check('launch: the form\'s values follow, an empty one left out', l.args.slice(-6).join('|') === '-Version|1.25.0|-Choice|draft|-NotesFile|C:\\n.txt');
  const k = checkLaunch('C:\\Windows', 'C:/M/The-Armory-Mod', 'C:/P/python.exe');
  check('launch: the checks start as the git steps start them, with no window', k.exe.endsWith('conhost.exe') && k.args.join('|') === '--headless|C:\\P\\python.exe|C:\\M\\The-Armory-Mod\\Studio\\Tools\\Checks\\check.py|--spawn');

  const before = new Map([['Languages/en-US/server.lang', 'a = A\n'], ['Languages/fr-FR/server.lang', 'a = B\n'], ['Languages/de-DE/server.lang', 'x\n']]);
  const after = new Map([['Languages/en-US/server.lang', 'a = A\nb = B\n'], ['Languages/fr-FR/server.lang', 'a = B\n'], ['Languages/es-ES/server.lang', 'y\n']]);
  check('names: a changed, an arrived and a gone language file are each named, an unchanged one is not',
    languageChanges(before, after).sort().join() === 'Languages/de-DE/server.lang,Languages/en-US/server.lang,Languages/es-ES/server.lang',
    languageChanges(before, after).join());
  check('names: nothing changed is nothing to do', languageChanges(before, new Map(before)).length === 0);

  check('get: clones her branch straight away, never main', /git clone --quiet --branch lady \$Source \$copy/.test(GET_SCRIPT) && ARMORY_SOURCE.endsWith('/The-Armory-Mod.git'));
  check('runs: the panel reads the folder the runner writes', RUNS === 'Studio/Tools/Runs/' && fs.readFileSync(path.resolve(here, '..', 'run.ps1'), 'utf8').includes("Join-Path $tools 'Runs'"));
  // The script reaches her as text inside this file, so nothing else ever checks it parses.
  const parsed = execFileSync('powershell.exe', ['-NoProfile', '-Command',
    '$e = $null; [void][System.Management.Automation.Language.Parser]::ParseInput([Console]::In.ReadToEnd(), [ref]$null, [ref]$e); $e.Count'],
    { input: GET_SCRIPT }).toString().trim();
  check('get: its script parses with no error', parsed === '0', parsed);
}

// The guide in the repository is the panel's text, word for word.
{
  const file = path.resolve(here, '..', '..', 'GUIDE.md');
  if (process.argv.includes('--write-guide')) fs.writeFileSync(file, guideMarkdown());
  const same = fs.existsSync(file) && fs.readFileSync(file, 'utf8') === guideMarkdown();
  check('Studio/GUIDE.md is the panel\'s text (node armory-studio.test.mjs --write-guide rewrites it)', same);
  const text = guideMarkdown();
  check('the guide is plain text', !/[^\x00-\x7F]/.test(text));
}

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
    // A Parent that is one of the mod's own items marks a colour version. A Parent from the game
    // (every shield and most weapons inherit a vanilla template) still makes a default piece.
    if (!own || typeof own.Parent === 'string' && index.has(own.Parent)) continue;
    const eff = typeof own.Parent === 'string' ? await reader.effective(own.Parent) : own;
    if (typeof (own.Model ?? eff?.Model) !== 'string' || typeof (own.Texture ?? eff?.Texture) !== 'string') continue;
    if (isAlterationRecipe(own.Recipe) || (own.Recipe && (own.Recipe.BenchRequirement || []).some(r => r && r.Id === 'ArmoryAlteration'))) continue;
    const a = await analyse(reader, rel);
    if (a.error || seen.has(a.set.pieceFamily)) continue;
    seen.add(a.set.pieceFamily);
    if (a.memberCount === 0) continue;
    sets++;
    lines.push(a.set.defaultId + '  SET  ' + a.memberCount + ' versions, model ' + a.set.baseShape);
    const p = plan(a, defaultChoice(a));
    unasked += p.create.length + p.edit.length + p.icons.length;
    for (const [what, rows] of [['create', p.create], ['edit', p.edit], ['icon', p.icons]]) {
      for (const row of rows) lines.push(a.set.defaultId + '  WRITE ' + what + '  ' + JSON.stringify(row).slice(0, 300));
    }
    for (const f of faultsOf(a)) {
      byKind.set(f.kind, (byKind.get(f.kind) || 0) + 1);
      lines.push(a.set.defaultId + '  ' + f.id + '  ' + f.kind + '  ' + f.text);
    }
  }
  console.log('SWEEP  ' + sets + ' colour sets with versions, faults by kind: ' + JSON.stringify(Object.fromEntries(byKind)));
  // What the button offers by default on the shipped mod is a finding in the mod's own data, listed
  // in the details file. What must never be offered is a family write: a family file of the game's
  // own, or a piece put into a family the game owns.
  const gameFamilies = new Set((await reader.listVanilla('Item/ResourceTypes/', ['json'])).map(r => r.slice(r.lastIndexOf('/') + 1, -5)));
  const familyWrites = lines.filter(l => / WRITE (create|edit) /.test(l)
    && [...gameFamilies].some(f => l.includes('"Item/ResourceTypes/' + f + '.json"') || l.includes('{"Id":"' + f + '"}')));
  console.log('SWEEP  offered by default on the shipped mod: ' + unasked + ' writes, listed in the details file');
  check('sweep: no set is offered a write into one of the game\'s families', familyWrites.length === 0, familyWrites.slice(0, 2).join(' | '));
  fs.writeFileSync(path.join(process.env.TEMP || here, 'armory-colours-sweep.txt'), lines.join('\n') + '\n');
  console.log('SWEEP  details: ' + path.join(process.env.TEMP || here, 'armory-colours-sweep.txt'));
}

console.log(failures ? 'FAILED ' + failures : 'ALL PASS');
process.exit(failures ? 1 : 0);
