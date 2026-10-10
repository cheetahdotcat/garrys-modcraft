# Garry's Modcraft quickstart (Linux, written for CachyOS / Arch)

You play Garry's Mod, and Minecraft runs alongside it: what you see is Garry's Mod, what you are is a
Minecraft player. You need **both games**: Garry's Mod on Steam and Minecraft Java on a Microsoft account.

You get from the server owner: the **server address** (`<server address>`, e.g. `<host>:27015`) and the
**server password**.

## 1. Garry's Mod on the 64-bit branch

1. Install Steam (`sudo pacman -S steam`), log in, and install **Garry's Mod**.
2. Library → Garry's Mod → Properties → **Betas** → choose **x86-64 - Chromium + 64-bit binaries**.
   Let Steam update, then start GMod once and quit it.

## 2. Prism Launcher with your Minecraft account

Any of these works; the **AppImage is the recommended one**:

- **AppImage** (recommended): download `PrismLauncher-Linux-x86_64.AppImage` from prismlauncher.org, put it in
  `~/Documents/Software/` (then it's found automatically), `chmod +x` it.
- **pacman**: `sudo pacman -S prismlauncher` (the launcher finds `/usr/bin/prismlauncher`).
- **flatpak**: `flatpak install flathub org.prismlauncher.PrismLauncher`. Not tested yet: Minecraft must
  see `/dev/shm`, so also run `flatpak override --user --device=shm org.prismlauncher.PrismLauncher`. If
  Minecraft starts but never links, use the AppImage instead.

Start Prism once, **add your Microsoft account** (Accounts → Add Microsoft) and close Prism.

## 3. Tk for the launcher window

    sudo pacman -S tk

## 4. Install Garry's Modcraft

Download `gmodcraft-launcher.pyz` and `garrys-modcraft-0.2.0.zip` from the release (check them against
`SHA256SUMS`: `sha256sum -c SHA256SUMS`), then:

    python gmodcraft-launcher.pyz

In the window: **Setup** tab → Release bundle → Browse → `garrys-modcraft-0.2.0.zip` → **Install / Update**.
If you use the pacman or flatpak Prism, set **Prism kind** first (auto picks the AppImage, then pacman, then
flatpak). All lines under Status should be green. The install creates the Prism instance `GmodCraft`
(Minecraft 26.3 + Fabric, Java 25 is downloaded for it).

Without a window: `python gmodcraft-launcher.pyz install --bundle garrys-modcraft-0.2.0.zip`, then
`python gmodcraft-launcher.pyz status`.

Optional: Settings → **Add to the applications menu**.

## 5. Add the server

**Play** tab → Servers → **Add...** → Name: anything, Address: `<server address>`, Minecraft port: `25565`
(unless the owner says otherwise), Password: the server password → Save. Within a few seconds the row
shows the map, players and a version badge (`v20 ok`).

## 6. Join

Double-click the server (or select it and **Join**). The launcher starts Minecraft and Garry's Mod together
and shows the progress: GMod started → module loaded → Minecraft started → linked → in world. The first
start of Minecraft takes a while. In game, your Minecraft joins the server's Minecraft world by itself.

## Troubleshooting

Open the **Logs** tab (GMod console or Minecraft log): known errors come with a hint above the log.

- **Version badge `vNN ≠ v20`**, or "protocol mismatch" in the Minecraft log: you and the server run
  different releases. Install the same release as the server.
- **Bad password**: edit the server (Play tab → Edit...) and enter the password again.
- **"Prism AppImage not found or not executable"** (in the GMod console): choose Prism in the Setup tab (or
  set Prism kind), then Install / Update once so `~/.config/garrys-modcraft/config.json` is written.
- **Minecraft never starts from inside GMod** (`steam-runtime-launch-client` or `systemd-run` errors in
  `/dev/shm/gmodcraft/launch.log`): GMod starts Prism on the host through Steam's launcher service and
  `systemd-run --user`. Check that `systemd-run --user true` works in a terminal, and that Steam itself was
  started normally (not from inside another sandbox). Starting Minecraft from the launcher (pre-warm, on by
  default) avoids this path.
- **Linked but Minecraft doesn't join the server's world**: Minecraft's chat says why; usually the server's
  Minecraft port isn't reachable (ask the owner about port 25565), or the join token expired (rejoin).
- Step stays "slow": the hint under the progress says where to look.

## For the server owner

One script sets up (and later updates) both servers on a Linux x86-64 machine, no root needed: the GMod
dedicated server (SteamCMD, app 4020, branch x86-64) with the module and addon, and next to it the Minecraft
server (Fabric, Java 25 downloaded if missing). Both must run on the same machine (shared memory).

1. Packages (once, with sudo). Debian/Ubuntu:

       sudo dpkg --add-architecture i386 && sudo apt update
       sudo apt install curl tar python3 tmux ca-certificates lib32gcc-s1 lib32stdc++6

   Arch/CachyOS (enable `[multilib]` first): `sudo pacman -S --needed curl tar python tmux lib32-gcc-libs`
2. Download `garrys-modcraft-<version>.zip`, `server_setup.sh` and `SHA256SUMS` from the release
   (`sha256sum -c --ignore-missing SHA256SUMS`), then:

       bash server_setup.sh --bundle garrys-modcraft-<version>.zip --accept-eula

   (or `--release <version>` to download and check the zip itself). It asks for the server password and
   the rcon password (never stored anywhere but `gmod/garrysmod/cfg/server.cfg`, mode 600) and puts
   everything in `~/gmodcraft-server` (another folder: pass it as the last argument). `--accept-eula` means
   you accept the [Minecraft EULA](https://aka.ms/MinecraftEULA). Options (`--help` lists all):
   `--hostname`, `--map`, `--maxplayers`, `--collection <workshop id>` (e.g. a collection with Wiremod
   and AdvDupe2), `--level-type mirror|flat_void_maps|flat_everywhere|underground`, `--memory 6G`,
   `--systemd` (a `systemctl --user` service; `loginctl enable-linger $USER` to start it at boot),
   `--dry-run` (print every step, change nothing).
3. `~/gmodcraft-server/start.sh` starts Minecraft and then srcds in tmux (watch: `tmux -L gmodcraft attach`,
   windows `mc` and `gmod`); `stop.sh` stops them through their consoles (Minecraft saves first);
   `status.sh` shows what runs and queries both servers the way players' launchers do.
4. Update: `~/gmodcraft-server/stop.sh`, then `bash ~/gmodcraft-server/tools/server_setup.sh --bundle
   garrys-modcraft-<new>.zip` (settings and server.cfg are kept; srcds is updated through SteamCMD too).

Ports:

- Forward **27015 UDP and TCP** (Garry's Mod) and **25565 TCP** (Minecraft) to the server machine.
- Players reach Minecraft at the same host they used for Garry's Mod, on the Minecraft port the server
  announces, so forwarding both ports on the same public address is enough. Only if Minecraft is reachable
  somewhere else, set `gmodcraft_mc_address <host>:<port>` on the GMod server. If the machine has several
  private addresses (docker, VPN), pass `--lan-ip <address players reach>`.
- The Minecraft server runs in online mode: every player needs a real Minecraft (Microsoft) account, and is kicked
  after 10 s unless they bring a join token from the GMod server. `--offline` (setup and `run_mc_server.sh`) turns
  the account check off for trusted LANs only: then anyone who can reach port 25565 can connect under any name.
  Give the GMod server a password (`sv_password`) either way.
