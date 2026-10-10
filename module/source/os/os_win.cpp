// The OS layer on win64 (see os.hpp). Paths are UTF-8 with '/' in the module and UTF-16 at the Win32
// boundary.
#ifdef _WIN32

#include "os.hpp"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <bcrypt.h>
#include <shellapi.h>

#include <cstdlib>
#include <filesystem>
#include <system_error>

#ifdef _MSC_VER
#pragma comment(lib, "bcrypt.lib")
#pragma comment(lib, "shell32.lib")
#endif

namespace gc
{
namespace os
{
namespace
{
std::wstring Widen(const std::string &s)
{
	if (s.empty())
		return std::wstring();
	int n = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, s.data(), static_cast<int>(s.size()), nullptr, 0);
	if (n <= 0)
		return std::wstring();
	std::wstring w(static_cast<std::size_t>(n), L'\0');
	MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, s.data(), static_cast<int>(s.size()), &w[0], n);
	return w;
}

std::string Narrow(const wchar_t *w, std::size_t len)
{
	if (len == 0)
		return std::string();
	int n = WideCharToMultiByte(CP_UTF8, 0, w, static_cast<int>(len), nullptr, 0, nullptr, nullptr);
	if (n <= 0)
		return std::string();
	std::string s(static_cast<std::size_t>(n), '\0');
	WideCharToMultiByte(CP_UTF8, 0, w, static_cast<int>(len), &s[0], n, nullptr, nullptr);
	return s;
}

std::uint64_t QpcFrequency()
{
	static const std::uint64_t f = [] {
		LARGE_INTEGER li;
		QueryPerformanceFrequency(&li);  // never fails on XP and later; fixed at boot
		return static_cast<std::uint64_t>(li.QuadPart);
	}();
	return f;
}
}  // namespace

// QPC ticks -> ns, exact in 64-bit integers: whole seconds and the remainder are scaled separately,
// so nothing overflows for ~584 years of uptime. rem * 1e9 needs rem < 1.8e10, i.e. a counter
// frequency below 18 GHz (Windows uses 10 MHz, or the TSC rate on some systems); a higher one takes
// the (slightly rounded) double path.
std::uint64_t MonoNs()
{
	LARGE_INTEGER li;
	QueryPerformanceCounter(&li);
	const std::uint64_t q = static_cast<std::uint64_t>(li.QuadPart);
	const std::uint64_t f = QpcFrequency();
	if (f == 0)
		return 0;
	const std::uint64_t whole = q / f, rem = q % f;
	const std::uint64_t frac = rem < 18000000000ull ? rem * 1000000000ull / f
	                                                : static_cast<std::uint64_t>(static_cast<double>(rem) * 1e9 / static_cast<double>(f));
	return whole * 1000000000ull + frac;
}

bool RandomBytes(void *out, std::size_t n)
{
	auto *p = static_cast<UCHAR *>(out);
	while (n > 0)
	{
		const ULONG chunk = n > 0x10000000u ? 0x10000000u : static_cast<ULONG>(n);
		if (!BCRYPT_SUCCESS(BCryptGenRandom(nullptr, p, chunk, BCRYPT_USE_SYSTEM_PREFERRED_RNG)))
			return false;
		p += chunk;
		n -= chunk;
	}
	return true;
}

std::string RunDir()
{
	const wchar_t *lad = _wgetenv(L"LOCALAPPDATA");
	if (lad == nullptr || *lad == 0)
		return std::string();
	std::string base = Narrow(lad, wcslen(lad));
	if (base.empty())
		return std::string();
	for (char &c : base)
		if (c == '\\')
			c = '/';
	while (base.size() > 3 && base.back() == '/')
		base.pop_back();
	return base + "/garrys-modcraft/run";
}

bool MakeDir(const std::string &dir, unsigned)
{
	const std::wstring w = Widen(dir);
	if (w.empty())
		return false;
	if (CreateDirectoryW(w.c_str(), nullptr))
		return true;
	// Exists already (as a directory), or a drive root like "C:" that CreateDirectory refuses.
	const DWORD a = GetFileAttributesW(w.c_str());
	return a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_DIRECTORY) != 0;
}

bool IsDir(const std::string &path)
{
	const std::wstring w = Widen(path);
	if (w.empty())
		return false;
	const DWORD a = GetFileAttributesW(w.c_str());
	return a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_DIRECTORY) != 0;
}

bool RenameReplace(const std::string &from, const std::string &to)
{
	const std::wstring f = Widen(from), t = Widen(to);
	return !f.empty() && !t.empty() && MoveFileExW(f.c_str(), t.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH) != 0;
}

bool ListDir(const std::string &dir, std::vector<std::string> *names)
{
	names->clear();
	const std::wstring w = Widen(dir);
	if (w.empty())
		return false;
	std::error_code ec;
	std::filesystem::directory_iterator it(std::filesystem::path(w), ec), end;
	if (ec)
		return false;
	for (; it != end; it.increment(ec))
	{
		if (ec)
			return false;
		const std::wstring name = it->path().filename().wstring();
		names->push_back(Narrow(name.c_str(), name.size()));
	}
	return true;
}

long long Pid()
{
	return static_cast<long long>(GetCurrentProcessId());
}

void LowerThreadPriority()
{
	SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_BELOW_NORMAL);
}

// UNVERIFIED: GMod's x86-64 branch ships bin/win64/engine.dll and materialsystem.dll (no "_client"
// suffix as on Linux). To be confirmed on a Windows install.
const char *EngineLibName(EngineLib lib)
{
	return lib == EngineLib::kEngine ? "engine.dll" : "materialsystem.dll";
}

void *EngineFactory(EngineLib lib)
{
	HMODULE h = GetModuleHandleA(EngineLibName(lib));  // never loads it; no reference taken
	if (h == nullptr)
		return nullptr;
	return reinterpret_cast<void *>(GetProcAddress(h, "CreateInterface"));
}

std::vector<std::string> CommandLineArgs()
{
	std::vector<std::string> out;
	int argc = 0;
	LPWSTR *argv = CommandLineToArgvW(GetCommandLineW(), &argc);
	if (argv == nullptr)
		return out;
	for (int i = 0; i < argc; ++i)
		out.push_back(Narrow(argv[i], wcslen(argv[i])));
	LocalFree(argv);
	return out;
}
}  // namespace os
}  // namespace gc

#endif  // _WIN32
