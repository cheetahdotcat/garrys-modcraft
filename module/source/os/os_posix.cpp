// The OS layer on Linux (see os.hpp). The bodies are the module's original POSIX code, moved here.
#ifndef _WIN32

#include "os.hpp"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <ctime>

#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <link.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace gc
{
namespace os
{
std::uint64_t MonoNs()
{
	timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return static_cast<std::uint64_t>(ts.tv_sec) * 1000000000ull + static_cast<std::uint64_t>(ts.tv_nsec);
}

bool RandomBytes(void *out, std::size_t n)
{
	int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
	if (fd < 0)
		return false;
	auto *p = static_cast<std::uint8_t *>(out);
	std::size_t got = 0;
	while (got < n)
	{
		ssize_t r = read(fd, p + got, n - got);
		if (r < 0 && errno == EINTR)
			continue;
		if (r <= 0)
			break;
		got += static_cast<std::size_t>(r);
	}
	close(fd);
	return got == n;
}

std::string RunDir()
{
	return "/dev/shm/gmodcraft";
}

bool MakeDir(const std::string &dir, unsigned mode)
{
	return mkdir(dir.c_str(), static_cast<mode_t>(mode)) == 0 || errno == EEXIST;
}

bool IsDir(const std::string &path)
{
	struct stat st{};
	return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool RenameReplace(const std::string &from, const std::string &to)
{
	return std::rename(from.c_str(), to.c_str()) == 0;
}

bool ListDir(const std::string &dir, std::vector<std::string> *names)
{
	names->clear();
	DIR *d = opendir(dir.c_str());
	if (d == nullptr)
		return false;
	while (const dirent *e = readdir(d))
		if (std::strcmp(e->d_name, ".") != 0 && std::strcmp(e->d_name, "..") != 0)
			names->push_back(e->d_name);
	closedir(d);
	return true;
}

long long Pid()
{
	return static_cast<long long>(getpid());
}

void LowerThreadPriority()
{
	setpriority(PRIO_PROCESS, static_cast<id_t>(syscall(SYS_gettid)), 10);
}

const char *EngineLibName(EngineLib lib)
{
	return lib == EngineLib::kEngine ? "engine_client.so" : "materialsystem_client.so";
}

namespace
{
struct FindLib
{
	const char *suffix;
	std::string path;
};

int FindLibCb(struct dl_phdr_info *info, size_t, void *data)
{
	auto *f = static_cast<FindLib *>(data);
	if (info->dlpi_name == nullptr)
		return 0;
	size_t n = std::strlen(info->dlpi_name), m = std::strlen(f->suffix);
	if (n >= m && std::strcmp(info->dlpi_name + n - m, f->suffix) == 0)
	{
		f->path = info->dlpi_name;
		return 1;
	}
	return 0;
}
}  // namespace

void *EngineFactory(EngineLib lib)
{
	std::string suffix = std::string("/") + EngineLibName(lib);
	FindLib f{ suffix.c_str(), {} };
	dl_iterate_phdr(FindLibCb, &f);
	if (f.path.empty())
		return nullptr;
	void *h = dlopen(f.path.c_str(), RTLD_NOW | RTLD_NOLOAD);
	if (h == nullptr)
		return nullptr;
	void *sym = dlsym(h, "CreateInterface");
	dlclose(h);  // the engine keeps it loaded
	return sym;
}

std::vector<std::string> CommandLineArgs()
{
	std::vector<std::string> out;
	FILE *f = std::fopen("/proc/self/cmdline", "rb");
	if (f == nullptr)
		return out;
	std::string all;
	char buf[4096];
	std::size_t n;
	while ((n = std::fread(buf, 1, sizeof buf, f)) > 0 && all.size() < (1u << 20))
		all.append(buf, n);
	std::fclose(f);
	std::size_t pos = 0;
	while (pos < all.size())
	{
		std::size_t end = all.find('\0', pos);
		if (end == std::string::npos)
			end = all.size();
		out.push_back(all.substr(pos, end - pos));
		pos = end + 1;
	}
	return out;
}
}  // namespace os
}  // namespace gc

#endif  // !_WIN32
