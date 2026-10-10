# Garry's Modcraft

*(code and file names use the short id `gmodcraft`)*

Play Garry's Mod as a Minecraft player. A port of [SkyCraft](https://github.com/hawktuahs/SkycraftFork)
(Skyrim + Minecraft) to Garry's Mod on Linux.

Work in progress.

| Folder | |
|---|---|
| `fabric/` | Minecraft 26.3 Fabric mod (forked from SkyCraft) |
| `protocol/` | Shared-memory protocol between GMod and Minecraft |
| `module/` | GMod binary modules (gmcl_/gmsv_gmodcraft, C++) |
| `addon/gmodcraft/` | GMod Lua addon |
| `launcher/` | Player launcher: install, status, play |
| `reference/` | Upstream SkyCraft Skyrim plugin and docs, kept read-only as porting reference |

## License
MIT. Derived from SkyCraft (MIT, `LICENSE.skycraft`), upstream commit `b0c8d88cffa8bab07bfbd4036bed4a9aef5695eb`.
