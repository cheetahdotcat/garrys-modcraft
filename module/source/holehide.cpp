// Holes drawn by the engine itself: see holehide.hpp.
#ifdef GMODCRAFT_CLIENT

#include "holehide.hpp"

#include "blockmesh.hpp"

#include <engine/ivmodelinfo.h>
#include <materialsystem/imaterial.h>
#include <materialsystem/imaterialsystem.h>
#include <materialsystem/imesh.h>
#include <mathlib/vector.h>
#include <texture_group_names.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <unordered_map>
#include <unordered_set>

#include "os/os.hpp"

// Windows: the engine binding below reads the process's mappings from /proc/self/maps. There is no
// such file there (a VirtualQuery walk would be the port, optional per the Windows plan), so the
// mapping lists stay empty, every Readable() check fails, Init fails closed and the stencil path
// (client/blocks.lua) does the holes.

namespace gc
{
IMaterialSystem *ClientMatSys();  // client.cpp

namespace hide
{
namespace
{
double NowMs() { return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count(); }

// ---- reads only inside readable mappings ---------------------------------------------------------
struct Range
{
	std::uintptr_t lo, hi;
	bool write;
	bool anon = false;  // no file behind it (heap, anonymous): where new[] arrays live
};
std::vector<Range> g_maps;

void LoadMaps()
{
	g_maps.clear();
#ifndef _WIN32  // Windows: stays empty, fails closed (see the includes)
	FILE *f = std::fopen("/proc/self/maps", "r");
	if (!f)
		return;
	char line[1024];
	while (std::fgets(line, sizeof line, f))
	{
		unsigned long lo = 0, hi = 0;
		char perm[8] = {};
		int pathAt = 0;
		if (std::sscanf(line, "%lx-%lx %7s %*s %*s %*s %n", &lo, &hi, perm, &pathAt) < 3 || perm[0] != 'r')
			continue;
		const char *path = pathAt > 0 ? line + pathAt : "";
		const bool anon = path[0] == '\0' || path[0] == '\n' || std::strncmp(path, "[heap]", 6) == 0;
		g_maps.push_back({ lo, hi, perm[1] == 'w', anon });
	}
	std::fclose(f);
#endif
}

const Range *MappingOf(std::uintptr_t a)
{
	auto it = std::upper_bound(g_maps.begin(), g_maps.end(), a, [](std::uintptr_t v, const Range &r) { return v < r.lo; });
	if (it == g_maps.begin() || a >= (it - 1)->hi)
		return nullptr;
	return &*(it - 1);
}

bool Readable(const void *p, std::size_t n, bool needWrite = false)
{
	// /proc/self/maps lists the mappings in address order: the last one starting at or below a.
	const auto a = reinterpret_cast<std::uintptr_t>(p);
	auto it = std::upper_bound(g_maps.begin(), g_maps.end(), a, [](std::uintptr_t v, const Range &r) { return v < r.lo; });
	if (it == g_maps.begin())
		return false;
	const Range &r = *(it - 1);
	return a + n <= r.hi && a + n >= a && (!needWrite || r.write);
}

template <class T> bool Rd(const void *base, std::size_t off, T &out)
{
	const auto *p = static_cast<const std::uint8_t *>(base) + off;
	if (!Readable(p, sizeof(T)))
		return false;
	std::memcpy(&out, p, sizeof(T));
	return true;
}

// ---- the map file --------------------------------------------------------------------------------
constexpr int kLumpPlanes = 1, kLumpTexdata = 2, kLumpVerts = 3, kLumpTexinfo = 6, kLumpFaces = 7, kLumpEdges = 12, kLumpSurfedges = 13,
			  kLumpModels = 14, kLumpTexStrData = 43, kLumpTexStrTable = 44, kLumpFacesHdr = 58;
constexpr std::size_t kFaceSize = 56, kTexinfoSize = 72, kPlaneSize = 20, kTexdataSize = 32, kModelSize = 48;
constexpr std::int32_t kSurfSky2d = 0x2, kSurfSky = 0x4, kSurfWarp = 0x8, kSurfNodraw = 0x80;

// Engine draw-flag bits that are relied on. kSkyBit is checked against the map (set on exactly the
// sky faces) before anything is written; kBumpBit is checked against the lightmap page layout.
constexpr std::uint32_t kSkyBit = 0x4, kBumpBit = 0x8;

using Lump = holes::MapLump;

using V3 = std::array<float, 3>;
using Poly = std::vector<V3>;

struct Face
{
	int index = 0;  // in the faces lump (= the engine's surface index)
	V3 n{};         // outward normal (the side's)
	float d = 0;    // n . p = d on the face
	Poly poly;
	float lo[3]{}, hi[3]{};
	int texinfo = -1;
	int lmMins[2]{}, lmSize[2]{};
	bool lit = false;  // has lightmap samples (lightofs >= 0)
	float texVec[2][4]{}, lmVec[2][4]{};
	std::string texName;  // texdata name, lowercased, '/' separators
	int sortId = -1;      // the engine's material sort id (BindSorts)
};

// ---- the engine's world ----------------------------------------------------------------------------
struct SortInfo
{
	IMaterial *material = nullptr;
	int page = 0;
	int pageW = 1, pageH = 1;
};
struct Engine
{
	const model_t *model = nullptr;
	std::string name;
	std::uint8_t *surf = nullptr;  // per-face draw data
	std::size_t stride = 0;
	std::size_t sortOff = 0;       // u16 material sort id (found by BindSorts)
	std::size_t texOff = 0;        // u16 texinfo << 1
	std::size_t planeOff = 0;      // pointer to the face's plane
	const std::uint8_t *light = nullptr;  // per-face lighting data
	std::size_t lstride = 0, lminsOff = 0;  // short mins[2], extents[2], offset into page[2]
	std::size_t count = 0;
	std::vector<SortInfo> sorts;
};

struct HiddenFace
{
	int face;
	int edges;            // the top byte the flags word must still hold
	std::uintptr_t plane;  // the plane pointer field it must still hold
};

struct Group
{
	int sort = 0;
	std::vector<IMesh *> meshes;
};

struct State
{
	bool ready = false;
	std::vector<Face> faces;   // world model faces that can be hidden
	std::vector<int> faceEdges;  // per faces-lump index: edge count (for the flags check)
	Engine eng;
	std::string mapName;
	// spatial index: faces per 256-unit cell (x, y, z)
	std::unordered_map<std::uint64_t, std::vector<int>> grid;
	std::vector<std::uint32_t> stamp;
	std::uint32_t stampGen = 0;
	// what is applied
	std::vector<HiddenFace> hidden;
	std::vector<Group> groups;
	// the crust's band depth: the solid under a face
	holes::SolidBrushes brushes;
	gmodcraft::mapcol::BspTree tree;
} g;
Stats g_stats;
std::uint64_t g_gen = 0;
int g_crust = 1;  // SetCrust

constexpr float kCell = 256.0f;
constexpr float kBehind = 1.0f;     // units behind a face that are tested (as the face cut)
constexpr float kMinHideArea = 1.0f;  // a face is hidden when a dug cell takes more than this of it

std::uint64_t CellKey(int x, int y, int z)
{
	return (static_cast<std::uint64_t>(static_cast<std::uint32_t>(x) & 0x1fffff) << 42) | (static_cast<std::uint64_t>(static_cast<std::uint32_t>(y) & 0x1fffff) << 21) |
		(static_cast<std::uint64_t>(static_cast<std::uint32_t>(z) & 0x1fffff));
}
int CellOf(float v) { return static_cast<int>(std::floor(v / kCell)); }

float Dot3(const float *a, const float *b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }


std::string Lower(std::string s)
{
	for (char &c : s)
	{
		if (c >= 'A' && c <= 'Z')
			c = static_cast<char>(c - 'A' + 'a');
		if (c == '\\')
			c = '/';
	}
	return s;
}

// ---- the engine binding ---------------------------------------------------------------------------
IVModelInfoClient *ModelInfo()
{
	static IVModelInfoClient *mi = nullptr;
	if (mi)
		return mi;
	auto factory = reinterpret_cast<CreateInterfaceFn>(os::EngineFactory(os::EngineLib::kEngine));
	if (factory)
		mi = static_cast<IVModelInfoClient *>(factory(VMODELINFO_CLIENT_INTERFACE_VERSION, nullptr));
	return mi;
}

// The world model as the engine has it now (or nullptr).
const model_t *WorldModel(std::string *name)
{
	IVModelInfoClient *mi = ModelInfo();
	const model_t *w = mi ? mi->GetModel(1) : nullptr;
	const char *nm = w ? mi->GetModelName(w) : nullptr;
	if (!w || !nm || std::strncmp(nm, "maps/", 5) != 0)
		return nullptr;
	if (name)
		*name = nm;
	return w;
}

struct FileFace
{
	std::uint16_t planenum;
	std::int16_t texinfo, dispinfo;
	int numedges, lightofs;
	int lmMins[2], lmSize[2];
};

// Finds the per-face arrays and checks them against the map file. err says which check failed.
bool BindEngine(const std::vector<FileFace> &ff, const Lump &texinfo, std::size_t planeCount, Engine &eng, std::string &err, std::string &probe)
{
	LoadMaps();
	eng = Engine{};
	IVModelInfoClient *mi = ModelInfo();
	const model_t *world = WorldModel(&eng.name);
	if (!mi || !world)
	{
		err = "no world model";
		return false;
	}
	eng.model = world;
	Vector mins, maxs;
	mi->GetModelBounds(world, mins, maxs);
	const float want[6] = { mins.x, mins.y, mins.z, maxs.x, maxs.y, maxs.z };
	std::size_t boundsOff = SIZE_MAX;
	for (std::size_t o = 0; o + 24 <= 0x200; o += 4)
	{
		float f[6];
		if (!Rd(world, o, f))
			break;
		if (std::memcmp(f, want, sizeof f) == 0)
		{
			boundsOff = o;
			break;
		}
	}
	if (boundsOff == SIZE_MAX)
	{
		err = "world bounds not found";
		return false;
	}
	const std::size_t faceCount = ff.size();
	const std::uint8_t *shared = nullptr;
	std::size_t planesOff = 0;
	std::vector<std::size_t> countOffs;
	for (std::size_t o = boundsOff + 24; o < boundsOff + 24 + 0x40 && !shared; o += 8)
	{
		std::uintptr_t p = 0;
		if (!Rd(world, o, p) || !Readable(reinterpret_cast<void *>(p), 0x400))
			continue;
		const auto *cand = reinterpret_cast<const std::uint8_t *>(p);
		std::size_t po = 0;
		std::vector<std::size_t> so;
		for (std::size_t q = 0; q + 16 <= 0x400; q += 4)
		{
			std::int32_t v = 0;
			Rd(cand, q, v);
			if (!po && static_cast<std::size_t>(v) == planeCount && v > 0)
				po = q;
			if (po && q > po && static_cast<std::size_t>(v) == faceCount)
				so.push_back(q);
		}
		if (po && !so.empty())
		{
			shared = cand;
			planesOff = po;
			countOffs = so;
		}
	}
	if (!shared)
	{
		err = "shared brush data not found";
		return false;
	}
	std::uintptr_t planes = 0;
	Rd(shared, (planesOff + 4 + 7) & ~std::size_t(7), planes);

	std::vector<char> isSky(faceCount);
	for (std::size_t i = 0; i < faceCount; ++i)
	{
		const int t = ff[i].texinfo;
		isSky[i] = t >= 0 && (texinfo.At<std::int32_t>(std::size_t(t) * kTexinfoSize + 64) & (kSurfSky | kSurfSky2d)) != 0;
	}
	// Candidate arrays: the pointers after numsurfaces.
	std::vector<std::uintptr_t> arrays;
	for (std::size_t co : countOffs)
		for (int k = 0; k < 6; ++k)
		{
			std::uintptr_t a = 0;
			if (Rd(shared, ((co + 4 + 7) & ~std::size_t(7)) + 8 * std::size_t(k), a) && a)
				arrays.push_back(a);
		}
	// The draw data: a stride where a pointer field is planes + 20 * planenum on every face, the
	// first word has the sky bit on exactly the sky faces and the face's edge count in its top byte,
	// and a u16 field >> 1 is the face's texinfo.
	for (std::uintptr_t a : arrays)
	{
		const auto *A = reinterpret_cast<std::uint8_t *>(a);
		for (std::size_t stride = 16; stride <= 128 && !eng.surf; stride += 4)
		{
			if (!Readable(A, stride * faceCount, true))
				continue;
			bool flagsOk = true;
			for (std::size_t i = 0; i < faceCount && flagsOk; ++i)
			{
				std::uint32_t fl;
				std::memcpy(&fl, A + i * stride, 4);
				flagsOk = ((fl & kSkyBit) != 0) == (isSky[i] != 0) && (fl >> 24) == static_cast<std::uint32_t>(std::min(255, ff[i].numedges));
			}
			if (!flagsOk)
				continue;
			bool planeOk = false;
			for (std::size_t off = 4; off + 8 <= stride && !planeOk; off += 4)
			{
				std::size_t i = 0;
				for (; i < faceCount; ++i)
				{
					std::uintptr_t v;
					std::memcpy(&v, A + i * stride + off, 8);
					if (v != planes + kPlaneSize * ff[i].planenum)
						break;
				}
				planeOk = i == faceCount;
				if (planeOk)
					eng.planeOff = off;
			}
			std::size_t texOff = 0;
			for (std::size_t off = 4; off + 2 <= stride && !texOff; off += 2)
			{
				std::size_t i = 0;
				for (; i < faceCount; ++i)
				{
					std::uint16_t v;
					std::memcpy(&v, A + i * stride + off, 2);
					if (ff[i].texinfo >= 0 && (v >> 1) != static_cast<std::uint16_t>(ff[i].texinfo))
						break;
				}
				if (i == faceCount)
					texOff = off;
			}
			if (planeOk && texOff >= 4)
			{
				eng.surf = const_cast<std::uint8_t *>(A);
				eng.stride = stride;
				eng.texOff = texOff;
			}
		}
		if (eng.surf)
			break;
	}
	if (!eng.surf)
	{
		err = "per-face draw data not found";
		return false;
	}
	eng.count = faceCount;
	// The lighting data: short mins[2] and extents[2] equal the file's lightmap mins / size on every lit face.
	for (std::uintptr_t a : arrays)
	{
		const auto *A = reinterpret_cast<const std::uint8_t *>(a);
		if (A == eng.surf)
			continue;
		for (std::size_t stride = 12; stride <= 96 && !eng.light; stride += 4)
		{
			if (!Readable(A, stride * faceCount))
				continue;
			for (std::size_t m = 0; m + 12 <= stride && !eng.light; m += 2)
			{
				std::size_t i = 0, lit = 0;
				for (; i < faceCount; ++i)
				{
					if (ff[i].lightofs < 0 || isSky[i])
						continue;
					std::int16_t s[4];
					std::memcpy(s, A + i * stride + m, 8);
					if (s[0] != ff[i].lmMins[0] || s[1] != ff[i].lmMins[1] || s[2] != ff[i].lmSize[0] || s[3] != ff[i].lmSize[1])
						break;
					++lit;
				}
				if (i == faceCount && lit > 0)
				{
					eng.light = A;
					eng.lstride = stride;
					eng.lminsOff = m;
				}
			}
		}
		if (eng.light)
			break;
	}
	if (!eng.light)
	{
		err = "per-face lighting data not found";
		return false;
	}
	char b[200];
	std::snprintf(b, sizeof b, "faces %zu, draw data stride %zu texinfo +%zu, lighting stride %zu mins +%zu", faceCount, eng.stride, eng.texOff, eng.lstride, eng.lminsOff);
	probe = b;
	return true;
}

std::uintptr_t SurfPlane(int face)
{
	std::uintptr_t v;
	std::memcpy(&v, g.eng.surf + std::size_t(face) * g.eng.stride + g.eng.planeOff, 8);
	return v;
}
// The face's draw data still is what was hidden (same edge count, same plane pointer, sky bit set).
bool StillOurs(const HiddenFace &h, std::uint32_t fl)
{
	return (fl >> 24) == static_cast<std::uint32_t>(h.edges) && (fl & kSkyBit) && SurfPlane(h.face) == h.plane;
}

std::uint32_t SurfFlags(int face)
{
	std::uint32_t fl;
	std::memcpy(&fl, g.eng.surf + std::size_t(face) * g.eng.stride, 4);
	return fl;
}
void SetSurfFlags(int face, std::uint32_t fl) { std::memcpy(g.eng.surf + std::size_t(face) * g.eng.stride, &fl, 4); }
int SortId(int face)
{
	std::uint16_t v;
	std::memcpy(&v, g.eng.surf + std::size_t(face) * g.eng.stride + g.eng.sortOff, 2);
	return v;
}
void LightOffset(int face, int out[2])
{
	std::int16_t s[2];
	std::memcpy(s, g.eng.light + std::size_t(face) * g.eng.lstride + g.eng.lminsOff + 8, 4);
	out[0] = s[0];
	out[1] = s[1];
}

// Mappings of one library (by file name suffix): its own and the anonymous one right after it (.bss).
struct LibRanges
{
	std::vector<Range> all, rw;
};
LibRanges LibMappings(const char *suffix)
{
	LibRanges out;
#ifdef _WIN32
	(void)suffix;  // stays empty: BindSorts fails closed (see the includes)
#else
	FILE *f = std::fopen("/proc/self/maps", "r");
	if (!f)
		return out;
	char line[1024];
	bool prevLib = false;
	const std::size_t m = std::strlen(suffix);
	while (std::fgets(line, sizeof line, f))
	{
		unsigned long lo = 0, hi = 0;
		char perm[8] = {};
		int pathAt = 0;
		if (std::sscanf(line, "%lx-%lx %7s %*s %*s %*s %n", &lo, &hi, perm, &pathAt) < 3)
			continue;
		std::string path = pathAt > 0 ? std::string(line + pathAt) : std::string();
		while (!path.empty() && (path.back() == '\n' || path.back() == ' '))
			path.pop_back();
		const bool lib = path.size() >= m && path.compare(path.size() - m, m, suffix) == 0;
		if (lib || (prevLib && path.empty()))
		{
			out.all.push_back({ lo, hi, perm[1] == 'w' });
			if (perm[0] == 'r' && perm[1] == 'w')
				out.rw.push_back({ lo, hi, true });
		}
		prevLib = lib;
	}
	std::fclose(f);
#endif
	return out;
}

// The engine's own table of material sort ids (material, lightmap page), as the world rendering
// uses it: a pointer held in engine_client.so's data to a heap array, found by the faces' sort ids
// holding the very material pointers the material system returns for the faces' texture names
// (FindMaterial). The u16 field of the per-face draw data that holds the sort id is found the same way.
bool BindSorts(std::string &err, std::string &probe)
{
	IMaterialSystem *ms = ClientMatSys();
	if (!ms)
	{
		err = "no material system";
		return false;
	}
	LoadMaps();
	const LibRanges eng = LibMappings((std::string("/") + os::EngineLibName(os::EngineLib::kEngine)).c_str());
	if (eng.rw.empty())
	{
		err = "engine mappings not found";
		return false;
	}
	// Sample faces with names, spread over the world.
	std::vector<const Face *> sample;
	for (std::size_t i = 0; i < g.faces.size() && sample.size() < 48; i += std::max<std::size_t>(1, g.faces.size() / 48))
		if (!g.faces[i].texName.empty())
			sample.push_back(&g.faces[i]);
	if (sample.size() < 8)
	{
		err = "too few named faces";
		return false;
	}
	auto u16At = [](int face, std::size_t off) {
		std::uint16_t v = 0xffff;
		if (off + 2 > g.eng.stride)
			return v;
		std::memcpy(&v, g.eng.surf + std::size_t(face) * g.eng.stride + off, 2);
		return v;
	};
	// Nothing found in memory is called: an entry counts when its material pointer is the one the
	// material system hands out for the face's texture name.
	std::unordered_map<std::string, std::uintptr_t> byName;
	auto wanted = [&](const std::string &tex) -> std::uintptr_t {
		auto it = byName.find(tex);
		if (it != byName.end())
			return it->second;
		IMaterial *m = ms->FindMaterial(tex.c_str(), TEXTURE_GROUP_WORLD, false);
		const std::uintptr_t v = reinterpret_cast<std::uintptr_t>(m);  // an error material never equals a table entry
		byName[tex] = v;
		return v;
	};
	auto entryAt = [&](std::uintptr_t table, int id) -> std::uintptr_t {
		std::uintptr_t m = 0;
		if (!Rd(reinterpret_cast<const void *>(table), std::size_t(id) * 16, m))
			return 0;
		return m;
	};
	auto nameOk = [&](std::uintptr_t m, const std::string &tex) { return m != 0 && m == wanted(tex); };
	std::uintptr_t table = 0;
	std::size_t sortOff = 0;
	std::size_t candidates = 0;
	for (const Range &r : eng.rw)
	{
		for (std::uintptr_t a = r.lo; a + 8 <= r.hi && !table; a += 8)
		{
			std::uintptr_t q;
			std::memcpy(&q, reinterpret_cast<const void *>(a), 8);
			if (!q || (q & 7))
				continue;
			const Range *qr = MappingOf(q);
			if (!qr || !qr->anon || q + 16 > qr->hi)
				continue;
			for (std::size_t off = 16; off <= 26 && !table; off += 2)
			{
				const Face &f0 = *sample[0];
				if (!nameOk(entryAt(q, u16At(f0.index, off)), f0.texName))
					continue;
				++candidates;
				std::size_t ok = 0;
				for (const Face *f : sample)
					ok += nameOk(entryAt(q, u16At(f->index, off)), f->texName);
				if (ok * 100 >= sample.size() * 95)
				{
					table = q;
					sortOff = off;
				}
			}
		}
		if (table)
			break;
	}
	if (!table)
	{
		err = "the engine's material sort table not found (" + std::to_string(candidates) + " candidates)";
		return false;
	}
	g.eng.sortOff = sortOff;
	int maxId = 0;
	for (const Face &f : g.faces)
		maxId = std::max(maxId, SortId(f.index));
	if (!Readable(reinterpret_cast<void *>(table), std::size_t(maxId + 1) * 16))
	{
		err = "the material sort table is shorter than the faces' sort ids";
		return false;
	}
	g.eng.sorts.assign(std::size_t(maxId) + 1, SortInfo{});
	std::size_t named = 0, matched = 0, placed = 0;
	for (Face &f : g.faces)
	{
		const int id = SortId(f.index);
		f.sortId = id;
		SortInfo &s = g.eng.sorts[std::size_t(id)];
		if (!s.material)
		{
			const std::uintptr_t m = entryAt(table, id);
			// Only a pointer the material system handed out for some face's name is used as a material.
			if (!m || m != wanted(f.texName))
			{
				err = "face " + std::to_string(f.index) + ": sort id " + std::to_string(id) + " doesn't hold its material";
				return false;
			}
			std::int32_t page = 0;
			Rd(reinterpret_cast<const void *>(table), std::size_t(id) * 16 + 8, page);
			s.material = reinterpret_cast<IMaterial *>(m);
			s.page = page;
			if (page > 4096)
			{
				err = "sort id " + std::to_string(id) + ": lightmap page " + std::to_string(page);
				return false;
			}
			if (page >= 0)
			{
				int w = 0, h = 0;
				ms->GetLightmapPageSize(page, &w, &h);
				if (w <= 0 || h <= 0 || w > 16384 || h > 16384)
				{
					err = "lightmap page " + std::to_string(page) + " size " + std::to_string(w) + "x" + std::to_string(h);
					return false;
				}
				s.pageW = w;
				s.pageH = h;
			}
			else if (page < -3)
			{
				err = "sort id " + std::to_string(id) + ": lightmap page " + std::to_string(page);
				return false;
			}
		}
		if (!f.texName.empty())
		{
			++named;
			matched += nameOk(reinterpret_cast<std::uintptr_t>(s.material), f.texName);
		}
		if (f.lit && s.page >= 0)
		{
			int off[2];
			LightOffset(f.index, off);
			const int mul = (SurfFlags(f.index) & kBumpBit) ? 4 : 1;
			if (off[0] < 0 || off[1] < 0 || off[0] + (f.lmSize[0] + 1) * mul > s.pageW || off[1] + f.lmSize[1] + 1 > s.pageH)
			{
				char b[160];
				std::snprintf(b, sizeof b, "face %d: lightmap %d,%d size %dx%d (x%d) outside page %d (%dx%d)", f.index, off[0], off[1], f.lmSize[0] + 1,
					f.lmSize[1] + 1, mul, s.page, s.pageW, s.pageH);
				err = b;
				return false;
			}
			++placed;
		}
	}
	if (named == 0 || matched * 100 < named * 95)
	{
		err = "material names match on " + std::to_string(matched) + " of " + std::to_string(named) + " faces";
		return false;
	}
	char b[200];
	std::snprintf(b, sizeof b, "; sort id +%zu, sort table %zu ids, names %zu/%zu, lit faces placed %zu", sortOff, g.eng.sorts.size(), matched, named, placed);
	probe += b;
	return true;
}

// ---- meshes ---------------------------------------------------------------------------------------
struct Vtx
{
	float pos[3], n[3], uv[2], lm[2], bump[2], ts[3], tt[3], sign;
};

// Meshes dropped without a render context: destroyed with the next one.
std::vector<IMesh *> g_orphans;

void DropMeshes(IMatRenderContext *ctx)
{
	for (Group &gr : g.groups)
		for (IMesh *m : gr.meshes)
			g_orphans.push_back(m);
	g.groups.clear();
	if (ctx)
	{
		for (IMesh *m : g_orphans)
			ctx->DestroyStaticMesh(m);
		g_orphans.clear();
	}
}

IMesh *BuildMesh(IMatRenderContext *ctx, IMaterial *mat, const Vtx *v, int n)
{
	VertexFormat_t fmt = mat->GetVertexFormat() & ~static_cast<VertexFormat_t>(VERTEX_FORMAT_COMPRESSED);
	fmt |= VERTEX_POSITION | VERTEX_NORMAL;
	for (int i = 0; i < 3; ++i)
		if (TexCoordSize(i, fmt) == 0)
			fmt |= VERTEX_TEXCOORD_SIZE(i, 2);
	IMesh *m = ctx->CreateStaticMesh(fmt, TEXTURE_GROUP_STATIC_VERTEX_BUFFER_WORLD, mat);
	if (!m)
		return nullptr;
	m->SetPrimitiveType(MATERIAL_TRIANGLES);
	MeshDesc_t d;
	std::memset(&d, 0, sizeof d);
	m->LockMesh(n, n, d);
	if (!d.m_pPosition || d.m_VertexSize_Position < 12 || d.m_CompressionType != VERTEX_COMPRESSION_NONE || !d.m_pIndices || d.m_nIndexSize == 0 ||
		d.m_nFirstVertex < 0 || d.m_nFirstVertex + n > 65535)
	{
		m->UnlockMesh(0, 0, d);
		ctx->DestroyStaticMesh(m);
		return nullptr;
	}
	if (d.m_ActualVertexSize > 0)
		std::memset(d.m_pPosition, 0, std::size_t(n) * std::size_t(d.m_ActualVertexSize));  // components not written stay 0
	auto put = [](void *base, int stride, int i, const float *src, int count, int have) {
		if (!base || stride == 0)
			return;
		float *p = reinterpret_cast<float *>(reinterpret_cast<std::uint8_t *>(base) + std::size_t(i) * std::size_t(stride));
		for (int k = 0; k < count && k < have; ++k)
			p[k] = src[k];
	};
	for (int i = 0; i < n; ++i)
	{
		const Vtx &x = v[i];
		put(d.m_pPosition, d.m_VertexSize_Position, i, x.pos, 3, 3);
		put(d.m_pNormal, d.m_VertexSize_Normal, i, x.n, 3, 3);
		const float *tc[3] = { x.uv, x.lm, x.bump };
		for (int t = 0; t < 3; ++t)
			put(d.m_pTexCoord[t], d.m_VertexSize_TexCoord[t], i, tc[t], 2, std::max(1, TexCoordSize(t, fmt)));
		put(d.m_pTangentS, d.m_VertexSize_TangentS, i, x.ts, 3, 3);
		put(d.m_pTangentT, d.m_VertexSize_TangentT, i, x.tt, 3, 3);
		if (d.m_pUserData && d.m_VertexSize_UserData)
		{
			const float u[4] = { x.ts[0], x.ts[1], x.ts[2], x.sign };
			put(d.m_pUserData, d.m_VertexSize_UserData, i, u, 4, std::max(1, UserDataSize(fmt)));
		}
		if (d.m_pColor && d.m_VertexSize_Color)
			std::memset(d.m_pColor + std::size_t(i) * std::size_t(d.m_VertexSize_Color), 255, 4);
		if (d.m_pSpecular && d.m_VertexSize_Specular)
			std::memset(d.m_pSpecular + std::size_t(i) * std::size_t(d.m_VertexSize_Specular), 255, 4);
		d.m_pIndices[i] = static_cast<unsigned short>(d.m_nFirstVertex + i);
	}
	m->UnlockMesh(n, n, d);
	return m;
}

void MakeVertex(const Face &f, const SortInfo &s, int off[2], bool bump, const V3 &p, int mapW, int mapH, Vtx &o)
{
	for (int k = 0; k < 3; ++k)
	{
		o.pos[k] = p[k];
		o.n[k] = f.n[k];
	}
	o.uv[0] = (Dot3(p.data(), f.texVec[0]) + f.texVec[0][3]) / static_cast<float>(mapW);
	o.uv[1] = (Dot3(p.data(), f.texVec[1]) + f.texVec[1][3]) / static_cast<float>(mapH);
	for (int k = 0; k < 2; ++k)
	{
		const float luxel = f.lit ? Dot3(p.data(), f.lmVec[k]) + f.lmVec[k][3] - static_cast<float>(f.lmMins[k]) + 0.5f : 0.5f;
		o.lm[k] = (luxel + static_cast<float>(off[k])) / static_cast<float>(k == 0 ? s.pageW : s.pageH);
	}
	o.bump[0] = bump ? static_cast<float>(f.lmSize[0] + 1) / static_cast<float>(s.pageW) : 0.0f;
	o.bump[1] = 0;
	float ts[3] = { f.texVec[0][0], f.texVec[0][1], f.texVec[0][2] }, tt[3] = { f.texVec[1][0], f.texVec[1][1], f.texVec[1][2] };
	const float ls = std::sqrt(Dot3(ts, ts)), lt = std::sqrt(Dot3(tt, tt));
	for (int k = 0; k < 3; ++k)
	{
		o.ts[k] = ls > 0 ? ts[k] / ls : 0;
		o.tt[k] = lt > 0 ? -tt[k] / lt : 0;  // texture v grows downwards
	}
	const float c[3] = { f.n[1] * o.ts[2] - f.n[2] * o.ts[1], f.n[2] * o.ts[0] - f.n[0] * o.ts[2], f.n[0] * o.ts[1] - f.n[1] * o.ts[0] };
	o.sign = Dot3(c, o.tt) >= 0 ? 1.0f : -1.0f;
}

void RestoreAll()
{
	if (!g.ready || g.hidden.empty())
	{
		g.hidden.clear();
		return;
	}
	LoadMaps();
	std::string name;
	const bool same = WorldModel(&name) == g.eng.model && name == g.eng.name;
	for (const HiddenFace &h : g.hidden)
	{
		std::uint8_t *w = g.eng.surf + std::size_t(h.face) * g.eng.stride;
		if (!same || !Readable(w, 4, true))
			break;
		std::uint32_t fl;
		std::memcpy(&fl, w, 4);
		if (StillOurs(h, fl))
		{
			fl &= ~kSkyBit;
			std::memcpy(w, &fl, 4);
		}
	}
	g.hidden.clear();
}

// ---- the crust -----------------------------------------------------------------------------
// The solid under faces: the brushes (detail ones too) and the tree's solid leaves. Without them (a
// compressed lump, an odd file) there is no crust; the holes work as before.
void InitCrust(const char *data, std::size_t n, const Lump *L)
{
	constexpr int kLumpBrushes = 18, kLumpBrushSides = 19;
	auto raw = [&](int i) {
		std::int32_t fourcc = 0;
		std::memcpy(&fourcc, data + 8 + 16 * i + 12, 4);
		return fourcc == 0 ? L[i] : Lump{};
	};
	g.brushes.Build(raw(kLumpBrushes), raw(kLumpBrushSides), raw(kLumpPlanes));
	namespace mc = gmodcraft::mapcol;
	mc::BspLumps lumps;
	std::string err;
	if (mc::SplitBsp(mc::Bytes{ reinterpret_cast<const std::uint8_t *>(data), n }, lumps, err))
		mc::DecodeBspTree(lumps, g.tree);
	g_stats.crustBrushes = g.brushes.Count();
	g_stats.crustTree = g.tree.ok;
}

float CrustDepth(const void *, const float p[3], const float down[3], float max)
{
	return holes::MapSolidDepth(g.tree.ok ? &g.tree : nullptr, &g.brushes, p, down, max);
}

// A band vertex: placed on the wall (pulled into the hole past the Minecraft wall quad, which lies on the
// cell face, 0.25 lower: blockmesh kTerrainDrop), textured and lit as the face at its fold point (the
// lightmap clamped to the face's own luxels), its tangents turned with the fold.
void CrustVertex(const Face &f, const SortInfo &s, int off[2], bool bump, const holes::CrustPiece &cp, const V3 &p, int mapW, int mapH, Vtx &o)
{
	float fp[3];
	const float n[3] = { f.n[0], f.n[1], f.n[2] };
	holes::CrustFold(cp, n, f.d, p.data(), fp);
	MakeVertex(f, s, off, bump, V3{ fp[0], fp[1], fp[2] }, mapW, mapH, o);
	const float pull = cp.wallN[2] < -0.5f ? 0.55f : 0.3f;
	for (int k = 0; k < 3; ++k)
	{
		o.pos[k] = p[k] + cp.wallN[k] * pull;
		o.n[k] = cp.wallN[k];
	}
	if (f.lit)
		for (int k = 0; k < 2; ++k)
		{
			float luxel = Dot3(fp, f.lmVec[k]) + f.lmVec[k][3] - static_cast<float>(f.lmMins[k]);
			luxel = std::min(std::max(luxel, 0.0f), static_cast<float>(f.lmSize[k])) + 0.5f;
			o.lm[k] = (luxel + static_cast<float>(off[k])) / static_cast<float>(k == 0 ? s.pageW : s.pageH);
		}
	// The fold turns the face's frame about the cut line: n -> wallN, fold -> -n, the cut line stays.
	float e[3] = { n[1] * cp.fold[2] - n[2] * cp.fold[1], n[2] * cp.fold[0] - n[0] * cp.fold[2], n[0] * cp.fold[1] - n[1] * cp.fold[0] };
	auto turn = [&](float v[3]) {
		const float a = Dot3(v, e), b = Dot3(v, cp.fold), c = Dot3(v, n);
		for (int k = 0; k < 3; ++k)
			v[k] = a * e[k] - b * n[k] + c * cp.wallN[k];
	};
	turn(o.ts);
	turn(o.tt);
	const float c[3] = { o.n[1] * o.ts[2] - o.n[2] * o.ts[1], o.n[2] * o.ts[0] - o.n[0] * o.ts[2], o.n[0] * o.ts[1] - o.n[1] * o.ts[0] };
	o.sign = Dot3(c, o.tt) >= 0 ? 1.0f : -1.0f;
}

// The band pieces of every hidden face, into the face's material bucket (no extra draw calls).
std::size_t AddCrust(const std::vector<holes::Box> &boxes, std::int32_t ox, std::int32_t oz, std::int32_t oy, const std::unordered_set<int> &hideSet,
	std::unordered_map<int, std::vector<Vtx>> &bySort)
{
	if (g_crust <= 0 || hideSet.empty() || (g.brushes.Count() == 0 && !g.tree.ok))
		return 0;
	// Mode 2 looks up to 1024 units (~25 blocks): deeper slabs are rare and a deep shaft under a big face would
	// otherwise clip one wall square per cell all the way down in one rebuild.
	const float cap = g_crust == 1 ? 40.0f : 1024.0f, probe = g_crust == 1 ? 80.0f : 1024.0f;
	holes::DugCells dug;
	dug.Build(boxes);
	std::vector<holes::CrustPiece> pieces;
	std::size_t count = 0;
	for (int fi : hideSet)
	{
		const Face &f = g.faces[std::size_t(fi)];
		const int sid = f.sortId;
		if (sid < 0 || static_cast<std::size_t>(sid) >= g.eng.sorts.size() || !g.eng.sorts[std::size_t(sid)].material)
			continue;
		const SortInfo &s = g.eng.sorts[std::size_t(sid)];
		if (s.material->IsTranslucent() || s.material->IsAlphaTested())
			continue;  // glass, grates, fences: no band
		pieces.clear();
		const float nn[3] = { f.n[0], f.n[1], f.n[2] };
		if (holes::FaceCrust(f.poly, nn, f.d, boxes, dug, ox, oz, oy, kBehind, cap, probe, CrustDepth, nullptr, pieces) == 0)
			continue;
		int mapW = std::max(1, s.material->GetMappingWidth()), mapH = std::max(1, s.material->GetMappingHeight());
		int off[2] = { 0, 0 };
		LightOffset(f.index, off);
		const bool bump = (SurfFlags(f.index) & kBumpBit) != 0;
		std::vector<Vtx> &out = bySort[sid];
		for (const holes::CrustPiece &cp : pieces)
		{
			++count;
			Vtx v0, v1, v2;
			CrustVertex(f, s, off, bump, cp, cp.poly[0], mapW, mapH, v0);
			for (std::size_t i = 1; i + 1 < cp.poly.size(); ++i)
			{
				CrustVertex(f, s, off, bump, cp, cp.poly[i], mapW, mapH, v1);
				CrustVertex(f, s, off, bump, cp, cp.poly[i + 1], mapW, mapH, v2);
				out.push_back(v0);  // clockwise seen from the hole (FaceCrust)
				out.push_back(v1);
				out.push_back(v2);
			}
			// The band is pulled into the hole: a strip in the face's own plane closes the slit between the
			// face's cut edge and the band's top (else the cleared hole shows through it as a dark line).
			const float pull = cp.wallN[2] < -0.5f ? 0.55f : 0.3f;
			holes::FacePoly lip;
			for (const V3 &p : cp.poly)
				if (std::fabs(f.d - Dot3(nn, p.data())) < 0.05f)
					lip.push_back(p);
			if (lip.size() == 2)
			{
				holes::FacePoly strip = { lip[0], lip[1] };
				for (int i = 1; i >= 0; --i)
					strip.push_back({ lip[std::size_t(i)][0] + cp.wallN[0] * pull, lip[std::size_t(i)][1] + cp.wallN[1] * pull, lip[std::size_t(i)][2] + cp.wallN[2] * pull });
				holes::OrientClockwise(strip, nn);
				Vtx s0, s1, s2;
				MakeVertex(f, s, off, bump, strip[0], mapW, mapH, s0);
				for (std::size_t i = 1; i + 1 < strip.size(); ++i)
				{
					MakeVertex(f, s, off, bump, strip[i], mapW, mapH, s1);
					MakeVertex(f, s, off, bump, strip[i + 1], mapW, mapH, s2);
					out.push_back(s0);
					out.push_back(s1);
					out.push_back(s2);
				}
			}
		}
	}
	return count;
}
}  // namespace

bool Init(IMatRenderContext *ctx, const char *data, std::size_t n, std::string &err)
{
	const double t0 = NowMs();
	RestoreAll();
	DropMeshes(ctx);
	g = State{};
	g_stats = Stats{};
	++g_gen;
	auto fail = [&](const std::string &e) {
		err = e;
		g_stats.error = e;
		g = State{};
		return false;
	};
	if (!data || n < 8 + 16 * 64 || std::memcmp(data, "VBSP", 4) != 0)
		return fail("not a VBSP map file");
	Lump L[64];
	for (int i = 0; i < 64; ++i)
	{
		std::int32_t off, len, fourcc;
		std::memcpy(&off, data + 8 + 16 * i, 4);
		std::memcpy(&len, data + 8 + 16 * i + 4, 4);
		std::memcpy(&fourcc, data + 8 + 16 * i + 12, 4);
		if (off < 0 || len < 0 || std::size_t(off) + std::size_t(len) > n)
			continue;
		if (fourcc != 0 && (i == kLumpFaces || i == kLumpFacesHdr || i == kLumpTexinfo || i == kLumpPlanes || i == kLumpVerts || i == kLumpEdges ||
							   i == kLumpSurfedges || i == kLumpModels || i == kLumpTexdata))
			return fail("compressed lump " + std::to_string(i));
		L[i] = { reinterpret_cast<const std::uint8_t *>(data) + off, std::size_t(len) };
	}
	const Lump &faces = L[kLumpFaces].n ? L[kLumpFaces] : L[kLumpFacesHdr];
	const std::size_t faceCount = faces.n / kFaceSize;
	if (faceCount == 0 || L[kLumpModels].n < kModelSize)
		return fail("no faces");
	std::vector<FileFace> ff(faceCount);
	for (std::size_t i = 0; i < faceCount; ++i)
	{
		const std::size_t o = i * kFaceSize;
		FileFace &f = ff[i];
		f.planenum = faces.At<std::uint16_t>(o);
		f.numedges = faces.At<std::int16_t>(o + 8);
		f.texinfo = faces.At<std::int16_t>(o + 10);
		f.dispinfo = faces.At<std::int16_t>(o + 12);
		f.lightofs = faces.At<std::int32_t>(o + 20);
		f.lmMins[0] = faces.At<std::int32_t>(o + 28);
		f.lmMins[1] = faces.At<std::int32_t>(o + 32);
		f.lmSize[0] = faces.At<std::int32_t>(o + 36);
		f.lmSize[1] = faces.At<std::int32_t>(o + 40);
	}
	// The world model's faces (brush entities move, so their faces are left alone).
	const int firstFace = L[kLumpModels].At<std::int32_t>(40), numFaces = L[kLumpModels].At<std::int32_t>(44);
	if (firstFace < 0 || numFaces <= 0 || std::size_t(firstFace) + std::size_t(numFaces) > faceCount)
		return fail("world model faces out of range");
	const Lump &planes = L[kLumpPlanes], &verts = L[kLumpVerts], &edges = L[kLumpEdges], &surfedges = L[kLumpSurfedges], &texinfo = L[kLumpTexinfo];
	const Lump &texdata = L[kLumpTexdata], &strTable = L[kLumpTexStrTable], &strData = L[kLumpTexStrData];
	const holes::MapFaceLumps geom{ faces, planes, verts, edges, surfedges };
	g.faceEdges.resize(faceCount);
	for (std::size_t i = 0; i < faceCount; ++i)
		g.faceEdges[i] = std::min(255, ff[i].numedges);
	for (int i = firstFace; i < firstFace + numFaces; ++i)
	{
		const FileFace &f = ff[std::size_t(i)];
		if (f.dispinfo >= 0 || f.texinfo < 0 || f.numedges < 3)
			continue;
		const std::size_t to = std::size_t(f.texinfo) * kTexinfoSize;
		const std::int32_t tflags = texinfo.At<std::int32_t>(to + 64);
		if (tflags & (kSurfSky | kSurfSky2d | kSurfWarp | kSurfNodraw))
			continue;
		Face face;
		face.index = i;
		face.texinfo = f.texinfo;
		// The stored plane is the face's front and the vertices go clockwise seen from it, side flag
		// or not (MapFace); OrientClockwise only guards that order for the redrawn rest.
		holes::MapFace(geom, std::size_t(i), face.poly, face.n.data(), face.d);
		holes::OrientClockwise(face.poly, face.n.data());
		for (int k = 0; k < 3; ++k)
		{
			face.lo[k] = 1e30f;
			face.hi[k] = -1e30f;
		}
		for (const V3 &p : face.poly)
			for (int k = 0; k < 3; ++k)
			{
				face.lo[k] = std::min(face.lo[k], p[k]);
				face.hi[k] = std::max(face.hi[k], p[k]);
			}
		for (int r = 0; r < 2; ++r)
			for (int c = 0; c < 4; ++c)
			{
				face.texVec[r][c] = texinfo.At<float>(to + std::size_t(r * 16 + c * 4));
				face.lmVec[r][c] = texinfo.At<float>(to + 32 + std::size_t(r * 16 + c * 4));
			}
		face.lit = f.lightofs >= 0;
		face.lmMins[0] = f.lmMins[0];
		face.lmMins[1] = f.lmMins[1];
		face.lmSize[0] = f.lmSize[0];
		face.lmSize[1] = f.lmSize[1];
		const std::int32_t td = texinfo.At<std::int32_t>(to + 68);
		if (td >= 0 && std::size_t(td + 1) * kTexdataSize <= texdata.n)
		{
			const std::int32_t sid = texdata.At<std::int32_t>(std::size_t(td) * kTexdataSize + 12);
			const std::int32_t so = strTable.At<std::int32_t>(std::size_t(sid) * 4);
			if (sid >= 0 && so >= 0 && std::size_t(so) < strData.n)
			{
				const char *s = reinterpret_cast<const char *>(strData.p + so);
				face.texName = Lower(std::string(s, strnlen(s, strData.n - std::size_t(so))));
			}
		}
		if (!face.texName.empty())  // its material is checked by name
			g.faces.push_back(std::move(face));
	}
	std::string probe;
	if (!BindEngine(ff, texinfo, planes.n / kPlaneSize, g.eng, err, probe))
		return fail(err);
	if (!BindSorts(err, probe))
		return fail(err);
	// The grid.
	for (std::size_t fi = 0; fi < g.faces.size(); ++fi)
	{
		const Face &f = g.faces[fi];
		const int x0 = CellOf(f.lo[0]), x1 = CellOf(f.hi[0]), y0 = CellOf(f.lo[1]), y1 = CellOf(f.hi[1]), z0 = CellOf(f.lo[2]), z1 = CellOf(f.hi[2]);
		for (int x = x0; x <= x1; ++x)
			for (int y = y0; y <= y1; ++y)
				for (int z = z0; z <= z1; ++z)
					g.grid[CellKey(x, y, z)].push_back(static_cast<int>(fi));
	}
	g.stamp.assign(g.faces.size(), 0);
	g.ready = true;
	g_stats.ready = true;
	g_stats.worldFaces = g.faces.size();
	g_stats.probe = probe;
	InitCrust(data, n, L);
	g_stats.initMs = NowMs() - t0;
	return true;
}

void Update(IMatRenderContext *ctx, const std::vector<holes::Box> &boxes, std::int32_t ox, std::int32_t oz, std::int32_t oy, bool enabled)
{
	const double t0 = NowMs();
	DropMeshes(ctx);
	if (!g.ready || !enabled || boxes.empty())
	{
		RestoreAll();
		g_stats.active = false;
		g_stats.hidden = g_stats.pieces = g_stats.vertices = g_stats.meshes = g_stats.groups = g_stats.crustPieces = 0;
		return;
	}
	LoadMaps();
	{
		std::string name;
		if (WorldModel(&name) != g.eng.model || name != g.eng.name || !Readable(g.eng.surf, g.eng.stride * g.eng.count, true))
		{
			// Another world than the one Init matched: never write into it.
			g.hidden.clear();
			g.ready = false;
			g_stats.ready = false;
			g_stats.active = false;
			g_stats.error = "the loaded world changed since the map file was read";
			return;
		}
	}
	// Faces touched by a dug box, and per face the pieces left.
	++g.stampGen;
	std::vector<int> touched;
	std::unordered_map<int, std::vector<Poly>> rest;  // face (in g.faces) -> pieces
	std::unordered_set<int> hideSet;
	for (const holes::Box &b : boxes)
	{
		float a[3], c[3];
		blk::McToSource(b.x0, b.y0, b.z0, ox, oz, oy, a);
		blk::McToSource(b.x1, b.y1, b.z1, ox, oz, oy, c);
		float lo[3], hi[3];
		for (int k = 0; k < 3; ++k)
		{
			lo[k] = std::min(a[k], c[k]);
			hi[k] = std::max(a[k], c[k]);
		}
		const float m = kBehind + 0.01f;
		for (int x = CellOf(lo[0] - m); x <= CellOf(hi[0] + m); ++x)
			for (int y = CellOf(lo[1] - m); y <= CellOf(hi[1] + m); ++y)
				for (int z = CellOf(lo[2] - m); z <= CellOf(hi[2] + m); ++z)
				{
					auto it = g.grid.find(CellKey(x, y, z));
					if (it == g.grid.end())
						continue;
					for (int fi : it->second)
					{
						const Face &f = g.faces[std::size_t(fi)];
						if (f.hi[0] < lo[0] - m || f.lo[0] > hi[0] + m || f.hi[1] < lo[1] - m || f.lo[1] > hi[1] + m || f.hi[2] < lo[2] - m ||
							f.lo[2] > hi[2] + m)
							continue;
						if (g.stamp[std::size_t(fi)] != g.stampGen)
						{
							g.stamp[std::size_t(fi)] = g.stampGen;
							touched.push_back(fi);
						}
					}
				}
	}
	for (int fi : touched)
	{
		const Face &f = g.faces[std::size_t(fi)];
		std::vector<Poly> pieces;
		const float nn[3] = { f.n[0], f.n[1], f.n[2] };
		if (holes::FaceRest(f.poly, nn, boxes, ox, oz, oy, kBehind, pieces) > kMinHideArea)
		{
			hideSet.insert(fi);
			rest[fi] = std::move(pieces);
		}
	}
	// Apply: restore faces no longer dug, hide the new ones (only the sky bit is touched).
	std::vector<HiddenFace> still;
	std::unordered_set<int> already, hideFaces;
	for (int fi : hideSet)
		hideFaces.insert(g.faces[std::size_t(fi)].index);
	for (const HiddenFace &h : g.hidden)
	{
		const std::uint32_t fl = SurfFlags(h.face);
		if (hideFaces.count(h.face))
		{
			still.push_back(h);
			already.insert(h.face);
		}
		else if (StillOurs(h, fl))
			SetSurfFlags(h.face, fl & ~kSkyBit);
	}
	for (int fi : hideSet)
	{
		const int face = g.faces[std::size_t(fi)].index;
		if (already.count(face))
			continue;
		const std::uint32_t fl = SurfFlags(face);
		if ((fl >> 24) != static_cast<std::uint32_t>(g.faceEdges[std::size_t(face)]) || (fl & kSkyBit))
			continue;  // not what Init checked, or already sky: leave it
		SetSurfFlags(face, fl | kSkyBit);
		still.push_back({ face, g.faceEdges[std::size_t(face)], SurfPlane(face) });
	}
	g.hidden.swap(still);
	// Meshes of the rest, per material sort id.
	std::unordered_map<int, std::vector<Vtx>> bySort;
	std::size_t pieceCount = 0;
	for (auto &kv : rest)
	{
		const Face &f = g.faces[std::size_t(kv.first)];
		const int sid = f.sortId;  // checked by BindSorts
		if (sid < 0 || static_cast<std::size_t>(sid) >= g.eng.sorts.size() || !g.eng.sorts[std::size_t(sid)].material)
			continue;
		const SortInfo &s = g.eng.sorts[std::size_t(sid)];
		int mapW = s.material->GetMappingWidth(), mapH = s.material->GetMappingHeight();
		if (mapW <= 0)
			mapW = 1;
		if (mapH <= 0)
			mapH = 1;
		int off[2] = { 0, 0 };
		LightOffset(f.index, off);
		const bool bump = (SurfFlags(f.index) & kBumpBit) != 0;
		std::vector<Vtx> &out = bySort[sid];
		for (const Poly &p : kv.second)
		{
			++pieceCount;
			Vtx v0, v1, v2;
			MakeVertex(f, s, off, bump, p[0], mapW, mapH, v0);
			for (std::size_t i = 1; i + 1 < p.size(); ++i)
			{
				MakeVertex(f, s, off, bump, p[i], mapW, mapH, v1);
				MakeVertex(f, s, off, bump, p[i + 1], mapW, mapH, v2);
				// Clockwise seen from the front (Source), as Init oriented the face; the pieces keep it.
				out.push_back(v0);
				out.push_back(v1);
				out.push_back(v2);
			}
		}
	}
	const double tc = NowMs();
	g_stats.crustPieces = AddCrust(boxes, ox, oz, oy, hideSet, bySort);
	g_stats.crustMs = NowMs() - tc;
	std::size_t verts = 0, meshes = 0;
	for (auto &kv : bySort)
	{
		Group gr;
		gr.sort = kv.first;
		IMaterial *mat = g.eng.sorts[std::size_t(kv.first)].material;
		constexpr int kChunk = 30000;
		for (std::size_t first = 0; first < kv.second.size(); first += kChunk)
		{
			const int cnt = static_cast<int>(std::min<std::size_t>(kChunk, kv.second.size() - first));
			if (IMesh *m = BuildMesh(ctx, mat, kv.second.data() + first, cnt))
			{
				gr.meshes.push_back(m);
				++meshes;
			}
		}
		verts += kv.second.size();
		g.groups.push_back(std::move(gr));
	}
	g_stats.active = !g.hidden.empty();
	g_stats.hidden = g.hidden.size();
	g_stats.pieces = pieceCount;
	g_stats.vertices = verts;
	g_stats.meshes = meshes;
	g_stats.groups = g.groups.size();
	++g_stats.updates;
	g_stats.lastUpdateMs = NowMs() - t0;
	g_stats.maxUpdateMs = std::max(g_stats.maxUpdateMs, g_stats.lastUpdateMs);
}

int Draw(IMatRenderContext *ctx)
{
	if (!g.ready || g.groups.empty() || !ctx)
		return 0;
	int calls = 0;
	ctx->MatrixMode(MATERIAL_MODEL);
	ctx->PushMatrix();
	ctx->LoadIdentity();
	for (const Group &gr : g.groups)
	{
		const SortInfo &s = g.eng.sorts[std::size_t(gr.sort)];
		ctx->Bind(s.material, nullptr);
		ctx->BindLightmapPage(s.page);
		for (IMesh *m : gr.meshes)
		{
			m->Draw();
			++calls;
		}
	}
	ctx->BindLightmapPage(MATERIAL_SYSTEM_LIGHTMAP_PAGE_WHITE);
	ctx->MatrixMode(MATERIAL_MODEL);
	ctx->PopMatrix();
	++g_stats.draws;
	g_stats.drawCalls += static_cast<std::uint64_t>(calls);
	return calls;
}

std::uint64_t Generation() { return g_gen; }

void SetCrust(int mode)
{
	mode = std::min(std::max(mode, 0), 2);
	if (mode != g_crust)
	{
		g_crust = mode;
		++g_gen;
	}
	g_stats.crustMode = g_crust;
}

void Close(IMatRenderContext *ctx)
{
	RestoreAll();
	DropMeshes(ctx);
	g = State{};
	g_stats = Stats{};
	++g_gen;
}

const Stats &GetStats() { return g_stats; }
}  // namespace hide
}  // namespace gc

#endif  // GMODCRAFT_CLIENT
