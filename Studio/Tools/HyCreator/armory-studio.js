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

// The colour a sheet stands for, as a version's id spells it. A leading piece word is not part
// of the colour: Helm_Blue.png is the colour Blue of a helmet.
export function sheetColour(sheet) {
  const words = stem(sheet).split(/[\s_]+/).filter(Boolean);
  if (words.length > 1 && pieceSlot(words[0])) words.shift();
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
    return { error: 'This item has no model and texture of its own, so it has no colour set.' };
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
        ? 'This is a colour version, and no default piece for its family was found, in the mod or among the game\'s items. Open the default piece instead.'
        : 'This is a colour version, and several pieces could be its default: ' + candidates.map(([id]) => id).join(', ') + '. Open the default piece instead.' };
    }
    defaultId = candidates[0][0];
  }
  const gameDefault = !index.has(defaultId);
  const def = { id: defaultId, rel: await reader.relOf(defaultId), own: await reader.ownDoc(defaultId), eff: await reader.effective(defaultId) };
  if (!def.eff || typeof def.eff.Model !== 'string' || typeof def.eff.Texture !== 'string') {
    return { error: 'The default piece ' + defaultId + ' has no model and texture, so it has no colour set.' };
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
      return m.id.endsWith('_' + colour) ? m.id.slice(0, -(colour.length + 1)) : null;
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
  for (const sheet of orderedSheets) for (const shape of orderedShapes) expected.set(idFor(set, sheet, shape, set.order), { sheet, shape });

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
      if (alike.length) item.faults.push({ kind: 'shared', text: 'Looks exactly like ' + alike.map(i => i.id).join(', ') + ' and is worn on the same slot.', fix: null });
    }
    cell.missing = cell.items.length === 0;
    cell.clash = cell.missing && index.has(cell.id) ? index.get(cell.id) : null;
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
    faults.push({ kind: 'iconField', text: doc.Icon ? 'Shows the icon ' + fileName(doc.Icon) + ', not its own.' : 'Names no icon.', fix: { ops: [[['Icon'], own]], icon: true }, byDefault: true });
  } else if (!icons.has(own)) {
    faults.push({ kind: 'iconMissing', text: 'Its icon picture does not exist yet.', fix: { ops: [], icon: true }, byDefault: true });
  }
  return faults;
}

// Gear missing the game's own category beside its Armory one cannot be found by other mods.
function categoryFault(id, eff, withArmory) {
  const want = wantedCategories(id, eff, withArmory);
  if (!want || canonical(want) === canonical(eff.Categories || null)) return [];
  const missing = want.filter(c => !(eff.Categories || []).includes(c));
  return [{ kind: 'category', text: 'Is not in ' + missing.join(' and ') + ', so ' + (missing.some(c => c.startsWith('ArmoryCategory.')) ? 'the Armory tab does not show it' : 'other mods cannot find it') + '.', fix: { ops: [[['Categories'], want]] }, byDefault: true }];
}

function defaultFaults(set, def, icons, twins) {
  if (set.gameDefault) {
    // The game's own piece: nothing in its file is ours to change but its family, which the
    // mod's patch on it gives.
    const faults = [];
    if (!def.declared.includes(set.pieceFamily)) {
      faults.push({ kind: 'notInFamily', text: 'Is not in the family ' + set.pieceFamily + ', so the Alteration Table cannot turn it into a colour. A patch of the mod adds it.', fix: { ops: [], patch: true }, byDefault: true });
    }
    faults.push(...coOwnerNote(set));
    if (set.inheritors.length) {
      faults.push({ kind: 'inheritors', text: set.inheritors.length + ' game items inherit from it with no family of their own, so they are in its family too and the table turns them into its colours: ' + set.inheritors.slice(0, 6).join(', ') + (set.inheritors.length > 6 ? '...' : '') + '. Tell Larsonix before making colours of it.', fix: null });
    }
    return faults;
  }
  const faults = iconFaults(set.defaultId, def.own, icons, twins);
  if (!def.declared.includes(set.pieceFamily)) {
    faults.push({ kind: 'notInFamily', text: 'Is not in the family ' + set.pieceFamily + ', so the Alteration Table cannot turn it into a colour.', fix: { ops: [[['ResourceTypes'], [...(def.own.ResourceTypes || []), { Id: set.pieceFamily }]]] }, byDefault: true });
  }
  faults.push(...categoryFault(set.defaultId, def.eff, false));
  faults.push(...coOwnerNote(set));
  return faults;
}

