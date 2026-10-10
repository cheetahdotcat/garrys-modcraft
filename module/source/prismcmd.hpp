// Which Prism Launcher the module starts (R1: AppImage, distro package, flatpak). Header-only and
// free of GMod/Lua headers: checked natively by module/test/prismcmd_test.cpp.
//
// The command comes only from the user's own files and environment, never from Lua (clientside Lua
// comes from whatever server you join). In order:
//   1. "prismCommand" in ~/.config/garrys-modcraft/config.json (written by the launcher when it found a
//      Prism): a JSON array of strings that must be exactly one of these shapes, else the launch is
//      refused. Missing or empty ([]): not set, the next sources apply.
//        ["<absolute path>"]                          an AppImage or an executable (/usr/bin/prismlauncher)
//        ["flatpak", "run", "org.prismlauncher.PrismLauncher"]
//   2. "prismAppImage" in that file, 3. env GMODCRAFT_PRISM, 4. ~/Documents/Software/PrismLauncher-Linux-x86_64.AppImage
// "--launch <instance>" is appended by the caller.
//
// Executable check: inside Steam's pressure-vessel container the host's /usr isn't /usr (that's the
// runtime's); the host's is at /run/host/usr. So a /usr/... path is checked at /run/host/usr/...; if
// /run/host isn't there either, only the allow-listed package paths skip the check.
#pragma once

#include "jsonfield.hpp"

#include <cstddef>
#include <string>
#include <vector>

