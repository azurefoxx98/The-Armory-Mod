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
import { makeReader, analyse, plan, defaultChoice, isAlterationRecipe, colourKey, madeByTool, guideMarkdown, checkView, noticeFor, fileIn, ladyGroups } from './armory-studio.js';

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
// game's Iron chest in their family, and 27 game chests inherit from the Iron chest with no family
// of their own, so the page names them and ticks nothing.
{
  const fromVersion = await analyse(makeReader(io()), 'Item/Items/Armor_Iron_Chest_Black.json');
  check('Iron chest colour: its default is the game piece', !fromVersion.error && fromVersion.set.defaultId === 'Armor_Iron_Chest' && fromVersion.set.gameDefault, fromVersion.error || fromVersion.set.defaultId);
  check('Iron chest colour: the family comes from the mod\'s patch', fromVersion.set.pieceFamily === 'Resource_Armor_Iron_Chest', fromVersion.set.pieceFamily);
  check('Iron chest colour: every colour is found', fromVersion.memberCount >= 20, String(fromVersion.memberCount));
  check('Iron chest colour: the 27 game chests inheriting it are named', fromVersion.set.inheritors.length === 27, String(fromVersion.set.inheritors.length));
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

// Files made by a tool: the 52 chest loot files and every showcase file, and nothing of Lady's.
{
  const server = diskFiles.filter(p => p.startsWith('Server/')).map(p => p.slice('Server/'.length));
  const common = diskFiles.filter(p => p.startsWith('Common/')).map(p => '../' + p);
  const loot = server.filter(p => madeByTool(p) === 'the chest loot list').length;
  check('made by a tool: the 52 chest loot files', loot === 52, String(loot));
  const showcase = [...server, ...common].filter(p => madeByTool(p) === 'the showcase world');
  const named = diskFiles.filter(p => /Showcase/.test(p)).length;
  check('made by a tool: every file named for the showcase', showcase.length === named && named > 0, showcase.length + ' of ' + named);
  check('made by a tool: no Atlantean file', !server.some(p => p.includes('Atlantean') && madeByTool(p)));
  check('made by a tool: the other patches stay hers', server.filter(p => p.endsWith('.patch') && !madeByTool(p)).length === 41);
}

// The plugin and the checks agree on every file a tool makes. The checks name a file by its path in
// the resources folder, the plugin by its path in Server or "../Common/", so both are asked about
// every file of the mod and their answers compared.
{
  const asked = diskFiles.filter(p => p.startsWith('Server/') || p.startsWith('Common/'));
  const answers = execFileSync('python', ['-B', '-c',
    'import json,sys; sys.path.insert(0, sys.argv[1]); import check; print(json.dumps([check.made_by(p) for p in json.load(sys.stdin)]))',
    path.resolve(here, '..', 'Checks')], { input: JSON.stringify(asked), maxBuffer: 1 << 26 }).toString();
  const theirs = JSON.parse(answers);
  const differ = asked.filter((p, i) => (madeByTool(p.startsWith('Server/') ? p.slice('Server/'.length) : '../' + p) || null) !== theirs[i]);
  check('made by a tool: the plugin and the checks agree on all ' + asked.length + ' files', differ.length === 0, differ.slice(0, 3).join(', '));
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
      { says: 'The check "R" could not run, so it says nothing about your work.', do: 'Send Larsonix a screenshot.', items: [], known: true, detail: 'boom' }] }] };
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
