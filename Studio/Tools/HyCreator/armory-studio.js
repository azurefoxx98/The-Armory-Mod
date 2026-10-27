// The Armory Studio, a HyCreator plugin for working on The Armory.
//
// It adds a Colours page beside every item. From the piece that is open it finds the whole
// colour set: the default piece, every colour sheet and alternate model in its model folder,
// and every colour version that already exists. It shows them as a grid, checks each version
// for the faults that used to stop a set from working, makes the missing versions and their
// icons, and fixes what is wrong, all with one button.
//
// A new colour version is a small file that names the default piece as its Parent and states
// only what differs: its texture, its model when it is an alternate shape, its light, its icon,
// its quality and its Alteration Table recipe. The engine does not inherit Recipe or Quality, so
// those two are always written. Everything else, stats included, comes from the default piece,
// so a stat changed there reaches every colour.
//
// The analysis below takes its file access as an argument, so it runs the same inside HyCreator
// and in a test under Node. Only the page at the bottom touches HyCreator itself.

export const ALTERATION_BENCH = 'ArmoryAlteration';
const ICON_FOLDER = 'Icons/ItemsGenerated/';
// Item fields the engine does not hand from a Parent to a child.
const NOT_INHERITED = new Set(['Recipe', 'Quality', 'Reticle', 'Ability', 'HudUI', 'BlockType', 'Parent']);

// ---- small helpers ----------------------------------------------------------------------------

export function fileName(path) {
  return path.slice(path.lastIndexOf('/') + 1);
}

export function stem(path) {
  const name = fileName(path);
  const dot = name.lastIndexOf('.');
  return dot > 0 ? name.slice(0, dot) : name;
}

export function folder(path) {
  return path.slice(0, path.lastIndexOf('/') + 1);
}

// "Deep orange.png" becomes "Deep_Orange", the way the mod's item ids spell a colour.
export function colourKey(sheet) {
  return stem(sheet).split(/[\s_]+/).filter(Boolean)
    .map(word => word[0].toUpperCase() + word.slice(1)).join('_');
}

export function canonical(value) {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value && typeof value === 'object') {
    return '{' + Object.keys(value).sort().map(k => JSON.stringify(k) + ':' + canonical(value[k])).join(',') + '}';
  }
  return JSON.stringify(value);
}

export function isAlterationRecipe(recipe) {
  if (!recipe || typeof recipe !== 'object') return false;
  return (recipe.BenchRequirement || []).some(row => row && row.Id === ALTERATION_BENCH
    && Array.isArray(row.Categories) && row.Categories.includes(ALTERATION_BENCH));
}

// Some recipe names the Alteration Table bench without its category, which the bench never
// shows. That is how 39 Atlantean pieces of the October delivery were lost.
function namesAlterationBench(recipe) {
  return !!recipe && (recipe.BenchRequirement || []).some(row => row && row.Id === ALTERATION_BENCH);
}

export function alterationRecipe(family) {
  return {
    Input: [{ Quantity: 1, ResourceTypeId: family }],
    BenchRequirement: [{ Id: ALTERATION_BENCH, Type: 'StructuralCrafting', Categories: [ALTERATION_BENCH] }],
    KnowledgeRequired: false,
    TimeSeconds: 0,
  };
}

function alterationInput(recipe) {
  const row = recipe && (recipe.Input || [])[0];
  return row && typeof row.ResourceTypeId === 'string' ? row.ResourceTypeId : null;
}

function families(doc) {
  return (doc && Array.isArray(doc.ResourceTypes) ? doc.ResourceTypes : [])
    .map(row => row && row.Id).filter(id => typeof id === 'string');
}

// The words a picture or model name starts with when it belongs to one armour piece, by the slot
// the piece is worn on. A folder can hold one picture per piece (Chest_Texture, Hands_Texture),
// and a picture named for another piece is never a colour of this one.
const PIECE_WORDS = {
  Head: ['head', 'helm', 'helmet', 'hat', 'hood', 'mask', 'crown', 'cap'],
  Chest: ['chest', 'body', 'torso', 'cuirass', 'robe', 'shirt'],
  Hands: ['hands', 'hand', 'gauntlets', 'gloves', 'arms', 'bracers'],
  Legs: ['legs', 'boots', 'feet', 'pants', 'greaves', 'leggings'],
};

export function pieceSlot(name) {
  const word = stem(name).split(/[\s_]+/)[0].toLowerCase();
  for (const [slot, words] of Object.entries(PIECE_WORDS)) if (words.includes(word)) return slot;
  return null;
}

// Words a texture's name uses for no colour at all: Head_Texture.png names a helmet's picture, not
// a colour called Texture.
const NO_COLOUR_WORDS = new Set(['texture', 'textures', 'tex', 'base', 'default', 'main', 'diffuse', 'albedo', 'color', 'colour', 'skin', 'map']);

// The colour a sheet stands for, as a version's id spells it. A leading piece word is not part
// of the colour: Helm_Blue.png is the colour Blue of a helmet. A name with no colour word left
// (Head_Texture.png) stands for no colour, and null says so: the page never invents a name from it.
export function sheetColour(sheet) {
  const words = stem(sheet).split(/[\s_]+/).filter(Boolean);
  if (words.length > 1 && pieceSlot(words[0])) words.shift();
  if (!words.length || words.every(w => NO_COLOUR_WORDS.has(w.toLowerCase()))) return null;
  return colourKey(words.join('_'));
}

// A Patchly patch applied to a game item as Patchly applies it to the item's own file: a plain
// key replaces (an object merges, null removes), + and ++ append to a list, - and -- prepend, ?
// fills only a missing key. Patchly works on the file itself, never on what it inherits.
export function applyPatch(base, patch) {
  const out = { ...(base || {}) };
  for (const [rawKey, value] of Object.entries(patch || {})) {
    const m = /^(.*?)(\+\+|--|\+|-|\?|~)?$/.exec(rawKey);
    const key = m[1], op = m[2] || '';
    const current = out[key];
    if (op === '?') {
      if (!(key in out)) out[key] = value;
    } else if (op === '+' || op === '++' || op === '-' || op === '--') {
      const list = Array.isArray(current) ? current : [];
      const add = (Array.isArray(value) ? value : []).filter(v => op.length === 2 || !list.some(x => canonical(x) === canonical(v)));
      out[key] = op[0] === '+' ? [...list, ...add] : [...add, ...list];
    } else if (op === '') {
      if (value === null) delete out[key];
      else if (value && typeof value === 'object' && !Array.isArray(value) && current && typeof current === 'object' && !Array.isArray(current)) out[key] = applyPatch(current, value);
      else out[key] = value;
    }
    // ~ merges a list by position; no family or category patch uses it, so it is not read here.
  }
  return out;
}

// Each Armory category and the game category its gear also carries, so other mods find it.
const ARMORY_CATEGORY = {
  armour: ['ArmoryCategory.ArmoryArmors', 'Items.Armors'],
  shield: ['ArmoryCategory.ArmoryShields', 'Items.Weapons'],
  weapon: ['ArmoryCategory.ArmoryWeapons', 'Items.Weapons'],
};

function gearKind(id, eff) {
  if (eff && eff.Armor) return 'armour';
  const family = eff && eff.Tags && Array.isArray(eff.Tags.Family) ? eff.Tags.Family : [];
  if (/shield/i.test(id) || family.some(f => /shield/i.test(f))) return 'shield';
  return eff && (eff.Weapon || /weapon/i.test(id)) ? 'weapon' : null;
}

// The categories a piece of gear should carry: the ones it has, plus the game's own beside each
// Armory category. withArmory also puts gear with no Armory category into the Armory tab, which
// is what a new colour of a game piece needs (the game piece carries the game's category only).
// An item with no category at all stays as it is: the Kanohi Hau masks carry none on purpose, so
// they stay out of the creative menu.
export function wantedCategories(id, eff, withArmory = false) {
  const have = eff && Array.isArray(eff.Categories) ? eff.Categories.filter(c => typeof c === 'string') : null;
  if (!have || have.length === 0) return have;
  const out = [...have];
  const kind = gearKind(id, eff);
  if (withArmory && kind && !Object.values(ARMORY_CATEGORY).some(pair => out.includes(pair[0]))) out.unshift(ARMORY_CATEGORY[kind][0]);
  for (const pair of Object.values(ARMORY_CATEGORY)) if (out.includes(pair[0]) && !out.includes(pair[1])) out.push(pair[1]);
  return out;
}

export function toJson(value) {
  return JSON.stringify(value, null, 2) + '\n';
}

// ---- reading the mod ----------------------------------------------------------------------------

// Runs fn over every value, a batch at a time, and returns the results in order.
export async function inBatches(values, fn, size = 64) {
  const out = [];
  for (let i = 0; i < values.length; i += size) out.push(...await Promise.all(values.slice(i, i + size).map(fn)));
  return out;
}

// io: { list(prefix, exts) -> paths, read(path) -> text or null, listVanilla(prefix, exts) -> paths }
// Server paths are relative to the Server folder. Common paths are given as "../Common/...".
export function makeReader(io) {
  const docs = new Map();
  let modItems = null;
  let vanillaItems = null;

  async function itemIndex() {
    if (!modItems) {
      modItems = new Map();
      for (const rel of await io.list('Item/Items/', ['json'])) modItems.set(stem(rel), rel);
    }
    return modItems;
  }

  async function relOf(id) {
    const mod = (await itemIndex()).get(id);
    if (mod) return mod;
    if (!vanillaItems) {
      vanillaItems = new Map();
      for (const rel of await io.listVanilla('Item/Items/', ['json'])) vanillaItems.set(stem(rel), rel);
    }
    return vanillaItems.get(id) || null;
  }

  async function docAt(rel) {
    if (!docs.has(rel)) {
      const text = await io.read(rel);
      let doc = null;
      try { doc = text == null ? null : JSON.parse(text); } catch { doc = null; }
      docs.set(rel, doc);
    }
    return docs.get(rel);
  }

  // The mod's patches on game items, by the game file they change.
  let patchRels = null;
  async function patchOf(rel) {
    if (!patchRels) patchRels = new Set(await io.list('Item/Items/', ['patch']));
    const patchRel = rel.replace(/\.json$/, '.patch');
    if (!patchRels.has(patchRel)) return null;
    const text = await io.read(patchRel);
    try { return { rel: patchRel, doc: JSON.parse(text) }; } catch { return { rel: patchRel, doc: null }; }
  }

  // An item's own file as the game loads it: a game item with the mod's patch on it applied.
  async function ownDoc(id) {
    const rel = await relOf(id);
    const doc = rel ? await docAt(rel) : null;
    if (!doc || (await itemIndex()).has(id)) return doc;
    const patch = await patchOf(rel);
    return patch && patch.doc ? applyPatch(doc, patch.doc) : doc;
  }

  async function effective(id, seen = []) {
    if (seen.includes(id)) throw new Error('Parent loop: ' + [...seen, id].join(' > '));
    const doc = await ownDoc(id);
    if (!doc) return null;
    if (typeof doc.Parent !== 'string') return doc;
    const parent = await effective(doc.Parent, [...seen, id]);
    const out = {};
    if (parent) for (const key of Object.keys(parent)) if (!NOT_INHERITED.has(key)) out[key] = parent[key];
    for (const key of Object.keys(doc)) out[key] = doc[key];
    return out;
  }

  // Every game item's own file, read once and only when a set needs it: finding the game piece a
  // set of colours starts from, and the game items that would inherit its family.
  // The reads run side by side: inside HyCreator each is a call to the app, and one after the
  // other the game's 3,964 items took 30 seconds.
  let gameDocs = null;
  async function gameItems() {
    if (!gameDocs) {
      const mod = await itemIndex();
      await relOf('');
      const ids = [...vanillaItems.keys()].filter(id => !mod.has(id));
      const docsRead = await inBatches(ids, id => ownDoc(id));
      gameDocs = new Map(ids.map((id, i) => [id, { rel: vanillaItems.get(id), doc: docsRead[i] }]));
    }
    return gameDocs;
  }

  return { itemIndex, relOf, docAt, ownDoc, effective, patchOf, gameItems, list: io.list, listVanilla: io.listVanilla, read: io.read };
}

// ---- the analysis -------------------------------------------------------------------------------

// Two ways the mod spells a version's id. A: Atlantean_Chest_Red_Alt. B: Atlantean_Chest_Alt_Red.
function idFor(set, sheet, shape, order) {
  if (sheet === set.defaultSheet && shape === set.baseShape) return set.defaultId;
  if (sheet !== set.defaultSheet && sheetColour(sheet) === null) return null;
  const colour = sheet === set.defaultSheet ? '' : '_' + sheetColour(sheet);
  const suffix = shape === set.baseShape ? '' : stem(shape).slice(stem(set.baseShape).length);
  return set.base + (order === 'A' ? colour + suffix : suffix + colour);
}

function isVersion(own) {
  return isAlterationRecipe(own && own.Recipe) || namesAlterationBench(own && own.Recipe);
}

