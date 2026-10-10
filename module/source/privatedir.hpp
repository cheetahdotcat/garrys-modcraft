// The discovery dir (/dev/shm/gmodcraft) is only trusted when it is ours: /dev/shm is world-writable
// (1777), so another local user could create the dir first and then plant or rename files in it (a fake
// discovery JSON, a fake lock, our client.json renamed to quit-<pid>). Before anything in it is read or
// written: lstat says a real directory (no symlink), owned by our uid, mode exactly 0700. Header-only,
// no GMod headers: checked natively by module/test/quitmarker_test.cpp.
#pragma once

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <string>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

namespace gc
{
inline bool CheckPrivateDir(const std::string &dir, uid_t uid, std::string *why)
{
	struct stat st{};
	if (lstat(dir.c_str(), &st) != 0)
	{
		*why = dir + ": " + std::strerror(errno);
		return false;
	}
	if (!S_ISDIR(st.st_mode))
	{
		*why = dir + " is not a directory (a symlink or a file): refusing it";
		return false;
	}
	if (st.st_uid != uid)
	{
		*why = dir + " belongs to another user (uid " + std::to_string(st.st_uid) + "): refusing it";
		return false;
	}
	if ((st.st_mode & 07777) != 0700)
	{
		char m[8];
		std::snprintf(m, sizeof m, "%04o", static_cast<unsigned>(st.st_mode & 07777));
		*why = dir + " has mode " + m + ", not 0700: refusing it (remove it, it is recreated)";
		return false;
	}
	return true;
}

// Creates the dir (0700) if missing, then checks it. The parent must exist.
inline bool EnsurePrivateDir(const std::string &dir, std::string *why)
{
	if (mkdir(dir.c_str(), 0700) != 0 && errno != EEXIST)
	{
		*why = "mkdir " + dir + ": " + std::strerror(errno);
		return false;
	}
	return CheckPrivateDir(dir, getuid(), why);
}
}  // namespace gc
