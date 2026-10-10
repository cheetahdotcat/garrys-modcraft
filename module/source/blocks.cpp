// gmcl_gmodcraft: Minecraft's placed blocks drawn natively (P3a, docs/DESIGN.md section 7).
// The render ring's sections and atlas arrive in blockmesh.* (engine-free: parsing, transform,
// lighting bake); this file owns the engine side: one static IMesh per section and pass (built
// with LockMesh, 16-bit chunks), the procedural atlas texture (ITextureRegenerator, throttled
// full Downloads while animated sprites change), and the draw calls the Lua hooks make. Lua API:
// RegisterBlocks below and addon/gmodcraft/README.md.
//
// The interfaces used here were checked against GMod's x86-64 binaries before use (RTTI + vtable
// dumps of CMeshDX8, CMatRenderContext, CMatQueuedRenderContext, CMaterialSystem: the SDK
// headers' slots, MeshDesc_t offsets and IMesh's virtual destructor all match; P3a report). The
// first mesh built is still checked at run time (VertexCount, format, IsDynamic, the lock
// descriptor) and drawing stays off if it fails.
#ifdef GMODCRAFT_CLIENT

#include "blockmesh.hpp"
#include "link.hpp"
#include "lua_util.hpp"

#include <materialsystem/imaterial.h>
#include <materialsystem/imaterialsystem.h>
#include <materialsystem/imesh.h>
#include <materialsystem/itexture.h>
#include <texture_group_names.h>
#include <vtf/vtf.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <deque>
#ifdef _WIN32
#include <string.h>
#ifndef strcasecmp
#define strcasecmp _stricmp  // the Source SDK may define it already
#endif
#else
#include <strings.h>
#endif
#include <unordered_map>
#include <vector>

namespace gc
{
using namespace GarrysMod::Lua;

IMaterialSystem *ClientMatSys();  // client.cpp: the checked IMaterialSystem, or nullptr
const std::string &ClientMatSysProbe();
bool IsDevMode();  // main.cpp
// entities.cpp (P3c): the per-frame things share this file's ring hook, reset, Lua table and close
void EntitiesOnMessage(std::uint32_t type, const std::uint8_t *p, std::uint32_t n);
void EntitiesReset();
void RegisterEntities(ILua *L);
void CloseEntities();
// holes.cpp (P5b): the dug-hole mask volume
void RegisterHoles(ILua *L);
void CloseHoles();

namespace
{
const VertexFormat_t kFormat = VERTEX_POSITION | VERTEX_NORMAL | VERTEX_COLOR | VERTEX_TEXCOORD_SIZE(0, 2);

blk::Store g_store;

// A section's meshes: [0] opaque + cutout, [1] translucent, each split into 16-bit chunks.
struct GpuSection
{
	// [0] opaque + cutout, [1] translucent on the atlas; [2], [3] the same on the animated texture
	std::vector<IMesh *> mesh[blk::kPasses];
	std::vector<blk::OutVertex> lua[blk::kPasses];  // the baked vertices, kept only for the Lua Mesh() fallback
	std::uint32_t version = 0;
};

std::vector<IMesh *> g_destroy;  // meshes to destroy at the next Prepare (released during a drain)
std::deque<std::uint64_t> g_rebuild;
bool g_luaMeshes = false;          // the fallback: Lua builds Mesh() objects from BlocksLuaVerts
std::uint64_t g_luaGeneration = 0;  // bumps whenever a section's baked vertices change or one goes

// The run-time check of the first mesh: -1 not yet, 0 failed (no drawing), 1 ok. Back to -1 when
// the draw path switches (BlocksConfig luaMeshes) or on BlocksRetryMesh (the Render panel).
int g_meshOk = -1;
std::string g_meshProbe;
std::uint64_t g_meshesBuilt = 0, g_meshFailures = 0, g_meshesLive = 0;

void ResetMeshCheck()
{
	g_meshOk = -1;
	g_meshProbe.clear();
}

double Ema(double avg, double v, std::uint64_t n) { return n == 0 ? v : avg * 0.95 + v * 0.05; }

struct Timer
{
	double last = 0, avg = 0, max = 0, windowMax = 0;
	std::uint64_t n = 0;
	double windowStart = 0;
	void Add(double ms)
	{
		last = ms;
		avg = Ema(avg, ms, n++);
		double now = NowMs();
		if (now - windowStart > 1000)
		{
			max = windowMax;
			windowMax = 0;
			windowStart = now;
		}
		windowMax = std::max(windowMax, ms);
	}
	double Max() const { return std::max(max, windowMax); }
};
Timer g_tDrain, g_tPrepare, g_tDraw[2], g_tProbe;
std::uint64_t g_drawCalls[2] = {}, g_lastDrawCalls[2] = {};

// ---- atlas texture --------------------------------------------------------------------------
struct AtlasTex final : public ITextureRegenerator
{
	std::string name;
	std::uint32_t w = 0, h = 0;
	ITexture *tex = nullptr;
	std::uint64_t regenCalls = 0, resampled = 0, mismatches = 0, downloads = 0, partialRegens = 0;
	std::string mismatchInfo;
	Timer download;
	double lastDownloadAt = -1e18;

	// Download() regenerates the whole texture (rect = everything). Download(&rect) goes through
	// CTexture::ReconstructPartialTexture (checked offline in GMod's materialsystem_client.so, P3b):
	// a full-size scratch VTF, this call with that rect, then TexSubImage2D of the rect alone. So
	// only the rect's rows are copied (the rest of the scratch image isn't read).
	void RegenerateTextureBits(ITexture *, IVTFTexture *vtf, Rect_t *rect) override
	{
		++regenCalls;
		if (vtf == nullptr)
			return;
		unsigned char *dst = vtf->ImageData(0, 0, 0);
		const int vw = vtf->Width(), vh = vtf->Height();
		if (dst == nullptr || vw <= 0 || vh <= 0 || vtf->Format() != IMAGE_FORMAT_BGRA8888)
		{
			if (mismatches++ == 0)
			{
				char b[160];
				std::snprintf(b, sizeof b, "vtf %dx%d fmt=%d, expected BGRA8888", vw, vh, dst ? static_cast<int>(vtf->Format()) : -1);
				mismatchInfo = b;
			}
			return;
		}
		const blk::Atlas &a = g_store.atlas;
		if (a.w == 0 || a.bgra.size() < static_cast<std::size_t>(a.w) * a.h * 4)
		{
			std::memset(dst, 0, static_cast<std::size_t>(vw) * vh * 4);
			return;
		}
		if (static_cast<std::uint32_t>(vw) == a.w && static_cast<std::uint32_t>(vh) == a.h)
		{
			int x0 = 0, y0 = 0, x1 = vw, y1 = vh;
			if (rect != nullptr)
			{
				x0 = std::max(0, rect->x);
				y0 = std::max(0, rect->y);
				x1 = std::min(vw, rect->x + rect->width);
				y1 = std::min(vh, rect->y + rect->height);
				if (x0 >= x1 || y0 >= y1)
					return;
			}
			if (x0 == 0 && y0 == 0 && x1 == vw && y1 == vh)
			{
				std::memcpy(dst, a.bgra.data(), static_cast<std::size_t>(vw) * vh * 4);
				return;
			}
			++partialRegens;
			const std::size_t row = static_cast<std::size_t>(x1 - x0) * 4;
			for (int y = y0; y < y1; ++y)
			{
				const std::size_t off = (static_cast<std::size_t>(y) * vw + x0) * 4;
				std::memcpy(dst + off, a.bgra.data() + off, row);
			}
			return;
		}
		// The engine gave us another size (e.g. rounded to a power of two): nearest-neighbour
		// resample. UVs are normalised, so the mapping survives.
		++resampled;
		for (int y = 0; y < vh; ++y)
		{
			const std::uint32_t sy = static_cast<std::uint32_t>((static_cast<std::uint64_t>(y) * a.h) / vh);
			for (int x = 0; x < vw; ++x)
			{
				const std::uint32_t sx = static_cast<std::uint32_t>((static_cast<std::uint64_t>(x) * a.w) / vw);
				std::memcpy(dst + (static_cast<std::size_t>(y) * vw + x) * 4, a.bgra.data() + (static_cast<std::size_t>(sy) * a.w + sx) * 4, 4);
			}
		}
	}

	void Release() override {}  // lifetime is ours (g_atlas)

