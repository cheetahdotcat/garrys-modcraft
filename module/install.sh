#!/usr/bin/env bash
# Dev install: symlink the built modules and the addon into the local GMod install.
#   garrysmod/lua/bin/gm{cl,sv}_gmodcraft_linux64.dll -> module/build/...
#   garrysmod/addons/gmodcraft                         -> addon/gmodcraft
# Usage: module/install.sh [--uninstall]   (GMOD_DIR overrides the install path)
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$MODULE_DIR/.." && pwd)"
GMOD_DIR="${GMOD_DIR:-$HOME/.local/share/Steam/steamapps/common/GarrysMod}"
GM="$GMOD_DIR/garrysmod"
case "${1:-}" in
	""|--uninstall) ;;
	*) echo "usage: $0 [--uninstall]" >&2; exit 2 ;;
esac
[ -d "$GM" ] || { echo "no GMod at $GMOD_DIR" >&2; exit 1; }

links=(
	"$GM/lua/bin/gmcl_gmodcraft_linux64.dll|$MODULE_DIR/build/gmcl_gmodcraft_linux64.dll"
	"$GM/lua/bin/gmsv_gmodcraft_linux64.dll|$MODULE_DIR/build/gmsv_gmodcraft_linux64.dll"
	"$GM/addons/gmodcraft|$REPO_DIR/addon/gmodcraft"
)

for l in "${links[@]}"; do
	dst="${l%%|*}"; src="${l##*|}"
	if [ "${1:-}" = "--uninstall" ]; then
		[ -L "$dst" ] && rm -v "$dst"
		continue
	fi
	if [ -e "$dst" ] && [ ! -L "$dst" ]; then
		echo "refusing to replace non-symlink $dst" >&2; exit 1
	fi
	mkdir -p "$(dirname "$dst")"
	ln -sfnv "$src" "$dst"
done
if [ "${1:-}" = "--uninstall" ]; then
	rmdir "$GM/lua/bin" 2>/dev/null || true
fi