function coOwnerNote(set) {
  if (!set.coOwners.length) return [];
  return [{ kind: 'coOwners', text: 'Its family ' + set.pieceFamily + ' is also the family of ' + set.coOwners.slice(0, 4).join(', ') + (set.coOwners.length > 4 ? '...' : '') + ', so these colours are that piece\'s too. Nothing is ticked for you: check with Larsonix which piece is the default.', fix: null }];
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
        ? 'Is crafted at ' + benches.join(', ') + ', not made at the Alteration Table. Tick this only if it should become a colour version.'
        : 'Never appears at the Alteration Table: its recipe names no Alteration Table bench and category.',
      fix: { ops: [[['Recipe'], alterationRecipe(set.pieceFamily)]] },
      byDefault: !crafted,
    });
  } else if (alterationInput(recipe) !== set.pieceFamily) {
    faults.push({ kind: 'wrongFamily', text: 'Is made from ' + alterationInput(recipe) + ' instead of ' + set.pieceFamily + '.', fix: { ops: [[['Recipe'], alterationRecipe(set.pieceFamily)]] }, byDefault: true });
  }
  if (!families(m.eff).includes(set.pieceFamily)) {
    const into = set.gameDefault ? [{ Id: set.pieceFamily }] : (def.eff.ResourceTypes || [{ Id: set.pieceFamily }]);
    faults.push({ kind: 'notInFamily', text: 'Is not in the family ' + set.pieceFamily + ', so the table cannot turn it back into another colour.', fix: { ops: [[['ResourceTypes'], into]] }, byDefault: true });
  }
  if (set.defaultQuality !== undefined && own.Quality !== set.defaultQuality && m.eff.Quality !== set.defaultQuality) {
    faults.push({ kind: 'quality', text: 'Has quality ' + (m.eff.Quality || 'none') + ', the default piece has ' + set.defaultQuality + '.', fix: { ops: [[['Quality'], set.defaultQuality]] }, byDefault: true });
  }
  faults.push(...iconFaults(m.id, own, icons, twins));
  faults.push(...categoryFault(m.id, m.eff, set.gameDefault));
  const named = expected.get(m.id);
  if (named && named.sheet !== m.eff.Texture) {
    faults.push({ kind: 'sheetMismatch', text: 'Its name says ' + stem(named.sheet) + ', but it wears ' + stem(m.eff.Texture) + '.', fix: { ops: [[['Texture'], named.sheet]] }, byDefault: false });
  }
  // The same rule as the mod's family check: the slot is judged on its own, and the cosmetics an
  // armour piece hides may differ on purpose (an open helmet shows the hair).
  const mine = statsOf(m.eff.Armor), theirs = statsOf(def.eff.Armor);
  if (canonical(mine.stats) !== canonical(theirs.stats)) {
    const inheritsFromDefault = own.Parent === set.defaultId;
    faults.push({ kind: 'stats', text: 'Its armour stats differ from the default piece, so changing colour at the table changes its stats.', fix: { ops: [[['Armor'], inheritsFromDefault ? undefined : def.eff.Armor]] }, byDefault: false });
  }
  if (mine.slot !== theirs.slot) {
    faults.push({ kind: 'slot', text: 'Is worn on ' + (mine.slot || 'no slot') + ', the default piece on ' + (theirs.slot || 'no slot') + '.', fix: null });
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
      if (!choice.make.has(cell.id) || cell.clash) continue;
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
    if (cell.missing && analysis.memberCount === 0 && !cell.clash) make.add(cell.id);
    for (const item of cell.items) for (const f of item.faults) if (f.fix && f.byDefault) fix.add(item.id + '|' + f.kind);
  }
  return { make, fix, lights: { ...analysis.lights } };
}

// ---- inside HyCreator --------------------------------------------------------------------------

let host = null;

// The game's files behind the workspace. HyCreator 1.3.8's listVanillaByExt answers nothing when
// the vanilla source is the game's Assets.zip (measured: 0 item files, where its own list behind
// the workspace holds all 3,964), so the app's list is read when the plugin listing is empty.
async function vanillaFiles(api) {
  try {
    const store = await api.modules.store();
    const hook = [store.useStudio, store, store.u, store.useStore, store.default].find(s => s && typeof s.getState === 'function');
    const files = hook && hook.getState().fallback && hook.getState().fallback.files;
    const list = Array.isArray(files) ? files : files && files.values ? [...files.values()] : [];
    return list.map(f => typeof f === 'string' ? f : f && f.rel).filter(f => typeof f === 'string');
  } catch {
    return [];
  }
}

// The game's own files, kept between openings of the page: they do not change while HyCreator
// runs, and reading all of them is what makes the first opening of a game piece's set slow.
const gameTexts = new Map();

