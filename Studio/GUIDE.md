# The Armory: how to work on the mod

This is the same guide as the panel in HyCreator. Edit it in Studio/Tools/HyCreator/armory-studio.js, never here.

## Your tools

- Blockbench, with the Hytale plugin: models, the texture layout, painting.
- Photoshop: patterns and textures.
- HyCreator, with this plugin: every item, its colours and icons, and sending your work.
- The game, in a solo world: how it looks when worn.
- Nothing else. The asset editor in the game is no longer needed.

## Set up, once

- Install Git for Windows from https://git-scm.com/download/win. Keep its default choices.
- In HyCreator, use Clone repository... with https://github.com/azurefoxx98/The-Armory-Mod, into any folder you like.
- Switch to your own branch: in the git menu at the top, Branches..., then lady. All your work goes there, and we bring it into the mod.
- In the cloned folder, double-click Studio\Setup.cmd. It sets old copies of The Armory aside, links the game to your copy, installs this plugin and installs our checks. Restart HyCreator afterwards.
- The first time you push, a GitHub sign-in window opens. Sign in once and it is remembered.
- Setup prints any step left for Blockbench. Do it once.

## Each time you work

- Open your copy of the mod in HyCreator.
- Before you start: Commit changes... if anything is left, then double-click Studio\Update.cmd. It brings in our latest fixes and code beside your work, and sends both to GitHub.
- After an update, restart your world, so the game runs the newest code.
- When you finish: Commit changes..., write one line saying what you did, then Push.
- If Update, Pull or Push says your branch has diverged, run Update. If Update says your work and ours changed the same lines, it has changed nothing: tell Larsonix which files and keep working.

## Make a release

- Commit your work in HyCreator first, then double-click Studio\Release.cmd.
- Type the version, or press Enter for the one it suggests. Write the changelog for players in the window that opens. The list beside it is only a reminder of what changed.
- Choose 1 to save the release as a draft nobody sees yet, 2 to save it as a draft with a message for us (an effect you want added, anything), or 3 to publish it to players at once.
- A publish waits when our checks find a problem, when the branch holds work not to be released yet, or when our code changed since the last release. It says which, and offers to save a draft with a message for us instead.
- The first release downloads Java and two picture libraries once. Every release builds the mod, shrinks its textures without changing a pixel anyone sees, and checks the jar on GitHub is the one built.

## Our checks

- After every commit and every update, our checks look at your work by themselves, in the background. You never wait for them.
- When they find something, HyCreator shows a notice. Open The Armory checks panel with the ! button at the top to see which item and what is wrong. Click a file name to open it.
- Fix what you can, then commit again. If you are not sure, push anyway and tell Larsonix. Nothing is ever blocked.
- To run them any time, double-click Studio\Check.cmd.

## Where your files go

- Everything the game uses stays in src/main/resources, as today.
- Photoshop files, helper textures and whole-set Blockbench files go in Studio/Work. They are shared with us but never go into the mod.
- HyCreator keeps a .bak copy of every file it saves. Git ignores them. You can leave them.

## Make the model

- Model the whole set as one Blockbench file on the player model, which shows the scale.
- Turn on Copy-Paste with Magenta Alpha in the Hytale plugin settings, so transparency survives the trip to Photoshop.
- Make the texture layout tight while the set is still one file: Resize UV Canvas cuts the empty canvas without moving the paint. Every piece of a set shares its colour sheets, so do it before the split.
- Then save each group as its own piece: File, Export, Save Each Group as a Piece. Untick the groups that are not a piece, name each file, pick the folder.
- A group hidden in Blockbench would be hidden in the game too. The dialog offers to show its parts in the piece. Leave that ticked unless a part should stay hidden.
- The first time, Blockbench asks whether The Armory may use files. Choose Always allow for this plugin.

## Make the default piece

- Make one item for each piece in HyCreator: stats, quality, recipe and name.
- Give it two categories: the Armory's and the game's own, Items.Armors for armour, Items.Weapons for weapons and shields. Other mods find gear by the game's one.
- Watch the Problems panel while you type. It names a wrong value before the game sees it.
- Write the name in English in HyCreator's language editor. Coralys does the translations.

## Make the colours

- Put one colour sheet per colour next to the model, and each alternate shape next to it named after the model, such as Chest_Alt.blockymodel beside Chest.blockymodel.
- Open the default piece and click Colours above the editor.
- Tick the versions you want, type a light colour on a row if that colour glows, and press the button.
- It makes each version, its Alteration Table recipe, its family and its icon. Pressing it again only fixes what is missing or wrong.
- A version takes its stats from the default piece. Change stats on the default piece only.
- Colours of a piece of the game, such as the Thorium chest: put the colour sheets in Common/Items/Armors/VanillaRecolors/Thorium/, named like Chest_Red.png, then open the game piece in HyCreator's vanilla list, or one of its colours, and click Colours. Never copy the game piece to start: the page builds every colour on it.
- For a game weapon, type the folder of its colour sheets in the box on the page and press Look there.

## Icons

- The Colours page makes the icons of the versions it makes.
- For one item, use Generate icon on its page.
- Never use the batch icon baker: it rewrites every icon in the mod.

## Look at it in the game

- Start a solo world with The Armory turned on in its mods list.
- A file you save in HyCreator is reloaded by the running world. If a piece still looks unchanged, take it off and put it on again, or rejoin the world.

## Effects and powers

- Item files alone can give resistance to any kind of damage, extra breath, and a shield's reaction when it blocks.
- The Armory adds stats you can put on armour. Speed and bonus stats are multipliers, written Multiplicative with the bonus as a fraction: 0.5 is 50 % more. They are Move_Speed, Jump_Height, Arrow_Damage_Bonus, TA_Swim_Speed, TA_Fire_Resistance, TA_Blast_Resistance, TA_Mining_Speed, TA_Ore_Find and TA_Unarmed_Damage.
- TA_Night_Mana and TA_Night_Health are amounts, written Additive with the number: 50 is 50 more at night.
- Anything else needs our code. Tell Larsonix what it should do.
- Use only particle and sound names HyCreator suggests. A name that does not exist shows nothing, and nothing warns you.
- Effectly tags are no longer used.

## Files made by a tool

- The chest loot tables and the showcase world are made by our tools from lists of their own. A change made in one of those files is erased the next time the tool runs.
- HyCreator opens such a file on a warning first. If something in it should change, tell Larsonix.

## When something goes wrong

- Tell Larsonix what you did and what you saw. A picture helps.
