#!/usr/bin/env bash
# Build gmcl_gmodcraft_linux64.dll and gmsv_gmodcraft_linux64.dll from clean.
#
#  1. garrysmod_common (pinned git submodule) is initialised recursively.
#  2. premake5 (pinned release, sha256-checked) is fetched into module/.tools/ and run on the
#     HOST: it only generates makefiles, and the release binary needs glibc >= 2.34.
#  3. make runs inside the Steam Runtime 2 "soldier" SDK image (pinned by digest), the same
#     runtime Steam launches GMod in (glibc 2.28, GCC 8). The worktree is mounted at the same
#     path so the generated makefiles work unchanged.
#  4. The outputs are checked: x86-64, old C++ ABI, static libstdc++, max GLIBC symbol <= 2.28.
#
# Usage: module/build.sh [--clean] [--config release|releasewithsymbols]
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$MODULE_DIR/.." && pwd)"

SDK_IMAGE="registry.gitlab.steamos.cloud/steamrt/soldier/sdk@sha256:f87b72ad00e2eafa93e5c1c717d3bf3e7756e0a800a3599eec999d626b21b4c9"
# ^ soldier SDK BUILD_ID 2.0.20260805.254767 (matches PRESSURE_VESSEL_RUNTIME seen in GMod).
PREMAKE_VERSION="5.0.0-beta7"
PREMAKE_URL="https://github.com/premake/premake-core/releases/download/v${PREMAKE_VERSION}/premake-${PREMAKE_VERSION}-linux.tar.gz"
PREMAKE_SHA256="805114ae7002fe90b643630aa0947476565071bfcefa237d97f95df836e0f2f1"
GLIBC_MAX="2.28"

CONFIG="release"
CLEAN=0
while [ $# -gt 0 ]; do
	case "$1" in
		--clean) CLEAN=1 ;;
		--config) CONFIG="$2"; shift ;;
		*) echo "unknown arg: $1" >&2; exit 2 ;;
	esac
	shift
done

log() { printf '\n== %s\n' "$*"; }

log "submodules"
# Already initialised (e.g. a worktree that got a copy, or a second build): no network round trip.
SUBST=$(git -C "$REPO_DIR" submodule status --recursive module/third_party/garrysmod_common 2>/dev/null || true)
if [ -n "$SUBST" ] && ! printf '%s\n' "$SUBST" | grep -q '^-'; then
	echo "garrysmod_common already initialised"
else
	git -C "$REPO_DIR" submodule update --init --recursive module/third_party/garrysmod_common
fi

TOOLS="$MODULE_DIR/.tools"
PREMAKE="$TOOLS/premake5"
if [ ! -x "$PREMAKE" ] || ! "$PREMAKE" --version 2>/dev/null | grep -q "$PREMAKE_VERSION"; then
	log "fetching premake $PREMAKE_VERSION"
	mkdir -p "$TOOLS"
	curl -fsSL -o "$TOOLS/premake.tgz" "$PREMAKE_URL"
	echo "$PREMAKE_SHA256  $TOOLS/premake.tgz" | sha256sum -c -
	tar -C "$TOOLS" -xzf "$TOOLS/premake.tgz" ./premake5
	rm -f "$TOOLS/premake.tgz"
fi

if [ "$CLEAN" = 1 ]; then
	log "clean"
	# premake also places the helper/SDK static-lib projects inside the submodule (gitignored there).
	rm -rf "$MODULE_DIR/projects" "$MODULE_DIR/build" \
		"$MODULE_DIR/third_party/garrysmod_common/projects" \
		"$MODULE_DIR/third_party/garrysmod_common/sourcesdk-minimal/projects"
fi

log "premake5 gmake (host)"
(cd "$MODULE_DIR" && "$PREMAKE" --file=premake5.lua gmake)

WS="$MODULE_DIR/projects/linux/gmake"
log "make config=${CONFIG}_x86_64 (soldier SDK container)"
podman image exists "$SDK_IMAGE" || podman pull "$SDK_IMAGE"
podman run --rm --userns=keep-id \
	-v "$MODULE_DIR:$MODULE_DIR" -v "$REPO_DIR/protocol:$REPO_DIR/protocol:ro" -w "$WS" \
	"$SDK_IMAGE" \
	make -j"$(nproc)" config="${CONFIG}_x86_64" gmsv_gmodcraft gmcl_gmodcraft

OUT_SRC="$WS/x86_64/$(case "$CONFIG" in release) echo Release ;; releasewithsymbols) echo ReleaseWithSymbols ;; *) echo "$CONFIG" ;; esac)"
OUT="$MODULE_DIR/build"
mkdir -p "$OUT"
for realm in gmsv gmcl; do
	cp -f "$OUT_SRC/${realm}_gmodcraft_linux64.dll" "$OUT/"
done

log "checks"
fail=0
for f in "$OUT"/gm{sv,cl}_gmodcraft_linux64.dll; do
	name="$(basename "$f")"
	file -b "$f" | grep -q 'ELF 64-bit.*x86-64' || { echo "$name: not x86-64 ELF"; fail=1; }
	glibc="$(objdump -T "$f" | grep -o 'GLIBC_[0-9.]*' | sed 's/GLIBC_//' | sort -uV | tail -1)"
	newest="$(printf '%s\n%s\n' "$glibc" "$GLIBC_MAX" | sort -V | tail -1)"
	[ "$newest" = "$GLIBC_MAX" ] || { echo "$name: needs GLIBC_$glibc > $GLIBC_MAX"; fail=1; }
	if readelf -d "$f" | grep -qE 'NEEDED.*(libstdc\+\+|libgcc_s|tier0|vstdlib)'; then
		echo "$name: unexpected NEEDED entry"; fail=1
	fi
	objdump -T "$f" | grep -q 'GLIBCXX_' && { echo "$name: imports GLIBCXX symbols"; fail=1; }
	exports="$(objdump -T "$f" | awk '$4 != "*UND*" && ($3 == "DF" || $3 == "DO") {print $NF}' | sort | tr '\n' ' ')"
	[ "$exports" = "gmod13_close gmod13_open " ] || { echo "$name: unexpected exports: $exports"; fail=1; }
	echo "$name: max GLIBC_$glibc, NEEDED: $(readelf -d "$f" | awk '/NEEDED/{gsub(/[][]/,"",$5); printf "%s ", $5}')"
done
[ "$fail" = 0 ] || { echo "checks FAILED"; exit 1; }
echo "OK: $OUT"
