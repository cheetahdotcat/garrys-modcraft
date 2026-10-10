# Changelog

## 0.6.0 (2026-10-09) "Hull"

Link protocol v45: 0.6.0 doesn't play with 0.5.x (update clients and servers together). Linux (x86-64).

**New**
- **Hull world type:** the GMod map is traced into real Minecraft blocks (matching materials, solid under floors and
  ground). GMod keeps drawing the map; digging into it carves the map. Terrain hills are filled (grass, dirt, stone;
  `gmodcraft_hull_fill_hills 0` keeps them hollow, new hull worlds only).
- **See-through holes:** holes dug into the map hide the dug part of the map's faces in the engine, so a hole through
  a wall shows what is behind it. Hole walls take the cut face's own material on their top band
  (`gmodcraft_hole_crust` 0/1/2), have floors, no white gaps along brush edges, and stop at the map's surfaces.
- **Day and night with Minecraft:** Minecraft's time drives the map's sun, light, shadows, fog and sky (StormFox2 can
  drive Minecraft instead). Optional Minecraft sky as the server default.
- **Physics blocks:** falling sand/gravel and primed TNT are GMod physics; the gravity gun pulls blocks out of the
  ground and throws them, landing as blocks; GMod explosions break Minecraft blocks.
- **Pistons** push and pull with their final shape at once.
- **Toolgun on Minecraft blocks:** weld, rope, structure export and the other tools act on the map at the clicked
  spot; the Block Tool and Map Link work on blocks.
- **Weapons:** picking a weapon in GMod (a stool in the spawn menu, the weapon tab) selects it in Minecraft, moving it
  into the hotbar if it's in the inventory.
- **Control panel rework:** a sidebar with Play / Admin / Debug pages and a Settings page; settings are kept across
  restarts.

**Fixes**
- Props and Minecraft entities rest on the floors and walls of holes dug into hull worlds.
- Physgun and gravgun can't grab through Minecraft blocks.
- Sneaking stops at block edges again.
- Minecraft server stops log how long they took and dump threads when a stop takes over 30 s.
- Test runs never start the player's Minecraft.

## 0.5.1 (2026-10-07)

Link protocol v40, like 0.5.0: 0.5.0 and 0.5.1 can play together (update anyway for the fixes).

**New**
- The launcher checks GitHub for new releases (at most every 6 h, or Settings → Check for updates) and updates the
  bundle and itself after verifying SHA256SUMS. Settings: check for updates (on), include betas (off).
- `server_setup.sh` sets up a dedicated server on a fresh Linux box in one command (SteamCMD GMod server on the x86-64
  branch, modules and addon, Minecraft server with Java 25, `start.sh` / `stop.sh` / `status.sh`, optional systemd).
  The Minecraft server runs in online mode by default (`--offline` for trusted LANs).
- New README.

**Fixes**
- `gmodcraft_mc_sky 1` works: the Minecraft sky no longer hides the Minecraft terrain.
- Minecarts run on rails laid on map surfaces; a welded or roped cart (or boat) stays on its rails and carries the
  props; carts weigh 100 kg, boats 80 kg.
- One break or one blast now removes a grass block and the map floor above it together.
- Explosions no longer turn open sky over thin map floors into floating stone; `/gmodcraft tool cleanfloating <r>`
  (op) removes such blocks. With `digThinWalls` off, thin walls stop a blast; blasts don't leave block faces poking
  out of walls.
- Props in dug holes stay visible and lit (not black).
- No stone lid over holes dug into thin floors (sidewalks).
- Players in Minecraft tunnels below a map are no longer "rescued" as if stuck in rock.
- Underground view is off by default (`gmodcraft_underground_view 0`): from a hole you see the outside.
- No more `gmod_hands` console spam.

## 0.5.0 (2026-10-07) "Building"