// The families an item declares itself. A piece of the mod also takes them from its parent, as
// the game gives them (a colour version of a game piece names the game piece as its Parent and
// is in its family through it). A game item takes nothing from a game parent here: the mod's Iron
// patch puts the game's Iron armour into the Iron colour families, and every game piece built on
// the Iron armour inherits that, which is a fault of the patch and never a family of the piece.
async function declaredFamilies(reader, id, seen = [], asVersion = false) {
  if (seen.includes(id)) return [];
  const own = await reader.ownDoc(id);
  if (!own) return [];
  if (Array.isArray(own.ResourceTypes)) return families(own);
  const index = await reader.itemIndex();
  if (typeof own.Parent !== 'string' || !index.has(id)) return [];
  // A piece of the mod built on a game item takes the game item's family only as one of its
  // colours does, by naming it as Parent; as a default of its own it has no family yet.
  if (!index.has(own.Parent) && !asVersion) return [];
  return declaredFamilies(reader, own.Parent, [...seen, id], asVersion);
}

// The game piece a set of colours starts from, when no piece of the mod is its default: the one
// game item the mod's patches put in the family, or else the game item wearing the same model
// that the others wearing it inherit from (the giant skeleton's sword, not its two copies held by
// the skeletons themselves).
async function findGameDefault(reader, family, version) {
  const games = await reader.gameItems();
  if (family) {
    const inFamily = [...games.entries()].filter(([, g]) => g.doc && families(g.doc).includes(family));
    if (inFamily.length === 1) return inFamily[0][0];
  }
  const wearing = new Set();
  for (const id of games.keys()) {
    const eff = await reader.effective(id);
    if (eff && eff.Model === version.Model) wearing.add(id);
  }
  const roots = [...wearing].filter(id => {
    const parent = games.get(id).doc && games.get(id).doc.Parent;
    return !(typeof parent === 'string' && wearing.has(parent));
  });
  roots.sort((a, b) => (Number(/npc/i.test(a)) - Number(/npc/i.test(b))) || a.localeCompare(b));
  return roots[0] || null;
}

