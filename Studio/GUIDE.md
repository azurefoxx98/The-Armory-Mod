<!-- Made from GUIDE in Studio/Tools/HyCreator/armory-studio.js. Edit it there, never here. -->

# The Armory guide

The same guide is in HyCreator: click ? at the top.

## Set up, once

- Install Git for Windows from https://git-scm.com/download/win, with its default choices.
- Put Get The Armory.cmd, the file Larsonix sends you, in the folder where you want the mod, such as Documents.
- Double-click it. Answer its question, then do the Blockbench step it shows.
- Restart HyCreator. Click Workspace at the top left, then Open mod folder..., and pick the The-Armory-Mod folder it made.
- The button at the top now says lady: your branch. All your work goes there.
- The first time you push, GitHub asks you to sign in. Sign in once.

## Each time you work

- Before you start, double-click Studio\Update.cmd. It brings in Larsonix's latest work and sends yours to GitHub.
- When you finish, click lady at the top, then Commit changes... Write what you did in one line, then click Commit.
- Then click lady at the top, then Push.
- If Pull or Push says your branch has diverged, double-click Update.cmd instead.
- If Update says your work and ours changed the same files, send Larsonix a screenshot and keep working.

## The checks

- The checks run by themselves each time you commit. When they find something, a notice appears.
- Click ! at the top to see what to fix. Click an item to open it.
- You can always push, even with things to fix.

## Where your files go

- Save models and textures from Blockbench and Photoshop in src\main\resources\Common, inside your copy, as before.
- Photoshop files, helper textures and Full models go in Studio\Work. Hytale never loads them.

## Split a Full model

- Open the Full model in Blockbench, then click File > Export > Save Each Group as a Model.
- Untick the groups that are not an item, check the file names and the folder, then click Confirm.
- A group you hid in Blockbench would stay hidden in Hytale too. The window shows it again unless you untick show.
- The first time, Blockbench asks whether The Armory may use files. Click Always allow for this plugin.

## Make the colors of an item

- Make the base item in HyCreator as usual: stats, quality, recipe and name.
- Armor takes the categories ArmoryCategory.ArmoryArmors and Items.Armors. Weapons and shields take Items.Weapons beside their Armory one.
- Put one texture per color beside the model, named for its color, such as Red.png.
- Put each alt style beside the model too, named after it, such as Chest_Open.blockymodel beside Chest.blockymodel.
- Open the base item and click Colors at the top of the editor.
- Tick the colors you want, then press the button. It makes each color, its Alteration Table recipe and its icon.
- If a color glows, type its light color in its Light box first, such as #300.
- Change stats on the base item only. Every color takes them from it.
- Pressing the button again only makes what is missing and fixes what is wrong.

## Colors of a vanilla item

- Put the textures in Common\Items\Armors\VanillaRecolors\Thorium for the Thorium armor, named like Chest_Red.png.
- Press Ctrl+K, type the vanilla item's name, such as Armor_Thorium_Chest, and open it. Then click Colors.
- Do not copy the vanilla item first. The Colors page builds every color on it.
- For a vanilla weapon, type the folder of its textures at the bottom of the Colors page, then click Look there.

## Icons

- The Colors page makes the icons of the colors it makes.
- For one item, click Generate icon on its page.
- Do not use Bake all icons (batch): it remakes every icon of the mod.

## See it in Hytale

- Start a world with The Armory turned on in its mods list.
- A file you save in HyCreator changes in the running world. If an item still looks the same, take it off and put it on again, or rejoin the world.

## Stats and effects

- Items alone can give resistance to any kind of damage, extra breath, and a shield's reaction when it blocks.
- The Armory adds Move_Speed, Jump_Height, Arrow_Damage_Bonus, TA_Swim_Speed, TA_Fire_Resistance, TA_Blast_Resistance, TA_Mining_Speed, TA_Ore_Find and TA_Unarmed_Damage.
- Write those as Multiplicative: 0.5 is 50% more.
- TA_Night_Mana and TA_Night_Health are Additive: 50 is 50 more at night.
- For anything else, ask Larsonix.
- For particles and sounds, use only the names HyCreator suggests.
- Effectly tags are no longer used.

## Make a release

- Commit your work first, then double-click Studio\Release.cmd.
- Type the version, or press Enter for the one it suggests.
- Write the changelog in the window that opens, then click Done.
- Type 1 for a draft players cannot see, 2 for a draft with a message for Larsonix, or 3 to publish.
- If it cannot publish, it says why and offers a draft instead.

## Files our tools make

- Our tools rewrite the chest loot tables and the showcase world. HyCreator opens those files on a warning.
- To change one, send Larsonix what should change.

## When something goes wrong

- Send Larsonix a screenshot, and say what you did.
