// The OS layer: everything the module needs from the operating system that differs between Linux and
// Windows, behind one small API. os_posix.cpp is the Linux implementation (the code that used to live in
// link.cpp / client.cpp / main.cpp / hulljob.cpp, moved here unchanged); os_win.cpp is the win64 one.
// No Lua, no engine headers, no <windows.h> here: this header is included by engine-free code that the
// host-side tests (module/test) compile natively.
//
// Paths are UTF-8 std::strings with '/' separators on both systems (Win32 accepts '/'); os_win.cpp
// converts to UTF-16 at the API boundary. The C runtime calls elsewhere in the module (fopen, remove)
// still take narrow paths, i.e. the ANSI code page on Windows: fine for ASCII paths only.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace gc
{
#ifdef _WIN32
// POSIX id types for the few signatures that carry them (quitmarker.hpp, client.cpp). Windows has no
// uid: the per-user profile's ACLs take its place, and uid arguments are ignored there.
using pid_t = int;
using uid_t = unsigned;
#endif

namespace os
{
// Monotonic clock in ns, the same clock Minecraft's System.nanoTime() reads:
//  * Linux: CLOCK_MONOTONIC (bit-identical to Java's nanoTime);
//  * Windows: QueryPerformanceCounter scaled to ns with 64-bit integer math. Java's nanoTime reads the
//    same counter but scales it its own way, so the two agree closely (well below a microsecond) but
//    not bit-exactly. Nothing may depend on exact equality across processes.
std::uint64_t MonoNs();

// n bytes from the OS CSPRNG (/dev/urandom; BCryptGenRandom). False when none could be read: callers
// then refuse (never a predictable value).
bool RandomBytes(void *out, std::size_t n);

// The per-user run dir for discovery files, locks and the hull files: /dev/shm/gmodcraft on Linux
// (shared with GMod's pressure-vessel container), %LOCALAPPDATA%/garrys-modcraft/run on Windows ("" when
// %LOCALAPPDATA% is unknown). Not created here.
std::string RunDir();

// One directory level. True when it was created or already exists (as a directory on Windows; as any
// entry on Linux, where the callers check the type themselves). mode is ignored on Windows.
bool MakeDir(const std::string &dir, unsigned mode);

// True when path is a directory (following links, like stat).
bool IsDir(const std::string &path);

// Renames from -> to, replacing an existing `to` (rename(2); MoveFileExW MOVEFILE_REPLACE_EXISTING).
bool RenameReplace(const std::string &from, const std::string &to);

// The entry names in dir (no "." / ".."). False when the dir can't be read.
bool ListDir(const std::string &dir, std::vector<std::string> *names);

// This process's id.
long long Pid();

// Lowers the calling thread below the game's threads (background work: nice 10; BELOW_NORMAL).
void LowerThreadPriority();

// The engine libraries the client module fetches interfaces from, already loaded by the game.
enum class EngineLib
{
	kEngine,          // engine_client.so | engine.dll
	kMaterialSystem,  // materialsystem_client.so | materialsystem.dll
};
// The file name of lib in this OS's GMod (Linux: checked; win64: the names in bin/win64, UNVERIFIED).
const char *EngineLibName(EngineLib lib);

// lib's exported CreateInterface (a CreateInterfaceFn), or nullptr when the library isn't loaded or
// has no such export. Never loads a library.
void *EngineFactory(EngineLib lib);

// This process's command line, one entry per argument (UTF-8 on Windows). Empty when unreadable.
std::vector<std::string> CommandLineArgs();
}  // namespace os
}  // namespace gc