// Where the mod keeps the colour sheets of a game model by convention: armour under
// Items/Armors/VanillaRecolors/<set>/ and <set>/<piece>/ (the Iron recolours), weapons in a
// folder named for the material and the weapon with Recolors (CobaltLongSwordRecolors).
async function conventionFolders(reader, baseShape) {
  const out = [];
  const modelFolder = folder(baseShape);
  const armour = /^Items\/Armors\/(.+)$/.exec(modelFolder);
  if (armour) {
    out.push('Items/Armors/VanillaRecolors/' + armour[1]);
    out.push('Items/Armors/VanillaRecolors/' + armour[1] + stem(baseShape) + '/');
  }
  if (modelFolder.startsWith('Items/Weapons/')) {
    const kind = modelFolder.slice('Items/Weapons/'.length).replace(/\/$/, '').split('/').pop().toLowerCase();
    const material = stem(baseShape).toLowerCase();
    const pictures = (await reader.list('../Common/Items/Weapons/', ['png'])).map(p => p.replace(/^\.\.\/Common\//, ''));
    for (const dir of new Set(pictures.map(folder))) {
      const name = dir.replace(/\/$/, '').split('/').pop().toLowerCase();
      if (name.includes('recolor') && name.includes(material) && name.replace(/[^a-z]/g, '').includes(kind.replace(/[^a-z]/g, ''))) out.push(dir);
    }
  }
  return out;
}

export async function analyse(reader, openRel, options = {}) {
  const index = await reader.itemIndex();
  const openId = stem(openRel);
  const open = await reader.effective(openId);
  if (!open) return { error: 'This file could not be read as an item.' };
  if (typeof open.Model !== 'string' || typeof open.Texture !== 'string') {
    return { error: 'This item has no model and texture, so it has no colors.' };
  }

  // A family of the game's own is never a colour family: a piece inheriting from a game item
  // carries the game's families too (the signs inherit Wood_Trunk), and taking one would put the
  // colours into it, so they would count as that material in every recipe, and write an empty
  // file over the game's own.
  const familyFiles = new Set((await reader.list('Item/ResourceTypes/', ['json'])).map(stem));
  const gameFamilies = new Set((await reader.listVanilla('Item/ResourceTypes/', ['json'])).map(stem));
  const colourFamily = f => familyFiles.has(f) || !gameFamilies.has(f);

  const readDocs = async rels => {
    const read = await inBatches(rels, async rel => ({ rel, own: await reader.docAt(rel), eff: await reader.effective(stem(rel)) }));
    return new Map(read.map(d => [stem(d.rel), d]));
  };
  let allDocs = null;
  const everyDoc = async () => allDocs || (allDocs = await readDocs([...index.values()]));

  // The folder of items this set lives in, read once.
  const itemFolder = folder(openRel);
  const folderDocs = await readDocs([...index.values()].filter(rel => rel.startsWith(itemFolder)));

  // The default piece: the open item, unless it is itself a colour version. Its default is looked
  // for in its folder, then anywhere in the mod, then among the game's items.
  let defaultId = openId;
  const openOwn = await reader.docAt(openRel);
  if (index.has(openId) && isVersion(openOwn)) {
    const family = alterationInput(openOwn.Recipe);
    // A family counts only when the piece declares it. A piece of the mod built on a game item
    // carries that item's family through the Iron patch without being in the set (the Praetorian
    // Elite chest is built on the game's Iron chest and is no default of the Iron chest colours).
    const defaultsIn = async docs => {
      const out = [];
      for (const [id, d] of docs.entries()) {
        if (!d.eff || !family || isVersion(d.own) || !families(d.eff).includes(family)) continue;
        if ((await declaredFamilies(reader, id)).includes(family)) out.push([id, d]);
      }
      return out;
    };
    // Beside it in its folder first. Then the game piece the mod's patch puts in the family, which
    // a patch does on purpose, before another piece elsewhere in the mod that also declares it
    // (an old Developer sword declares the Iron sword family beside the game's Iron sword).
    let candidates = await defaultsIn(folderDocs);
    if (candidates.length === 0 && family) {
      const patched = [...(await reader.gameItems()).entries()].filter(([, g]) => g.doc && families(g.doc).includes(family));
      if (patched.length === 1) candidates = [[patched[0][0]]];
    }
    if (candidates.length === 0) candidates = await defaultsIn(await everyDoc());
    if (candidates.length === 0) {
      const game = await findGameDefault(reader, family, open);
      if (game) candidates = [[game]];
    }
    if (candidates.length !== 1) {
      return { error: candidates.length === 0
        ? 'This is a color, and its base item was not found. Open the base item instead.'
        : 'This is a color, and several items could be its base: ' + candidates.map(([id]) => id).join(', ') + '. Open the base item instead.' };
    }
    defaultId = candidates[0][0];
  }
  const gameDefault = !index.has(defaultId);
  const def = { id: defaultId, rel: await reader.relOf(defaultId), own: await reader.ownDoc(defaultId), eff: await reader.effective(defaultId) };
  if (!def.eff || typeof def.eff.Model !== 'string' || typeof def.eff.Texture !== 'string') {
    return { error: 'Its base item ' + defaultId + ' has no model and texture, so it has no colors.' };
  }

  // The items its versions may be among: the default's folder for a piece of the mod (as before),
  // every item of the mod for a game piece, whose colours live wherever they were made.
  let pool;
  if (gameDefault) pool = await everyDoc();
  else if (folder(def.rel) === itemFolder) pool = folderDocs;
  else pool = new Map([...folderDocs, ...await readDocs([...index.values()].filter(rel => rel.startsWith(folder(def.rel))))]);

  // The shapes: the default's model and its alternates beside it in the mod.
  const baseShape = def.eff.Model;
  const modelFolder = folder(baseShape);
  const common = p => p.replace(/^\.\.\/Common\//, '');
  const inside = dir => path => folder(path) === dir;
  const modelsIn = async dir => (await reader.list('../Common/' + dir, ['blockymodel'])).map(common).filter(inside(dir));
  const picturesIn = async dir => (await reader.list('../Common/' + dir, ['png'])).map(common).filter(inside(dir));
  const shapes = (await modelsIn(modelFolder)).filter(path => stem(path) === stem(baseShape) || stem(path).startsWith(stem(baseShape) + '_'));
  const orderedShapes = [baseShape, ...shapes.filter(s => s !== baseShape).sort()];
  const consumes = m => alterationInput(m.own && m.own.Recipe);
  const shapeMates = [...pool.entries()].filter(([id, d]) => id !== defaultId && d.eff && orderedShapes.includes(d.eff.Model)).map(([id, d]) => ({ id, ...d }));

  // The piece family: one of the default's own families, the one colour versions are made from,
  // and among equals the most specific. A default with no family takes it from the pieces wearing
  // its shapes, by how many belong to it and how many are made from it, belonging first (nine
  // helmets in the helmet family made from the gauntlet family by mistake are helmets).
  const defaultFamilies = (await declaredFamilies(reader, defaultId)).filter(colourFamily);
  const madeFrom = f => shapeMates.filter(m => consumes(m) === f).length;
  const owners = f => [...pool.values()].filter(d => d.eff && families(d.eff).includes(f)).length;
  let pieceFamily = null;
  if (defaultFamilies.length) {
    pieceFamily = [...defaultFamilies].sort((a, b) => (madeFrom(b) - madeFrom(a)) || (owners(a) - owners(b)))[0];
  } else {
    // A family another piece declares as its own is that piece's set: the game's Iron sword is in
    // the Iron sword family through the mod's patch, and a mod sword built on it wearing the same
    // model is not the default of those colours.
    const owned = new Set();
    for (const [id, d] of pool) if (id !== defaultId && d.own && !isVersion(d.own) && Array.isArray(d.own.ResourceTypes)) for (const f of families(d.own)) owned.add(f);
    for (const rel of await reader.list('Item/Items/', ['patch'])) {
      try {
        const patch = JSON.parse(await reader.read(rel));
        for (const k of ['ResourceTypes', 'ResourceTypes+', 'ResourceTypes++']) for (const f of families({ ResourceTypes: patch[k] })) owned.add(f);
      } catch { /* a patch that does not parse declares nothing */ }
    }
    const score = new Map();
    const bump = (f, i) => { if (!f || !colourFamily(f) || owned.has(f)) return; const s = score.get(f) || [0, 0]; s[i]++; score.set(f, s); };
    for (const m of shapeMates) { for (const f of families(m.eff)) bump(f, 0); bump(consumes(m), 1); }
    pieceFamily = [...score.entries()].sort((a, b) => (b[1][0] + b[1][1] - a[1][0] - a[1][1]) || (b[1][0] - a[1][0]))[0]?.[0] || null;
  }
  const familyIsNew = !pieceFamily;
  const family = pieceFamily || 'Resource_' + (defaultId.endsWith('_Default') ? defaultId.slice(0, -8) : defaultId);


  // The colour sheets: the pictures beside the model in the mod, the folders the family's pieces
  // already take their pictures from, the folder the mod uses by convention for a game piece, and
  // any folder typed on the page. A picture named for another piece (Hands_Texture beside a chest)
  // or for another model of its folder is that piece's, never a colour of this one.
  const sheetFolders = [modelFolder];
  for (const m of shapeMates) {
    if (typeof m.eff.Texture !== 'string' || folder(m.eff.Texture) === modelFolder) continue;
    if (consumes(m) === family || families(m.eff).includes(family)) sheetFolders.push(folder(m.eff.Texture));
  }
  if (gameDefault) sheetFolders.push(...await conventionFolders(reader, baseShape));
  if (typeof options.sheetFolder === 'string' && options.sheetFolder.trim()) {
    sheetFolders.push(options.sheetFolder.trim().replace(/\\/g, '/').replace(/^\/?(Common\/)?/, '').replace(/\/?$/, '/'));
  }
  const folders = [...new Set(sheetFolders)];
  const slot = statsOf(def.eff.Armor).slot;
  const otherModels = new Set();
  for (const dir of folders) for (const m of await modelsIn(dir)) if (!orderedShapes.includes(m)) otherModels.add(stem(m).toLowerCase());
  // A picture a piece of this shape already wears is this set's whatever its name says: the
  // Wizened sleeves wear the robe's chest_*.png sheets. The name only judges pictures nothing
  // of this shape wears yet.
  const worn = new Set(shapeMates.map(m => m.eff.Texture));
  const ownSheet = sheet => {
    if (worn.has(sheet)) return true;
    const piece = pieceSlot(sheet);
    if (slot && piece && piece !== slot) return false;
    const name = stem(sheet).toLowerCase();
    return ![...otherModels].some(m => name === m || name.startsWith(m + '_'));
  };
  const defaultSheet = def.eff.Texture;
  const found = new Set();
  for (const dir of folders) for (const p of await picturesIn(dir)) if (p !== defaultSheet && ownSheet(p)) found.add(p);
  const orderedSheets = [defaultSheet, ...[...found].sort()];

  // Items wearing one of these sheets on one of these shapes. A model folder can hold several
  // families (Rook and Rook Elite share their sheets), so wearing the same art is only a
  // candidate: belonging to the set is decided by family.
  const candidates = shapeMates.filter(m => orderedSheets.includes(m.eff.Texture));
  const members = pieceFamily ? candidates.filter(m => consumes(m) === pieceFamily || families(m.eff).includes(pieceFamily)) : [];

  // Other pieces outside the set that declare its family as their own: the set is theirs as much
  // as this one's, so the page names them and ticks nothing for her. A colour of the set made
  // craftable is still one of its colours, never a rival.
  const coOwners = [];
  if (pieceFamily) {
    const inSet = new Set([defaultId, ...candidates.map(m => m.id)]);
    for (const [id, d] of await everyDoc()) if (!inSet.has(id) && d.own && !isVersion(d.own) && families(d.own).includes(pieceFamily)) coOwners.push(id);
    for (const [id, g] of await reader.gameItems()) if (!inSet.has(id) && g.doc && families(g.doc).includes(pieceFamily)) coOwners.push(id);
  }

  // A game piece's own name and quality are the game's: Lady's Ruined swords are Legendary and
  // named Ruined_Sword_Gold, the giant skeleton's sword they start from is Common. So a set on a
  // game piece takes both from the colours it has, where they agree.
  const majority = values => {
    const count = new Map();
    for (const v of values) if (v !== undefined && v !== null) count.set(v, (count.get(v) || 0) + 1);
    const best = [...count.entries()].sort((a, b) => b[1] - a[1])[0];
    return best && best[1] * 2 > values.length ? best[0] : undefined;
  };
  let base = defaultId.endsWith('_Default') ? defaultId.slice(0, -'_Default'.length) : defaultId;
  let setQuality = def.eff.Quality;
  if (gameDefault && members.length) {
    const prefixes = members.map(m => {
      const colour = sheetColour(m.eff.Texture);
      return colour && m.id.endsWith('_' + colour) ? m.id.slice(0, -(colour.length + 1)) : null;
    });
    base = majority(prefixes) || base;
    setQuality = majority(members.map(m => m.eff.Quality)) ?? setQuality;
  }

  // Where new versions go: beside the versions it has, else beside the default.
  const placed = new Map();
  for (const m of members) placed.set(folder(m.rel), (placed.get(folder(m.rel)) || 0) + 1);
  const versionFolder = [...placed.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] || folder(def.rel);

  // Game items that inherit from a game default without a family of their own join its family in
  // game the moment the default does.
  let inheritors = [];
  if (gameDefault) {
    inheritors = [...(await reader.gameItems()).entries()]
      .filter(([, g]) => g.doc && g.doc.Parent === defaultId && !Array.isArray(g.doc.ResourceTypes)).map(([id]) => id).sort();
  }

  const set = {
    defaultId, defaultRel: def.rel, base,
    baseShape, defaultSheet, sheets: orderedSheets, shapes: orderedShapes, sheetFolders: folders,
    pieceFamily: family, familyIsNew, gameDefault, versionFolder, inheritors, coOwners: coOwners.sort(),
    defaultFamilies: families(def.eff),
    defaultQuality: setQuality, defaultLight: def.eff.Light || null,
    versionCategories: gameDefault ? wantedCategories(defaultId, def.eff, true) : null,
    defaultCategories: def.eff.Categories || null,
    patch: gameDefault ? (await reader.patchOf(def.rel)) || { rel: def.rel.replace(/\.json$/, '.patch'), doc: null } : null,
    familyFileMissing: !familyFiles.has(family),
  };

  // How this set spells its ids, read from the versions it already has.
  let a = 0, b = 0;
  for (const m of members) {
    if (m.id === idFor(set, m.eff.Texture, m.eff.Model, 'A')) a++;
    if (m.id === idFor(set, m.eff.Texture, m.eff.Model, 'B')) b++;
  }
  set.order = b > a ? 'B' : 'A';

  const icons = new Set((await reader.list('../Common/' + ICON_FOLDER, ['png'])).map(common));
  const expected = new Map();
  for (const sheet of orderedSheets) for (const shape of orderedShapes) {
    const id = idFor(set, sheet, shape, set.order);
    if (id) expected.set(id, { sheet, shape });
  }

  // The grid, and every fault in it.
  const cells = new Map();
  const key = (sheet, shape) => sheet + '|' + shape;
  for (const sheet of orderedSheets) for (const shape of orderedShapes) {
    cells.set(key(sheet, shape), { sheet, shape, id: idFor(set, sheet, shape, set.order), items: [] });
  }
  // Items that look exactly alike may share one icon: it is the same picture.
  const everyone = [{ id: defaultId, ...def }, ...members];
  const twinsOf = m => new Set(everyone.filter(o => o.id !== m.id && o.eff.Model === m.eff.Model && o.eff.Texture === m.eff.Texture).map(o => o.id));
  cells.get(key(defaultSheet, baseShape)).items.push({ id: defaultId, rel: def.rel, isDefault: true, isGame: gameDefault, slot: statsOf(def.eff.Armor).slot,
    faults: defaultFaults(set, { ...def, declared: defaultFamilies }, icons, twinsOf({ id: defaultId, ...def })) });

  for (const m of members) {
    const cell = cells.get(key(m.eff.Texture, m.eff.Model));
    cell.items.push({ id: m.id, rel: m.rel, slot: statsOf(m.eff.Armor).slot, faults: memberFaults(set, def, m, icons, expected, twinsOf(m)) });
  }
  for (const cell of cells.values()) {
    // Two items with the same look worn on different slots (an amulet at the neck or on the head)
    // are two versions, not a clash. Two on the same slot are worth a look.
    for (const item of cell.items) {
      const alike = cell.items.filter(i => i !== item && i.slot === item.slot);
      if (alike.length) item.faults.push({ kind: 'shared', text: 'Looks exactly like ' + alike.map(i => i.id).join(', ') + ', on the same slot.', fix: null });
    }
    cell.missing = cell.items.length === 0;
    // A texture whose name says no colour: an item already wearing it keeps its own name, and none
    // is made from it, since its name would be invented.
    cell.unnamed = cell.id === null;
    cell.clash = cell.missing && cell.id && index.has(cell.id) ? index.get(cell.id) : null;
  }

  // A light per colour: the one its versions already use, otherwise the default's.
  const lights = {};
  for (const sheet of orderedSheets) {
    const wearer = members.find(m => m.eff.Texture === sheet && m.eff.Light);
    lights[sheet] = sheet === defaultSheet ? set.defaultLight : (wearer ? wearer.eff.Light : set.defaultLight);
  }
  return { set, cells: [...cells.values()], lights, memberCount: members.length };
}

function iconFaults(id, doc, icons, twins) {
  const own = ICON_FOLDER + id + '.png';
  const faults = [];
  const twinIcon = typeof doc.Icon === 'string' && doc.Icon.startsWith(ICON_FOLDER) && twins.has(stem(doc.Icon)) && icons.has(doc.Icon);
  if (twinIcon) return faults;
  if (doc.Icon !== own) {
    faults.push({ kind: 'iconField', text: doc.Icon ? 'Shows the icon ' + fileName(doc.Icon) + ' instead of its own.' : 'Has no icon.', fix: { ops: [[['Icon'], own]], icon: true }, byDefault: true });
  } else if (!icons.has(own)) {
    faults.push({ kind: 'iconMissing', text: 'Its icon is not made yet.', fix: { ops: [], icon: true }, byDefault: true });
  }
  return faults;
}

// Gear missing the game's own category beside its Armory one cannot be found by other mods.
function categoryFault(id, eff, withArmory) {
  const want = wantedCategories(id, eff, withArmory);
  if (!want || canonical(want) === canonical(eff.Categories || null)) return [];
  const missing = want.filter(c => !(eff.Categories || []).includes(c));
  return [{ kind: 'category', text: 'Missing the category ' + missing.join(' and ') + ', so ' + (missing.some(c => c.startsWith('ArmoryCategory.')) ? 'the Armory tab does not show it' : 'other mods cannot find it') + '.', fix: { ops: [[['Categories'], want]] }, byDefault: true }];
}

function defaultFaults(set, def, icons, twins) {
  if (set.gameDefault) {
    // The game's own piece: nothing in its file is ours to change but its family, which the
    // mod's patch on it gives.
    const faults = [];
    if (!def.declared.includes(set.pieceFamily)) {
      faults.push({ kind: 'notInFamily', text: 'Does not have the resource type ' + set.pieceFamily + ' yet, so the Alteration Table cannot turn it into a color.', fix: { ops: [], patch: true }, byDefault: true });
    }
    faults.push(...coOwnerNote(set));
    if (set.inheritors.length) {
      faults.push({ kind: 'inheritors', text: set.inheritors.length + ' vanilla items are built on it, so the Alteration Table would turn them into its colors too: ' + set.inheritors.slice(0, 6).join(', ') + (set.inheritors.length > 6 ? '...' : '') + '. Send Larsonix a screenshot before making colors of it.', fix: null });
    }
    return faults;
  }
  const faults = iconFaults(set.defaultId, def.own, icons, twins);
  if (!def.declared.includes(set.pieceFamily)) {
    faults.push({ kind: 'notInFamily', text: 'Does not have the resource type ' + set.pieceFamily + ', so the Alteration Table cannot turn it into a color.', fix: { ops: [[['ResourceTypes'], [...(def.own.ResourceTypes || []), { Id: set.pieceFamily }]]] }, byDefault: true });
  }
  faults.push(...categoryFault(set.defaultId, def.eff, false));
  faults.push(...coOwnerNote(set));
  return faults;
}

function coOwnerNote(set) {
  if (!set.coOwners.length) return [];
  const many = set.coOwners.length > 1;
  return [{ kind: 'coOwners', text: set.coOwners.slice(0, 4).join(', ') + (set.coOwners.length > 4 ? '...' : '') + (many ? ' also have' : ' also has') + ' the resource type ' + set.pieceFamily + ', so these colors are ' + (many ? 'theirs' : 'its') + ' too. Nothing is ticked: send Larsonix a screenshot to know which one is the base.', fix: null }];
}

function memberFaults(set, def, m, icons, expected, twins) {
  const own = m.own || {};
  const faults = [];
  const recipe = own.Recipe;
  if (!isAlterationRecipe(recipe)) {
    // Crafted means a real bench is named, so the version is a way into the set and replacing its
    // recipe would take that away. Such a fault is shown but left for her to tick.
    const benches = recipe ? (recipe.BenchRequirement || []).map(r => r && r.Id).filter(b => typeof b === 'string' && b !== ALTERATION_BENCH) : [];
    const crafted = benches.length > 0;
    faults.push({
      kind: 'noTable',
      text: crafted
        ? 'Crafted at ' + benches.join(', ') + ', not at the Alteration Table. Tick this only if it should be a color of this set.'
        : 'Not at the Alteration Table: its Recipe does not name it.',
      fix: { ops: [[['Recipe'], alterationRecipe(set.pieceFamily)]] },
      byDefault: !crafted,
    });
  } else if (alterationInput(recipe) !== set.pieceFamily) {
    faults.push({ kind: 'wrongFamily', text: 'Made at the Alteration Table from ' + alterationInput(recipe) + ' instead of ' + set.pieceFamily + '.', fix: { ops: [[['Recipe'], alterationRecipe(set.pieceFamily)]] }, byDefault: true });
  }
  if (!families(m.eff).includes(set.pieceFamily)) {
    const into = set.gameDefault ? [{ Id: set.pieceFamily }] : (def.eff.ResourceTypes || [{ Id: set.pieceFamily }]);
    faults.push({ kind: 'notInFamily', text: 'Does not have the resource type ' + set.pieceFamily + ', so the Alteration Table cannot turn it into another color.', fix: { ops: [[['ResourceTypes'], into]] }, byDefault: true });
  }
  if (set.defaultQuality !== undefined && own.Quality !== set.defaultQuality && m.eff.Quality !== set.defaultQuality) {
    faults.push({ kind: 'quality', text: 'Has the quality ' + (m.eff.Quality || 'none') + ', but its base has ' + set.defaultQuality + '.', fix: { ops: [[['Quality'], set.defaultQuality]] }, byDefault: true });
  }
  faults.push(...iconFaults(m.id, own, icons, twins));
  faults.push(...categoryFault(m.id, m.eff, set.gameDefault));
  const named = expected.get(m.id);
  if (named && named.sheet !== m.eff.Texture) {
    faults.push({ kind: 'sheetMismatch', text: 'Its name says ' + stem(named.sheet) + ', but it wears the texture ' + stem(m.eff.Texture) + '.', fix: { ops: [[['Texture'], named.sheet]] }, byDefault: false });
  }
  // The same rule as the mod's family check: the slot is judged on its own, and the cosmetics an
  // armour piece hides may differ on purpose (an open helmet shows the hair).
  const mine = statsOf(m.eff.Armor), theirs = statsOf(def.eff.Armor);
  if (canonical(mine.stats) !== canonical(theirs.stats)) {
    const inheritsFromDefault = own.Parent === set.defaultId;
    faults.push({ kind: 'stats', text: 'Its armor stats differ from its base, so changing its color at the Alteration Table changes its stats.', fix: { ops: [[['Armor'], inheritsFromDefault ? undefined : def.eff.Armor]] }, byDefault: false });
  }
  if (mine.slot !== theirs.slot) {
    faults.push({ kind: 'slot', text: 'Worn on ' + (mine.slot || 'no slot') + ', but its base is worn on ' + (theirs.slot || 'no slot') + '.', fix: null });
  }
  return faults;
}

function statsOf(armor) {
  if (!armor || typeof armor !== 'object') return { stats: null, slot: null };
  const stats = {};
  for (const key of Object.keys(armor)) if (key !== 'ArmorSlot' && key !== 'CosmeticsToHide') stats[key] = armor[key];
  return { stats, slot: armor.ArmorSlot || null };
}

// ---- the plan ---------------------------------------------------------------------------------

// The mod's patch on a game piece with its family added. A plain ResourceTypes already in the
// patch takes the family; otherwise Patchly's + adds it to whatever the game file holds.
function familyPatchText(set) {
  const doc = { ...(set.patch.doc || {}) };
  const entry = { Id: set.pieceFamily };
  if (Array.isArray(doc.ResourceTypes)) doc.ResourceTypes = [...doc.ResourceTypes, entry];
  else doc['ResourceTypes+'] = [...(Array.isArray(doc['ResourceTypes+']) ? doc['ResourceTypes+'] : []), entry];
  return toJson(doc);
}

// What one press of the button writes, as plain data: new files, edits to existing files, icons.
export function plan(analysis, choice) {
  const { set } = analysis;
  const create = [];
  const edit = new Map();
  const icons = [];
  const addEdit = (rel, ops) => { if (!edit.has(rel)) edit.set(rel, []); edit.get(rel).push(...ops); };

  if (set.familyFileMissing) create.push({ rel: 'Item/ResourceTypes/' + set.pieceFamily + '.json', text: '{}\n' });

  for (const cell of analysis.cells) {
    if (cell.missing) {
      if (cell.unnamed || !choice.make.has(cell.id) || cell.clash) continue;
      const doc = { Parent: set.defaultId };
      if (set.defaultQuality !== undefined) doc.Quality = set.defaultQuality;
      doc.Icon = ICON_FOLDER + cell.id + '.png';
      if (cell.shape !== set.baseShape) doc.Model = cell.shape;
      if (cell.sheet !== set.defaultSheet) doc.Texture = cell.sheet;
      const light = choice.lights[cell.sheet];
      if (light && canonical(light) !== canonical(set.defaultLight)) doc.Light = light;
      if (set.versionCategories && canonical(set.versionCategories) !== canonical(set.defaultCategories)) doc.Categories = set.versionCategories;
      doc.Recipe = alterationRecipe(set.pieceFamily);
      create.push({ rel: set.versionFolder + cell.id + '.json', text: toJson(doc) });
      icons.push(cell.id);
      continue;
    }
    for (const item of cell.items) {
      for (const fault of item.faults) {
        if (!fault.fix || !choice.fix.has(item.id + '|' + fault.kind)) continue;
        if (fault.fix.patch) create.push({ rel: set.patch.rel, text: familyPatchText(set) });
        if (fault.fix.ops.length) addEdit(item.rel, fault.fix.ops);
        if (fault.fix.icon && !icons.includes(item.id)) icons.push(item.id);
      }
    }
  }
  return { create, edit: [...edit.entries()].map(([rel, ops]) => ({ rel, ops })), icons };
}

// The choices the page starts with: every fault with a clear fix is ticked, the two that may be
// deliberate are not, and missing versions are ticked only for a set that has none yet, because
// an existing set may leave a colour out on purpose. A game piece other game items inherit from
// starts with nothing ticked, since making colours of it changes those items too, and so does a
// set whose family another piece also declares.
export function defaultChoice(analysis) {
  const make = new Set();
  const fix = new Set();
  if ((analysis.set.inheritors && analysis.set.inheritors.length) || (analysis.set.coOwners && analysis.set.coOwners.length)) return { make, fix, lights: { ...analysis.lights } };
  for (const cell of analysis.cells) {
    if (cell.missing && analysis.memberCount === 0 && !cell.clash && !cell.unnamed) make.add(cell.id);
    for (const item of cell.items) for (const f of item.faults) if (f.fix && f.byDefault) fix.add(item.id + '|' + f.kind);
  }
  return { make, fix, lights: { ...analysis.lights } };
}

// ---- inside HyCreator --------------------------------------------------------------------------

let host = null;

// The oldest HyCreator the plugin works in. Before 1.3.9, a Gradle mod like this one, whose files sit
// in src/main/resources, met two faults: the vanilla listing a plugin gets came back empty when the
// game's Assets.zip is the vanilla source, so the Colors page saw no vanilla item and misread every
// set built on one, and the file picker's tab for the mod showed 0 files. Measured in 1.3.8 and 1.3.9
// side by side on the same copy of the mod: 0 against 3,964 vanilla items, 0 against 2,486 textures.
export const NEEDS_HYCREATOR = '1.3.9';

// Whether the running HyCreator is older than that. Every HyCreator this plugin has met reports its
// version, so one that cannot be read is a newer way of writing it, never an old app, and is let through.
export function hyCreatorTooOld(version) {
  const parts = v => String(v).split(/[.-]/).slice(0, 3).map(n => Number.parseInt(n, 10));
  const have = parts(version);
  const need = parts(NEEDS_HYCREATOR);
  if (!version || have.length < 3 || have.some(Number.isNaN)) return false;
  for (let i = 0; i < 3; i++) if (have[i] !== need[i]) return have[i] < need[i];
  return false;
}

const UPDATE_STEPS = 'Click ⚙ at the top, then Application, then Check for updates...';

// The game's own files, kept between openings of the page: they do not change while HyCreator
// runs, and reading all of them is what makes the first opening of a game piece's set slow.
const gameTexts = new Map();

function hyCreatorReader(api) {
  let gameItems = null;
  let modFiles = null;
  return makeReader({
    list: (prefix, exts) => api.workspace.listByExt(prefix, exts),
    listVanilla: (prefix, exts) => api.workspace.listVanillaByExt(prefix, exts),
    read: async rel => {
      // Only a file on the game's own list that the mod does not hold is kept: a colour the page
      // writes during the session is never mistaken for a game file.
      if (!modFiles) modFiles = new Set(await api.workspace.listByExt('Item/', ['json', 'patch']));
      if (!gameItems) gameItems = new Set(await api.workspace.listVanillaByExt('Item/', ['json']));
      const game = rel.startsWith('Item/') && !modFiles.has(rel) && gameItems.has(rel);
      const key = (api.workspace.current() || {}).root + '|' + rel;
      if (game && gameTexts.has(key)) return gameTexts.get(key);
      let text = null;
      try { text = await api.workspace.readText(rel); } catch { text = null; }
      if (game && text != null) gameTexts.set(key, text);
      return text;
    },
  });
}

// Writes one edit to an existing file through HyCreator's own serializer, so every line the edit
// does not touch stays byte for byte as it was.
async function applyEdit(api, rel, ops) {
  const { serializer } = await api.modules.core();
  const text = await api.workspace.readText(rel);
  const { doc, layout } = serializer.parseAsset(text);
  for (const [path, value] of ops) {
    serializer.layoutForWrite(layout, doc, path, value);
    let target = doc;
    for (const step of path.slice(0, -1)) target = target[step];
    const last = path[path.length - 1];
    if (value === undefined) delete target[last]; else target[last] = value;
  }
  await api.workspace.writeText(rel, serializer.serializeAsset(doc, layout));
}

// Bakes an item's icon with HyCreator's own icon pipeline and writes the picture beside the
// mod's other icons. Writing a picture is not part of the plugin contract, so this goes through
// the app's own file writer, and a failure is reported rather than thrown.
async function bakeIcon(api, reader, id) {
  const [models, render, core, store] = await Promise.all([api.modules.models(), api.modules.render(), api.modules.core(), api.modules.store()]);
  const rel = await reader.relOf(id);
  const doc = await reader.effective(id);
  // The app's state hook is named useStudio in 1.3.8 and 1.3.9. The other names are guesses for a rename.
  const hook = [store.useStudio, store, store.u, store.useStore, store.default].find(s => s && typeof s.getState === 'function');
  if (!hook) throw new Error('this HyCreator version keeps its state where the plugin does not look');
  const state = hook.getState();
  const entry = state.workspace.byRel.get(rel) || { kind: 'Item', rel, name: id };
  const ctx = models.modelCtx();
  const parts = await models.loadPartsFor(ctx, entry, doc);
  try {
    if (!parts.length) throw new Error('its model could not be loaded');
    const inputs = await render.icons.resolveIconInputs(ctx, doc);
    let look = null;
    try {
      const url = performance.getEntriesByType('resource').map(r => r.name).find(n => /\/assets\/iconLook-[^/]+\.js$/.test(n));
      if (url) { const lk = (await import(url)).a; look = lk.loadLook(lk.lookKeyOf(state.workspace.source.root, rel)); }
    } catch { look = null; }
    const png = await render.icons.bakeIcon(parts, inputs.props, 64, { look, fx: null });
    const bytes = png instanceof Uint8Array ? png : new Uint8Array(png);
    const source = state.workspace.source;
    await core.wsfs.writeServerBytes({ ...source, serverRoot: core.wsfs.commonRootOf(source) }, ICON_FOLDER + id + '.png', bytes);
  } finally {
    for (const p of parts) { try { p.geometry.dispose(); p.texture.dispose(); } catch { /* already gone */ } }
  }
}

async function apply(api, analysis, choice, progress) {
  const steps = plan(analysis, choice);
  const reader = hyCreatorReader(api);
  const done = { created: 0, edited: 0, icons: 0, failed: [] };
  // Each failure: what she reads, and the error itself for the screenshot.
  const failed = (text, e) => done.failed.push({ text, detail: String(e && e.message || e) });
  for (const file of steps.create) {
    try { await api.workspace.writeText(file.rel, file.text); done.created++; } catch (e) { failed(stem(file.rel) + ' could not be written. Send Larsonix a screenshot of this page.', e); }
  }
  for (const file of steps.edit) {
    try { await applyEdit(api, file.rel, file.ops); done.edited++; } catch (e) { failed(stem(file.rel) + ' could not be fixed. Send Larsonix a screenshot of this page.', e); }
  }
  for (const id of steps.icons) {
    progress('Making icon ' + (done.icons + 1) + ' of ' + steps.icons.length + ': ' + id);
    try { await bakeIcon(api, reader, id); done.icons++; } catch (e) { failed('The icon of ' + id + ' could not be made. Open ' + id + ' and click Generate icon.', e); }
  }
  return done;
}

// HyCreator's own look: its theme colours and its button classes, so the pages read like the rest
// of the app in either theme. The dark theme's values stand in should a token ever be renamed.
const LOOK = {
  card: 'var(--card, #151b28)', border: 'var(--border-2, #242d3b)', text: 'var(--text, #cbd3e0)',
  strong: 'var(--text-strong, #e6e9ef)', dim: 'var(--text-mid, #8a94a8)', accent: 'var(--accent, #4f9cff)',
  ok: 'var(--ok, #4ade80)', warn: 'var(--warn, #e8b449)', danger: 'var(--danger, #ff7b72)',
};
const tint = (colour, percent) => 'color-mix(in srgb, ' + colour + ' ' + percent + '%, transparent)';
const cardStyle = extra => ({ background: LOOK.card, border: '1px solid ' + LOOK.border, borderRadius: 8, padding: '10px 12px', marginBottom: 10, ...extra });
const tagStyle = (colour, extra) => ({
  display: 'inline-block', padding: '0 8px', borderRadius: 999, fontSize: 11.5, lineHeight: '18px', whiteSpace: 'nowrap',
  background: tint(colour, 16), color: colour, border: '1px solid ' + tint(colour, 40), marginRight: 4, ...extra,
});
const plural = (n, one, many) => n + ' ' + (n === 1 ? one : many);

// A short name for each kind of fault, for the tag beside a piece. The full sentence is in the
// list at the top of the page and in the tag's tooltip.
const FAULT_TAG = {
  iconField: 'icon', iconMissing: 'icon', category: 'category', notInFamily: 'resource type', noTable: 'Alteration Table',
  wrongFamily: 'recipe', quality: 'quality', sheetMismatch: 'texture', stats: 'stats', slot: 'slot',
  coOwners: 'shared resource type', inheritors: 'vanilla items', shared: 'same look',
};

function ColoursPage({ rel }) {
  const api = host;
  const h = api.React.createElement;
  const { useState, useEffect, useCallback } = api.React;
  const [analysis, setAnalysis] = useState(null);
  const [choice, setChoice] = useState(null);
  const [status, setStatus] = useState('Reading the colors...');
  const [detail, setDetail] = useState('');
  const [busy, setBusy] = useState(false);
  const [report, setReport] = useState(null);
  const [sheetFolder, setSheetFolder] = useState('');
  const [typedFolder, setTypedFolder] = useState('');

  const load = useCallback(async () => {
    setStatus('Reading the colors...');
    setDetail('');
    const version = api.host && api.host.version;
    if (hyCreatorTooOld(version)) {
      setAnalysis(null);
      setStatus('The Colors page needs HyCreator ' + NEEDS_HYCREATOR + ' or newer, and you have ' + version + '. ' + UPDATE_STEPS);
      return;
    }
    try {
      const result = await analyse(hyCreatorReader(api), rel, { sheetFolder });
      setAnalysis(result);
      setChoice(result.error ? null : defaultChoice(result));
      setStatus(result.error || '');
    } catch (e) {
      setAnalysis(null);
      setStatus('The colors could not be read. Send Larsonix a screenshot of this page.');
      setDetail(String(e && e.message || e));
    }
  }, [rel, sheetFolder]);
  useEffect(() => { load(); }, [load]);

  if (!analysis || analysis.error || !choice) {
    return h('div', { style: { padding: 16, color: LOOK.text } }, h('h3', { style: { marginTop: 0 } }, 'Colors'), h('p', null, status),
      detail ? h('p', { style: { fontSize: 12, color: LOOK.dim } }, 'For Larsonix: ' + detail) : null);
  }
  const { set, cells } = analysis;
  const steps = plan(analysis, choice);
  const toggle = (setName, value) => {
    const next = new Set(choice[setName]);
    next.has(value) ? next.delete(value) : next.add(value);
    setChoice({ ...choice, [setName]: next });
  };
  const toggleAll = (keys, on) => {
    const next = new Set(choice.fix);
    keys.forEach(k => on ? next.add(k) : next.delete(k));
    setChoice({ ...choice, fix: next });
  };
  const tickAllMissing = on => {
    const make = new Set(on ? cells.filter(c => c.missing && !c.clash && !c.unnamed).map(c => c.id) : []);
    setChoice({ ...choice, make });
  };
  const press = async () => {
    const words = 'This writes ' + plural(steps.create.length, 'new file', 'new files') + ', changes ' + plural(steps.edit.length, 'file', 'files') + ' and makes ' + plural(steps.icons.length, 'icon', 'icons') + '.';
    const ok = await api.ui.confirmDanger({ title: 'Make the colors', body: words, items: [...steps.create.map(f => f.rel), ...steps.edit.map(f => f.rel)], confirmLabel: 'Make them' });
    if (!ok) return;
    setBusy(true);
    const done = await apply(api, analysis, choice, setStatus);
    setReport(done);
    setBusy(false);
    api.ui.notify(done.failed.length
      ? 'Colors: ' + plural(done.failed.length, 'thing', 'things') + ' could not be made. The Colors page says which.'
      : '✓ Colors: ' + done.created + ' made, ' + done.edited + ' fixed, ' + plural(done.icons, 'icon', 'icons') + '.');
    await load();
  };

  // What the page found, gathered once: each fix and each note with the pieces it concerns, so a
  // sentence true of sixteen pieces is read once.
  const fixes = new Map();
  const notes = new Map();
  let made = 0;
  for (const cell of cells) {
    for (const item of cell.items || []) {
      made++;
      for (const f of item.faults) {
        const into = f.fix ? fixes : notes;
        const key = f.kind + '|' + f.text;
        if (!into.has(key)) into.set(key, { kind: f.kind, text: f.text, items: [], keys: [] });
        into.get(key).items.push(item.id);
        into.get(key).keys.push(item.id + '|' + f.kind);
      }
    }
  }
  const missing = cells.filter(c => c.missing && !c.clash && !c.unnamed);
  const versions = steps.create.filter(f => f.rel.startsWith('Item/Items/') && f.rel.endsWith('.json')).length;
  const fixedFiles = steps.edit.length + steps.create.filter(f => f.rel.endsWith('.patch')).length;
  const parts = [];
  if (versions) parts.push('make ' + plural(versions, 'color', 'colors'));
  if (fixedFiles) parts.push('fix ' + plural(fixedFiles, 'file', 'files'));
  if (steps.icons.length) parts.push('make ' + plural(steps.icons.length, 'icon', 'icons'));
  const action = parts.length ? parts.join(', ').replace(/, ([^,]*)$/, ' and $1') : '';
  const summary = missing.length === 0 && fixes.size === 0
    ? 'Every color is made, and nothing needs fixing.'
    : [plural(made, 'color made', 'colors made'), missing.length ? missing.length + ' missing' : null,
        fixes.size ? plural(new Set([...fixes.values()].flatMap(g => g.items)).size, 'item to fix', 'items to fix') : null].filter(Boolean).join(', ') + '.';

  const fixTag = (item, f) => {
    const key = item.id + '|' + f.kind;
    const on = choice.fix.has(key);
    return h('label', { key: f.kind, title: f.text + (on ? ' Ticked: fixed when you press the button.' : ' Not ticked.'),
      style: tagStyle(on ? LOOK.warn : LOOK.dim, { cursor: 'pointer', marginTop: 3 }) },
      h('input', { type: 'checkbox', checked: on, onChange: () => toggle('fix', key), style: { margin: '0 4px 0 0', verticalAlign: 'middle' } }),
      'fix ' + (FAULT_TAG[f.kind] || f.kind));
  };
  const cellView = cell => {
    if (!cell) return null;
    if (cell.missing) {
      if (cell.unnamed) return h('span', { style: tagStyle(LOOK.dim) }, 'missing: rename this texture to its color to make it');
      if (cell.clash) return h('span', { title: cell.clash, style: tagStyle(LOOK.danger) }, 'missing, but the name ' + cell.id + ' is taken');
      const on = choice.make.has(cell.id);
      return h('label', { style: { cursor: 'pointer' } },
        h('input', { type: 'checkbox', checked: on, onChange: () => toggle('make', cell.id), style: { marginRight: 6, verticalAlign: 'middle' } }),
        h('span', { style: tagStyle(on ? LOOK.accent : LOOK.dim) }, on ? 'will be made' : 'missing'),
        h('span', { style: { color: LOOK.dim, fontSize: 12 } }, cell.id));
    }
    return h('div', null, cell.items.map(item => h('div', { key: item.id, style: { marginBottom: 2 } },
      h('a', { href: '#', style: { color: LOOK.strong, marginRight: 6 }, onClick: e => { e.preventDefault(); api.session.openFile(item.rel); } }, item.id),
      item.isDefault ? h('span', { style: tagStyle(LOOK.accent) }, 'base') : null,
      item.faults.length === 0 ? h('span', { style: tagStyle(LOOK.ok) }, 'ok') : null,
      h('div', null,
        item.faults.filter(f => f.fix).map(f => fixTag(item, f)),
        item.faults.filter(f => !f.fix).map(f => h('span', { key: f.kind, title: f.text, style: tagStyle(LOOK.warn, { marginTop: 3 }) }, 'note: ' + (FAULT_TAG[f.kind] || f.kind)))))));
  };

  const lightOf = sheet => choice.lights[sheet];
  const setLight = (sheet, colour) => {
    const base = lightOf(sheet) || set.defaultLight || { Radius: 5 };
    setChoice({ ...choice, lights: { ...choice.lights, [sheet]: colour ? { ...base, Color: colour } : null } });
  };
  const names = list => list.slice(0, 4).join(', ') + (list.length > 4 ? ' and ' + (list.length - 4) + ' more' : '');
  const th = text => h('th', { key: text, style: { textAlign: 'left', padding: '6px 8px', color: LOOK.dim, fontWeight: 600, fontSize: 12 } }, text);

  return h('div', { style: { padding: '0 16px 16px', overflow: 'auto', height: '100%', color: LOOK.text, lineHeight: 1.45 } },
    h('div', { style: { margin: '16px 0 10px' } },
      h('h3', { style: { margin: '0 0 2px', color: LOOK.strong } }, 'Colors of ' + set.defaultId, ' ', set.gameDefault ? h('span', { style: tagStyle(LOOK.accent) }, 'vanilla item') : null),
      h('div', { style: { color: LOOK.dim, fontSize: 12 } }, 'Resource type ' + set.pieceFamily + (set.familyFileMissing ? ' (new)' : '')
        + ', ' + plural(set.sheets.length, 'texture', 'textures') + ', ' + plural(set.shapes.length, 'style', 'styles') + '.'),
      set.gameDefault ? h('div', { style: { color: LOOK.dim, fontSize: 12 } }, 'The vanilla item gets the resource type ' + set.pieceFamily + ' too, so the Alteration Table takes it.') : null),

    h('div', { style: cardStyle({ position: 'sticky', top: 0, zIndex: 1, display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8 }) },
      h('div', { style: { flex: '1 1 220px', color: LOOK.strong, fontWeight: 600 } }, summary),
      missing.length ? h('button', { className: 'btn slim', onClick: () => tickAllMissing(true), disabled: busy }, 'Tick every missing color') : null,
      missing.length ? h('button', { className: 'btn slim', onClick: () => tickAllMissing(false), disabled: busy }, 'Untick them') : null,
      h('button', { className: 'btn primary', style: { fontWeight: 600 }, onClick: press, disabled: busy || !action },
        busy ? 'Working...' : action ? action.charAt(0).toUpperCase() + action.slice(1) : 'Nothing to do')),

    notes.size ? h('div', { style: cardStyle({ borderColor: tint(LOOK.warn, 50) }) },
      h('div', { style: { fontWeight: 600, color: LOOK.warn, marginBottom: 4 } }, 'Read before you press'),
      [...notes.values()].map(n => h('div', { key: n.kind + n.text, style: { marginBottom: 4 } }, n.text,
        h('span', { style: { color: LOOK.dim, fontSize: 12 } }, ' (' + names(n.items) + ')')))) : null,

    fixes.size ? h('div', { style: cardStyle() },
      h('div', { style: { fontWeight: 600, color: LOOK.strong, marginBottom: 4 } }, 'To fix. The ticked ones are fixed when you press the button.'),
      [...fixes.values()].map(g => {
        const all = g.keys.every(k => choice.fix.has(k));
        const some = !all && g.keys.some(k => choice.fix.has(k));
        return h('label', { key: g.kind + g.text, style: { display: 'block', cursor: 'pointer', marginBottom: 3 } },
          h('input', { type: 'checkbox', checked: all, ref: el => { if (el) el.indeterminate = some; }, onChange: () => toggleAll(g.keys, !all), style: { marginRight: 6, verticalAlign: 'middle' } }),
          h('span', { style: tagStyle(LOOK.warn) }, plural(g.items.length, 'item', 'items')),
          g.text,
          h('span', { style: { color: LOOK.dim, fontSize: 12 } }, ' (' + names(g.items) + ')'));
      })) : null,

    h('div', { style: cardStyle({ padding: 0, overflow: 'auto' }) },
      h('table', { style: { borderCollapse: 'collapse', width: '100%' } },
        h('thead', null, h('tr', null, th('Texture'), th('Light'), set.shapes.map(shape => th(stem(shape))))),
        h('tbody', null, set.sheets.map((sheet, i) => h('tr', { key: sheet, style: { borderTop: '1px solid ' + LOOK.border, background: i % 2 ? tint(LOOK.strong, 3) : 'transparent' } },
          h('td', { style: { padding: '6px 8px', whiteSpace: 'nowrap', color: LOOK.strong, verticalAlign: 'top' } }, stem(sheet)),
          h('td', { style: { padding: '5px 8px', verticalAlign: 'top' } }, h('input', {
            className: 'tree-search', style: { width: 96, padding: '2px 8px', fontSize: 12 }, value: (lightOf(sheet) && lightOf(sheet).Color) || '', placeholder: 'as base',
            title: 'The color of the light this color gives off, such as #300. Empty: the same light as the base.', onChange: e => setLight(sheet, e.target.value),
          })),
          set.shapes.map(shape => h('td', { key: shape, style: { padding: '6px 8px', verticalAlign: 'top' } }, cellView(cells.find(c => c.sheet === sheet && c.shape === shape))))))))),

    h('div', { style: cardStyle({ fontSize: 12, color: LOOK.dim }) },
      'Textures are read from ', set.sheetFolders.map((dir, i) => h('span', { key: dir }, i ? ', ' : '', h('code', null, dir))), '.',
      h('div', { style: { display: 'flex', gap: 6, marginTop: 6, flexWrap: 'wrap' } },
        h('input', { className: 'tree-search', style: { flex: '1 1 260px' }, value: typedFolder, placeholder: 'Another folder of textures, such as Items/Weapons/CobaltLongSwordRecolors', onChange: e => setTypedFolder(e.target.value) }),
        h('button', { className: 'btn slim', onClick: () => setSheetFolder(typedFolder), disabled: busy || !typedFolder.trim() }, 'Look there'))),
    status ? h('p', null, status) : null,
    report && report.failed.length ? h('div', { style: cardStyle({ borderColor: tint(LOOK.danger, 50) }) }, h('div', { style: { color: LOOK.danger, fontWeight: 600 } }, 'These could not be made:'),
      h('ul', { style: { margin: '4px 0 0', paddingLeft: 18 } }, report.failed.map((f, i) => h('li', { key: i }, f.text,
        h('div', { style: { fontSize: 11.5, color: LOOK.dim } }, 'For Larsonix: ' + f.detail))))) : null);
}

// ---- files made by a tool --------------------------------------------------------------------

// Files the mod's own tools write whole from a list of their own: the patches that put the chest
// loot tables in vanilla chests, and the showcase world. An edit made in one of these is erased
// the next time its tool runs, so HyCreator opens them on a warning first. The rule follows the
// folders those tools own; a new generated folder belongs here too. The chest loot tables
// themselves, Drops/TheArmory, are Lady's to edit in HyCreator's own loot editor.
export function madeByTool(rel) {
  const path = rel.replace(/^\.\.\//, '');
  if (/(^|[/_])(Armory_Showcase|Showcase_Theatre)([_./]|$)/.test(path)) return 'the showcase world';
  if (/^Drops\/Prefabs\/[^/]+\.patch$/.test(path)) return 'the list of chests your loot goes in';
  return null;
}

function MadeByToolPage({ rel }) {
  const h = host.React.createElement;
  return h('div', { style: { padding: 16, maxWidth: 640 } },
    h('h3', null, 'Do not edit this file'),
    h('p', null, 'Our tools rewrite it from ' + madeByTool(rel) + ', so a change made here would be lost.'),
    h('p', null, 'If something in it should change, send Larsonix a screenshot and say what. The tabs above still show the file.'));
}

// ---- the checks -------------------------------------------------------------------------------

// Git runs the mod's checks after every commit, pull and branch switch (Studio/Tools/Checks), and
// they write their result beside themselves. This panel shows the latest one, and a notice says so
// when a new result arrives with something wrong. The files sit outside the mod's Server and
// Common folders, which is all the plugin contract can read, so they are read through the app's
// own file access, the same route HyCreator uses for its own files.
export const CHECK_RESULT = 'Studio/Tools/Checks/last-check.json';
export const CHECK_RUNNING = 'Studio/Tools/Checks/last-check.running.json';
// The problems she judged normal for her work, one problem on one item each, committed with her work.
// check.py reads the same file (NORMAL), so its window and her release leave them out too.
export const CHECK_NORMAL = 'Studio/Checks/normal.json';

// What the panel shows, from the result, the running marker and her "Normal for me" marks, as plain
// data. Every problem comes as check.py translated it for Lady: what is wrong, the one thing to change,
// the items it is about, and whose it is. Hers come first, as cards to fix; Larsonix's come apart, read
// only; the ones she judged normal sit behind one closed line. A result written before that
// translation existed shows each line whole until the next check.
export function checkView(result, running, marks) {
  if (!result || typeof result !== 'object') {
    return { state: running ? 'running' : 'none', groups: [], ours: [], hidden: [], stale: [], problems: 0 };
  }
  const wanted = new Map((Array.isArray(marks) ? marks : []).filter(m => m && typeof m.key === 'string').map(m => [m.key + '|' + (m.item || ''), m]));
  const seen = new Set();
  const mine = [], ours = [], hidden = [];
  for (const c of Array.isArray(result.checks) ? result.checks : []) {
    if (c.status === 'pass') continue;
    const entries = [];
    if (Array.isArray(c.lady)) entries.push(...c.lady);
    else {
      for (const line of c.problems || []) {
        const open = fileIn(line);
        entries.push({ says: line, do: '', items: open ? [{ label: stem(open), open }] : [] });
      }
      if (c.status === 'error') entries.push({ says: 'The check "' + c.title + '" could not run.', do: 'Send Larsonix a screenshot of this panel.', items: [] });
    }
    for (const e of entries) {
      if (e.owner === 'us') { ours.push(e); continue; }
      const key = e.key || (c.id + '|' + (e.line || e.says));
      const items = (e.items || []).length ? e.items : [null];
      const kept = [];
      for (const i of items) {
        const k = key + '|' + (i ? i.label : '');
        if (wanted.has(k)) { seen.add(k); hidden.push({ key, item: i ? i.label : '', open: i, says: e.says }); } else kept.push(i);
      }
      if (kept.length) mine.push({ ...e, key, items: kept.filter(Boolean) });
    }
  }
  const groups = ladyGroups(mine);
  const ourGroups = ladyGroups(ours);
  const state = result.error ? 'error' : groups.length ? 'problems' : 'pass';
  // A marker newer than the result means a later run is still going.
  const busy = !!running && (!result.finished || String(running.started || '') > String(result.finished));
  return {
    state, busy, groups, ours: ourGroups, hidden, stale: [...wanted.entries()].filter(([k]) => !seen.has(k)).map(([, m]) => m),
    // The same problems one by one, for the view by item: hers with her marks already applied, and ours.
    entries: mine, ourEntries: ours,
    problems: groups.length, error: result.error || null, errorDetail: result.error_detail || null,
    finished: result.finished || null, branch: result.branch || null,
  };
}

// The problems that say the same thing and ask for the same fix, as one: what her work added first, then
// the largest. The same grouping as check.py's own window. Each item keeps the key of its own problem,
// which is what "Normal for me" remembers.
export function ladyGroups(entries) {
  const groups = new Map();
  for (const e of entries) {
    const key = e.says + '|' + (e.do || '');
    if (!groups.has(key)) groups.set(key, { key, says: e.says, do: e.do || '', items: [], details: [], count: 0, new: false, problems: [] });
    const g = groups.get(key);
    g.count++;
    g.new = g.new || !!e.new;
    if (!(e.items || []).length) g.problems.push(e.key || null);
    for (const i of e.items || []) if (!g.items.some(x => x.label === i.label)) g.items.push({ ...i, problem: e.key || null });
    if (e.detail) g.details.push(e.detail);
  }
  return [...groups.values()].sort((a, b) => (b.new - a.new) || (Math.max(b.count, b.items.length) - Math.max(a.count, a.items.length)));
}

// The same problems item by item, for the view by item: each item once, with every problem the checks prove
// on it, what her work added first. Each problem keeps its own key, so "Normal for me" marks the same thing
// in both views. A problem about no item (a check that could not run) has nothing to sit under, so it stays a
// card of its own, grouped as in the view by problem.
export function ladyItems(entries) {
  const items = new Map();
  const loose = [];
  for (const e of entries || []) {
    if (!(e.items || []).length) { loose.push(e); continue; }
    for (const i of e.items) {
      if (!items.has(i.label)) items.set(i.label, { label: i.label, open: i.open, find: i.find, problems: [], new: false });
      const item = items.get(i.label);
      item.problems.push({ key: e.key || null, says: e.says, do: e.do || '', new: !!e.new, detail: e.detail || null });
      item.new = item.new || !!e.new;
    }
  }
  for (const item of items.values()) item.problems.sort((a, b) => b.new - a.new);
  return { items: [...items.values()], loose: ladyGroups(loose) };
}

// The orders the view by item offers. Each breaks its ties by name, so the list never shuffles between checks.
export const ITEM_ORDERS = [
  { id: 'new', label: 'New first', title: 'What your latest work added first, then the items with the most problems.' },
  { id: 'count', label: 'Most problems', title: 'The items with the most problems first.' },
  { id: 'name', label: 'Name', title: 'By name, A to Z.' },
  { id: 'folder', label: 'Folder', title: 'By the folder the item is in, so the items of one set sit together.' },
];
const byName = (a, b) => a.label.localeCompare(b.label, undefined, { numeric: true, sensitivity: 'base' });
const folderOf = i => folder(i.open || i.label);
const ORDER_COMPARE = {
  new: (a, b) => (b.new - a.new) || (b.problems.length - a.problems.length) || byName(a, b),
  count: (a, b) => (b.problems.length - a.problems.length) || byName(a, b),
  name: byName,
  folder: (a, b) => folderOf(a).localeCompare(folderOf(b), undefined, { sensitivity: 'base' }) || byName(a, b),
};
export function sortItems(items, order) {
  const compare = ORDER_COMPARE[order] || ORDER_COMPARE.new;
  return [...items].sort(compare);
}

// Whether an item's name holds every word typed in the filter, in any case and in any order. A space and an
// underscore count the same, so "praetorian gray" finds Praetorian_Boots_Gray.
export function matchesFilter(label, filter) {
  const words = String(filter || '').toLowerCase().split(/[\s_]+/).filter(Boolean);
  const name = String(label || '').toLowerCase();
  return words.every(w => name.includes(w));
}

// Her marks as the file holds them, with one more or one less. The file is written whole each time.
export function withMark(marks, mark) {
  return [...(marks || []).filter(m => !(m.key === mark.key && (m.item || '') === (mark.item || ''))), mark];
}
export function withoutMark(marks, mark) {
  return (marks || []).filter(m => !(m.key === mark.key && (m.item || '') === (mark.item || '')));
}

// The item file a check line names, as HyCreator opens it: relative to the Server folder.
export function fileIn(text) {
  const m = /^Server\/([^:]+\.json):/.exec(text);
  return m ? m[1] : null;
}

// The notice for a new result, or null when there is nothing to say. Only a change is announced:
// a pass after something else, or a list of problems that differs from the last one, so a pull
// that leaves the same problem in place does not repeat it.
export function noticeFor(previous, next, marks) {
  if (!next || !next.finished || (previous && previous.finished === next.finished)) return null;
  const view = checkView(next, null, marks);
  const before = previous ? checkView(previous, null, marks) : null;
  if (view.state === 'error') {
    return before && before.state === 'error' && before.error === view.error ? null : 'The Armory checks could not run. Click ! at the top to see why.';
  }
  if (view.state === 'problems') {
    if (before && before.state === 'problems' && problemList(before) === problemList(view)) return null;
    return 'The Armory checks found ' + plural(view.problems, 'thing', 'things') + ' to fix. Click ! at the top to see them.';
  }
  return before && before.state !== 'pass' ? '✓ The Armory checks: everything is fine again.' : null;
}

function problemList(view) {
  return JSON.stringify(view.groups.map(g => [g.says, g.do, g.items.map(i => i.label)]));
}

async function readRepoText(api, rel) {
  const current = api.workspace.current();
  if (!current || current.kind !== 'folder') return { missing: true };
  const root = String(current.root).replace(/\\/g, '/').replace(/\/$/, '');
  const path = root + '/' + rel;
  const tauri = globalThis.__TAURI_INTERNALS__;
  if (!tauri || typeof tauri.invoke !== 'function') return { unreadable: true };
  try {
    if (!(await tauri.invoke('plugin:fs|exists', { path }))) return { missing: true };
    const raw = await tauri.invoke('plugin:fs|read_text_file', { path });
    return { text: typeof raw === 'string' ? raw : new TextDecoder().decode(new Uint8Array(raw)) };
  } catch {
    // HyCreator's own reader, the route its asset search uses.
    try {
      const [text] = await tauri.invoke('read_texts_tiered', { tiers: [{ kind: 'folder', root, serverRoot: root }], rels: [rel] });
      return typeof text === 'string' ? { text } : { missing: true };
    } catch {
      return { unreadable: true };
    }
  }
}

// Writes a file of the mod's own folder outside Server and Common, through the app's own file access,
// the route readRepoText reads by. Proven in a hidden HyCreator 1.3.9: the folder is made and the file
// written where git sees it.
async function writeRepoText(api, rel, text) {
  const current = api.workspace.current();
  const tauri = globalThis.__TAURI_INTERNALS__;
  if (!current || current.kind !== 'folder' || !tauri || typeof tauri.invoke !== 'function') return false;
  const root = String(current.root).replace(/\\/g, '/').replace(/\/$/, '');
  const path = root + '/' + rel;
  try {
    await tauri.invoke('plugin:fs|mkdir', { path: path.slice(0, path.lastIndexOf('/')), options: { recursive: true } });
    await tauri.invoke('plugin:fs|write_text_file', new TextEncoder().encode(text), { headers: { path: encodeURIComponent(path), options: JSON.stringify({}) } });
    return true;
  } catch {
    return false;
  }
}

async function readJson(api, rel) {
  const r = await readRepoText(api, rel);
  if (r.text === undefined) return r;
  try { return { value: JSON.parse(r.text) }; } catch { return { missing: true }; }
}

async function hasStudio(api) {
  const r = await readRepoText(api, 'Studio/Tools/Checks/check.py');
  return r.text !== undefined ? true : r.unreadable ? null : false;
}

function ChecksPanel() {
  const api = host;
  const h = api.React.createElement;
  const { useState, useEffect } = api.React;
  const [data, setData] = useState({ loading: true });
  const [opened, setOpened] = useState({});
  // The view, the order and the filter. The first two are remembered between openings of the panel.
  const remembered = (name, allowed, fallback) => {
    try { const v = api.storage && api.storage.get(name); return allowed.includes(v) ? v : fallback; } catch { return fallback; }
  };
  const remember = (name, value) => { try { if (api.storage) api.storage.set(name, value); } catch { /* kept until HyCreator closes */ } };
  const [mode, setModeState] = useState(() => remembered('checks.view', ['problem', 'item'], 'problem'));
  const [order, setOrderState] = useState(() => remembered('checks.order', ITEM_ORDERS.map(o => o.id), 'new'));
  const [filter, setFilter] = useState('');
  const setMode = v => { setModeState(v); remember('checks.view', v); };
  const setOrder = v => { setOrderState(v); remember('checks.order', v); };
  useEffect(() => {
    let alive = true;
    const load = async () => {
      const [result, running, studio, normal] = await Promise.all([readJson(api, CHECK_RESULT), readJson(api, CHECK_RUNNING), hasStudio(api), readJson(api, CHECK_NORMAL)]);
      const marks = normal.value && Array.isArray(normal.value.normal) ? normal.value.normal : [];
      if (alive) setData({ result: result.value || null, running: running.value || null, studio, marks, unreadable: !!result.unreadable });
    };
    load();
    const timer = setInterval(load, 4000);
    return () => { alive = false; clearInterval(timer); };
  }, []);

  // An item named by its id alone is found among the mod's files when clicked.
  const openSubject = async s => {
    if (s.open) return api.session.openFile(s.open);
    const rel = (await api.workspace.listByExt(s.find || 'Item/Items/', ['json'])).find(r => stem(r) === s.label);
    if (rel) api.session.openFile(rel);
    else api.ui.notify(s.label + ' is not in your files: it is a vanilla one.');
  };
  // "Normal for me": one problem on one item, written to her file at once and shown so before the next check.
  const saveMarks = async marks => {
    const ok = await writeRepoText(api, CHECK_NORMAL, JSON.stringify({ normal: marks }, null, 1) + '\n');
    if (ok) setData(d => ({ ...d, marks }));
    else api.ui.notify('This could not be saved. Send Larsonix a screenshot of this panel.');
  };
  const today = () => new Date().toISOString().slice(0, 10);
  const markNormal = (key, item, says) => saveMarks(withMark(data.marks, { key, item, says, by: 'Lady', date: today() }));
  const showAgain = m => saveMarks(withoutMark(data.marks, m));
  const when = text => {
    const date = new Date(text);
    return isNaN(date) ? String(text || '') : date.toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  };

  const box = children => h('div', { style: { padding: 12, overflow: 'auto', height: '100%', lineHeight: 1.45, color: LOOK.text } }, children);
  const foot = h('p', { key: 'f', style: { fontSize: 12, color: LOOK.dim } },
    'The checks run by themselves each time you commit or update. You can always push, even with things to fix. To check now, double-click Check.cmd in the Studio folder.');
  if (data.loading) return box([h('p', { key: 'l' }, 'Reading the last check...')]);
  if (data.unreadable) return box([h('p', { key: 'u' }, 'This HyCreator cannot show the checks. Double-click Check.cmd in the Studio folder to see them.'), foot]);
  if (data.studio === false) return box([h('p', { key: 's' }, 'Open your whole copy of the mod in HyCreator, the folder that holds Studio, to see the checks.')]);
  const view = checkView(data.result, data.running, data.marks);
  if (view.state === 'none' || view.state === 'running') {
    return box([h('p', { key: 'n' }, view.state === 'running' ? 'Checking...' : 'No check yet. The first one runs when you commit, or double-click Check.cmd in the Studio folder.'), foot]);
  }

  // Both views read the same problems and the same marks. The filter keeps the items whose name holds
  // every word typed, in both views, in her problems and in ours.
  const mine = ladyItems(view.entries);
  const ours = ladyItems(view.ourEntries);
  const typed = filter.trim();
  const match = s => matchesFilter(s.label, typed);
  const byItem = mode === 'item';
  const shownGroups = typed ? view.groups.map(g => ({ ...g, items: g.items.filter(match) })).filter(g => g.items.length) : view.groups;
  const shownOurGroups = typed ? view.ours.map(g => ({ ...g, items: g.items.filter(match) })).filter(g => g.items.length) : view.ours;
  const shownItems = sortItems(mine.items.filter(match), order);
  const shownOurItems = sortItems(ours.items.filter(match), order);
  const shownLoose = typed ? [] : mine.loose;
  const shownOurLoose = typed ? [] : ours.loose;
  const listed = view.groups.length + view.ours.length > 0;

  const small = { padding: '0 8px', fontSize: 11.5 };
  const headline = view.state === 'pass' ? '✓ Everything is fine'
    : view.state === 'error' ? 'The checks could not run'
    : byItem ? plural(mine.items.length, 'item', 'items') + ' to fix' + (mine.loose.length ? ', and ' + plural(mine.loose.length, 'other thing', 'other things') : '')
    : plural(view.problems, 'thing', 'things') + ' to fix';
  const headColour = view.state === 'pass' ? LOOK.ok : LOOK.danger;

  // The switch between the two views, left of the count: two joined buttons, HyCreator's own "on" marking the
  // one shown. With nothing listed there is nothing to switch.
  const segButton = (on, label, onClick, title, extra) => h('button', { key: label, className: 'btn slim' + (on ? ' on' : ''), title, onClick, style: extra }, label);
  const switcher = listed ? h('div', { style: { display: 'inline-flex', flexShrink: 0 } },
    segButton(!byItem, 'By problem', () => setMode('problem'), 'Each problem once, with every item it is on.', { borderTopRightRadius: 0, borderBottomRightRadius: 0 }),
    segButton(byItem, 'By item', () => setMode('item'), 'Each item once, with every problem it has.', { borderTopLeftRadius: 0, borderBottomLeftRadius: 0, marginLeft: -1 })) : null;

  // The order of the view by item, beside the filter.
  const fieldStyle = { background: 'var(--field)', color: LOOK.text, border: '1px solid ' + LOOK.border, borderRadius: 'var(--radius-m, 6px)', fontSize: 12.5, padding: '3px 8px' };
  const sorter = byItem ? h('label', { style: { display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, color: LOOK.dim, flexShrink: 0 } }, 'Sort by',
    h('select', { value: order, onChange: e => setOrder(e.target.value), style: fieldStyle, title: (ITEM_ORDERS.find(o => o.id === order) || {}).title },
      ITEM_ORDERS.map(o => h('option', { key: o.id, value: o.id }, o.label)))) : null;
  const filterBox = listed ? h('input', { type: 'search', className: 'tree-search', value: filter, placeholder: 'Find an item by its name',
    style: { margin: 0, flex: '1 1 160px', minWidth: 0, padding: '3px 9px', fontSize: 12.5 }, onChange: e => setFilter(e.target.value) }) : null;
  const filterNote = typed && mine.items.length ? h('div', { style: { fontSize: 12, color: LOOK.dim, marginTop: 4 } },
    shownItems.length ? plural(shownItems.length, 'item', 'items') + ' of your ' + mine.items.length + ' match.' : 'None of your ' + plural(mine.items.length, 'item', 'items') + ' matches.') : null;

  // The head stays at the top while the list scrolls, so the switch, the filter and the order are always at hand.
  const items = [h('div', { key: 'head', style: cardStyle({ position: 'sticky', top: 0, zIndex: 1 }) },
    h('div', { style: { display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' } },
      switcher,
      h('div', { style: { fontSize: 15, fontWeight: 600, color: headColour } }, headline)),
    h('div', { style: { fontSize: 12, color: LOOK.dim, marginTop: 2 } }, 'Checked ' + when(view.finished) + '.'),
    view.busy ? h('div', { style: { fontSize: 12, color: LOOK.accent, fontStyle: 'italic' } }, 'Checking your latest changes...') : null,
    view.error ? h('div', { style: { color: LOOK.danger, marginTop: 4 } }, view.error) : null,
    view.errorDetail ? h('div', { style: { fontSize: 11.5, color: LOOK.dim } }, 'For Larsonix: ' + view.errorDetail) : null,
    filterBox ? h('div', { style: { display: 'flex', alignItems: 'center', gap: 8, marginTop: 8, flexWrap: 'wrap' } }, filterBox, sorter) : null,
    filterNote)];

  // The button keeps its one line; a long item name gives way instead, cut short with its full name on hover.
  const normalButton = (key, item, says) => h('button', { className: 'btn slim', style: { ...small, color: LOOK.dim, flexShrink: 0, whiteSpace: 'nowrap' },
    title: 'Hide this problem on ' + (item || 'this card') + ' only. It stays on every other item, and you can show it again below.',
    onClick: () => markNormal(key, item, says) }, 'Normal for me');

  // A card of the view by problem: what is wrong, the fix, and the items, each with its own Normal for me.
  const problemCard = (g, readOnly) => {
    if (readOnly) {
      return h('div', { key: 'ours-' + g.key, style: cardStyle({ borderColor: tint(LOOK.dim, 45) }) },
        h('div', { style: { color: LOOK.text } },
          g.items.length > 1 ? h('span', { style: tagStyle(LOOK.dim) }, plural(g.items.length, 'item', 'items')) : null, g.says),
        g.items.length ? h('div', { style: { fontSize: 11.5, color: LOOK.dim, marginTop: 4 } },
          g.items.slice(0, 12).map(s => s.label).join(', ') + (g.items.length > 12 ? ' and ' + (g.items.length - 12) + ' more' : '')) : null);
    }
    const all = opened[g.key];
    const shown = all || typed ? g.items : g.items.slice(0, 12);
    return h('div', { key: g.key, style: cardStyle({ borderColor: tint(LOOK.danger, 45) }) },
      h('div', { style: { fontWeight: 600, color: LOOK.strong } },
        g.new ? h('span', { style: tagStyle(LOOK.accent) }, 'new') : null,
        g.items.length > 1 ? h('span', { style: tagStyle(LOOK.warn) }, plural(g.items.length, 'item', 'items')) : null, g.says),
      g.do ? h('div', { style: { marginTop: 4 } }, h('span', { style: { color: LOOK.dim } }, 'To fix: '), g.do) : null,
      shown.length ? h('div', { style: { display: 'flex', flexDirection: 'column', gap: 3, marginTop: 6 } },
        // The item's name on the left, its Normal for me on the right edge of the card.
        shown.map((s, i) => h('div', { key: i, style: { display: 'flex', gap: 4, justifyContent: 'space-between', alignItems: 'center' } },
          h('button', { className: 'btn slim', style: { ...small, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
            title: 'Open ' + s.label, onClick: () => openSubject(s) }, s.label),
          s.problem ? normalButton(s.problem, s.label, g.says) : null)),
        g.items.length > 12 && !typed ? h('button', { className: 'btn slim', style: { ...small, color: LOOK.accent, alignSelf: 'flex-start' }, onClick: () => setOpened({ ...opened, [g.key]: !all }) },
          all ? 'Show fewer' : '+' + (g.items.length - 12) + ' more') : null) : null,
      !g.items.length && g.problems.length === 1 && g.problems[0] ? h('div', { style: { marginTop: 6, display: 'flex', justifyContent: 'flex-end' } }, normalButton(g.problems[0], '', g.says)) : null,
      g.details.map((d, i) => h('div', { key: i, style: { fontSize: 11.5, color: LOOK.dim, wordBreak: 'break-word', whiteSpace: 'pre-wrap', marginTop: 4 } }, 'For Larsonix: ' + d)));
  };

  // A row of the view by item: folded to its name and its count, opened to its problems, each with its fix
  // and its own Normal for me. Our rows open to read only.
  const itemKey = (it, readOnly) => (readOnly ? 'ours:' : 'item:') + it.label;
  const count = (it, readOnly) => h('span', { style: tagStyle(readOnly ? LOOK.dim : LOOK.warn, { marginRight: 0, minWidth: 18, textAlign: 'center' }),
    title: plural(it.problems.length, 'problem', 'problems') }, String(it.problems.length));
  const itemRow = (it, readOnly) => {
    const k = itemKey(it, readOnly);
    const isOpen = !!opened[k];
    const toggle = () => setOpened({ ...opened, [k]: !isOpen });
    const name = h('button', { className: 'btn slim', title: (isOpen ? 'Fold ' : 'Show the problems of ') + it.label, onClick: toggle,
      style: { ...small, flex: '1 1 auto', minWidth: 0, textAlign: 'left', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', border: 'none', background: 'transparent', color: readOnly ? LOOK.text : LOOK.strong } },
      (isOpen ? '▾ ' : '▸ ') + it.label);
    const newTag = it.new ? h('span', { style: tagStyle(LOOK.accent, { marginRight: 0 }) }, 'new') : null;
    const openButton = readOnly ? null : h('button', { className: 'btn slim', style: { ...small, flexShrink: 0 }, title: 'Open ' + it.label, onClick: () => openSubject(it) }, 'Open');
    const head = h('div', { style: { display: 'flex', alignItems: 'center', gap: 6 } }, name, newTag, count(it, readOnly), openButton);
    const body = isOpen ? h('div', { style: { display: 'flex', flexDirection: 'column', gap: 6, margin: '4px 0 2px 22px' } },
      it.problems.map((p, i) => h('div', { key: i, style: { display: 'flex', gap: 8, justifyContent: 'space-between', alignItems: 'flex-start', borderTop: i ? '1px solid ' + LOOK.border : 'none', paddingTop: i ? 6 : 0 } },
        h('div', { style: { minWidth: 0 } },
          h('div', { style: { color: readOnly ? LOOK.text : LOOK.strong, fontWeight: readOnly ? 400 : 600 } }, p.new ? h('span', { style: tagStyle(LOOK.accent) }, 'new') : null, p.says),
          !readOnly && p.do ? h('div', { style: { marginTop: 2 } }, h('span', { style: { color: LOOK.dim } }, 'To fix: '), p.do) : null,
          !readOnly && p.detail ? h('div', { style: { fontSize: 11.5, color: LOOK.dim, wordBreak: 'break-word', whiteSpace: 'pre-wrap', marginTop: 2 } }, 'For Larsonix: ' + p.detail) : null),
        !readOnly && p.key ? normalButton(p.key, it.label, p.says) : null))) : null;
    return h('div', { key: k, style: cardStyle({ padding: '4px 8px 4px 2px', marginBottom: 4, borderColor: readOnly ? tint(LOOK.dim, 45) : tint(LOOK.danger, 35) }) }, head, body);
  };
  // Opens or folds every row of one list.
  const foldAll = (list, readOnly) => {
    const keys = list.map(it => itemKey(it, readOnly));
    const allOpen = keys.length > 0 && keys.every(k => opened[k]);
    return h('button', { className: 'btn slim', style: { ...small, flexShrink: 0 }, onClick: () => {
      const next = { ...opened };
      for (const k of keys) next[k] = !allOpen;
      setOpened(next);
    } }, allOpen ? 'Fold all' : 'Open all');
  };
  const listHead = (list, readOnly) => list.length > 1
    ? h('div', { key: (readOnly ? 'ours-' : '') + 'fold', style: { display: 'flex', justifyContent: 'flex-end', marginBottom: 4 } }, foldAll(list, readOnly)) : null;

  if (byItem) {
    for (const g of shownLoose) items.push(problemCard(g, false));
    if (shownItems.length) {
      items.push(listHead(shownItems, false));
      for (const it of shownItems) items.push(itemRow(it, false));
    }
  } else {
    for (const g of shownGroups) items.push(problemCard(g, false));
  }
  if (view.state === 'problems' && !typed) items.push(h('p', { key: 'rest', style: { fontSize: 12, color: LOOK.ok } }, 'Everything else is fine.'));

  // Ours: the file is ours, or only code can fix it. Read only, nothing to click but folding.
  const anyOurs = byItem ? shownOurItems.length + shownOurLoose.length : shownOurGroups.length;
  if (anyOurs) {
    items.push(h('div', { key: 'ours-head', style: { fontSize: 13, fontWeight: 600, color: LOOK.strong, margin: '14px 0 4px' } },
      "Larsonix's problems: he fixes these. Nothing to do for you."));
    if (byItem) {
      for (const g of shownOurLoose) items.push(problemCard(g, true));
      if (shownOurItems.length) {
        items.push(listHead(shownOurItems, true));
        for (const it of shownOurItems) items.push(itemRow(it, true));
      }
    } else {
      for (const g of shownOurGroups) items.push(problemCard(g, true));
    }
  }

  // What she judged normal: one closed line, opened to show each one again.
  const hiddenCount = view.hidden.length + view.stale.length;
  if (hiddenCount) {
    const open = !!opened['hidden'];
    items.push(h('button', { key: 'hidden', className: 'btn slim', style: { ...small, marginTop: 12 }, onClick: () => setOpened({ ...opened, hidden: !open }) },
      (open ? 'Hide again ' : '') + 'Hidden: ' + view.hidden.length));
    if (open) {
      for (const [n, x] of view.hidden.entries()) {
        items.push(h('div', { key: 'h' + n, style: { display: 'flex', gap: 6, alignItems: 'baseline', fontSize: 12, marginTop: 4 } },
          h('button', { className: 'btn slim', style: small, onClick: () => showAgain({ key: x.key, item: x.item }) }, 'Show again'),
          h('span', null, (x.item ? x.item + ': ' : '') + x.says)));
      }
      for (const [n, m] of view.stale.entries()) {
        items.push(h('div', { key: 's' + n, style: { display: 'flex', gap: 6, alignItems: 'baseline', fontSize: 12, marginTop: 4, color: LOOK.dim } },
          h('button', { className: 'btn slim', style: small, onClick: () => showAgain(m) }, 'Remove'),
          h('span', null, 'No longer found: ' + (m.item ? m.item + ': ' : '') + (m.says || ''))));
      }
    }
  }
  items.push(foot);
  return box(items);
}

// Watches for new results while HyCreator runs, and raises a notice for one that needs a look.
// The first reading of a folder is the starting point and raises nothing, so opening HyCreator
// never repeats an old notice. Every result after it is new, the very first one included.
function watchChecks(api) {
  let root = null;
  let primed = false;
  let last = null;
  let stopped = false;
  const tick = async () => {
    if (stopped) return;
    const current = api.workspace.current();
    const now = current ? String(current.root) : null;
    if (now !== root) { root = now; primed = false; last = null; }
    if (!now) return;
    const r = await readJson(api, CHECK_RESULT);
    if (!primed) { primed = true; last = r.value || null; return; }
    if (r.value && (!last || r.value.finished !== last.finished)) {
      const normal = await readJson(api, CHECK_NORMAL);
      const text = noticeFor(last, r.value, normal.value && Array.isArray(normal.value.normal) ? normal.value.normal : []);
      if (text) api.ui.notify(text);
      last = r.value;
    }
  };
  tick();
  const timer = setInterval(tick, 4000);
  return () => { stopped = true; clearInterval(timer); };
}

// ---- the guide -------------------------------------------------------------------------------

// Every section is one job: what it is for, then the steps. The same text is saved as
// Studio/GUIDE.md for reading in a browser, and a test fails when the two differ.
export const GUIDE = [
  { title: 'Set up, once', steps: [
    'Install Git for Windows from https://git-scm.com/download/win, with its default choices.',
    'Put Get The Armory.cmd, the file Larsonix sends you, in the folder where you want the mod, such as Documents.',
    'Double-click it. Answer its question, then do the Blockbench step it shows.',
    'Restart HyCreator. Click Workspace at the top left, then Open mod folder..., and pick the The-Armory-Mod folder it made.',
    'The button at the top now says lady: your branch. All your work goes there.',
    'The first time you push, GitHub asks you to sign in. Sign in once.',
  ] },
  { title: 'Each time you work', steps: [
    'Before you start, double-click Studio\\Update.cmd. It brings in Larsonix\'s latest work and sends yours to GitHub.',
    'When you finish, click lady at the top, then Commit changes... Write what you did in one line, then click Commit.',
    'Then click lady at the top, then Push.',
    'If Pull or Push says your branch has diverged, double-click Update.cmd instead.',
    'If Update says your work and ours changed the same files, send Larsonix a screenshot and keep working.',
  ] },
  { title: 'The checks', steps: [
    'The checks run by themselves each time you commit. When they find something, a notice appears.',
    'Click ! at the top to see what to fix. Click an item to open it.',
    'Click By item at the top of the panel to see each item once, with all its problems. Open opens the item.',
    'To find one item, type part of its name in the box at the top of the panel.',
    'If a problem is how you want that item, click Normal for me beside it. It hides that problem on that item only. Hidden, at the bottom, shows it again.',
    "Larsonix's problems are his to fix. Nothing to do for you.",
    'You can always push, even with things to fix.',
  ] },
  { title: 'Where your files go', steps: [
    'Save models and textures from Blockbench and Photoshop in src\\main\\resources\\Common, inside your copy, as before.',
    'Photoshop files, helper textures and Full models go in Studio\\Work. Hytale never loads them.',
  ] },
  { title: 'Split a Full model', steps: [
    'Open the Full model in Blockbench, then click File > Export > Save Each Group as a Model.',
    'Untick the groups that are not an item, check the file names and the folder, then click Confirm.',
    'A group you hid in Blockbench would stay hidden in Hytale too. The window shows it again unless you untick show.',
    'The first time, Blockbench asks whether The Armory may use files. Click Always allow for this plugin.',
  ] },
  { title: 'Make the colors of an item', steps: [
    'Make the base item in HyCreator as usual: stats, quality, recipe and name.',
    'Armor takes the categories ArmoryCategory.ArmoryArmors and Items.Armors. Weapons and shields take Items.Weapons beside their Armory one.',
    'Put one texture per color beside the model, named for its color, such as Red.png.',
    'Put each alt style beside the model too, named after it, such as Chest_Open.blockymodel beside Chest.blockymodel.',
    'Open the base item and click Colors at the top of the editor.',
    'Tick the colors you want, then press the button. It makes each color, its Alteration Table recipe and its icon.',
    'If a color glows, type its light color in its Light box first, such as #300.',
    'Change stats on the base item only. Every color takes them from it.',
    'Pressing the button again only makes what is missing and fixes what is wrong.',
  ] },
  { title: 'Colors of a vanilla item', steps: [
    'Put the textures in Common\\Items\\Armors\\VanillaRecolors\\Thorium for the Thorium armor, named like Chest_Red.png.',
    'Press Ctrl+K, type the vanilla item\'s name, such as Armor_Thorium_Chest, and open it. Then click Colors.',
    'Do not copy the vanilla item first. The Colors page builds every color on it.',
    'For a vanilla weapon, type the folder of its textures at the bottom of the Colors page, then click Look there.',
  ] },
  { title: 'Icons', steps: [
    'The Colors page makes the icons of the colors it makes.',
    'For one item, click Generate icon on its page.',
    'Do not use Bake all icons (batch): it remakes every icon of the mod.',
  ] },
  { title: 'See it in Hytale', steps: [
    'Start a world with The Armory turned on in its mods list.',
    'A file you save in HyCreator changes in the running world. If an item still looks the same, take it off and put it on again, or rejoin the world.',
    'To get an item in your world, click /give at the top of its page, then paste it in Hytale\'s chat.',
    'Never click Deploy at the top of HyCreator. The game already reads your copy, and Deploy only adds a large unused file to your mods folder.',
  ] },
  { title: 'Undo a mistake', steps: [
    'To undo every change to one file since your last commit, right-click it in the file tree, then Restore from last commit.',
    'A file you delete goes to the trash. Click Workspace at the top left, then Trash (restore deleted)..., to bring it back.',
  ] },
  { title: 'Stats and effects', steps: [
    'Items alone can give resistance to any kind of damage, extra breath, and a shield\'s reaction when it blocks.',
    'The Armory adds Move_Speed, Jump_Height, Arrow_Damage_Bonus, TA_Swim_Speed, TA_Fire_Resistance, TA_Blast_Resistance, TA_Mining_Speed, TA_Ore_Find and TA_Unarmed_Damage.',
    'Write those as Multiplicative: 0.5 is 50% more.',
    'TA_Night_Mana and TA_Night_Health are Additive: 50 is 50 more at night.',
    'For anything else, ask Larsonix.',
    'For particles and sounds, use only the names HyCreator suggests.',
    'Effectly tags are no longer used.',
  ] },
  { title: 'Chest loot', steps: [
    'Each zone has its own chest loot table, in Drops > TheArmory: Armory_Zone1_Loot to Armory_Zone4_Loot.',
    'A table has three groups. A chest gets an item from the first group 25% of the time, from the second 5%, and from the third 1%.',
    'Inside a group, every entry has the same chance. A whole set is one entry, and the chest gets one item of it.',
    'Click the arrow of a group to see its entries. To add one, use + Add entry... inside that group, choose Single: an item, and pick the item.',
    'Click 10k rolls at the top to see how often each item drops.',
    'Keep the four table names as they are: the chests find them by name.',
  ] },
  { title: 'Make a release', steps: [
    'Commit your work first, then double-click Studio\\Release.cmd.',
    'Type the version, or press Enter for the one it suggests.',
    'Write the changelog in the window that opens, then click Done.',
    'Type 1 for a draft players cannot see, 2 for a draft with a message for Larsonix, or 3 to publish.',
    'If it cannot publish, it says why and offers a draft instead.',
  ] },
  { title: 'Files our tools make', steps: [
    'Our tools rewrite the showcase world and the list of chests your loot goes in. HyCreator opens those files on a warning.',
    'To change one, send Larsonix what should change.',
  ] },
  { title: 'When something goes wrong', steps: [
    'Send Larsonix a screenshot, and say what you did.',
  ] },
];

export function guideMarkdown() {
  const lines = ['<!-- Made from GUIDE in Studio/Tools/HyCreator/armory-studio.js. Edit it there, never here. -->', '',
    '# The Armory guide', '', 'The same guide is in HyCreator: click ? at the top.', ''];
  for (const section of GUIDE) {
    lines.push('## ' + section.title, '');
    for (const step of section.steps) lines.push('- ' + step);
    lines.push('');
  }
  return lines.join('\n');
}

function GuidePanel() {
  const h = host.React.createElement;
  return h('div', { style: { padding: 12, overflow: 'auto', height: '100%', lineHeight: 1.45 } },
    GUIDE.map(section => h('section', { key: section.title, style: { marginBottom: 14 } },
      h('h4', { style: { margin: '6px 0' } }, section.title),
      h('ul', { style: { margin: 0, paddingLeft: 18 } }, section.steps.map((step, i) => h('li', { key: i }, step))))));
}

let unregister = [];

export default {
  id: 'armory.studio',
  name: 'The Armory Studio',
  version: '0.4.0',
  minApi: 2,
  author: 'The Armory',
  description: 'The Colors page, the guide, and what the checks found.',
  activate(api) {
    host = api;
    const version = api.host && api.host.version;
    if (hyCreatorTooOld(version)) {
      api.ui.notify('HyCreator ' + version + ' cannot list your textures and models when you browse for a file. ' + UPDATE_STEPS);
    }
    unregister.push(api.editors.registerPage({
      id: 'plugin:armory.madebytool',
      label: 'Made by our tools',
      title: 'Our tools rewrite this file',
      matches: rel => madeByTool(rel) !== null,
      defaultFor: rel => madeByTool(rel) !== null,
      component: MadeByToolPage,
    }));
    unregister.push(api.editors.registerPage({
      id: 'plugin:armory.colours',
      label: 'Colors',
      title: 'Make and fix every color of this item',
      matches: (rel, doc) => rel.startsWith('Item/Items/') && !!doc && typeof doc === 'object' && (typeof doc.Model === 'string' || typeof doc.Parent === 'string'),
      component: ColoursPage,
    }));
    if (api.ui.registerSidePanel) {
      api.ui.registerSidePanel({ id: 'armory.studio.guide', title: 'The Armory guide', glyph: '?', component: GuidePanel, defaultWidth: 440 });
      api.ui.registerSidePanel({ id: 'armory.studio.checks', title: 'The Armory checks', glyph: '!', component: ChecksPanel, defaultWidth: 480 });
    }
    unregister.push(watchChecks(api));
    // The warning page above only reaches editors that offer plugin pages, and HyCreator's own
    // drop list and patch editors do not open on it. So the first edit of such a file, in any
    // editor, also raises a notice. HyCreator reports an edit without naming the file, so the
    // file is the one shown at that moment. It also reports a file being opened, so only a file
    // whose tab holds unsaved changes counts as edited.
    api.modules.store().then(store => {
      const state = [store.useStudio, store.u].find(s => s && typeof s.getState === 'function');
      if (!state) return;
      const warned = new Set();
      unregister.push(api.session.subscribeDocs(() => {
        const now = state.getState();
        const rel = now.activeRel;
        const tool = typeof rel === 'string' ? madeByTool(rel) : null;
        if (!tool || warned.has(rel) || !now.sessions?.get?.(rel)?.dirty) return;
        warned.add(rel);
        api.ui.notify(fileName(rel) + ' is rewritten by our tools from ' + tool + ', so this change will be lost. Send Larsonix what should change instead.');
      }));
    }).catch(() => { /* the page warning still stands */ });
  },
  deactivate() {
    for (const off of unregister) { try { off(); } catch { /* already gone */ } }
    unregister = [];
  },
};