	void Destroy()
	{
		if (tex != nullptr)
		{
			tex->SetTextureRegenerator(nullptr);
			tex->DecrementReferenceCount();
			tex = nullptr;
		}
	}
};
AtlasTex *g_atlas = nullptr;
std::string g_atlasError;
double g_atlasHz = 2.0;  // full re-downloads per second at most: only when sub-rect uploads can't be used
std::uint64_t g_atlasVersion = 0;

// Animated sprites, g_animMode:
//   1 (default) their own small texture (Atlas::anim's layout; the bake remaps their UVs, passes
//     2/3), re-uploaded whole at most g_animHz times a second when a sprite changed;
//   2 (experimental) sub-rect uploads into the atlas: a batch takes every changed rect
//     (Atlas::dirtyRects) at most g_animHz times a second and uploads it with Download(&rect),
//     spread over frames (~kAnimBudgetMs each). Live: ~3 ms per rect (docs/spikes/p3b);
//   0 full atlas re-uploads at most g_atlasHz times a second (P3a).
int g_animMode = 1;
double g_animHz = 10.0;
constexpr double kAnimBudgetMs = 0.8;

// The animated texture (mode 1): the sprites copied from the CPU atlas into the packed layout.
struct AnimTex final : public ITextureRegenerator
{
	std::string name;
	std::uint32_t w = 0, h = 0;
	ITexture *tex = nullptr;
	std::uint64_t downloads = 0, layoutVersion = 0, mismatches = 0;
	Timer download;
	double lastDownloadAt = -1e18;
	void RegenerateTextureBits(ITexture *, IVTFTexture *vtf, Rect_t *) override
	{
		if (vtf == nullptr || vtf->Format() != IMAGE_FORMAT_BGRA8888 || static_cast<std::uint32_t>(vtf->Width()) != w ||
			static_cast<std::uint32_t>(vtf->Height()) != h)
		{
			++mismatches;
			return;
		}
		unsigned char *dst = vtf->ImageData(0, 0, 0);
		if (dst == nullptr)
			return;
		std::memset(dst, 0, static_cast<std::size_t>(w) * h * 4);
		const blk::Atlas &a = g_store.atlas;
		if (a.bgra.size() < static_cast<std::size_t>(a.w) * a.h * 4)
			return;
		for (const blk::AnimSprite &s : a.anim.sprites)
		{
			if (s.ax + s.w > a.w || s.ay + s.h > a.h || s.dx + s.w > w || s.dy + s.h > h)
				continue;
			for (std::uint32_t y = 0; y < s.h; ++y)
				std::memcpy(dst + (static_cast<std::size_t>(s.dy + y) * w + s.dx) * 4, a.bgra.data() + (static_cast<std::size_t>(s.ay + y) * a.w + s.ax) * 4,
					static_cast<std::size_t>(s.w) * 4);
		}
	}
	void Release() override {}
	void Destroy()
	{
		if (tex != nullptr)
		{
			tex->SetTextureRegenerator(nullptr);
			tex->DecrementReferenceCount();
			tex = nullptr;
		}
	}
};
AnimTex *g_animTex = nullptr;
std::string g_animError;
std::uint64_t g_animFailedLayout = ~std::uint64_t{ 0 };  // the layout version whose texture couldn't be made

// Mode 1 is only used while its texture works; otherwise the sprites stay on the atlas (as mode 0)
// and the bake doesn't remap them (P3b review).
bool AnimUsable()
{
	return g_animMode == 1 && g_animTex != nullptr && g_animTex->tex != nullptr && g_animTex->mismatches == 0 && g_animError.empty();
}

// A procedural BGRA texture (point sampled, no mips), or the one an earlier module load left.
ITexture *MakeProcTexture(IMaterialSystem *ms, const char *name, std::uint32_t w, std::uint32_t h, std::string *err)
{
	if (ms->IsTextureLoaded(name))
	{
		ITexture *old = ms->FindTexture(name, TEXTURE_GROUP_OTHER, false);
		if (old == nullptr || old->IsError() || old->GetImageFormat() != IMAGE_FORMAT_BGRA8888)
		{
			*err = std::string(name) + " exists with another format";
			return nullptr;
		}
		old->IncrementReferenceCount();
		return old;
	}
	const int flags = TEXTUREFLAGS_PROCEDURAL | TEXTUREFLAGS_NOMIP | TEXTUREFLAGS_NOLOD | TEXTUREFLAGS_SINGLECOPY | TEXTUREFLAGS_POINTSAMPLE;
	ITexture *tex = ms->CreateProceduralTexture(name, TEXTURE_GROUP_OTHER, static_cast<int>(w), static_cast<int>(h), IMAGE_FORMAT_BGRA8888, flags);
	if (tex == nullptr || tex->IsError())
	{
		*err = "CreateProceduralTexture failed";
		return nullptr;
	}
	return tex;
}

void UploadAnimTex(IMaterialSystem *ms)
{
	blk::Atlas &a = g_store.atlas;
	if (g_animMode != 1 || a.anim.w == 0 || a.anim.h == 0 || !a.animDirty)
		return;
	if (g_animTex == nullptr || g_animTex->w != a.anim.w || g_animTex->h != a.anim.h)
	{
		if (g_animFailedLayout == a.anim.version)
			return;  // tried for this layout already

		if (g_animTex != nullptr)
		{
			g_animTex->Destroy();
			delete g_animTex;
			g_animTex = nullptr;
		}
		char name[64];
		std::snprintf(name, sizeof name, "gmodcraft/anim_%ux%u", a.anim.w, a.anim.h);
		ITexture *tex = MakeProcTexture(ms, name, a.anim.w, a.anim.h, &g_animError);
		if (tex == nullptr)
		{
			g_animFailedLayout = a.anim.version;
			return;
		}
		g_animTex = new AnimTex();
		g_animTex->name = name;
		g_animTex->w = a.anim.w;
		g_animTex->h = a.anim.h;
		g_animTex->tex = tex;
		tex->SetTextureRegenerator(g_animTex);
		g_animError.clear();
	}
	const double now = NowMs();
	// a new layout goes up at once; frames at most g_animHz times a second
	if (g_animTex->layoutVersion == a.anim.version && (!(g_animHz > 0) || now - g_animTex->lastDownloadAt < 1000.0 / g_animHz))
		return;
	const double t0 = NowMs();
	g_animTex->tex->Download();
	g_animTex->download.Add(NowMs() - t0);
	g_animTex->lastDownloadAt = now;
	g_animTex->layoutVersion = a.anim.version;
	++g_animTex->downloads;
	a.animDirty = false;
}
struct Anim
{
	std::vector<blk::AtlasRect> batch;  // the current batch's rects not uploaded yet
	double batchStart = -1e18;
	std::uint64_t batches = 0, rectUploads = 0, fullFallbacks = 0;
	Timer frameMs;  // upload time in frames that uploaded something
	Timer rectMs;       // one Download(&rect), not the frame's first
	Timer firstRectMs;  // the frame's first Download(&rect) (may wait for the material system lock)
	std::unordered_map<std::uint64_t, std::uint64_t> perRect;  // (x << 32 | y) -> uploads, for the rate check
	void Reset()
	{
		batch.clear();
		batchStart = -1e18;
	}
};
Anim g_anim;

bool EnsureAtlasTex(IMaterialSystem *ms, std::uint32_t w, std::uint32_t h)
{
	if (g_atlas != nullptr && g_atlas->w == w && g_atlas->h == h && g_atlas->tex != nullptr)
		return true;
	if (g_atlas != nullptr)
	{
		g_atlas->Destroy();
		delete g_atlas;
		g_atlas = nullptr;
	}
	char name[64];
	std::snprintf(name, sizeof name, "gmodcraft/atlas_%ux%u", w, h);
	ITexture *tex = nullptr;
	if (ms->IsTextureLoaded(name))
	{
		// Left over from an earlier module load (map change): reuse it if it is what we'd create.
		ITexture *old = ms->FindTexture(name, TEXTURE_GROUP_OTHER, false);
		if (old == nullptr || old->IsError() || old->GetImageFormat() != IMAGE_FORMAT_BGRA8888)
		{
			g_atlasError = std::string(name) + " exists with another format";
			return false;
		}
		tex = old;
		tex->IncrementReferenceCount();
	}
	else
	{
		const int flags = TEXTUREFLAGS_PROCEDURAL | TEXTUREFLAGS_NOMIP | TEXTUREFLAGS_NOLOD | TEXTUREFLAGS_SINGLECOPY | TEXTUREFLAGS_POINTSAMPLE;
		tex = ms->CreateProceduralTexture(name, TEXTURE_GROUP_OTHER, static_cast<int>(w), static_cast<int>(h), IMAGE_FORMAT_BGRA8888, flags);
		if (tex == nullptr || tex->IsError())
		{
			g_atlasError = "CreateProceduralTexture failed";
			return false;
		}
	}
	g_atlas = new AtlasTex();
	g_atlas->name = name;
	g_atlas->w = w;
	g_atlas->h = h;
	g_atlas->tex = tex;
	tex->SetTextureRegenerator(g_atlas);
	g_atlasError.clear();
	return true;
}

void UploadAtlas(IMaterialSystem *ms)
{
	blk::Atlas &a = g_store.atlas;
	if (a.w == 0 || !(a.fullDirty || a.regionDirty))
		return;
	if (!EnsureAtlasTex(ms, a.w, a.h))
		return;
	const double now = NowMs();
	if (!a.fullDirty && AnimUsable())
	{
		// the sprites are drawn from the animated texture (UploadAnimTex): the atlas copy may lag
		a.regionDirty = false;
		a.dirtyRects.clear();
		return;
	}
	// A new atlas goes up at once (full Download). Mode 2: animated sprites as sub-rects; mode 0, or
	// if the engine gave the texture another size (resampled: rects wouldn't map), as full Downloads
	// at most g_atlasHz times a second.
	const bool subRect = g_atlas->resampled == 0 && g_animMode == 2 && g_animHz > 0;
	if (a.fullDirty || !subRect)
	{
		if (!a.fullDirty && now - g_atlas->lastDownloadAt < 1000.0 / std::max(0.1, g_atlasHz))
			return;
		if (!a.fullDirty)
			++g_anim.fullFallbacks;
		const double t0 = NowMs();
		g_atlas->tex->Download();
		g_atlas->download.Add(NowMs() - t0);
		g_atlas->lastDownloadAt = now;
		++g_atlas->downloads;
		a.fullDirty = a.regionDirty = false;
		a.dirtyRects.clear();
		g_anim.Reset();
		g_atlasVersion = a.version;
		return;
	}
	if (g_anim.batch.empty())
	{
		if (a.dirtyRects.empty())
		{
			a.regionDirty = false;
			return;
		}
		if (!(g_animHz > 0) || now - g_anim.batchStart < 1000.0 / g_animHz)
			return;
		g_anim.batch.swap(a.dirtyRects);
		a.dirtyRects.clear();
		g_anim.batchStart = now;
		++g_anim.batches;
	}
	// The budget clock starts after the frame's first rect: Download() takes the material system
	// lock, which may first wait for the queued render thread; that wait must not throttle the
	// batch to one rect per frame (it is timed on its own: firstRectMs).
	const double t0 = NowMs();
	double tBudget = t0;
	int n = 0;
	while (!g_anim.batch.empty() && (n == 0 || NowMs() - tBudget < kAnimBudgetMs))
	{
		const blk::AtlasRect r = g_anim.batch.back();
		g_anim.batch.pop_back();
		Rect_t rect;
		rect.x = static_cast<int>(r.x);
		rect.y = static_cast<int>(r.y);
		rect.width = static_cast<int>(r.w);
		rect.height = static_cast<int>(r.h);
		const double t1 = NowMs();
		g_atlas->tex->Download(&rect);
		const double t2 = NowMs();
		(n == 0 ? g_anim.firstRectMs : g_anim.rectMs).Add(t2 - t1);
		if (n == 0)
			tBudget = t2;
		++g_anim.rectUploads;
		++g_anim.perRect[(static_cast<std::uint64_t>(r.x) << 32) | r.y];
		++n;
	}
	g_anim.frameMs.Add(NowMs() - t0);
	if (g_anim.batch.empty() && a.dirtyRects.empty())
		a.regionDirty = false;
}

// ---- meshes ---------------------------------------------------------------------------------
// The lock descriptor must look like an interleaved, uncompressed vertex with our elements
// (MeshDesc_t offsets were checked offline; this catches a format the engine changed on us).
bool DescSane(const MeshDesc_t &d, std::string *why)
{
	const int s = d.m_VertexSize_Position;
	char b[200];
	std::snprintf(b, sizeof b, "stride %d/%d/%d/%d actual %d comp %d pos %p nrm %p col %p uv %p idx %p idxSize %d first %d", s, d.m_VertexSize_Normal,
		d.m_VertexSize_Color, d.m_VertexSize_TexCoord[0], d.m_ActualVertexSize, static_cast<int>(d.m_CompressionType), static_cast<void *>(d.m_pPosition),
		static_cast<void *>(d.m_pNormal), static_cast<void *>(d.m_pColor), static_cast<void *>(d.m_pTexCoord[0]), static_cast<void *>(d.m_pIndices),
		static_cast<int>(d.m_nIndexSize), d.m_nFirstVertex);
	*why = b;
	if (s < 36 || s > 256 || d.m_VertexSize_Normal != s || d.m_VertexSize_Color != s || d.m_VertexSize_TexCoord[0] != s)
		return false;
	if (d.m_CompressionType != VERTEX_COMPRESSION_NONE || d.m_pPosition == nullptr || d.m_pNormal == nullptr || d.m_pColor == nullptr ||
		d.m_pTexCoord[0] == nullptr || d.m_pIndices == nullptr || d.m_nIndexSize == 0)
		return false;
	auto inVertex = [&](const void *p, std::size_t bytes) {
		const auto *b0 = reinterpret_cast<const std::uint8_t *>(d.m_pPosition), *q = reinterpret_cast<const std::uint8_t *>(p);
		return q >= b0 && q + bytes <= b0 + s;
	};
	return inVertex(d.m_pNormal, 12) && inVertex(d.m_pColor, 4) && inVertex(d.m_pTexCoord[0], 8);
}

IMesh *BuildMesh(IMatRenderContext *ctx, const blk::OutVertex *v, std::uint32_t n)
{
	if (g_meshOk == 0 || n == 0 || n > blk::kMaxMeshVertices)
		return nullptr;
	IMesh *m = ctx->CreateStaticMesh(kFormat, TEXTURE_GROUP_STATIC_VERTEX_BUFFER_WORLD, nullptr);
	if (m == nullptr)
	{
		++g_meshFailures;
		if (g_meshOk < 0)
		{
			g_meshOk = 0;
			g_meshProbe = "CreateStaticMesh returned null";
		}
		return nullptr;
	}
	m->SetPrimitiveType(MATERIAL_TRIANGLES);
	MeshDesc_t desc;
	std::memset(&desc, 0, sizeof desc);
	m->LockMesh(static_cast<int>(n), static_cast<int>(n), desc);
	std::string why;
	if (!DescSane(desc, &why))
	{
		m->UnlockMesh(0, 0, desc);
		ctx->DestroyStaticMesh(m);
		++g_meshFailures;
		if (g_meshOk < 0)
		{
			g_meshOk = 0;
			g_meshProbe = "lock descriptor: " + why;
		}
		return nullptr;
	}
	auto *pos = reinterpret_cast<std::uint8_t *>(desc.m_pPosition);
	auto *nrm = reinterpret_cast<std::uint8_t *>(desc.m_pNormal);
	auto *col = desc.m_pColor;
	auto *uv = reinterpret_cast<std::uint8_t *>(desc.m_pTexCoord[0]);
	const std::size_t s = static_cast<std::size_t>(desc.m_VertexSize_Position);
	for (std::uint32_t i = 0; i < n; ++i)
	{
		std::memcpy(pos + i * s, v[i].pos, 12);
		std::memcpy(nrm + i * s, v[i].normal, 12);
		std::memcpy(col + i * s, v[i].color, 4);
		std::memcpy(uv + i * s, v[i].uv, 8);
		desc.m_pIndices[i] = static_cast<unsigned short>(desc.m_nFirstVertex + i);
	}
	m->UnlockMesh(static_cast<int>(n), static_cast<int>(n), desc);
	++g_meshesBuilt;
	if (g_meshOk < 0)
	{
		// The first mesh: read it back through the vtable before trusting it for drawing.
		const int vc = m->VertexCount();
		const VertexFormat_t fmt = m->GetVertexFormat();
		const bool dyn = static_cast<IVertexBuffer *>(m)->IsDynamic();  // slot 4 (verified offline)
		char b[320];
		std::snprintf(b, sizeof b, "VertexCount %d (want %u), format %#llx (want %#llx), dynamic %d; %s", vc, n, static_cast<unsigned long long>(fmt),
			static_cast<unsigned long long>(kFormat), dyn ? 1 : 0, why.c_str());
		g_meshProbe = b;
		g_meshOk = (vc == static_cast<int>(n) && (fmt & kFormat) == kFormat && !dyn) ? 1 : 0;
		if (g_meshOk == 0)
		{
			ctx->DestroyStaticMesh(m);
			return nullptr;
		}
	}
	++g_meshesLive;
	return m;
}

void ReleaseGpu(blk::Section &s)
{
	auto *g = static_cast<GpuSection *>(s.gpu);
	if (g == nullptr)
		return;
	for (auto &pass : g->mesh)
		for (IMesh *m : pass)
			g_destroy.push_back(m);
	delete g;
	s.gpu = nullptr;
	++g_luaGeneration;
}

void DestroyPending(IMatRenderContext *ctx)
{
	for (IMesh *m : g_destroy)
	{
		ctx->DestroyStaticMesh(m);
		--g_meshesLive;
	}
	g_destroy.clear();
}

blk::SectionPasses g_scratch;

// (Re)builds one section's meshes (or, for the Lua fallback, its baked vertex lists).
void RebuildSection(IMatRenderContext *ctx, blk::Section &s)
{
	const blk::BakeConfig &cfg = g_store.Config();
	const blk::Atlas &a = g_store.atlas;
	blk::BuildSectionPasses(s.sx, s.sy, s.sz, s.raw.data(), static_cast<std::uint32_t>(s.raw.size()), cfg, g_scratch, cfg.cellLight ? s.cellLight : nullptr,
		AnimUsable() ? &a.anim : nullptr, a.w, a.h);
	auto *g = static_cast<GpuSection *>(s.gpu);
	if (g == nullptr)
		s.gpu = g = new GpuSection();
	for (int p = 0; p < blk::kPasses; ++p)
	{
		for (IMesh *m : g->mesh[p])
			g_destroy.push_back(m);
		g->mesh[p].clear();
		g->lua[p].clear();
		const auto &v = g_scratch.pass[p];
		if (g_luaMeshes)
			g->lua[p] = v;
		else
			for (auto &c : blk::Chunks(static_cast<std::uint32_t>(v.size())))
				if (IMesh *m = BuildMesh(ctx, v.data() + c.first, c.second))
					g->mesh[p].push_back(m);
	}
	s.opaqueVerts = static_cast<std::uint32_t>(g_scratch.pass[0].size() + g_scratch.pass[2].size());
	s.translucentVerts = static_cast<std::uint32_t>(g_scratch.pass[1].size() + g_scratch.pass[3].size());
	blk::Store::MarkBuilt(s);
	++g->version;
	++g_luaGeneration;
}

// ---- materials --------------------------------------------------------------------------------
struct MatSlot
{
	std::string name;
	IMaterial *mat = nullptr;
	std::string error;
};
MatSlot g_mat[5];  // 0 opaque, 1 translucent, 2 probe, 3 / 4 opaque / translucent on the animated texture

IMaterial *FindMat(IMaterialSystem *ms, int slot, const char *name)
{
	MatSlot &s = g_mat[slot];
	if (s.mat != nullptr && s.name == name)
		return s.mat;
	s.name = name;
	s.mat = nullptr;
	IMaterial *m = ms->FindMaterial(name, TEXTURE_GROUP_OTHER, false);
	// A missing material comes back as the error material: check by name (no IsErrorMaterial
	// call, one vtable slot less to trust). Failures aren't cached: Lua may create it later.
	const char *got = m ? m->GetName() : nullptr;
	if (m == nullptr || got == nullptr || strcasecmp(got, name) != 0)
	{
		s.error = std::string("FindMaterial(") + name + ") -> " + (got ? got : "null");
		return nullptr;
	}
	s.error.clear();
	s.mat = m;
	return m;
}

// The material argument: a Lua IMaterial (the userdata's pointer: materials made by Lua's
// CreateMaterial are "manually created" and FindMaterial doesn't see them, P3a live run), or a name
// for FindMaterial (materials from .vmt files).
IMaterial *ArgMat(ILua *L, int idx, IMaterialSystem *ms, int slot)
{
	if (L->IsType(idx, Type::Material))
	{
		IMaterial *m = L->GetUserType<IMaterial>(idx, Type::Material);
		MatSlot &s = g_mat[slot];
		const char *nm = m ? m->GetName() : nullptr;
		if (m == nullptr || nm == nullptr)
		{
			s.mat = nullptr;
			s.error = "IMaterial userdata without a material";
			return nullptr;
		}
		if (s.mat != m)
		{
			s.mat = m;
			s.name = nm;
			s.error.clear();
		}
		return m;
	}
	if (L->IsType(idx, Type::String))
		return FindMat(ms, slot, L->GetString(idx));
	return nullptr;
}

// The probe quad(s) the live calibration draws (BlocksProbe / BlocksProbeDraw).
std::vector<IMesh *> g_probe;

// The context for one batch of work (CMatRenderContextPtr's pattern; never cached).
struct Ctx
{
	IMatRenderContext *ctx = nullptr;
	explicit Ctx(IMaterialSystem *ms)
	{
		ctx = ms->GetRenderContext();
		if (ctx)
			ctx->BeginRender();
	}
	~Ctx()
	{
		if (ctx)
			ctx->EndRender();
	}
};

int DrawMeshes(IMatRenderContext *ctx, IMaterial *mat, const std::vector<IMesh *> *const *lists, std::size_t count)
{
	ctx->MatrixMode(MATERIAL_MODEL);
	ctx->PushMatrix();
	ctx->LoadIdentity();
	ctx->Bind(mat, nullptr);
	int calls = 0;
	for (std::size_t i = 0; i < count; ++i)
		for (IMesh *m : *lists[i])
		{
			m->Draw();
			++calls;
		}
	ctx->MatrixMode(MATERIAL_MODEL);
	ctx->PopMatrix();
	return calls;
}

// ---- Lua ----------------------------------------------------------------------------------------
// BlocksPrepare([budgetMs]) -> rebuilt. Once per frame before drawing (PreRender): destroys
// released meshes, uploads the atlas when due, rebuilds changed sections within the budget.
LUA_FUNCTION_STATIC(BlocksPrepare)
{
	IMaterialSystem *ms = ClientMatSys();
	if (ms == nullptr)
	{
		LUA->PushNumber(0);
		return 1;
	}
	const double t0 = NowMs();
	const double budget = std::min(std::max(ArgNum(LUA, 1, 4.0), 0.5), 50.0);
	int rebuilt = 0;
	{
		Ctx c(ms);
		if (c.ctx == nullptr)
		{
			LUA->PushNumber(0);
			return 1;
		}
		DestroyPending(c.ctx);
		UploadAnimTex(ms);  // first: UploadAtlas and the bake need to know whether it works
		{
			static bool lastUsable = false;
			const bool usable = AnimUsable();
			if (usable != lastUsable)
			{
				lastUsable = usable;
				g_store.MarkAllDirty();  // the remap comes or goes
				if (!usable)
					g_store.atlas.regionDirty = true;  // the sprites go back to the atlas
			}
		}
		UploadAtlas(ms);
		for (std::uint64_t k : g_store.TakeDirty())
			g_rebuild.push_back(k);
		while (!g_rebuild.empty() && (rebuilt == 0 || NowMs() - t0 < budget))
		{
			auto it = g_store.sections.find(g_rebuild.front());
			g_rebuild.pop_front();
			if (it == g_store.sections.end() || !it->second.dirty)
				continue;
			RebuildSection(c.ctx, it->second);
			++rebuilt;
		}
		DestroyPending(c.ctx);
	}
	g_tPrepare.Add(NowMs() - t0);
	LUA->PushNumber(rebuilt);
	return 1;
}

// Draws (material, meshes) pairs in order, binding a material only when it changes.
struct DrawItem
{
	IMaterial *mat;
	const std::vector<IMesh *> *meshes;
};
int DrawItems(IMatRenderContext *ctx, const std::vector<DrawItem> &items)
{
	ctx->MatrixMode(MATERIAL_MODEL);
	ctx->PushMatrix();
	ctx->LoadIdentity();
	IMaterial *bound = nullptr;
	int calls = 0;
	for (const DrawItem &it : items)
	{
		if (it.mat != bound)
		{
			ctx->Bind(it.mat, nullptr);
			bound = it.mat;
		}
		for (IMesh *m : *it.meshes)
		{
			m->Draw();
			++calls;
		}
	}
	ctx->MatrixMode(MATERIAL_MODEL);
	ctx->PopMatrix();
	return calls;
}

// BlocksDraw(pass, material, animMaterial|nil[, eyeX, eyeY, eyeZ]) (materials: IMaterial or a .vmt
// name) -> draw calls. pass 0: opaque + cutout (any order: the atlas meshes, then the animated
// texture's), 1: translucent, sections back to front from the eye (Source units), each section's
// atlas part before its animated part.
LUA_FUNCTION_STATIC(BlocksDraw)
{
	IMaterialSystem *ms = ClientMatSys();
	const int pass = static_cast<int>(ArgNum(LUA, 1)) == 1 ? 1 : 0;
	if (ms == nullptr || g_meshOk != 1 || g_luaMeshes)
	{
		LUA->PushNumber(0);
		return 1;
	}
	const double t0 = NowMs();
	IMaterial *mat = ArgMat(LUA, 2, ms, pass);
	IMaterial *animMat = ArgMat(LUA, 3, ms, 3 + pass);  // nil: the animated parts aren't drawn
	if (mat == nullptr)
	{
		LUA->PushNumber(0);
		return 1;
	}
	static std::vector<DrawItem> items;
	items.clear();
	if (pass == 0)
	{
		for (auto &kv : g_store.sections)
			if (auto *g = static_cast<GpuSection *>(kv.second.gpu); g && !g->mesh[0].empty())
				items.push_back(DrawItem{ mat, &g->mesh[0] });
		if (animMat != nullptr)
			for (auto &kv : g_store.sections)
				if (auto *g = static_cast<GpuSection *>(kv.second.gpu); g && !g->mesh[2].empty())
					items.push_back(DrawItem{ animMat, &g->mesh[2] });
	}
	else
	{
		static std::vector<blk::Section *> order;
		g_store.BackToFront(ArgNum(LUA, 4), ArgNum(LUA, 5), ArgNum(LUA, 6), order);
		for (blk::Section *s : order)
			if (auto *g = static_cast<GpuSection *>(s->gpu))
			{
				if (!g->mesh[1].empty())
					items.push_back(DrawItem{ mat, &g->mesh[1] });
				if (animMat != nullptr && !g->mesh[3].empty())
					items.push_back(DrawItem{ animMat, &g->mesh[3] });
			}
	}
	int calls = 0;
	if (!items.empty())
	{
		Ctx c(ms);
		if (c.ctx)
			calls = DrawItems(c.ctx, items);
	}
	g_drawCalls[pass] += calls;
	g_lastDrawCalls[pass] = calls;
	g_tDraw[pass].Add(NowMs() - t0);
	LUA->PushNumber(calls);
	return 1;
}

// BlocksConfig({linearVertexColor, rgbaOrder, atlasHz, animHz, cellLight, luaMeshes}) -> applied
// config. Changing the bake (colour space, byte order, cell light) or the mesh path rebuilds every
// section from its raw data.
LUA_FUNCTION_STATIC(BlocksConfig)
{
	blk::BakeConfig cfg = g_store.Config();
	if (LUA->IsType(1, Type::Table))
	{
		cfg.linearVertexColor = FieldBool(LUA, 1, "linearVertexColor", cfg.linearVertexColor);
		cfg.rgbaOrder = FieldBool(LUA, 1, "rgbaOrder", cfg.rgbaOrder);
		cfg.cellLight = FieldBool(LUA, 1, "cellLight", cfg.cellLight);
		g_atlasHz = FieldNum(LUA, 1, "atlasHz", g_atlasHz);
		if (!(g_atlasHz > 0.1) || g_atlasHz > 240)
			g_atlasHz = 2.0;
		g_animHz = FieldNum(LUA, 1, "animHz", g_animHz);
		if (!(g_animHz >= 0) || g_animHz > 240)
			g_animHz = 10.0;
		int mode = static_cast<int>(FieldNum(LUA, 1, "animMode", g_animMode));
		mode = mode < 0 || mode > 2 ? 1 : mode;
		if (mode != g_animMode)
		{
			g_animMode = mode;
			g_store.atlas.regionDirty = g_store.atlas.animDirty = true;  // the atlas / animated texture catch up
			g_store.MarkAllDirty();  // the remap to the animated texture comes or goes
		}
		const bool lua = FieldBool(LUA, 1, "luaMeshes", g_luaMeshes);
		if (lua != g_luaMeshes)
		{
			g_luaMeshes = lua;
			ResetMeshCheck();  // switching paths checks the IMesh path again (a failure isn't sticky)
			g_store.MarkAllDirty();
		}
		g_store.SetConfig(cfg);
	}
	LUA->CreateTable();
	SetBool(LUA, "linearVertexColor", cfg.linearVertexColor);
	SetBool(LUA, "rgbaOrder", cfg.rgbaOrder);
	SetNum(LUA, "atlasHz", g_atlasHz);
	SetNum(LUA, "animHz", g_animHz);
	SetNum(LUA, "animMode", g_animMode);
	SetBool(LUA, "cellLight", cfg.cellLight);
	SetBool(LUA, "luaMeshes", g_luaMeshes);
	SetNum(LUA, "originX", cfg.ox);
	SetNum(LUA, "originZ", cfg.oz);
	SetNum(LUA, "originYUnits", cfg.oy);
	return 1;
}

// BlocksMeshOk() -> -1 not checked yet, 0 the IMesh path failed its check, 1 ok.
LUA_FUNCTION_STATIC(BlocksMeshOk)
{
	LUA->PushNumber(g_meshOk);
	return 1;
}

// BlocksLightQuery(max[, out]) -> n, out: up to `max` cells that want GMod's light sampled
// (never-sampled first), 25 numbers each from index 1: cx, cy, cz (global cell coordinates, 4
// blocks a cell), k (candidate points, 1..7), then 7 x, y, z triples (Source units; the first k
// used: the cell's face directions' points, then its centre). Entries after 25 * n are left as they were.
LUA_FUNCTION_STATIC(BlocksLightQuery)
{
	static std::vector<blk::LightProbe> probes;
	g_store.LightQuery(static_cast<std::size_t>(std::min(std::max(ArgNum(LUA, 1, 64), 0.0), 4096.0)), probes);
	LUA->PushNumber(static_cast<double>(probes.size()));
	if (LUA->IsType(2, Type::Table))
		LUA->Push(2);
	else
		LUA->CreateTable();
	double k = 0;
	auto put = [&](double x) {
		LUA->PushNumber(++k);
		LUA->PushNumber(x);
		LUA->RawSet(-3);
	};
	for (const blk::LightProbe &p : probes)
	{
		put(p.cx);
		put(p.cy);
		put(p.cz);
		put(p.n);
		for (int i = 0; i < blk::kProbePoints; ++i)
			for (int a = 0; a < 3; ++a)
				put(i < p.n ? p.pos[i][a] : 0.0);
	}
	return 2;
}

// BlocksLightSet(list, n) -> sections re-baked: list holds 4 numbers per cell, cx, cy, cz, S (0..1).
LUA_FUNCTION_STATIC(BlocksLightSet)
{
	if (!LUA->IsType(1, Type::Table))
	{
		LUA->PushNumber(0);
		return 1;
	}
	const int n = static_cast<int>(std::min(std::max(ArgNum(LUA, 2, 0), 0.0), 4096.0));
	int rebaked = 0;
	for (int i = 0; i < n; ++i)
	{
		double f[4];
		for (int k = 0; k < 4; ++k)
		{
			LUA->PushNumber(i * 4 + k + 1);
			LUA->RawGet(1);
			f[k] = LUA->IsType(-1, Type::Number) ? LUA->GetNumber(-1) : -1;
			LUA->Pop();
		}
		std::int32_t c[3];
		if (!blk::CellArg(f, c) || !std::isfinite(f[3]))
			continue;
		if (g_store.LightSet(c[0], c[1], c[2], static_cast<float>(f[3])))
			++rebaked;
	}
	LUA->PushNumber(rebaked);
	return 1;
}

// BlocksLightRefresh() -> started: every cell with faces wants a new sample, once the previous
// sweep finished (Lua calls it at a low rate).
LUA_FUNCTION_STATIC(BlocksLightRefresh)
{
	LUA->PushBool(g_store.LightRefresh());
	return 1;
}

// BlocksLights(eyeX, eyeY, eyeZ, budget, dt[, out]) -> n, out (P3d): advances the block lights'
// clock by dt seconds and rebuilds them when due (blk::BlockLights: every 0.25 s, at once after a
// kRenLights or a budget change), around the eye (Source units). out holds 9 numbers per active
// slot from index 1: slot (0..23, sticky: one GMod dynamic light per slot), x, y, z (Source units),
// r, g, b (0..1), radius (Source units), intensity (base by level times the flicker now). Entries
// after 9 * n are left as they were.
LUA_FUNCTION_STATIC(BlocksLights)
{
	const blk::BakeConfig &cfg = g_store.Config();
	const double ex = ArgNum(LUA, 1), ey = ArgNum(LUA, 2), ez = ArgNum(LUA, 3);
	const int budget = static_cast<int>(std::min(std::max(ArgNum(LUA, 4, 10), 0.0), static_cast<double>(blk::kMaxLights)));
	blk::BlockLights &bl = g_store.lights;
	bl.Update(ArgNum(LUA, 5), ex / 40.0 + cfg.ox, (ez + cfg.oy) / 40.0, -ey / 40.0 + cfg.oz, budget);
	int n = 0;
	for (const blk::LightSlot &s : bl.slots)
		n += s.active ? 1 : 0;
	LUA->PushNumber(n);
	if (LUA->IsType(6, Type::Table))
		LUA->Push(6);
	else
		LUA->CreateTable();
	double k = 0;
	auto put = [&](double x) {
		LUA->PushNumber(++k);
		LUA->PushNumber(x);
		LUA->RawSet(-3);
	};
	for (int i = 0; i < blk::kMaxLights; ++i)
	{
		const blk::LightSlot &s = bl.slots[i];
		if (!s.active)
			continue;
		float pos[3];
		blk::McToSource(s.mx, s.my, s.mz, cfg.ox, cfg.oz, cfg.oy, pos);
		put(i);
		put(pos[0]);
		put(pos[1]);
		put(pos[2]);
		put(s.r);
		put(s.g);
		put(s.b);
		put(s.radiusBlocks * 40.0);
		put(bl.Intensity(s));
	}
	return 2;
}

// BlocksLightsCount(x0, y0, z0, x1, y1, z1) -> Minecraft light emitters inside that MC block box
// (inclusive), or -1 for bad arguments: the scripted test checks that a box holds none of the
// user's before it places and removes its own torches there.
LUA_FUNCTION_STATIC(BlocksLightsCount)
{
	double f[6];
	for (int i = 0; i < 6; ++i)
		f[i] = ArgNum(LUA, i + 1);
	std::int32_t a[3], b[3];
	if (!blk::CellArg(f, a) || !blk::CellArg(f + 3, b))
	{
		LUA->PushNumber(-1);
		return 1;
	}
	LUA->PushNumber(static_cast<double>(g_store.lights.CountIn(a[0], a[1], a[2], b[0], b[1], b[2])));
	return 1;
}

// BlocksRetryMesh(): forget a failed (or passed) IMesh check and rebuild every section, so the
// next mesh built is checked again (the Render panel's retry button).
LUA_FUNCTION_STATIC(BlocksRetryMesh)
{
	ResetMeshCheck();
	g_store.MarkAllDirty();
	return 0;
}

// BlocksAtlas() -> textureName, w, h | nil: the atlas texture once Minecraft's atlas arrived
// and went up (Lua makes the block materials from it).
// BlocksAnim() -> textureName, w, h | nil: the animated sprites' texture (mode 1) once it went up
// (Lua makes the animated block materials from it; the name changes with its size).
LUA_FUNCTION_STATIC(BlocksAnim)
{
	if (!AnimUsable() || g_animTex->downloads == 0)
		return 0;
	LUA->PushString(g_animTex->name.c_str());
	LUA->PushNumber(g_animTex->w);
	LUA->PushNumber(g_animTex->h);
	return 3;
}

LUA_FUNCTION_STATIC(BlocksAtlas)
{
	if (g_atlas == nullptr || g_atlas->tex == nullptr || g_atlas->downloads == 0)
		return 0;
	LUA->PushString(g_atlas->name.c_str());
	LUA->PushNumber(g_atlas->w);
	LUA->PushNumber(g_atlas->h);
	return 3;
}

void PushTimer(ILua *L, const char *key, const Timer &t)
{
	L->CreateTable();
	SetNum(L, "last", t.last);
	SetNum(L, "avg", t.avg);
	SetNum(L, "max", t.Max());
	L->SetField(-2, key);
}

// BlocksInfo() -> everything the Render panel shows.
LUA_FUNCTION_STATIC(BlocksInfo)
{
	LUA->CreateTable();
	std::uint64_t ov = 0, tv = 0, meshes = 0;
	for (auto &kv : g_store.sections)
	{
		ov += kv.second.opaqueVerts;
		tv += kv.second.translucentVerts;
		if (auto *g = static_cast<GpuSection *>(kv.second.gpu))
			for (auto &pm : g->mesh) meshes += pm.size();
	}
	SetNum(LUA, "sections", static_cast<double>(g_store.sections.size()));
	SetNum(LUA, "rawVertices", static_cast<double>(g_store.totalVertices));
	SetNum(LUA, "opaqueVertices", static_cast<double>(ov));
	SetNum(LUA, "translucentVertices", static_cast<double>(tv));
	SetNum(LUA, "meshes", static_cast<double>(meshes));
	SetNum(LUA, "meshesLive", static_cast<double>(g_meshesLive));
	SetNum(LUA, "meshesBuilt", static_cast<double>(g_meshesBuilt));
	SetNum(LUA, "meshFailures", static_cast<double>(g_meshFailures));
	SetNum(LUA, "pendingRebuild", static_cast<double>(g_rebuild.size()));
	SetNum(LUA, "meshOk", g_meshOk);
	SetStr(LUA, "meshProbe", g_meshProbe.c_str());
	SetStr(LUA, "matsysProbe", ClientMatSysProbe().c_str());
	SetBool(LUA, "luaMeshes", g_luaMeshes);
	SetNum(LUA, "drawCallsOpaque", static_cast<double>(g_lastDrawCalls[0]));
	SetNum(LUA, "drawCallsTranslucent", static_cast<double>(g_lastDrawCalls[1]));
	PushTimer(LUA, "drainMs", g_tDrain);
	PushTimer(LUA, "prepareMs", g_tPrepare);
	PushTimer(LUA, "drawOpaqueMs", g_tDraw[0]);
	PushTimer(LUA, "drawTranslucentMs", g_tDraw[1]);
	// Messages by type (the render ring's kRen* numbers).
	const blk::Counters &c = g_store.counters;
	static const char *const kTypes[] = { "pad", "atlas", "section", "clearAll", "texture", "avatar", "scene", "atlasRegion", "lights", "ragdoll", "solids", "dug" };
	LUA->CreateTable();
	for (int i = 1; i < 12; ++i)
	{
		LUA->CreateTable();
		SetNum(LUA, "messages", static_cast<double>(c.byType[i]));
		SetNum(LUA, "bytes", static_cast<double>(c.bytesByType[i]));
		LUA->SetField(-2, kTypes[i]);
	}
	LUA->SetField(-2, "messages");
	SetNum(LUA, "unknown", static_cast<double>(c.unknown));
	SetNum(LUA, "malformed", static_cast<double>(c.malformed));
	SetNum(LUA, "sectionUpdates", static_cast<double>(c.sectionUpdates));
	SetNum(LUA, "sectionRemovals", static_cast<double>(c.sectionRemovals));
	SetNum(LUA, "clears", static_cast<double>(c.clears));
	SetNum(LUA, "aliased", static_cast<double>(c.aliased));
	LUA->CreateTable();
	const blk::Atlas &a = g_store.atlas;
	SetNum(LUA, "w", a.w);
	SetNum(LUA, "h", a.h);
	SetNum(LUA, "received", static_cast<double>(c.atlases));
	SetNum(LUA, "regions", static_cast<double>(a.regions));
	SetNum(LUA, "regionBytes", static_cast<double>(a.regionBytes));
	SetNum(LUA, "regionRejects", static_cast<double>(a.regionRejects));
	SetBool(LUA, "dirty", a.fullDirty || a.regionDirty);
	SetNum(LUA, "hz", g_atlasHz);
	SetStr(LUA, "error", g_atlasError.c_str());
	if (g_atlas != nullptr)
	{
		SetStr(LUA, "name", g_atlas->name.c_str());
		SetNum(LUA, "downloads", static_cast<double>(g_atlas->downloads));
		SetNum(LUA, "regen", static_cast<double>(g_atlas->regenCalls));
		SetNum(LUA, "resampled", static_cast<double>(g_atlas->resampled));
		SetNum(LUA, "mismatches", static_cast<double>(g_atlas->mismatches));
		SetStr(LUA, "mismatchInfo", g_atlas->mismatchInfo.c_str());
		SetNum(LUA, "partialRegens", static_cast<double>(g_atlas->partialRegens));
		PushTimer(LUA, "downloadMs", g_atlas->download);
	}
	SetNum(LUA, "regionsUnchanged", static_cast<double>(a.regionsUnchanged));
	SetNum(LUA, "pendingRects", static_cast<double>(a.dirtyRects.size() + g_anim.batch.size()));
	SetNum(LUA, "animHz", g_animHz);
	SetNum(LUA, "animBatches", static_cast<double>(g_anim.batches));
	SetNum(LUA, "rectUploads", static_cast<double>(g_anim.rectUploads));
	SetNum(LUA, "fullFallbacks", static_cast<double>(g_anim.fullFallbacks));
	PushTimer(LUA, "animFrameMs", g_anim.frameMs);
	PushTimer(LUA, "rectMs", g_anim.rectMs);
	PushTimer(LUA, "firstRectMs", g_anim.firstRectMs);
	// uploads per animated rect: { {x, y, n}, ... }
	LUA->CreateTable();
	{
		int i = 0;
		for (const auto &kv : g_anim.perRect)
		{
			LUA->PushNumber(++i);
			LUA->CreateTable();
			SetNum(LUA, "x", static_cast<double>(kv.first >> 32));
			SetNum(LUA, "y", static_cast<double>(kv.first & 0xFFFFFFFFu));
			SetNum(LUA, "n", static_cast<double>(kv.second));
			LUA->SetTable(-3);
		}
	}
	LUA->SetField(-2, "rects");
	LUA->SetField(-2, "atlas");
	// the animated sprites' texture (mode 1)
	LUA->CreateTable();
	SetNum(LUA, "mode", g_animMode);
	SetNum(LUA, "hz", g_animHz);
	SetNum(LUA, "sprites", static_cast<double>(a.anim.sprites.size()));
	SetNum(LUA, "w", a.anim.w);
	SetNum(LUA, "h", a.anim.h);
	SetNum(LUA, "layoutVersion", static_cast<double>(a.anim.version));
	SetBool(LUA, "dirty", a.animDirty);
	SetStr(LUA, "error", g_animError.c_str());
	SetBool(LUA, "usable", AnimUsable());
	if (g_animTex != nullptr)
	{
		SetStr(LUA, "name", g_animTex->name.c_str());
		SetNum(LUA, "downloads", static_cast<double>(g_animTex->downloads));
		SetNum(LUA, "mismatches", static_cast<double>(g_animTex->mismatches));
		PushTimer(LUA, "downloadMs", g_animTex->download);
	}
	LUA->SetField(-2, "anim");
	LUA->CreateTable();
	SetBool(LUA, "on", g_store.Config().cellLight);
	SetNum(LUA, "cells", static_cast<double>(g_store.LightCells()));
	SetNum(LUA, "pending", static_cast<double>(g_store.LightPending()));
	SetNum(LUA, "sets", static_cast<double>(g_store.light.sets));
	SetNum(LUA, "firstSamples", static_cast<double>(g_store.light.firstSamples));
	SetNum(LUA, "rebakes", static_cast<double>(g_store.light.rebakes));
	SetNum(LUA, "refreshes", static_cast<double>(g_store.light.refreshes));
	LUA->SetField(-2, "light");
	{
		const blk::BlockLights &bl = g_store.lights;
		LUA->CreateTable();
		SetNum(LUA, "emitters", static_cast<double>(bl.Emitters()));
		SetNum(LUA, "sections", static_cast<double>(bl.Sections()));
		SetNum(LUA, "inRange", static_cast<double>(bl.lastInRange));
		SetNum(LUA, "cells", static_cast<double>(bl.lastCells));
		SetNum(LUA, "active", static_cast<double>(bl.Active()));
		SetNum(LUA, "messages", static_cast<double>(bl.messages));
		SetNum(LUA, "malformed", static_cast<double>(bl.malformed));
		SetNum(LUA, "rebuilds", static_cast<double>(bl.rebuilds));
		LUA->SetField(-2, "lights");
	}
	LUA->CreateTable();
	static const char *const kMatNames[5] = { "opaque", "translucent", "probe", "animOpaque", "animTranslucent" };
	for (int i = 0; i < 5; ++i)
	{
		LUA->CreateTable();
		SetStr(LUA, "name", g_mat[i].name.c_str());
		SetBool(LUA, "found", g_mat[i].mat != nullptr);
		SetStr(LUA, "error", g_mat[i].error.c_str());
		LUA->SetField(-2, kMatNames[i]);
	}
	LUA->SetField(-2, "materials");
	return 1;
}

// BlocksProbe(verts) -> ok, info. Builds the calibration mesh from a flat list of vertices
// {x, y, z, u, v, r, g, b, a, ...} (Source units, whole triangles in Source's clockwise order,
// colours raw: only the byte order follows the config). Replaces the previous probe. Dev only
// (-gmodcraft_dev), like the other probe functions and BlocksDevInject.
LUA_FUNCTION_STATIC(BlocksProbe)
{
	if (!IsDevMode())
		return PushFail(LUA, "dev only (-gmodcraft_dev)");
	IMaterialSystem *ms = ClientMatSys();
	if (ms == nullptr)
		return PushFail(LUA, ("material system: " + ClientMatSysProbe()).c_str());
	if (!LUA->IsType(1, Type::Table))
		return PushFail(LUA, "verts table expected");
	std::vector<blk::OutVertex> v;
	const int n = static_cast<int>(LUA->ObjLen(1));
	for (int i = 0; i + 9 <= n && v.size() < 3000; i += 9)
	{
		double f[9];
		for (int k = 0; k < 9; ++k)
		{
			LUA->PushNumber(i + k + 1);
			LUA->GetTable(1);
			f[k] = LUA->IsType(-1, Type::Number) ? LUA->GetNumber(-1) : 0;
			LUA->Pop();
		}
		blk::OutVertex o{};
		for (int k = 0; k < 3; ++k)
			o.pos[k] = static_cast<float>(f[k]);
		o.normal[2] = 1;
		o.uv[0] = static_cast<float>(f[3]);
		o.uv[1] = static_cast<float>(f[4]);
		std::uint8_t rgba[4];
		for (int k = 0; k < 4; ++k)
			rgba[k] = static_cast<std::uint8_t>(std::min(std::max(f[5 + k], 0.0), 255.0));
		const bool swap = !g_store.Config().rgbaOrder;
		o.color[0] = swap ? rgba[2] : rgba[0];
		o.color[1] = rgba[1];
		o.color[2] = swap ? rgba[0] : rgba[2];
		o.color[3] = rgba[3];
		v.push_back(o);
	}
	v.resize(v.size() - v.size() % 3);
	const double t0 = NowMs();
	Ctx c(ms);
	if (c.ctx == nullptr)
		return PushFail(LUA, "no render context");
	for (IMesh *m : g_probe)
		c.ctx->DestroyStaticMesh(m);
	g_probe.clear();
	if (IMesh *m = BuildMesh(c.ctx, v.data(), static_cast<std::uint32_t>(v.size())))
		g_probe.push_back(m);
	g_tProbe.Add(NowMs() - t0);
	LUA->PushBool(!g_probe.empty());
	LUA->PushString(g_meshProbe.c_str());
	return 2;
}

// The calibration texture: 4 x 1 BGRA, made like the atlas (procedural, point sampled, no mips):
// texels white, grey 128, red, black, at u = 0.125, 0.375, 0.625, 0.875.
struct ProbeTex final : public ITextureRegenerator
{
	ITexture *tex = nullptr;
	void RegenerateTextureBits(ITexture *, IVTFTexture *vtf, Rect_t *) override
	{
		if (vtf == nullptr || vtf->Format() != IMAGE_FORMAT_BGRA8888 || vtf->Width() != 4 || vtf->Height() != 1)
			return;
		static const std::uint8_t kTexels[16] = { 255, 255, 255, 255, 128, 128, 128, 255, 0, 0, 255, 255, 0, 0, 0, 255 };
		if (unsigned char *dst = vtf->ImageData(0, 0, 0))
			std::memcpy(dst, kTexels, sizeof kTexels);
	}
	void Release() override {}
};
ProbeTex g_probeTex;

// BlocksProbeTexture() -> textureName | nil, err. Dev only.
LUA_FUNCTION_STATIC(BlocksProbeTexture)
{
	if (!IsDevMode())
		return PushFail(LUA, "dev only (-gmodcraft_dev)");
	IMaterialSystem *ms = ClientMatSys();
	if (ms == nullptr)
		return PushFail(LUA, "no material system");
	const char *name = "gmodcraft/probe_4x1";
	if (g_probeTex.tex == nullptr)
	{
		ITexture *t = ms->IsTextureLoaded(name) ? ms->FindTexture(name, TEXTURE_GROUP_OTHER, false) : nullptr;
		if (t != nullptr)
			t->IncrementReferenceCount();
		else
			t = ms->CreateProceduralTexture(name, TEXTURE_GROUP_OTHER, 4, 1, IMAGE_FORMAT_BGRA8888,
				TEXTUREFLAGS_PROCEDURAL | TEXTUREFLAGS_NOMIP | TEXTUREFLAGS_NOLOD | TEXTUREFLAGS_SINGLECOPY | TEXTUREFLAGS_POINTSAMPLE);
		if (t == nullptr || t->IsError())
			return PushFail(LUA, "CreateProceduralTexture failed");
		g_probeTex.tex = t;
		t->SetTextureRegenerator(&g_probeTex);
		t->Download();
	}
	LUA->PushString(name);
	return 1;
}

// BlocksProbeDraw(material) -> draw calls (IMaterial or a .vmt name). Dev only (0 otherwise).
LUA_FUNCTION_STATIC(BlocksProbeDraw)
{
	IMaterialSystem *ms = ClientMatSys();
	if (!IsDevMode() || ms == nullptr || g_probe.empty() || g_meshOk != 1)
	{
		LUA->PushNumber(0);
		return 1;
	}
	IMaterial *mat = ArgMat(LUA, 1, ms, 2);
	int calls = 0;
	if (mat != nullptr)
	{
		Ctx c(ms);
		const std::vector<IMesh *> *l = &g_probe;
		if (c.ctx)
			calls = DrawMeshes(c.ctx, mat, &l, 1);
	}
	LUA->PushNumber(calls);
	return 1;
}

// BlocksLuaGeneration() -> number: changes whenever a section's baked vertices change or one is
// removed (the Lua Mesh() fallback re-lists sections then).
LUA_FUNCTION_STATIC(BlocksLuaGeneration)
{
	LUA->PushNumber(static_cast<double>(g_luaGeneration));
	return 1;
}

// BlocksLuaSections() -> { {sx, sy, sz, version, opaque, translucent}, ... }
LUA_FUNCTION_STATIC(BlocksLuaSections)
{
	LUA->CreateTable();
	int i = 0;
	for (auto &kv : g_store.sections)
	{
		auto *g = static_cast<GpuSection *>(kv.second.gpu);
		if (g == nullptr)
			continue;
		LUA->PushNumber(++i);
		LUA->CreateTable();
		SetNum(LUA, "sx", kv.second.sx);
		SetNum(LUA, "sy", kv.second.sy);
		SetNum(LUA, "sz", kv.second.sz);
		SetNum(LUA, "version", g->version);
		SetNum(LUA, "opaque", static_cast<double>(g->lua[0].size()));
		SetNum(LUA, "translucent", static_cast<double>(g->lua[1].size()));
		SetNum(LUA, "animOpaque", static_cast<double>(g->lua[2].size()));
		SetNum(LUA, "animTranslucent", static_cast<double>(g->lua[3].size()));
		LUA->SetTable(-3);
	}
	return 1;
}

// BlocksLuaVerts(sx, sy, sz, pass[, out]) -> vertexCount, out | nil. Fills `out` (or a new table)
// with 5 numbers per vertex, raw-set from index 1: x, y, z (Source units), uv, rgba. Entries past
// 5 * vertexCount are left as they were (the caller reuses one table).
//   uv   = U * 2^24 + V, U and V the texture coordinates times kUvScale (2^24 - 1), rounded
//   rgba = R * 2^24 + G * 2^16 + B * 2^8 + A (colour as R, G, B, A whatever the bake's byte order)
// Both are integers below 2^53, exact in a double. 5 table sets per vertex instead of 9.
constexpr double kUvScale = 16777215.0;
LUA_FUNCTION_STATIC(BlocksLuaVerts)
{
	const blk::Section *s = g_store.Find(static_cast<std::int32_t>(ArgNum(LUA, 1)), static_cast<std::int32_t>(ArgNum(LUA, 2)),
		static_cast<std::int32_t>(ArgNum(LUA, 3)));
	const int pass = std::min(std::max(static_cast<int>(ArgNum(LUA, 4)), 0), blk::kPasses - 1);
	if (s == nullptr || s->gpu == nullptr)
		return 0;
	const auto &v = static_cast<const GpuSection *>(s->gpu)->lua[pass];
	const bool bgra = !g_store.Config().rgbaOrder;
	LUA->PushNumber(static_cast<double>(v.size()));
	if (LUA->IsType(5, Type::Table))
		LUA->Push(5);
	else
		LUA->CreateTable();
	auto fixed = [](float x) { return std::round(static_cast<double>(std::min(std::max(x, 0.0f), 1.0f)) * kUvScale); };
	double k = 0;
	for (const blk::OutVertex &o : v)
	{
		const double r = bgra ? o.color[2] : o.color[0], b = bgra ? o.color[0] : o.color[2];
		const double f[5] = { o.pos[0], o.pos[1], o.pos[2], fixed(o.uv[0]) * 16777216.0 + fixed(o.uv[1]),
			((r * 256.0 + o.color[1]) * 256.0 + b) * 256.0 + o.color[3] };
		for (double x : f)
		{
			LUA->PushNumber(++k);
			LUA->PushNumber(x);
			LUA->RawSet(-3);
		}
	}
	return 2;
}

// BlocksDevInject(sections[, cubesPerSection, mcX, mcY, mcZ]) -> injected. Dev only: synthetic
// sections (cubes on a grid of sections around the MC block given) through the real message path,
// for the frame-cost measurement. 0 removes them.
struct SecCoord
{
	std::int32_t sx, sy, sz;
};
std::vector<SecCoord> g_injected;
LUA_FUNCTION_STATIC(BlocksDevInject)
{
	if (!IsDevMode())
		return PushFail(LUA, "dev only (-gmodcraft_dev)");
	const int count = static_cast<int>(std::min(std::max(ArgNum(LUA, 1), 0.0), 4096.0));
	const int cubes = static_cast<int>(std::min(std::max(ArgNum(LUA, 2, 2), 1.0), 4096.0));
	const int bx = static_cast<int>(std::floor(ArgNum(LUA, 3) / 16)), by = static_cast<int>(std::floor(ArgNum(LUA, 4) / 16)),
			  bz = static_cast<int>(std::floor(ArgNum(LUA, 5) / 16));
	for (const SecCoord &c : g_injected)
	{
		if (g_store.Find(c.sx, c.sy, c.sz) == nullptr)
			continue;
		P::RenSection h{ c.sx, c.sy, c.sz, 0 };
		g_store.OnMessage(P::kRenSection, reinterpret_cast<const std::uint8_t *>(&h), sizeof h);
	}
	g_injected.clear();
	static const int kFaces[6][4][3] = {
		{ { 0, 1, 0 }, { 0, 1, 1 }, { 1, 1, 1 }, { 1, 1, 0 } }, { { 0, 0, 0 }, { 1, 0, 0 }, { 1, 0, 1 }, { 0, 0, 1 } },
		{ { 1, 0, 0 }, { 0, 0, 0 }, { 0, 1, 0 }, { 1, 1, 0 } }, { { 0, 0, 1 }, { 1, 0, 1 }, { 1, 1, 1 }, { 0, 1, 1 } },
		{ { 0, 0, 0 }, { 0, 0, 1 }, { 0, 1, 1 }, { 0, 1, 0 } }, { { 1, 0, 1 }, { 1, 0, 0 }, { 1, 1, 0 }, { 1, 1, 1 } },
	};
	static const std::uint32_t kCode[6] = { 2, 1, 3, 4, 5, 6 };
	const float u1 = g_store.atlas.w ? 16.0f / g_store.atlas.w : 0.01f, v1 = g_store.atlas.h ? 16.0f / g_store.atlas.h : 0.01f;
	std::vector<std::uint8_t> msg;
	const int side = static_cast<int>(std::ceil(std::sqrt(static_cast<double>(count))));
	for (int i = 0; i < count; ++i)
	{
		const int sx = bx - side / 2 + i % side, sz = bz - side / 2 + i / side, sy = by + 2;
		const std::uint32_t verts = static_cast<std::uint32_t>(cubes) * 36;
		msg.assign(sizeof(P::RenSection) + verts * sizeof(P::RenVertex), 0);
		P::RenSection h{ sx, sy, sz, verts };
		std::memcpy(msg.data(), &h, sizeof h);
		auto *v = reinterpret_cast<P::RenVertex *>(msg.data() + sizeof h);
		int w = 0;
		for (int c = 0; c < cubes; ++c)
		{
			const float ox = static_cast<float>((c * 3) % 16), oy = static_cast<float>((c / 16) % 16), oz = static_cast<float>((c * 7) % 16);
			for (int f = 0; f < 6; ++f)
				for (int k : { 0, 1, 2, 0, 2, 3 })
				{
					P::RenVertex &r = v[w++];
					r.x = ox + kFaces[f][k][0];
					r.y = oy + kFaces[f][k][1];
					r.z = oz + kFaces[f][k][2];
					r.u = (k == 1 || k == 2) ? u1 : 0;
					r.v = (k == 2 || k == 3) ? v1 : 0;
					r.color = 0xFFFFFFFFu;
					r.light = 0x0F00;
					r.flags = blk::kFlagCutout | (kCode[f] << blk::kNormalShift);
				}
		}
		g_store.OnMessage(P::kRenSection, msg.data(), static_cast<std::uint32_t>(msg.size()));
		g_injected.push_back(SecCoord{ sx, sy, sz });
	}
	LUA->PushNumber(count);
	return 1;
}
}  // namespace

// ---- called from entities.cpp (P3c) --------------------------------------------------------------
const blk::BakeConfig &BlocksBakeConfig()
{
	return g_store.Config();
}

bool BlocksDescSane(const MeshDesc_t &d, std::string *why)
{
	return DescSane(d, why);
}

// The static IMesh path's state (BlocksMeshOk) and one checked static mesh from baked vertices,
// for the per-frame fallback when the dynamic mesh fails its check; BlocksDestroyLater hands a
// mesh to the next BlocksPrepare.
int BlocksMeshState()
{
	return g_meshOk;
}

IMesh *BlocksStaticMesh(IMatRenderContext *ctx, const blk::OutVertex *v, std::uint32_t n)
{
	return BuildMesh(ctx, v, n);
}

void BlocksDestroyLater(IMesh *m)
{
	if (m != nullptr)
		g_destroy.push_back(m);
}

// ---- called from client.cpp ----------------------------------------------------------------------
void BlocksOnMessage(std::uint32_t type, const std::uint8_t *p, std::uint32_t n)
{
	g_store.OnMessage(type, p, n);
	if (type == P::kRenTexture || type == P::kRenAvatar || type == P::kRenScene || type == P::kRenRagdoll)
		EntitiesOnMessage(type, p, n);
	else if (type == P::kRenClearAll)
		EntitiesReset();  // a new level: entity texture ids restart at 1
}

void BlocksDrainTime(double ms)
{
	g_tDrain.Add(ms);
}

void BlocksSetOrigin(std::int32_t ox, std::int32_t oz, std::int32_t oy)
{
	blk::BakeConfig cfg = g_store.Config();
	cfg.ox = ox;
	cfg.oz = oz;
	cfg.oy = oy;
	g_store.SetConfig(cfg);
}

// A new Minecraft or a closed link: its sections go (it sends everything again on attach). The
// atlas stays until a new one arrives.
void BlocksReset()
{
	g_store.Clear();
	g_rebuild.clear();
	EntitiesReset();
}

void RegisterBlocks(ILua *L)
{
	g_store.onRelease = ReleaseGpu;
	struct Fn
	{
		const char *name;
		CFunc fn;
	};
	static const Fn fns[] = {
		{ "BlocksPrepare", BlocksPrepare },
		{ "BlocksDraw", BlocksDraw },
		{ "BlocksConfig", BlocksConfig },
		{ "BlocksRetryMesh", BlocksRetryMesh },
		{ "BlocksMeshOk", BlocksMeshOk },
		{ "BlocksLightQuery", BlocksLightQuery },
		{ "BlocksLightSet", BlocksLightSet },
		{ "BlocksLightRefresh", BlocksLightRefresh },
		{ "BlocksLights", BlocksLights },
		{ "BlocksLightsCount", BlocksLightsCount },
		{ "BlocksAtlas", BlocksAtlas },
		{ "BlocksAnim", BlocksAnim },
		{ "BlocksInfo", BlocksInfo },
		{ "BlocksProbe", BlocksProbe },
		{ "BlocksProbeDraw", BlocksProbeDraw },
		{ "BlocksProbeTexture", BlocksProbeTexture },
		{ "BlocksLuaGeneration", BlocksLuaGeneration },
		{ "BlocksLuaSections", BlocksLuaSections },
		{ "BlocksLuaVerts", BlocksLuaVerts },
		{ "BlocksDevInject", BlocksDevInject },
	};
	for (const Fn &f : fns)
	{
		L->PushCFunction(f.fn);
		L->SetField(-2, f.name);
	}
	RegisterEntities(L);
	RegisterHoles(L);
}

// Module close: every mesh and the atlas texture (the texture must not keep our regenerator).
void CloseBlocks()
{
	CloseEntities();  // first: its meshes go through g_destroy below
	CloseHoles();     // the same
	g_store.Clear();
	ResetMeshCheck();  // the .so may stay loaded into the next map: check the IMesh path afresh
	g_rebuild.clear();
	g_injected.clear();
	if (IMaterialSystem *ms = ClientMatSys())
	{
		Ctx c(ms);
		if (c.ctx)
		{
			for (IMesh *m : g_probe)
				c.ctx->DestroyStaticMesh(m);
			DestroyPending(c.ctx);
		}
	}
	g_probe.clear();
	g_destroy.clear();
	if (g_probeTex.tex != nullptr)
	{
		g_probeTex.tex->SetTextureRegenerator(nullptr);
		g_probeTex.tex->DecrementReferenceCount();
		g_probeTex.tex = nullptr;
	}
	for (MatSlot &m : g_mat)
		m = MatSlot{};
	if (g_atlas != nullptr)
	{
		g_atlas->Destroy();
		delete g_atlas;
		g_atlas = nullptr;
	}
	g_anim = Anim{};
	g_animFailedLayout = ~std::uint64_t{ 0 };
	g_animError.clear();
	if (g_animTex != nullptr)
	{
		g_animTex->Destroy();
		delete g_animTex;
		g_animTex = nullptr;
	}
}
}  // namespace gc

#endif  // GMODCRAFT_CLIENT
