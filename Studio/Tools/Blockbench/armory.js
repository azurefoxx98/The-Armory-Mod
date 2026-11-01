// The Armory, a Blockbench plugin for working on The Armory's armour.
//
// Save Each Group as a Model: a whole set is modelled as one file (Lady calls it the Full model),
// then saved as one .blockymodel per item (helmet, chest, gauntlets, leggings). Each top group of
// the model becomes its own file, written by the official Hytale plugin's own export, so each is
// exactly the file that exporting that group alone would give. Nothing in a group is renamed or moved.
//
// It needs the official Hytale plugin, whose export it uses.
(function () {
  let action = null;

  // The piece a group most likely is, from the player bones it covers. Only a starting name.
  function guessName(node) {
    const names = [];
    (function walk(n) { names.push(n.name); (n.children || []).forEach(walk); })(node);
    const has = re => names.some(n => re.test(n));
    if (has(/^Head$/) && !has(/Pelvis|Chest/)) return 'Helmet';
    if (has(/Forearm|Hand/) && !has(/Thigh|Calf|Foot/)) return 'Gauntlets';
    if (has(/Thigh|Calf|Foot/) && !has(/Forearm|Hand/)) return 'Leggings';
    if (has(/Chest|Belly/)) return 'Chest';
    return node.name;
  }

  function countShapes(node) {
    let count = node.shape && node.shape.type && node.shape.type !== 'none' ? 1 : 0;
    for (const child of node.children || []) count += countShapes(child);
    return count;
  }

  // The official export of one top group alone: every other top group is left out of the export
  // for the call and put back after, so the piece is exactly what that plugin writes, ids and
  // layout included, and follows it when it changes.
  //
  // A part hidden in Blockbench is saved hidden, and the game honours that (its own models hide
  // parts on purpose). Hiding a group to work on another is common, so with showHidden the
  // group's hidden parts are shown for the export only and hidden again after.
  function exportAlone(roots, keep, showHidden) {
    const saved = roots.map(r => r.export);
    const hidden = showHidden ? elementsOf(keep).filter(e => e.visibility === false) : [];
    try {
      roots.forEach(r => { r.export = r === keep; });
      hidden.forEach(e => { e.visibility = true; });
      return Codecs.blockymodel.compile();
    } finally {
      roots.forEach((r, i) => { r.export = saved[i]; });
      hidden.forEach(e => { e.visibility = false; });
    }
  }

  function elementsOf(root) {
    const all = [root];
    for (let i = 0; i < all.length; i++) all.push(...(all[i].children || []));
    return all;
  }

  function hiddenShapes(node) {
    let count = node.shape && node.shape.visible === false ? 1 : 0;
    for (const child of node.children || []) count += hiddenShapes(child);
    return count;
  }

  function folderOf(path) {
    const at = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return at > 0 ? path.slice(0, at) : '';
  }

  function savePieces() {
    const codec = Codecs.blockymodel;
    if (!codec) {
      Blockbench.showMessageBox({ title: 'The Armory', message: 'This needs the Hytale plugin. Install it from File > Plugins..., then try again.' });
      return;
    }
    const model = codec.compile({ raw: true });
    // The export's top nodes, in its own order, matched to the model's top groups.
    const roots = Outliner.root.filter(n => (n instanceof Group || n instanceof Cube) && n.export);
    if (roots.length !== model.nodes.length) {
      Blockbench.showMessageBox({ title: 'The Armory', message: 'The groups of this model could not be read, so nothing was saved. Close this model, open it again, then try again.' });
      return;
    }
    if (!model.nodes.length) {
      Blockbench.showMessageBox({ title: 'The Armory', message: 'This model has no groups to save.' });
      return;
    }
    // One row per top group, so a whole set fits in one window without scrolling: a long label
    // per field would widen the form's label column and squeeze every input.
    const rows = model.nodes.map(node => ({ group: node.name, boxes: countShapes(node), hidden: hiddenShapes(node), take: true, name: guessName(node), show: true }));
    const anyHidden = rows.some(r => r.hidden);
    const dialog = new Dialog({
      id: 'armory_save_pieces',
      title: 'Save Each Group as a Model',
      width: 680,
      form_first: true,
      form: {
        folder: { label: 'Save in', type: 'folder', value: Project.export_path ? folderOf(Project.export_path) : '' },
      },
      component: {
        data: () => ({ rows, anyHidden }),
        template: `<div style="padding: 4px 0 0">
          <p style="margin: 0 0 8px">Each ticked group is saved as its own model.<span v-if="anyHidden"> Cubes you hid here would stay hidden in Hytale, so they are shown again unless you untick show.</span></p>
          <table style="width: 100%; border-collapse: collapse">
            <tr style="text-align: left; opacity: 0.7">
              <th style="padding: 4px; width: 40px">Save</th><th style="padding: 4px">Group</th><th style="padding: 4px; width: 60px">Cubes</th>
              <th style="padding: 4px; width: 200px">File name</th><th v-if="anyHidden" style="padding: 4px; width: 150px">Hidden cubes</th>
            </tr>
            <tr v-for="(row, i) in rows" :key="i" style="border-top: 1px solid var(--color-border)">
              <td style="padding: 4px"><input type="checkbox" v-model="row.take"></td>
              <td style="padding: 4px; overflow-wrap: anywhere">{{ row.group }}</td>
              <td style="padding: 4px">{{ row.boxes }}</td>
              <td style="padding: 4px"><input type="text" class="dark_bordered" style="width: 100%; box-sizing: border-box" v-model="row.name" :disabled="!row.take"></td>
              <td v-if="anyHidden" style="padding: 4px"><label v-if="row.hidden"><input type="checkbox" v-model="row.show" :disabled="!row.take"> show {{ row.hidden }}</label></td>
            </tr>
          </table>
        </div>`,
      },
      onConfirm(result) {
        if (!result.folder) {
          Blockbench.showQuickMessage('Pick the folder to save in first');
          return false;
        }
        const pieces = [];
        roots.forEach((root, i) => {
          const row = rows[i];
          if (!row.take) return;
          const name = String(row.name || '').trim().replace(/\.blockymodel$/i, '');
          if (!name) return;
          pieces.push({ path: PathModule.join(result.folder, name + '.blockymodel'), content: exportAlone(roots, root, row.show && row.hidden > 0) });
        });
        const names = pieces.map(p => p.path);
        if (new Set(names).size !== names.length) {
          Blockbench.showMessageBox({ title: 'The Armory', message: 'Two groups have the same file name. Give each its own.' });
          return false;
        }
        // Blockbench hands a plugin the file system through its own permission check.
        const fs = requireNativeModule('fs', { message: 'The Armory needs this to save your models, and to ask before it replaces one.' });
        const existing = pieces.filter(p => fs.existsSync(p.path)).map(p => PathModule.basename(p.path));
        const write = () => {
          fs.mkdirSync(result.folder, { recursive: true });
          for (const p of pieces) Blockbench.writeFile(p.path, { content: p.content, savetype: 'text' });
          Blockbench.showQuickMessage('Saved ' + pieces.length + (pieces.length === 1 ? ' model' : ' models'));
        };
        if (existing.length) {
          Blockbench.showMessageBox({
            title: 'Replace them?',
            message: 'These models are already in that folder. Replace them? ' + existing.join(', '),
            buttons: ['Replace', 'Cancel'],
          }, button => { if (button === 0) write(); });
        } else {
          write();
        }
      },
    });
    dialog.show();
  }

  BBPlugin.register('armory', {
    title: 'The Armory',
    author: 'The Armory',
    description: 'Saves each group of a Full model as its own model.',
    icon: 'call_split',
    version: '0.3.0',
    min_version: '5.2.0',
    variant: 'desktop',
    onload() {
      action = new Action('armory_save_pieces', {
        name: 'Save Each Group as a Model',
        description: 'Save each group of this Full model as its own model: helmet, chest, gauntlets, leggings',
        icon: 'call_split',
        condition: () => !!Codecs.blockymodel && !!Format && /^hytale_/.test(Format.id),
        click: savePieces,
      });
      MenuBar.addAction(action, 'file.export');
    },
    onunload() {
      if (action) action.delete();
      action = null;
    },
  });
})();