Link protocol v40: GMod server, GMod clients and Minecraft must all run 0.5.0 (the betas and 0.4.0 can't join).
Everything from 0.5.0-beta1 and -beta2 below, plus:

**World**
- v39 "Underground" world type: real Minecraft caves, ore veins, deepslate and aquifers below the maps (a 4-block
  stone cap right under each map floor), grass over the same underground outside the maps. Control centre World
  page or the launcher's New world.
- Dug holes are sealed: every face of a dug cell against map geometry (any brush, floors and ceilings too) gets a wall.
- v38 server rule `digThinWalls` (on): off refuses digging thin walls (mostly-air cells with no solid on either side).
- Experimental `gmodcraft_dig_cut_brush 1`: a hole cuts only the dug solid's own faces (a thin wall's hole leaves
  the room's floor and ceiling intact).

**Building**
- v40 structure tools (admin): Structure Export copies a box of Minecraft blocks into an AdvDupe2 dupe (and a vanilla
  .nbt structure); Blockify turns selected GMod props into Minecraft blocks (undo with Z).
- Spawning props and the toolgun now hit Minecraft blocks (props land on them, welds and ropes take).
- Microblocks drop their pieces when blown up or broken by pistons; no seams between glass pieces; no server tick.
- Prop items show their icons for every player and after restarts.

**Mobs and sky**
- Falling props hurt Minecraft mobs (crush damage by impact speed and mass).
- v37 `gmodcraft_mc_sky 1` replaces the map's skybox with the Minecraft sky: time of day, sunrise/sunset, sun,
  moon with phase, stars, rain.

## 0.5.0-beta2 (2026-10-06)

Link protocol v36: GMod server, GMod clients and Minecraft must all run 0.5.0-beta2 (beta1 and 0.4.0 can't join).
Quick beta, no full test pass.

**World**
- Deep flat worlds: `flat_everywhere` and `flat_void_maps` (outside the maps' void) now generate a vanilla-like
  column under the floor: grass, 3 dirt, stone down to y 0, deepslate below, jagged bedrock at y -64. Ore veins at
  vanilla-ish depths and rates (coal, iron, copper, gold, redstone, lapis, diamond; deepslate variants below 0),
  plus gravel, granite, diorite, andesite and tuff blobs. No caves. Only chunks generated from now on: an existing
  flat world keeps its old 4-block-deep chunks (a visible step where new chunks meet them); `mirror` stays void.
- Underground view: with your eye inside the map's rock (a dug tunnel), the frame clears to cave colour with fog and
  shows only Minecraft blocks, mobs and nearby GMod props/NPCs: no sky patches or map faces cutting through
  (`gmodcraft_underground_view` 1 cave, 2 cave + sky, 0 off).

**Building**
- v36 microblocks get exact GMod collision: props rest on thin covers, vehicles climb panel stairs, NPCs and
  GMod players are blocked only by the thin piece itself.

## 0.5.0-beta1 (2026-10-06)

Link protocol v35: GMod server, GMod clients and Minecraft must all run 0.5.0-beta1. A quick beta of the 0.5
"Building" work, released without a test pass: expect rough edges.

**Building**
- Microblocks: a hand saw (iron + stick) cuts any block into slabs, panels, covers, posts and corners (1/8 grid);
  several pieces share one block space, mining takes back only the piece you hit. Drawn in GMod too; GMod
  collision is still approximate (thin floor pieces walk-through, thin posts solid as a block).
- v33 GMod props as Minecraft items: sneak + right-click a prop with an empty hand to pick it up (stacks with
  identical props, spawn icon), right-click the item on a surface to place it (prop protection and the prop limit
  apply).
- GMod map surfaces act like their blocks: a hoe tills map grass into real farmland, saplings plant on map grass,
  sugar cane on map sand (with real water next to it), snow layers rest on the map floor, icy surfaces are
  slippery, spawn rules read the surface.

**Physics and mobs**
- v32 held Minecraft mobs turn with their GMod body: spin a physgunned cow or swing a roped pig and the
  Minecraft mob (and its hit boxes) faces the way the body faces (mobs stay upright: no roll).
- GMod fire sets Minecraft mobs on fire (vanilla burning: cooked drops, "burned" death message) instead of
  explosion damage.

**Rendering**
- No more z-fighting between Minecraft terrain and map surfaces: terrain is drawn 0.25 units lower, and block faces
  lying on a map surface (same plane, same direction) aren't drawn.

**Control centre (v34)**
- The Garry's Modcraft spawn menu tab gets admin pages: **Maps** (the server's maps with thumbnails, workshop ones
  marked, each map's Minecraft slot and vertical offset, the current map marked; click to change level) and
  **World** (the running world type, the world and its backups, a new world of a chosen type or a restored backup,
  scheduled for the dedicated Minecraft server's next start; "apply now" restarts it). The running world is always
  kept as a backup (`<world>.bak-<timestamp>`), never deleted. Single player: the launcher's New world.
- Server page "Mobs and difficulty": Minecraft rules `difficulty` (peaceful/easy/normal/hard), `mobSpawning`
  (natural spawning, off by default as before), `mobCapPercent` (natural spawning caps, % of vanilla); GMod convars
  `gmodcraft_npc_vs_mobs` (NPCs fight or ignore hostile Minecraft mobs) and `gmodcraft_mob_damage_scale` (GMod damage
  on Minecraft mobs x this).
- tools/run_mc_server.sh applies a scheduled world operation (`server-dir/gmodcraft-world-op`) before Java starts and
  restarts the server when it stopped for one (`server-dir/gmodcraft-restart`).

**Minecraft tab (v35)**
- New spawn menu tab **Minecraft**: Minecraft's inventory (its own screen, scaled into the tab) opens with the tab and
  closes with it; mouse and, after a click into the tab, keys go to Minecraft. Drag a weapon from the Weapons tab
  (hover the Minecraft tab's button to switch) onto a hotbar slot: you get that weapon's item in that slot (hybrid
  mode; the same Spawnable / AdminOnly / PlayerGiveSWEP checks as any weapon from Minecraft).

## 0.4.0 (2026-10-06)

Link protocol v31: GMod server, GMod clients and Minecraft must all run 0.4.0 (0.3.0 can't join a 0.4.0 server).

**Physics**
- Props, ragdolls and vehicles use one physics world with Minecraft: they fall into dug holes and tunnels, rest on
  Minecraft blocks and slide down them, and the jeep drives over blocks (convar `gmodcraft_physics_world`, on).
- v30 moving platforms carry Minecraft players: stand on a moving prop (sliding, physgunned, on a rope), a
  func_door/func_movelinear lift or a train and you ride along, turning with it; walls of a cabin still block.
- v27 Minecraft mobs in GMod: every mob, animal, minecart and boat near GMod players gets an invisible GMod body.
  GMod bullets, melee and explosions hurt it in Minecraft (kills and loot credited to the shooter's Minecraft
  player), players bump into it, and HL2 NPCs fight hostile mobs (combine vs zombies).
- v29 physgun and gravgun on Minecraft mobs (server rule `physgunMobs`, on by default): pick up a cow, carry it,
  throw a zombie (it takes fall damage in Minecraft), freeze a mob in mid-air, rope or balloon a pig, punt
  one with the gravgun. While GMod holds, freezes or constrains a mob it owns it (Minecraft pauses its AI and
  gravity, nothing saved); on release Minecraft takes it back with the throw's velocity. A held or roped mob
  follows its GMod body tick by tick (no trailing).
- v31 mob hit boxes follow the model: bullets and traces hit a zombie's arms, a cow's or pig's head, a spider's
  legs, turned with the mob (babies scaled); bullets on the head do double damage. Movement and bumping still
  use Minecraft's collision box. `gmodcraft_debug_mchitbox 1` draws both.
- v26 block shapes: slabs and stairs reach GMod as half-height shapes (props, vehicles, NPC and player traces);
  carpets, pressure plates and thin snow no longer block GMod players and props. Vehicles drive up
  half-block steps (slab staircases become ramps), e.g. the demo ramp.

**Fire**
- Fire crosses between the games (server rule `fireCrossover`, on by default; off with Minecraft's fire spread
  gamerule at 0): GMod props touching Minecraft fire or lava catch fire, flame arrows and fire charges set
  props alight, and burning GMod things (props, ragdolls, env_fire, exploding gas cans and barrels) light
  flammable Minecraft blocks next to them (a few per tick, never on the GMod map's own surfaces or dug cells).

**Redstone**
- Map doors, buttons and triggers work with Minecraft redstone, no Wiremod needed: the admin "Map Link" tool
  links a map entity to a Redstone Wire Bridge block. A pressed button, an open door or an occupied trigger
  powers the bridge; a powered bridge opens/closes doors and func_movelinear, presses buttons and switches
  lights. The link is saved with the block and comes back when the map loads (`gmodcraft_maplink_everyone 1`
  lets everyone use the tool).

**Startup**
- A loading screen while Minecraft starts: launching, link, world, slot, spawned, ready, with the elapsed time
  and what to do if it fails. Space/Esc hides it to a corner indicator; "Minecraft reconnecting…" in the corner
  after a restart (`gmodcraft_loading_screen 0` turns it off).

**Fixes**
- A Minecraft mob killed by a GMod NPC no longer leaves its GMod body behind.
- A dormant map env_fire no longer counts as burning.
- The admin "Server" page no longer says "not admin" until the spawnmenu is reloaded.

## 0.3.0 (2026-10-06)

Link protocol v24: GMod server, GMod clients and Minecraft must all run 0.3.0 (0.2.0 can't join a 0.3.0 server).

**Worlds**
- New maps line up with Minecraft: a map's main floor (detected from its spawn points) sits exactly on y 64.
- Admins can re-anchor an existing map's Minecraft world up or down (backup first, exact undo, survives crashes).
- World types for new worlds: void, superflat with the map area left empty, or superflat everywhere.
- Server rules (gamemode, PvP, keepInventory, digging into the map, MC noclip) editable live on an admin
  "Server" page in the spawnmenu; "New world" keeps the old world as a backup.

**Play**
- Noclip works in Minecraft mode (V, GMod's own rules decide).
- NPCs and props stay visible while your view is inside the map; the sky renders there too.
- No more mouse jumps when you alt-tab while the game starts.

**Launcher**
- "Quit game" closes GMod and Minecraft cleanly; Minecraft also closes ~15 s after GMod exits.

## 0.2.0 (2026-10-06)

First release for friends. Link protocol v20: GMod server, GMod clients and Minecraft must all run 0.2.0.

**Multiplayer**
- Join a Garry's Modcraft server and your Minecraft joins its Minecraft world on its own (single-use join
  tokens, one Minecraft player per GMod player). Works through port forwards: your Minecraft connects to the
  host you used to reach the GMod server, on the server's Minecraft port.
- Dedicated servers run a void Minecraft world, like single player (no more spawning inside terrain).
- Minecraft arrows and projectiles hit GMod props and breakables; arrows stuck in a prop fall when it moves.
- Drive GMod vehicles as a Minecraft player; GMod NPCs are solid in Minecraft; props push you out instead of
  trapping you.

**Hybrid mode**
- GMod weapons are Minecraft items: they show in your inventory with their GMod icons, fire the real GMod
  weapon, and use GMod ammo (ammo bar in Minecraft). GMod viewmodel and ammo HUD while one is held.
- Physgun works while holding (rotate, scroll), plus input toggles: use mode, reload/inventory keys, vehicle
  controls, GMod-mode shortcuts.

**Wiremod**
- Redstone <-> Wiremod bridge: wire a Minecraft lever, lamp or clock to Wiremod gates and back
  (only when Wiremod is installed).

**Tools and demos** (admins)
- Garry's Modcraft tools in the spawn menu: Terrain Repair (refill dug cells), Block Tool, Resync,
  Inspector, Collision Viewer.
- One-click demo builds (rail loop, redstone/Wiremod, projectile range, vehicle ramp, NPC arena, dig wall),
  each with an exact undo.

**Looks**
- The sky stays visible when your eye is inside a block (3D skybox), and walls of far-away dug holes show.

**Launcher**
- Server list with live status (map, players, Minecraft players, version badge); server passwords per server,
  stored privately (0600) and never on the command line.
- Launch progress (GMod started, module loaded, Minecraft started, linked, in world) with hints when a step
  is slow; Logs tab for the GMod console and Minecraft's log, with hints for common errors and passwords hidden.
- Starts Minecraft in parallel with Garry's Mod (faster start; Settings → Start-up).
- Prism Launcher as the AppImage, the distro package (`/usr/bin/prismlauncher`, e.g. pacman) or the flatpak.
- Dark/light look, window icon, "Add to the applications menu".

## 0.1.2

Single player and LAN: Garry's Mod as a Minecraft player (link, collision, rendering, combat, hazards,
lights, dug holes), the first launcher.