function hyCreatorReader(api) {
  let vanilla = null;
  let vanillaSet = null;
  let modFiles = null;
  const gameList = async () => {
    if (!vanilla) vanilla = await vanillaFiles(api);
    if (!vanillaSet) vanillaSet = new Set(vanilla);
    return vanilla;
  };
  return makeReader({
    list: (prefix, exts) => api.workspace.listByExt(prefix, exts),
    listVanilla: async (prefix, exts) => {
      const listed = await api.workspace.listVanillaByExt(prefix, exts);
      if (listed.length) return listed;
      return (await gameList()).filter(rel => rel.startsWith(prefix) && exts.includes(rel.slice(rel.lastIndexOf('.') + 1)));
    },
    read: async rel => {
      // Only a file on the game's own list that the mod does not hold is kept: a colour the page
      // writes during the session is never mistaken for a game file.
      if (!modFiles) modFiles = new Set(await api.workspace.listByExt('Item/', ['json', 'patch']));
      await gameList();
      const game = rel.startsWith('Item/') && !modFiles.has(rel) && vanillaSet.has(rel);
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
  // The app's state hook is named useStudio in 1.3.8. The other names are guesses for a rename.
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
  for (const file of steps.create) {
    try { await api.workspace.writeText(file.rel, file.text); done.created++; } catch (e) { done.failed.push(file.rel + ': ' + (e && e.message || e)); }
  }
  for (const file of steps.edit) {
    try { await applyEdit(api, file.rel, file.ops); done.edited++; } catch (e) { done.failed.push(file.rel + ': ' + (e && e.message || e)); }
  }
  for (const id of steps.icons) {
    progress('Icon ' + (done.icons + 1) + ' of ' + steps.icons.length + ': ' + id);
    try { await bakeIcon(api, reader, id); done.icons++; } catch (e) { done.failed.push('icon of ' + id + ': ' + (e && e.message || e) + '. Use Generate icon on that item.'); }
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
  iconField: 'icon', iconMissing: 'icon', category: 'category', notInFamily: 'family', noTable: 'table recipe',
  wrongFamily: 'recipe family', quality: 'quality', sheetMismatch: 'picture', stats: 'stats', slot: 'slot',
  coOwners: 'shared family', inheritors: 'game items',
};

function ColoursPage({ rel }) {
  const api = host;
  const h = api.React.createElement;
  const { useState, useEffect, useCallback } = api.React;
  const [analysis, setAnalysis] = useState(null);
  const [choice, setChoice] = useState(null);
  const [status, setStatus] = useState('Reading the set...');
  const [busy, setBusy] = useState(false);
  const [report, setReport] = useState(null);
  const [sheetFolder, setSheetFolder] = useState('');
  const [typedFolder, setTypedFolder] = useState('');

  const load = useCallback(async () => {
    setStatus('Reading the set...');
    try {
      const result = await analyse(hyCreatorReader(api), rel, { sheetFolder });
      setAnalysis(result);
      setChoice(result.error ? null : defaultChoice(result));
      setStatus(result.error || '');
    } catch (e) {
      setAnalysis(null);
      setStatus('The set could not be read: ' + (e && e.message || e));
    }
  }, [rel, sheetFolder]);
  useEffect(() => { load(); }, [load]);

  if (!analysis || analysis.error || !choice) {
    return h('div', { style: { padding: 16, color: LOOK.text } }, h('h3', { style: { marginTop: 0 } }, 'Colours'), h('p', null, status));
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
    const make = new Set(on ? cells.filter(c => c.missing && !c.clash).map(c => c.id) : []);
    setChoice({ ...choice, make });
  };
  const press = async () => {
    const words = steps.create.length + ' new files, ' + steps.edit.length + ' fixed files and ' + steps.icons.length + ' icons.';
    const ok = await api.ui.confirmDanger({ title: 'Make the colours', body: 'This writes ' + words + ' Git keeps the previous version of every file.', items: [...steps.create.map(f => f.rel), ...steps.edit.map(f => f.rel)], confirmLabel: 'Write' });
    if (!ok) return;
    setBusy(true);
    const done = await apply(api, analysis, choice, setStatus);
    setReport(done);
    setBusy(false);
    api.ui.notify((done.failed.length ? 'Colours: ' + done.failed.length + ' failed. ' : '✓ ') + done.created + ' made, ' + done.edited + ' fixed, ' + done.icons + ' icons.');
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
  const missing = cells.filter(c => c.missing && !c.clash);
  const versions = steps.create.filter(f => f.rel.startsWith('Item/Items/') && f.rel.endsWith('.json')).length;
  const fixedFiles = steps.edit.length + steps.create.filter(f => f.rel.endsWith('.patch')).length;
  const parts = [];
  if (versions) parts.push('make ' + plural(versions, 'colour', 'colours'));
  if (fixedFiles) parts.push('fix ' + plural(fixedFiles, 'file', 'files'));
  if (steps.icons.length) parts.push('make ' + plural(steps.icons.length, 'icon', 'icons'));
  const action = parts.length ? parts.join(', ').replace(/, ([^,]*)$/, ' and $1') : '';
  const summary = missing.length === 0 && fixes.size === 0
    ? 'Every colour is made and correct.'
    : [plural(made, 'colour made', 'colours made'), missing.length ? plural(missing.length, 'colour missing', 'colours missing') : null,
        fixes.size ? plural(new Set([...fixes.values()].flatMap(g => g.items)).size, 'piece to fix', 'pieces to fix') : null].filter(Boolean).join(', ') + '.';

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
      if (cell.clash) return h('span', { title: cell.clash, style: tagStyle(LOOK.danger) }, 'missing, its name ' + cell.id + ' is taken');
      const on = choice.make.has(cell.id);
      return h('label', { style: { cursor: 'pointer' } },
        h('input', { type: 'checkbox', checked: on, onChange: () => toggle('make', cell.id), style: { marginRight: 6, verticalAlign: 'middle' } }),
        h('span', { style: tagStyle(on ? LOOK.accent : LOOK.dim) }, on ? 'will be made' : 'missing'),
        h('span', { style: { color: LOOK.dim, fontSize: 12 } }, cell.id));
    }
    return h('div', null, cell.items.map(item => h('div', { key: item.id, style: { marginBottom: 2 } },
      h('a', { href: '#', style: { color: LOOK.strong, marginRight: 6 }, onClick: e => { e.preventDefault(); api.session.openFile(item.rel); } }, item.id),
      item.isDefault ? h('span', { style: tagStyle(LOOK.accent) }, 'default') : null,
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
      h('h3', { style: { margin: '0 0 2px', color: LOOK.strong } }, 'Colours of ' + set.defaultId, ' ', set.gameDefault ? h('span', { style: tagStyle(LOOK.accent) }, 'a piece of the game') : null),
      h('div', { style: { color: LOOK.dim, fontSize: 12 } }, 'Family ' + set.pieceFamily + (set.familyFileMissing ? ' (its file will be made)' : '')
        + ', ' + plural(set.sheets.length, 'colour', 'colours') + ', ' + plural(set.shapes.length, 'shape', 'shapes') + '.'),
      set.gameDefault ? h('div', { style: { color: LOOK.dim, fontSize: 12 } }, 'Its colours are pieces of the mod that name it as their Parent. A small patch puts the game\'s own piece in their family, so the Alteration Table takes it too.') : null),

    h('div', { style: cardStyle({ position: 'sticky', top: 0, zIndex: 1, display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8 }) },
      h('div', { style: { flex: '1 1 220px', color: LOOK.strong, fontWeight: 600 } }, summary),
      missing.length ? h('button', { className: 'btn slim', onClick: () => tickAllMissing(true), disabled: busy }, 'Tick every missing colour') : null,
      missing.length ? h('button', { className: 'btn slim', onClick: () => tickAllMissing(false), disabled: busy }, 'Untick them') : null,
      h('button', { className: 'btn primary', style: { fontWeight: 600 }, onClick: press, disabled: busy || !action },
        busy ? 'Working...' : action ? action.charAt(0).toUpperCase() + action.slice(1) : 'Nothing to do')),

    notes.size ? h('div', { style: cardStyle({ borderColor: tint(LOOK.warn, 50) }) },
      h('div', { style: { fontWeight: 600, color: LOOK.warn, marginBottom: 4 } }, 'Worth knowing'),
      [...notes.values()].map(n => h('div', { key: n.kind + n.text, style: { marginBottom: 4 } }, n.text,
        h('span', { style: { color: LOOK.dim, fontSize: 12 } }, ' (' + names(n.items) + ')')))) : null,

    fixes.size ? h('div', { style: cardStyle() },
      h('div', { style: { fontWeight: 600, color: LOOK.strong, marginBottom: 4 } }, 'What can be fixed. Ticked fixes are made when you press the button.'),
      [...fixes.values()].map(g => {
        const all = g.keys.every(k => choice.fix.has(k));
        const some = !all && g.keys.some(k => choice.fix.has(k));
        return h('label', { key: g.kind + g.text, style: { display: 'block', cursor: 'pointer', marginBottom: 3 } },
          h('input', { type: 'checkbox', checked: all, ref: el => { if (el) el.indeterminate = some; }, onChange: () => toggleAll(g.keys, !all), style: { marginRight: 6, verticalAlign: 'middle' } }),
          h('span', { style: tagStyle(LOOK.warn) }, plural(g.items.length, 'piece', 'pieces')),
          g.text,
          h('span', { style: { color: LOOK.dim, fontSize: 12 } }, ' (' + names(g.items) + ')'));
      })) : null,

    h('div', { style: cardStyle({ padding: 0, overflow: 'auto' }) },
      h('table', { style: { borderCollapse: 'collapse', width: '100%' } },
        h('thead', null, h('tr', null, th('Colour'), th('Light'), set.shapes.map(shape => th(stem(shape))))),
        h('tbody', null, set.sheets.map((sheet, i) => h('tr', { key: sheet, style: { borderTop: '1px solid ' + LOOK.border, background: i % 2 ? tint(LOOK.strong, 3) : 'transparent' } },
          h('td', { style: { padding: '6px 8px', whiteSpace: 'nowrap', color: LOOK.strong, verticalAlign: 'top' } }, stem(sheet)),
          h('td', { style: { padding: '5px 8px', verticalAlign: 'top' } }, h('input', {
            className: 'tree-search', style: { width: 96, padding: '2px 8px', fontSize: 12 }, value: (lightOf(sheet) && lightOf(sheet).Color) || '', placeholder: 'default',
            title: 'The colour of the light this version gives off, such as #300. Empty: the default piece\'s light.', onChange: e => setLight(sheet, e.target.value),
          })),
          set.shapes.map(shape => h('td', { key: shape, style: { padding: '6px 8px', verticalAlign: 'top' } }, cellView(cells.find(c => c.sheet === sheet && c.shape === shape))))))))),

    h('div', { style: cardStyle({ fontSize: 12, color: LOOK.dim }) },
      'Colour pictures are read from ', set.sheetFolders.map((dir, i) => h('span', { key: dir }, i ? ', ' : '', h('code', null, dir))), '.',
      h('div', { style: { display: 'flex', gap: 6, marginTop: 6, flexWrap: 'wrap' } },
        h('input', { className: 'tree-search', style: { flex: '1 1 260px' }, value: typedFolder, placeholder: 'Another folder of colour pictures, such as Items/Weapons/CobaltLongSwordRecolors', onChange: e => setTypedFolder(e.target.value) }),
        h('button', { className: 'btn slim', onClick: () => setSheetFolder(typedFolder), disabled: busy || !typedFolder.trim() }, 'Look there'))),
    status ? h('p', null, status) : null,
    report && report.failed.length ? h('div', { style: cardStyle({ borderColor: tint(LOOK.danger, 50) }) }, h('div', { style: { color: LOOK.danger, fontWeight: 600 } }, 'These did not work:'),
      h('ul', { style: { margin: '4px 0 0', paddingLeft: 18 } }, report.failed.map(f => h('li', { key: f }, f)))) : null);
}

// ---- files made by a tool --------------------------------------------------------------------

// Files the mod's own tools write whole from a list of their own: the chest loot tables and the
// patches that add them to vanilla chests, and the showcase world. An edit made in one of these
// is erased the next time its tool runs, so HyCreator opens them on a warning first. The rule
// follows the folders those tools own; a new generated folder belongs here too.
export function madeByTool(rel) {
  const path = rel.replace(/^\.\.\//, '');
  if (/(^|[/_])(Armory_Showcase|Showcase_Theatre)([_./]|$)/.test(path)) return 'the showcase world';
  if (path.startsWith('Drops/TheArmory/')) return 'the chest loot list';
  if (/^Drops\/Prefabs\/[^/]+\.patch$/.test(path)) return 'the chest loot list';
  return null;
}

function MadeByToolPage({ rel }) {
  const h = host.React.createElement;
  return h('div', { style: { padding: 16, maxWidth: 640 } },
    h('h3', null, 'This file is made by a tool'),
    h('p', null, 'It is written whole from ' + madeByTool(rel) + '. A change made here is erased the next time that tool runs.'),
    h('p', null, 'If something in it should change, tell Larsonix what and why. The other pages above still show the file.'));
}

// ---- the checks -------------------------------------------------------------------------------

// Git runs the mod's checks after every commit, pull and branch switch (Studio/Tools/Checks), and
// they write their result beside themselves. This panel shows the latest one, and a notice says so
// when a new result arrives with something wrong. The files sit outside the mod's Server and
// Common folders, which is all the plugin contract can read, so they are read through the app's
// own file access, the same route HyCreator uses for its own files.
export const CHECK_RESULT = 'Studio/Tools/Checks/last-check.json';
export const CHECK_RUNNING = 'Studio/Tools/Checks/last-check.running.json';

// What the panel shows, from the result and the running marker, as plain data.
export function checkView(result, running) {
  if (!result || typeof result !== 'object') {
    return { state: running ? 'running' : 'none', checks: [], problems: 0 };
  }
  const checks = (Array.isArray(result.checks) ? result.checks : []).map(c => ({
    id: c.id, title: c.title, meaning: c.meaning, status: c.status,
    problems: (c.problems || []).map(text => ({ text, open: fileIn(text) })),
    tail: c.status === 'error' ? (c.output_tail || []) : [],
  }));
  const problems = checks.reduce((n, c) => n + (c.status === 'pass' ? 0 : Math.max(c.problems.length, 1)), 0);
  const state = result.error ? 'error' : problems ? 'problems' : 'pass';
  // A marker newer than the result means a later run is still going.
  const busy = !!running && (!result.finished || String(running.started || '') > String(result.finished));
  return {
    state, busy, checks, problems, error: result.error || null,
    finished: result.finished || null, commit: typeof result.commit === 'string' ? result.commit.slice(0, 7) : null,
    branch: result.branch || null, uncommitted: !!result.uncommitted,
  };
}

// The item file a problem names, as HyCreator opens it: relative to the Server folder.
export function fileIn(text) {
  const m = /^Server\/([^:]+\.json):/.exec(text);
  return m ? m[1] : null;
}

// The notice for a new result, or null when there is nothing to say. Only a change is announced:
// a pass after something else, or a list of problems that differs from the last one, so a pull
// that leaves the same problem in place does not repeat it.
export function noticeFor(previous, next) {
  if (!next || !next.finished || (previous && previous.finished === next.finished)) return null;
  const view = checkView(next, null);
  const before = previous ? checkView(previous, null) : null;
  if (view.state === 'error') {
    return before && before.state === 'error' && before.error === view.error ? null : 'The Armory checks could not run. Open The Armory checks panel to see why.';
  }
  if (view.state === 'problems') {
    if (before && before.state === 'problems' && problemList(before) === problemList(view)) return null;
    return 'The Armory checks found ' + view.problems + ' problem' + (view.problems === 1 ? '' : 's') + '. Open The Armory checks panel to see them.';
  }
  return before && before.state !== 'pass' ? '\u2713 The Armory checks pass again.' : null;
}

function problemList(view) {
  return JSON.stringify(view.checks.filter(c => c.status !== 'pass').map(c => [c.id, c.status, c.problems.map(p => p.text)]));
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

async function readJson(api, rel) {
  const r = await readRepoText(api, rel);
  if (r.text === undefined) return r;
  try { return { value: JSON.parse(r.text) }; } catch { return { missing: true }; }
}

async function hasStudio(api) {
  const r = await readRepoText(api, 'Studio/Tools/Checks/check.py');
  return r.text !== undefined ? true : r.unreadable ? null : false;
}

// A line of a check, read as what it is about and what it says. The gates print one line per
// item, so one sentence can repeat for dozens of items.
export function readProblem(text) {
  const line = String(text);
  let subject = null;
  let message = line;
  let m = /^Server\/([^:]+\.json):\s*([\s\S]*)$/.exec(line);
  if (m) {
    subject = { kind: 'item', label: stem(m[1]), open: m[1] };
    message = m[2];
  } else if ((m = /^item (\S+):\s*([\s\S]*)$/.exec(line))) {
    subject = { kind: 'item', label: m[1] };
    message = m[2];
  } else if ((m = /^(Resource_\S+):\s*([\s\S]*)$/.exec(line))) {
    subject = { kind: 'family', label: m[1] };
    message = m[2];
  } else if ((m = /^stat (\S+)\s+([\s\S]*)$/.exec(line))) {
    subject = { kind: 'stat', label: m[1] };
    message = m[2];
  }
  // A gate's own name for its rule, such as REACHABILITY, means nothing to the reader.
  message = message.replace(/^[A-Z][A-Z ]+:\s*/, '');
  // The list after the sentence belongs to its one subject, not to the group.
  let detail = '';
  const cut = /\s+(owning items:|profile \d+:)/.exec(message);
  if (cut) {
    detail = message.slice(cut.index).trim();
    message = message.slice(0, cut.index);
  }
  message = message.trim();
  return { subject, message: message.charAt(0).toUpperCase() + message.slice(1), detail };
}

// The lines of one check grouped by what they say, the largest group first.
export function problemGroups(lines) {
  const groups = new Map();
  for (const line of lines) {
    const p = readProblem(line);
    const key = (p.subject ? p.subject.kind : '') + '|' + p.message;
    if (!groups.has(key)) groups.set(key, { key, kind: p.subject ? p.subject.kind : null, message: p.message, subjects: [], details: [] });
    const g = groups.get(key);
    if (p.subject) g.subjects.push({ ...p.subject, detail: p.detail });
    else if (p.detail) g.details.push(p.detail);
  }
  return [...groups.values()].sort((a, b) => Math.max(b.subjects.length, 1) - Math.max(a.subjects.length, 1));
}

const SUBJECT_WORDS = { item: ['item', 'items'], family: ['family', 'families'], stat: ['stat', 'stats'] };

function ChecksPanel() {
  const api = host;
  const h = api.React.createElement;
  const { useState, useEffect } = api.React;
  const [data, setData] = useState({ loading: true });
  const [opened, setOpened] = useState({});
  useEffect(() => {
    let alive = true;
    const load = async () => {
      const [result, running, studio] = await Promise.all([readJson(api, CHECK_RESULT), readJson(api, CHECK_RUNNING), hasStudio(api)]);
      if (alive) setData({ result: result.value || null, running: running.value || null, studio, unreadable: !!result.unreadable });
    };
    load();
    const timer = setInterval(load, 4000);
    return () => { alive = false; clearInterval(timer); };
  }, []);

  // An item or a family named by its id alone is found among the mod's files when clicked.
  const openSubject = async s => {
    if (s.open) return api.session.openFile(s.open);
    const folder = s.kind === 'family' ? 'Item/ResourceTypes/' : 'Item/Items/';
    const rel = (await api.workspace.listByExt(folder, ['json'])).find(r => stem(r) === s.label);
    if (rel) api.session.openFile(rel);
    else api.ui.notify(s.label + ' is not a file of the mod: it is one of the game\'s.');
  };
  const when = text => {
    const date = new Date(text);
    return isNaN(date) ? String(text || '') : date.toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  };

  const box = children => h('div', { style: { padding: 12, overflow: 'auto', height: '100%', lineHeight: 1.45, color: LOOK.text } }, children);
  const foot = h('p', { key: 'f', style: { fontSize: 12, color: LOOK.dim } },
    'The checks run by themselves after every commit and every update. Nothing is ever blocked: you can always push. To run them now, double-click Studio\\Check.cmd.');
  if (data.loading) return box([h('p', { key: 'l' }, 'Reading the last result...')]);
  if (data.unreadable) return box([h('p', { key: 'u' }, 'This HyCreator version does not let the plugin read the result. Double-click Studio\\Check.cmd to see it.'), foot]);
  if (data.studio === false) return box([h('p', { key: 's' }, 'Open the whole folder of your copy of the mod in HyCreator, the one holding Studio, to see the checks.')]);
  const view = checkView(data.result, data.running);
  if (view.state === 'none' || view.state === 'running') {
    return box([h('p', { key: 'n' }, view.state === 'running' ? 'The first check is running...' : 'No check has run yet. They run after your next commit, or double-click Studio\\Check.cmd.'), foot]);
  }

  const failing = view.checks.filter(c => c.status !== 'pass');
  const passing = view.checks.filter(c => c.status === 'pass');
  const headline = view.state === 'pass' ? '✓ Everything passes'
    : view.state === 'error' ? 'The checks could not run'
    : '✗ ' + view.problems + ' to look at, in ' + plural(failing.length, 'check', 'checks');
  const items = [h('div', { key: 'head', style: cardStyle() },
    h('div', { style: { fontSize: 15, fontWeight: 600, color: view.state === 'pass' ? LOOK.ok : LOOK.danger } }, headline),
    h('div', { style: { fontSize: 12, color: LOOK.dim } }, 'Checked ' + when(view.finished) + ', branch ' + (view.branch || '?') + ', commit ' + (view.commit || '?')
      + (view.uncommitted ? ', with uncommitted changes' : '') + '.'),
    view.busy ? h('div', { style: { fontSize: 12, color: LOOK.accent, fontStyle: 'italic' } }, 'Checking your latest commit...') : null,
    view.error ? h('div', { style: { color: LOOK.danger } }, view.error) : null)];

  for (const c of failing) {
    const groups = problemGroups(c.problems.map(p => p.text));
    items.push(h('div', { key: c.id, style: cardStyle({ borderColor: tint(LOOK.danger, 45) }) },
      h('div', { style: { fontWeight: 600, color: LOOK.strong } }, c.title),
      h('div', { style: { fontSize: 12, color: LOOK.dim, marginBottom: 4 } },
        c.status === 'error' ? 'This check could not run, so it says nothing about your work. Tell Larsonix.' : c.meaning),
      groups.map(g => {
        const words = SUBJECT_WORDS[g.kind];
        const all = opened[c.id + g.key];
        const shown = all ? g.subjects : g.subjects.slice(0, 12);
        return h('div', { key: g.key, style: { marginTop: 8 } },
          h('div', null, words ? h('span', { style: tagStyle(LOOK.warn) }, plural(g.subjects.length, words[0], words[1])) : null, g.message),
          shown.length ? h('div', { style: { display: 'flex', flexWrap: 'wrap', gap: 4, marginTop: 4 } },
            shown.map((s, i) => s.kind === 'stat'
              ? h('span', { key: i, style: tagStyle(LOOK.dim) }, s.label)
              : h('button', { key: i, className: 'btn slim', style: { padding: '0 8px', fontSize: 11.5 }, title: s.detail || ('Open ' + s.label), onClick: () => openSubject(s) }, s.label)),
            g.subjects.length > 12 ? h('button', { className: 'btn slim', style: { padding: '0 8px', fontSize: 11.5, color: LOOK.accent }, onClick: () => setOpened({ ...opened, [c.id + g.key]: !all }) },
              all ? 'Show fewer' : '+' + (g.subjects.length - 12) + ' more') : null) : null,
          g.details.map((d, i) => h('div', { key: i, style: { fontSize: 11.5, color: LOOK.dim, wordBreak: 'break-word' } }, d)));
      }),
      c.tail.length ? h('pre', { style: { fontSize: 11, whiteSpace: 'pre-wrap', color: LOOK.dim } }, c.tail.join('\n')) : null));
  }
  if (passing.length) {
    items.push(h('div', { key: 'pass', style: cardStyle() },
      h('div', { style: { fontWeight: 600, color: LOOK.ok } }, '✓ ' + plural(passing.length, 'check passes', 'checks pass')),
      passing.map(c => h('div', { key: c.id, style: { fontSize: 12, color: LOOK.dim } }, c.title))));
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
      const text = noticeFor(last, r.value);
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
  { title: 'Your tools', steps: [
    'Blockbench, with the Hytale plugin: models, the texture layout, painting.',
    'Photoshop: patterns and textures.',
    'HyCreator, with this plugin: every item, its colours and icons, and sending your work.',
    'The game, in a solo world: how it looks when worn.',
    'Nothing else. The asset editor in the game is no longer needed.',
  ] },
  { title: 'Set up, once', steps: [
    'Install Git for Windows from https://git-scm.com/download/win. Keep its default choices.',
    'In HyCreator, use Clone repository... with https://github.com/azurefoxx98/The-Armory-Mod, into any folder you like.',
    'Switch to your own branch: in the git menu at the top, Branches..., then lady. All your work goes there, and we bring it into the mod.',
    'In the cloned folder, double-click Studio\\Setup.cmd. It sets old copies of The Armory aside, links the game to your copy, installs this plugin and installs our checks. Restart HyCreator afterwards.',
    'The first time you push, a GitHub sign-in window opens. Sign in once and it is remembered.',
    'Setup prints any step left for Blockbench. Do it once.',
  ] },
  { title: 'Each time you work', steps: [
    'Open your copy of the mod in HyCreator.',
    'Before you start: Commit changes... if anything is left, then double-click Studio\\Update.cmd. It brings in our latest fixes and code beside your work, and sends both to GitHub.',
    'After an update, restart your world, so the game runs the newest code.',
    'When you finish: Commit changes..., write one line saying what you did, then Push.',
    'If Update, Pull or Push says your branch has diverged, run Update. If Update says your work and ours changed the same lines, it has changed nothing: tell Larsonix which files and keep working.',
  ] },
  { title: 'Make a release', steps: [
    'Commit your work in HyCreator first, then double-click Studio\\Release.cmd.',
    'Type the version, or press Enter for the one it suggests. Write the changelog for players in the window that opens. The list beside it is only a reminder of what changed.',
    'Choose 1 to save the release as a draft nobody sees yet, 2 to save it as a draft with a message for us (an effect you want added, anything), or 3 to publish it to players at once.',
    'A publish waits when our checks find a problem, when the branch holds work not to be released yet, or when our code changed since the last release. It says which, and offers to save a draft with a message for us instead.',
    'The first release downloads Java and two picture libraries once. Every release builds the mod, shrinks its textures without changing a pixel anyone sees, and checks the jar on GitHub is the one built.',
  ] },
  { title: 'Our checks', steps: [
    'After every commit and every update, our checks look at your work by themselves, in the background. You never wait for them.',
    'When they find something, HyCreator shows a notice. Open The Armory checks panel with the ! button at the top to see which item and what is wrong. Click a file name to open it.',
    'Fix what you can, then commit again. If you are not sure, push anyway and tell Larsonix. Nothing is ever blocked.',
    'To run them any time, double-click Studio\\Check.cmd.',
  ] },
  { title: 'Where your files go', steps: [
    'Everything the game uses stays in src/main/resources, as today.',
    'Photoshop files, helper textures and whole-set Blockbench files go in Studio/Work. They are shared with us but never go into the mod.',
    'HyCreator keeps a .bak copy of every file it saves. Git ignores them. You can leave them.',
  ] },
  { title: 'Make the model', steps: [
    'Model the whole set as one Blockbench file on the player model, which shows the scale.',
    'Turn on Copy-Paste with Magenta Alpha in the Hytale plugin settings, so transparency survives the trip to Photoshop.',
    'Make the texture layout tight while the set is still one file: Resize UV Canvas cuts the empty canvas without moving the paint. Every piece of a set shares its colour sheets, so do it before the split.',
    'Then save each group as its own piece: File, Export, Save Each Group as a Piece. Untick the groups that are not a piece, name each file, pick the folder.',
    'A group hidden in Blockbench would be hidden in the game too. The dialog offers to show its parts in the piece. Leave that ticked unless a part should stay hidden.',
    'The first time, Blockbench asks whether The Armory may use files. Choose Always allow for this plugin.',
  ] },
  { title: 'Make the default piece', steps: [
    'Make one item for each piece in HyCreator: stats, quality, recipe and name.',
    "Give it two categories: the Armory's and the game's own, Items.Armors for armour, Items.Weapons for weapons and shields. Other mods find gear by the game's one.",
    'Watch the Problems panel while you type. It names a wrong value before the game sees it.',
    'Write the name in English in HyCreator\'s language editor. Coralys does the translations.',
  ] },
  { title: 'Make the colours', steps: [
    'Put one colour sheet per colour next to the model, and each alternate shape next to it named after the model, such as Chest_Alt.blockymodel beside Chest.blockymodel.',
    'Open the default piece and click Colours above the editor.',
    'Tick the versions you want, type a light colour on a row if that colour glows, and press the button.',
    'It makes each version, its Alteration Table recipe, its family and its icon. Pressing it again only fixes what is missing or wrong.',
    'A version takes its stats from the default piece. Change stats on the default piece only.',
    'Colours of a piece of the game, such as the Thorium chest: put the colour sheets in Common/Items/Armors/VanillaRecolors/Thorium/, named like Chest_Red.png, then open the game piece in HyCreator\'s vanilla list, or one of its colours, and click Colours. Never copy the game piece to start: the page builds every colour on it.',
    'For a game weapon, type the folder of its colour sheets in the box on the page and press Look there.',
  ] },
  { title: 'Icons', steps: [
    'The Colours page makes the icons of the versions it makes.',
    'For one item, use Generate icon on its page.',
    'Never use the batch icon baker: it rewrites every icon in the mod.',
  ] },
  { title: 'Look at it in the game', steps: [
    'Start a solo world with The Armory turned on in its mods list.',
    'A file you save in HyCreator is reloaded by the running world. If a piece still looks unchanged, take it off and put it on again, or rejoin the world.',
  ] },
  { title: 'Effects and powers', steps: [
    'Item files alone can give resistance to any kind of damage, extra breath, and a shield\'s reaction when it blocks.',
    'The Armory adds stats you can put on armour. Speed and bonus stats are multipliers, written Multiplicative with the bonus as a fraction: 0.5 is 50 % more. They are Move_Speed, Jump_Height, Arrow_Damage_Bonus, TA_Swim_Speed, TA_Fire_Resistance, TA_Blast_Resistance, TA_Mining_Speed, TA_Ore_Find and TA_Unarmed_Damage.',
    'TA_Night_Mana and TA_Night_Health are amounts, written Additive with the number: 50 is 50 more at night.',
    'Anything else needs our code. Tell Larsonix what it should do.',
    'Use only particle and sound names HyCreator suggests. A name that does not exist shows nothing, and nothing warns you.',
    'Effectly tags are no longer used.',
  ] },
  { title: 'Files made by a tool', steps: [
    'The chest loot tables and the showcase world are made by our tools from lists of their own. A change made in one of those files is erased the next time the tool runs.',
    'HyCreator opens such a file on a warning first. If something in it should change, tell Larsonix.',
  ] },
  { title: 'When something goes wrong', steps: [
    'Tell Larsonix what you did and what you saw. A picture helps.',
  ] },
];

export function guideMarkdown() {
  const lines = ['# The Armory: how to work on the mod', '',
    'This is the same guide as the panel in HyCreator. Edit it in Studio/Tools/HyCreator/armory-studio.js, never here.', ''];
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
  version: '0.3.0',
  minApi: 2,
  author: 'The Armory',
  description: 'Colour versions made and checked from one page, the working guide, and what the checks of the mod found.',
  activate(api) {
    host = api;
    unregister.push(api.editors.registerPage({
      id: 'plugin:armory.madebytool',
      label: 'Made by a tool',
      title: 'This file is written by one of the mod\'s tools',
      matches: rel => madeByTool(rel) !== null,
      defaultFor: rel => madeByTool(rel) !== null,
      component: MadeByToolPage,
    }));
    unregister.push(api.editors.registerPage({
      id: 'plugin:armory.colours',
      label: 'Colours',
      title: 'Every colour version of this piece, made and checked in one place',
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
    // file is the one shown at that moment.
    api.modules.store().then(store => {
      const state = [store.useStudio, store.u].find(s => s && typeof s.getState === 'function');
      if (!state) return;
      const warned = new Set();
      unregister.push(api.session.subscribeDocs(() => {
        const rel = state.getState().activeRel;
        const tool = typeof rel === 'string' ? madeByTool(rel) : null;
        if (!tool || warned.has(rel)) return;
        warned.add(rel);
        api.ui.notify(fileName(rel) + ' is made by a tool from ' + tool + '. A change here is erased the next time the tool runs. Tell Larsonix what should change.');
      }));
    }).catch(() => { /* the page warning still stands */ });
  },
  deactivate() {
    for (const off of unregister) { try { off(); } catch { /* already gone */ } }
    unregister = [];
  },
};