namespace gc
{
inline constexpr const char *kFlatpakApp = "org.prismlauncher.PrismLauncher";
inline constexpr std::size_t kMaxPrismArgs = 8;

// A JSON array of strings for top-level "key": true and the strings when it parses (at most maxItems,
// each through the same string decoder as JsonStringField); false when the key is missing or the
// value isn't such an array. *present tells the two apart (key there but not a string array).
inline bool JsonStringArrayField(const std::string &json, const char *key, std::vector<std::string> *out, bool *present,
	std::size_t maxItems = kMaxPrismArgs)
{
	out->clear();
	*present = false;
	const std::string want = std::string("\"") + key + "\"";
	std::size_t at = 0;
	while ((at = json.find(want, at)) != std::string::npos)
	{
		std::size_t p = at + want.size();
		while (p < json.size() && JsonSpace(json[p]))
			++p;
		if (p >= json.size() || json[p] != ':')
		{
			at = p;
			continue;
		}
		*present = true;
		++p;
		while (p < json.size() && JsonSpace(json[p]))
			++p;
		if (p >= json.size() || json[p] != '[')
			return false;
		++p;
		for (;;)
		{
			while (p < json.size() && JsonSpace(json[p]))
				++p;
			if (p >= json.size())
				return false;
			if (json[p] == ']' && out->empty())
				return true;
			if (json[p] != '"' || out->size() >= maxItems)
				return false;
			// Reuse JsonStringField's decoder on the string starting here.
			std::string one = JsonStringField("{\"v\":" + json.substr(p), "v");
			// Find the closing quote of this string (skipping escapes) to continue after it.
			std::size_t q = p + 1;
			for (; q < json.size(); ++q)
			{
				if (json[q] == '\\')
				{
					++q;
					continue;
				}
				if (json[q] == '"')
					break;
			}
			if (q >= json.size())
				return false;
			if (one.empty() && q != p + 1)
				return false;  // a string that didn't decode
			out->push_back(one);
			p = q + 1;
			while (p < json.size() && JsonSpace(json[p]))
				++p;
			if (p < json.size() && json[p] == ',')
			{
				++p;
				continue;
			}
			if (p < json.size() && json[p] == ']')
				return true;
			return false;
		}
	}
	return false;
}

inline bool PrismSafeArg(const std::string &s)
{
	if (s.empty() || s.size() > 512)
		return false;
	for (char c : s)
		if (static_cast<unsigned char>(c) < 0x20 || c == 0x7f)
			return false;
	return true;
}

inline bool AbsoluteCleanPath(const std::string &p)
{
	if (p.empty() || p[0] != '/' || !PrismSafeArg(p))
		return false;
	if (p.find("/../") != std::string::npos || p.find("/./") != std::string::npos || (p.size() >= 3 && p.compare(p.size() - 3, 3, "/..") == 0))
		return false;
	return true;
}

struct PrismCommand
{
	std::vector<std::string> argv;  // without "--launch <instance>"
	std::string from;               // where it came from (for messages; never the path itself)
	std::string kind;               // "appimage" / "executable" / "flatpak"
	std::string checkPath;          // what to access(X_OK) before starting; "" = skip the check
};

// Environment facts the resolution depends on (injected, so the test can play every case).
struct PrismEnv
{
	std::string configText;  // config.json contents ("" if unreadable)
	bool configRead = false;
	std::string envPrism;    // GMODCRAFT_PRISM
	std::string home;        // HOME
	bool pressureVessel = false;
	bool hostRoot = false;   // /run/host/usr exists
	std::string flatpakOnPath;  // outside the container: "flatpak" found on PATH ("" = not found: /usr/bin/flatpak)
};

// The executable check path for an absolute command path.
inline std::string PrismCheckPath(const std::string &path, const PrismEnv &env)
{
	if (!env.pressureVessel || path.compare(0, 5, "/usr/") != 0)
		return path;  // outside the container, or a host path the container shares (e.g. under /home)
	if (env.hostRoot)
		return "/run/host" + path;
	static const char *const kAllow[] = { "/usr/bin/prismlauncher", "/usr/local/bin/prismlauncher" };
	for (const char *a : kAllow)
		if (path == a)
			return "";  // can't see the host's /usr: trust the allow-listed package path
	return path;      // checked in the container's /usr: fails, with a clear message
}

// Fills *out, or returns false with *err (a message for Lua: never the path itself).
inline bool ResolvePrismCommand(const PrismEnv &env, PrismCommand *out, std::string *err)
{
	*out = PrismCommand{};
	if (env.configRead)
	{
		std::vector<std::string> argv;
		bool present = false;
		bool ok = JsonStringArrayField(env.configText, "prismCommand", &argv, &present);
		if (present && ok && argv.empty())
			present = false;  // [] = the launcher found no Prism: fall through to the other sources
		if (present)
		{
			out->from = "config.json prismCommand";
			if (!ok)
			{
				*err = "prismCommand in ~/.config/garrys-modcraft/config.json isn't a list of strings";
				return false;
			}
			if (argv.size() == 3 && argv[0] == "flatpak" && argv[1] == "run" && argv[2] == kFlatpakApp)
			{
				out->argv = argv;
				out->kind = "flatpak";
				// Started on the host (systemd-run resolves "flatpak" on its PATH). Checked: inside the
				// container at the host's /usr (or not at all without /run/host), outside on PATH.
				out->checkPath = env.pressureVessel ? (env.hostRoot ? "/run/host/usr/bin/flatpak" : "")
													: (env.flatpakOnPath.empty() ? "/usr/bin/flatpak" : env.flatpakOnPath);
				return true;
			}
			if (argv.size() == 1 && AbsoluteCleanPath(argv[0]))
			{
				out->argv = argv;
				out->kind = argv[0].size() > 9 && argv[0].compare(argv[0].size() - 9, 9, ".AppImage") == 0 ? "appimage" : "executable";
				out->checkPath = PrismCheckPath(argv[0], env);
				return true;
			}
			*err = "prismCommand in ~/.config/garrys-modcraft/config.json must be [\"/absolute/path\"] or "
				   "[\"flatpak\", \"run\", \"org.prismlauncher.PrismLauncher\"]";
			return false;
		}
	}
	std::string path;
	if (env.configRead && !(path = JsonStringField(env.configText, "prismAppImage")).empty())
		out->from = "config.json prismAppImage";
	else if (!env.envPrism.empty())
	{
		path = env.envPrism;
		out->from = "GMODCRAFT_PRISM";
	}
	else
	{
		path = env.home + "/Documents/Software/PrismLauncher-Linux-x86_64.AppImage";
		out->from = "the default ~/Documents/Software/PrismLauncher-Linux-x86_64.AppImage";
	}
	if (!AbsoluteCleanPath(path))
	{
		*err = "Prism path (from " + out->from + ") isn't an absolute path";
		return false;
	}
	out->argv = { path };
	out->kind = "appimage";
	out->checkPath = PrismCheckPath(path, env);
	return true;
}

// The full argv that starts Prism: [steam-runtime-launch-client --alongside-steam --] systemd-run
// --user --collect --quiet [--setenv=GMODCRAFT_DEV_COMMANDS=1] -- <prism argv> --launch <instance>.
// dev = GMod itself runs with -gmodcraft_dev (the module reads its own /proc/self/cmdline; Lua never
// decides it): then Minecraft accepts dev commands (DevCommands.java). Nothing else differs.
inline std::vector<std::string> PrismLaunchArgv(bool pressureVessel, bool dev, const std::vector<std::string> &prismArgv, const std::string &instance)
{
	std::vector<std::string> args;
	if (pressureVessel)
		args = { "steam-runtime-launch-client", "--alongside-steam", "--" };
	for (const char *a : { "systemd-run", "--user", "--collect", "--quiet" })
		args.push_back(a);
	if (dev)
		args.push_back("--setenv=GMODCRAFT_DEV_COMMANDS=1");
	args.push_back("--");
	for (const std::string &a : prismArgv)
		args.push_back(a);
	args.push_back("--launch");
	args.push_back(instance);
	return args;
}
}  // namespace gc
