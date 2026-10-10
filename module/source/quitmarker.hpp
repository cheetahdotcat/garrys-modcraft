// L2: the launcher's gentle "quit" request. Header-only, no GMod headers: checked natively by
// module/test/quitmarker_test.cpp.
//
// The launcher writes <discovery dir>/quit-<GMod pid> (a regular file, owned by the user). The client
// module looks for it about once a second (from Frame) and, when it is there and genuine, removes it and
// runs "quit" through the engine (ClientCmd_Unrestricted), so GMod shuts down the normal way and writes
// cfg/config.cfg. Lua can't trigger it: no Lua function takes a path or a command here, and clientside
// Lua can't create files outside garrysmod/data.
#pragma once

#include "privatedir.hpp"

#include <string>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

namespace gc
{
inline std::string QuitMarkerPath(const std::string &dir, pid_t pid)
{
	return dir + "/quit-" + std::to_string(static_cast<long long>(pid));
}

// True when the marker for this process is there, is a regular file (not a link) and belongs to uid.
// A marker that isn't genuine is left alone (and ignored).
inline bool QuitMarkerGenuine(const std::string &path, uid_t uid)
{
	struct stat st{};
	if (lstat(path.c_str(), &st) != 0)
		return false;
	return S_ISREG(st.st_mode) && st.st_uid == uid && st.st_size <= 4096;
}

// Checks and consumes the marker: true = quit now. Nothing in a dir that isn't privately ours counts.
inline bool TakeQuitMarker(const std::string &dir, pid_t pid, uid_t uid)
{
	std::string why;
	if (!CheckPrivateDir(dir, uid, &why))
		return false;
	const std::string path = QuitMarkerPath(dir, pid);
	if (!QuitMarkerGenuine(path, uid))
		return false;
	unlink(path.c_str());
	return true;
}
}  // namespace gc
