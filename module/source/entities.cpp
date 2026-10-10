// gmcl_gmodcraft: Minecraft's per-frame things drawn natively (P3c, docs/DESIGN.md section 7).
// The render ring's entity textures, F5 player model, scene (mobs, block entities, particles) and
// ragdoll snapshot arrive through blocks.cpp's hook into entmesh.* (engine-free: parsing, bake,
// WorldEntity geometry); this file owns the engine side: one procedural BGRA texture per entity
// texture id, the per-frame bake (EntitiesPrepare, once per frame in PreRender), and the draw
// calls through the material system's dynamic mesh (GetDynamicMeshEx with the blocks' vertex
// format, so the lock descriptor is checked exactly like the static path). Lua makes the materials
// (client/entities.lua) and draws the block outline and shadow blobs itself.
//
// ABI (checked offline against GMod's x86-64 binaries, docs/spikes/p3c/README.md):
// IMatRenderContext::GetDynamicMesh 54, GetMaxToRender 127, GetDynamicMeshEx 164 in both
// CMatRenderContext and CMatQueuedRenderContext (argument use matches the SDK); CDynamicMeshDX8
// and CMatQueuedMesh override LockMesh 19 / UnlockMesh 22 / Draw 13 of the IMesh layout P3a
// verified. The first dynamic lock is still checked at run time (DescSane); if it fails, the
// geometry goes through checked static meshes destroyed the next frame (slower, same picture).
#ifdef GMODCRAFT_CLIENT

#include "entmesh.hpp"
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
#include <unordered_map>
#include <vector>

namespace gc
{
using namespace GarrysMod::Lua;
namespace P = gmodcraft::proto;

IMaterialSystem *ClientMatSys();  // client.cpp
bool IsDevMode();                 // main.cpp
// blocks.cpp
const blk::BakeConfig &BlocksBakeConfig();
bool BlocksDescSane(const MeshDesc_t &d, std::string *why);
int BlocksMeshState();
IMesh *BlocksStaticMesh(IMatRenderContext *ctx, const blk::OutVertex *v, std::uint32_t n);
void BlocksDestroyLater(IMesh *m);

namespace
{
const VertexFormat_t kFormat = VERTEX_POSITION | VERTEX_NORMAL | VERTEX_COLOR | VERTEX_TEXCOORD_SIZE(0, 2);
constexpr int kMaxChunk = 30000;  // vertices per dynamic draw at most (whole triangles; GetMaxToRender may lower it)

ent::Store g_store;
ent::LightCache g_light;
ent::Baked g_baked;
ent::WorldGeometry g_world;
P::WorldEntities g_we;  // the last good snapshot (Frame copies it; zeroed on reset)
std::uint64_t g_weReads = 0, g_weFails = 0;
double g_lastPrune = 0;

double Ema(double avg, double v, std::uint64_t n) { return n == 0 ? v : avg * 0.95 + v * 0.05; }
struct Timer
{
	double last = 0, avg = 0, max = 0, windowMax = 0, windowStart = 0;
	std::uint64_t n = 0;
	void Add(double ms)
	{
		last = ms;
		avg = Ema(avg, ms, n++);
		const double now = NowMs();
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
Timer g_tPrepare, g_tBake, g_tDraw[2], g_tUpload;

// ---- entity textures ------------------------------------------------------------------------------
struct EntTex final : public ITextureRegenerator
{
	std::uint32_t id = 0, w = 0, h = 0;
	std::string name;
	ITexture *tex = nullptr;
	std::uint64_t uploaded = 0, mismatches = 0;
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
		auto it = g_store.textures.find(id);
		const std::size_t bytes = static_cast<std::size_t>(w) * h * 4;
		if (it == g_store.textures.end() || it->second.w != w || it->second.h != h || it->second.bgra.size() < bytes)
			std::memset(dst, 0, bytes);
		else
			std::memcpy(dst, it->second.bgra.data(), bytes);
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
std::unordered_map<std::uint32_t, EntTex *> g_tex;
std::uint64_t g_texGen = 0;  // bumps when a texture is (re)created or replaced: Lua re-lists them
std::string g_texError;

ITexture *ProcTexture(IMaterialSystem *ms, const char *name, std::uint32_t w, std::uint32_t h, std::string *err)
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
		*err = std::string("CreateProceduralTexture failed: ") + name;
		return nullptr;
	}
	return tex;
}

void UploadTextures(IMaterialSystem *ms)
{
	if (g_store.changedTextures.empty())
		return;
	const double t0 = NowMs();
	for (std::uint32_t id : g_store.changedTextures)
	{
		auto it = g_store.textures.find(id);
		if (it == g_store.textures.end())
			continue;
		const ent::Texture &t = it->second;
		EntTex *&e = g_tex[id];
		if (e != nullptr && (e->w != t.w || e->h != t.h))
		{
			e->Destroy();
			delete e;
			e = nullptr;
		}
		if (e == nullptr)
		{
			char name[64];
			std::snprintf(name, sizeof name, "gmodcraft/ent_%u_%ux%u", id, t.w, t.h);
			ITexture *tex = ProcTexture(ms, name, t.w, t.h, &g_texError);
			if (tex == nullptr)
			{
				g_tex.erase(id);
				continue;
			}
			e = new EntTex();
			e->id = id;
			e->w = t.w;
			e->h = t.h;
			e->name = name;
			e->tex = tex;
			tex->SetTextureRegenerator(e);
		}
		e->tex->Download();
		++e->uploaded;
		++g_texGen;
	}
	g_store.changedTextures.clear();
	g_tUpload.Add(NowMs() - t0);
}

// The soft contact-shadow blob (Lua's shadow material): black, alpha falling off to the rim.
struct BlobTex final : public ITextureRegenerator
{
	ITexture *tex = nullptr;
	void RegenerateTextureBits(ITexture *, IVTFTexture *vtf, Rect_t *) override
	{
		if (vtf == nullptr || vtf->Format() != IMAGE_FORMAT_BGRA8888 || vtf->Width() != 64 || vtf->Height() != 64)
			return;
		unsigned char *d = vtf->ImageData(0, 0, 0);
		if (d == nullptr)
			return;
		for (int y = 0; y < 64; ++y)
			for (int x = 0; x < 64; ++x)
			{
				const float dx = (x + 0.5f) / 32.0f - 1.0f, dy = (y + 0.5f) / 32.0f - 1.0f;
				const float r = std::sqrt(dx * dx + dy * dy);
				const float t = std::min(std::max(1.0f - r, 0.0f) * 1.6f, 1.0f);
				unsigned char *p = d + (y * 64 + x) * 4;
				p[0] = p[1] = p[2] = 0;
				p[3] = static_cast<unsigned char>(t * t * (3 - 2 * t) * 255.0f + 0.5f);
			}
	}
	void Release() override {}
};
BlobTex g_blob;

// ---- materials (made by Lua per texture id; 0 = the block/item atlas) -----------------------------
struct MatPair
{
	IMaterial *m[2] = { nullptr, nullptr };  // cutout, translucent
};
std::unordered_map<std::uint32_t, MatPair> g_mats;

// ---- drawing ------------------------------------------------------------------------------------------
int g_dynOk = -1;  // the first dynamic lock's check: -1 not yet, 0 failed (static fallback), 1 ok
std::string g_dynProbe;
int g_chunk = 0;  // vertices per dynamic draw (GetMaxToRender, capped)
std::uint64_t g_drawCalls[2] = {}, g_lastCalls[2] = {}, g_noMaterial = 0, g_staticFallbackDraws = 0, g_lastVerts[2] = {};
std::uint64_t g_indexCuts = 0;  // dynamic locks cut short because m_nFirstVertex left less than a chunk of 16-bit index room

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

void Write(const MeshDesc_t &desc, const blk::OutVertex *v, int n)
{
	auto *pos = reinterpret_cast<std::uint8_t *>(desc.m_pPosition);
	auto *nrm = reinterpret_cast<std::uint8_t *>(desc.m_pNormal);
	auto *col = desc.m_pColor;
	auto *uv = reinterpret_cast<std::uint8_t *>(desc.m_pTexCoord[0]);
	const std::size_t s = static_cast<std::size_t>(desc.m_VertexSize_Position);
	for (int i = 0; i < n; ++i)
	{
		std::memcpy(pos + i * s, v[i].pos, 12);
		std::memcpy(nrm + i * s, v[i].normal, 12);
		std::memcpy(col + i * s, v[i].color, 4);
		std::memcpy(uv + i * s, v[i].uv, 8);
		desc.m_pIndices[i] = static_cast<unsigned short>(desc.m_nFirstVertex + i);
	}
}

// Draws n baked vertices with the bound material. Returns draw calls.
int DrawVerts(IMatRenderContext *ctx, const blk::OutVertex *v, std::size_t n)
{
	int calls = 0;
	std::size_t first = 0;
	while (first < n && g_dynOk != 0)
	{
		IMesh *m = ctx->GetDynamicMeshEx(kFormat, true, nullptr, nullptr, nullptr);
		if (m == nullptr)
		{
			g_dynOk = 0;
			g_dynProbe = "GetDynamicMeshEx returned null";
			break;
		}
		if (g_chunk == 0)
		{
			int mv = 0, mi = 0;
			ctx->GetMaxToRender(m, false, &mv, &mi);
			int c = std::min(std::min(mv, mi), kMaxChunk);
			g_chunk = c - c % 3;
			if (g_chunk < 3)
			{
				char b[96];
				std::snprintf(b, sizeof b, "GetMaxToRender: %d vertices, %d indices", mv, mi);
				g_dynOk = 0;
				g_dynProbe = b;
				g_chunk = 0;
				break;
			}
		}
		int cnt = static_cast<int>(std::min<std::size_t>(static_cast<std::size_t>(g_chunk), n - first));
		MeshDesc_t desc;
		std::memset(&desc, 0, sizeof desc);
		m->LockMesh(cnt, cnt, desc);
		// Every lock: the buffers must be there (the first lock's full check is below).
		if (desc.m_pPosition == nullptr || desc.m_pNormal == nullptr || desc.m_pColor == nullptr || desc.m_pTexCoord[0] == nullptr ||
			desc.m_pIndices == nullptr)
		{
			m->UnlockMesh(0, 0, desc);
			g_dynOk = 0;
			g_dynProbe = "LockMesh returned a descriptor without buffers";
			break;
		}
		// 16-bit indices: m_nFirstVertex + cnt must stay <= 65535 (whole triangles).
		if (desc.m_nFirstVertex < 0 || desc.m_nFirstVertex + cnt > 65535)
		{
			const int room = desc.m_nFirstVertex < 0 ? 0 : 65535 - desc.m_nFirstVertex;
			cnt = room - room % 3;
			++g_indexCuts;
			if (cnt < 3)
			{
				m->UnlockMesh(0, 0, desc);
				break;
			}
		}
		if (g_dynOk < 0)
		{
			std::string why;
			const bool ok = BlocksDescSane(desc, &why);
			char b[96];
			std::snprintf(b, sizeof b, "; chunk %d", g_chunk);
			g_dynProbe = why + b;
			if (!ok)
			{
				m->UnlockMesh(0, 0, desc);
				g_dynOk = 0;
				break;
			}
			g_dynOk = 1;
		}
		Write(desc, v + first, cnt);
		m->UnlockMesh(cnt, cnt, desc);
		m->Draw();
		++calls;
		first += static_cast<std::size_t>(cnt);
	}
	// The fallback: checked static meshes, destroyed at the next BlocksPrepare.
	while (first < n && g_dynOk == 0 && BlocksMeshState() != 0)
	{
		const std::uint32_t cnt = static_cast<std::uint32_t>(std::min<std::size_t>(blk::kMaxMeshVertices, n - first));
		IMesh *m = BlocksStaticMesh(ctx, v + first, cnt);
		if (m == nullptr)
			break;
		m->Draw();
		BlocksDestroyLater(m);
		++calls;
		++g_staticFallbackDraws;
		first += cnt;
	}
	return calls;
}

// ---- dev: a synthetic busy scene ------------------------------------------------------------------------
ent::MeshMsg g_inject;
std::uint32_t g_injectMobs = 0, g_injectParticles = 0;
void BuildInject(double mx, double my, double mz)
{
	g_inject = ent::MeshMsg{};
	g_inject.origin[0] = std::floor(mx);
	g_inject.origin[1] = std::floor(my);
	g_inject.origin[2] = std::floor(mz);
	const float uv[4] = { 0, 0, 0.004f, 0.004f };
	std::vector<P::RenVertex> v;
	// mobs: 10 boxes each (360 vertices, ~a cow), in a ring 4-12 blocks around
	for (std::uint32_t i = 0; i < g_injectMobs; ++i)
	{
		const float a = static_cast<float>(i) * 2.399963f, r = 4.0f + static_cast<float>(i % 9);
		const float cx = std::cos(a) * r, cz = std::sin(a) * r;
		for (int b = 0; b < 10; ++b)
		{
			const float mn[3] = { cx - 0.4f + 0.08f * b, 0.1f * b, cz - 0.3f };
			const float sz[3] = { 0.3f, 0.3f, 0.6f };
			ent::Box(v, mn, sz, a, uv, uv, uv, 0, blk::kFlagCutout | blk::kFlagNoMip, true);
		}
	}
	const std::uint32_t mobVerts = static_cast<std::uint32_t>(v.size());
	for (std::uint32_t i = 0; i < g_injectParticles; ++i)
	{
		const float a = static_cast<float>(i) * 0.61803f * 6.2831853f, r = 1.0f + static_cast<float>(i % 7);
		const float x = std::cos(a) * r, y = 1.0f + static_cast<float>(i % 5) * 0.4f, z = std::sin(a) * r, h = 0.05f;
		const float q[4][3] = { { x - h, y + h, z }, { x + h, y + h, z }, { x + h, y - h, z }, { x - h, y - h, z } };
		ent::Quad(v, q, uv, 0xC0FFFFFFu, blk::kFlagTranslucent | blk::kFlagNoMip);
	}
	g_inject.verts = v;
	if (mobVerts)
		g_inject.batches.push_back(P::RenBatch{ 0, 0, mobVerts, 0 });
	if (v.size() > mobVerts)
		g_inject.batches.push_back(P::RenBatch{ 0, mobVerts, static_cast<std::uint32_t>(v.size()) - mobVerts, 1 });
}

// ---- Lua ----------------------------------------------------------------------------------------------
// EntitiesPrepare({ feet = bool, x, y, z (Source feet), s = defaultS, light = bool, avatar, scene, world })
// Once per frame (PreRender, after BlocksPrepare): uploads new entity textures and bakes the F5
// model (at the feet given), the scene and the WorldEntities geometry for this frame's draws.
LUA_FUNCTION_STATIC(EntitiesPrepare)
{
	IMaterialSystem *ms = ClientMatSys();
	const double t0 = NowMs();
	if (ms != nullptr)
		UploadTextures(ms);
	const bool tbl = LUA->IsType(1, Type::Table);
	const bool lightOn = tbl ? FieldBool(LUA, 1, "light", true) : true;
	const bool avatarOn = tbl ? FieldBool(LUA, 1, "avatar", true) : true;
	const bool sceneOn = tbl ? FieldBool(LUA, 1, "scene", true) : true;
	const bool worldOn = tbl ? FieldBool(LUA, 1, "world", true) : true;
	const bool feet = tbl && FieldBool(LUA, 1, "feet", false);
	g_light.defaultS = static_cast<float>(std::min(std::max(tbl ? FieldNum(LUA, 1, "s", 1.0) : 1.0, 0.0), 1.0));  // FieldNum: finite
	const blk::BakeConfig &cfg = BlocksBakeConfig();
	ent::LightCache *light = lightOn ? &g_light : nullptr;
	const double t1 = NowMs();
	g_baked.Clear();
	if (avatarOn && feet && !g_store.avatar.batches.empty())
	{
		const double fx = FieldNum(LUA, 1, "x"), fy = FieldNum(LUA, 1, "y"), fz = FieldNum(LUA, 1, "z");
		const double o[3] = { fx / 40.0 + cfg.ox, (fz + cfg.oy) / 40.0, -fy / 40.0 + cfg.oz };
		ent::BakeMesh(g_store.avatar.batches.data(), g_store.avatar.batches.size(), g_store.avatar.verts.data(), g_store.avatar.verts.size(), o, cfg, light,
			t1, g_baked);
	}
	if (sceneOn)
	{
		const ent::MeshMsg &s = g_store.scene;
		ent::BakeMesh(s.batches.data(), s.batches.size(), s.verts.data(), s.verts.size(), s.origin, cfg, light, t1, g_baked);
		if (!g_inject.batches.empty())
			ent::BakeMesh(g_inject.batches.data(), g_inject.batches.size(), g_inject.verts.data(), g_inject.verts.size(), g_inject.origin, cfg, light, t1,
				g_baked);
	}
	g_world.Clear();
	if (worldOn)
	{
		const double o[3] = { static_cast<double>(cfg.ox), 0.0, static_cast<double>(cfg.oz) };
		ent::BuildWorld(g_we, o, cfg, g_world);
		const P::RenBatch bo{ 0, 0, static_cast<std::uint32_t>(g_world.opaque.size()), 0 };
		const P::RenBatch bt{ 0, 0, static_cast<std::uint32_t>(g_world.translucent.size()), 1 };
		if (bo.count)
			ent::BakeMesh(&bo, 1, g_world.opaque.data(), g_world.opaque.size(), o, cfg, light, t1, g_baked);
		if (bt.count)
			ent::BakeMesh(&bt, 1, g_world.translucent.data(), g_world.translucent.size(), o, cfg, light, t1, g_baked);
	}
	if (t1 - g_lastPrune > 1000)
	{
		g_light.Prune(t1);
		g_lastPrune = t1;
	}
	const double t2 = NowMs();
	g_tBake.Add(t2 - t1);
	g_tPrepare.Add(t2 - t0);
	return 0;
}

// EntitiesDraw(pass) -> draw calls: 0 opaque + cutout, 1 translucent (after the blocks' own).
LUA_FUNCTION_STATIC(EntitiesDraw)
{
	const int pass = static_cast<int>(ArgNum(LUA, 1)) == 1 ? 1 : 0;
	IMaterialSystem *ms = ClientMatSys();
	int calls = 0;
	std::uint64_t verts = 0;
	const double t0 = NowMs();
	if (ms != nullptr && g_baked.used[pass] > 0)
	{
		Ctx c(ms);
		if (c.ctx != nullptr)
		{
			c.ctx->MatrixMode(MATERIAL_MODEL);
			c.ctx->PushMatrix();
			c.ctx->LoadIdentity();
			for (std::size_t i = 0; i < g_baked.used[pass]; ++i)
			{
				const ent::Group &g = g_baked.pass[pass][i];
				if (g.v.empty())
					continue;
				auto it = g_mats.find(g.texture);
				IMaterial *mat = it == g_mats.end() ? nullptr : it->second.m[pass];
				if (mat == nullptr)
				{
					++g_noMaterial;
					continue;
				}
				c.ctx->Bind(mat, nullptr);
				calls += DrawVerts(c.ctx, g.v.data(), g.v.size());
				verts += g.v.size();
			}
			c.ctx->MatrixMode(MATERIAL_MODEL);
			c.ctx->PopMatrix();
		}
	}
	g_drawCalls[pass] += calls;
	g_lastCalls[pass] = calls;
	g_lastVerts[pass] = verts;
	g_tDraw[pass].Add(NowMs() - t0);
	LUA->PushNumber(calls);
	return 1;
}

// EntitiesTextures(gen) -> nil when gen is current, else newGen, { {id, name, w, h}, ... } (Lua
// makes each texture's materials when its name changed).
LUA_FUNCTION_STATIC(EntitiesTextures)
{
	const double gen = ArgNum(LUA, 1, -1);
	if (gen >= 0 && gen == static_cast<double>(g_texGen))
		return 0;
	LUA->PushNumber(static_cast<double>(g_texGen));
	LUA->CreateTable();
	int i = 0;
	for (const auto &kv : g_tex)
	{
		if (kv.second == nullptr || kv.second->tex == nullptr)
			continue;
		LUA->PushNumber(++i);
		LUA->CreateTable();
		SetNum(LUA, "id", kv.first);
		SetStr(LUA, "name", kv.second->name.c_str());
		SetNum(LUA, "w", kv.second->w);
		SetNum(LUA, "h", kv.second->h);
		LUA->SetTable(-3);
	}
	return 2;
}

// EntitiesSetMaterials(id, cutout, translucent): IMaterial objects (CreateMaterial'd: FindMaterial
// doesn't see them, P3a) for texture id (0 = the atlas). nil clears.
LUA_FUNCTION_STATIC(EntitiesSetMaterials)
{
	const double idArg = ArgNum(LUA, 1);
	if (!(idArg >= 0 && idArg <= 4294967295.0))
		return 0;
	const std::uint32_t id = static_cast<std::uint32_t>(idArg);
	MatPair p;
	for (int k = 0; k < 2; ++k)
		if (LUA->IsType(2 + k, Type::Material))
			p.m[k] = LUA->GetUserType<IMaterial>(2 + k, Type::Material);
	if (p.m[0] == nullptr && p.m[1] == nullptr)
		g_mats.erase(id);
	else
		g_mats[id] = p;
	return 0;
}

// EntitiesShadowTexture() -> name | nil: the contact-shadow blob (64 x 64).
LUA_FUNCTION_STATIC(EntitiesShadowTexture)
{
	IMaterialSystem *ms = ClientMatSys();
	if (ms == nullptr)
		return 0;
	const char *name = "gmodcraft/shadow_blob";
	if (g_blob.tex == nullptr)
	{
		std::string err;
		ITexture *t = ProcTexture(ms, name, 64, 64, &err);
		if (t == nullptr)
			return PushFail(LUA, err.c_str());
		g_blob.tex = t;
		t->SetTextureRegenerator(&g_blob);
		t->Download();
	}
	LUA->PushString(name);
	return 1;
}

// EntitiesLightQuery(max[, out]) -> n, out: 6 numbers per cell wanting GMod's light: cx, cy, cz,
// x, y, z (Source units: the first point the geometry needed it at).
LUA_FUNCTION_STATIC(EntitiesLightQuery)
{
	static std::vector<ent::LightProbe> probes;
	const blk::BakeConfig &cfg = BlocksBakeConfig();
	g_light.Query(static_cast<std::size_t>(std::min(std::max(ArgNum(LUA, 1, 16), 0.0), 1024.0)), NowMs(), cfg.ox, cfg.oz, cfg.oy, probes);
	LUA->PushNumber(static_cast<double>(probes.size()));
	if (LUA->IsType(2, Type::Table))
		LUA->Push(2);
	else
		LUA->CreateTable();
	double k = 0;
	for (const ent::LightProbe &p : probes)
	{
		const double f[6] = { static_cast<double>(p.cx), static_cast<double>(p.cy), static_cast<double>(p.cz), p.pos[0], p.pos[1], p.pos[2] };
		for (double x : f)
		{
			LUA->PushNumber(++k);
			LUA->PushNumber(x);
			LUA->RawSet(-3);
		}
	}
	return 2;
}

// EntitiesLightSet(list, n): 4 numbers per cell: cx, cy, cz, S (0..1).
LUA_FUNCTION_STATIC(EntitiesLightSet)
{
	if (!LUA->IsType(1, Type::Table))
		return 0;
	const int n = static_cast<int>(std::min(std::max(ArgNum(LUA, 2), 0.0), 1024.0));
	const double now = NowMs();
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
		g_light.Set(c[0], c[1], c[2], static_cast<float>(f[3]), now);
	}
	return 0;
}

// EntitiesWorld(out[, withEntities]) -> out: what Lua draws itself and the tests read.
//   sel (bool), selMin / selMax ({x, y, z} Source units, reused tables), nShadows, shadows = flat
//   x, y, z, radius per shadow (Source units); withEntities: nEnts, ents = flat kind, id, yaw, x,
//   y, z (MC) per entity.
LUA_FUNCTION_STATIC(EntitiesWorld)
{
	if (!LUA->IsType(1, Type::Table))
		LUA->CreateTable();
	else
		LUA->Push(1);
	const int t = LUA->Top();
	LUA->PushBool(g_world.hasSelection);
	LUA->SetField(t, "sel");
	auto vec = [&](const char *key, const float v[3]) {
		LUA->GetField(t, key);
		if (!LUA->IsType(-1, Type::Table))
		{
			LUA->Pop();
			LUA->CreateTable();
			LUA->Push(-1);
			LUA->SetField(t, key);
		}
		for (int k = 0; k < 3; ++k)
		{
			LUA->PushNumber(k + 1);
			LUA->PushNumber(v[k]);
			LUA->RawSet(-3);
		}
		LUA->Pop();
	};
	vec("selMin", g_world.selMin);
	vec("selMax", g_world.selMax);
	LUA->PushNumber(static_cast<double>(g_world.shadows.size()));
	LUA->SetField(t, "nShadows");
	auto flat = [&](const char *key) {
		LUA->GetField(t, key);
		if (!LUA->IsType(-1, Type::Table))
		{
			LUA->Pop();
			LUA->CreateTable();
			LUA->Push(-1);
			LUA->SetField(t, key);
		}
	};
	flat("shadows");
	double k = 0;
	for (const ent::Shadow &s : g_world.shadows)
		for (double x : { static_cast<double>(s.pos[0]), static_cast<double>(s.pos[1]), static_cast<double>(s.pos[2]), static_cast<double>(s.radius) })
		{
			LUA->PushNumber(++k);
			LUA->PushNumber(x);
			LUA->RawSet(-3);
		}
	LUA->Pop();
	if (LUA->GetBool(2))
	{
		const std::uint32_t n = std::min(g_we.count, P::kMaxWorldEntities);
		LUA->PushNumber(n);
		LUA->SetField(t, "nEnts");
		flat("ents");
		k = 0;
		for (std::uint32_t i = 0; i < n; ++i)
		{
			const P::WorldEntity &e = g_we.entities[i];
			for (double x : { static_cast<double>(e.kind), static_cast<double>(e.id), static_cast<double>(e.yaw), static_cast<double>(e.x),
					 static_cast<double>(e.y), static_cast<double>(e.z) })
			{
				LUA->PushNumber(++k);
				LUA->PushNumber(x);
				LUA->RawSet(-3);
			}
		}
		LUA->Pop();
	}
	LUA->Push(t);
	return 1;
}

void PushTimer(ILua *L, const char *key, const Timer &t)
{
	L->CreateTable();
	SetNum(L, "last", t.last);
	SetNum(L, "avg", t.avg);
	SetNum(L, "max", t.Max());
	L->SetField(-2, key);
}

void PushMeshMsg(ILua *L, const char *key, const ent::MeshMsg &m)
{
	L->CreateTable();
	SetNum(L, "batches", static_cast<double>(m.batches.size()));
	SetNum(L, "vertices", static_cast<double>(m.verts.size()));
	SetNum(L, "sent", m.sentVerts);
	SetNum(L, "dropped", m.droppedVerts);
	SetNum(L, "seq", static_cast<double>(m.seq));
	L->SetField(-2, key);
}

// EntitiesInfo() -> everything the Render panel shows for P3c.
LUA_FUNCTION_STATIC(EntitiesInfo)
{
	LUA->CreateTable();
	const ent::Counters &c = g_store.counters;
	SetNum(LUA, "textures", static_cast<double>(g_tex.size()));
	SetNum(LUA, "texturesReceived", static_cast<double>(c.textures));
	SetNum(LUA, "textureRejects", static_cast<double>(c.textureRejects));
	SetStr(LUA, "textureError", g_texError.c_str());
	SetNum(LUA, "materials", static_cast<double>(g_mats.size()));
	SetNum(LUA, "avatars", static_cast<double>(c.avatars));
	SetNum(LUA, "scenes", static_cast<double>(c.scenes));
	SetNum(LUA, "ragdolls", static_cast<double>(c.ragdolls));
	SetNum(LUA, "malformed", static_cast<double>(c.malformed));
	SetNum(LUA, "badBatches", static_cast<double>(c.badBatches));
	SetNum(LUA, "overflowMsgs", static_cast<double>(c.overflowMsgs));
	SetNum(LUA, "overflowVerts", static_cast<double>(c.overflowVerts));
	PushMeshMsg(LUA, "avatar", g_store.avatar);
	PushMeshMsg(LUA, "scene", g_store.scene);
	PushMeshMsg(LUA, "ragdoll", g_store.ragdoll);
	SetNum(LUA, "worldEntities", static_cast<double>(std::min(g_we.count, P::kMaxWorldEntities)));
	SetNum(LUA, "items", g_world.items);
	SetNum(LUA, "blocks", g_world.blocks);
	SetNum(LUA, "arrows", g_world.arrows);
	SetNum(LUA, "cracks", g_world.cracks);
	SetNum(LUA, "shadows", static_cast<double>(g_world.shadows.size()));
	SetBool(LUA, "selection", g_world.hasSelection);
	SetNum(LUA, "worldReads", static_cast<double>(g_weReads));
	SetNum(LUA, "worldReadFails", static_cast<double>(g_weFails));
	SetNum(LUA, "bakedVertices", static_cast<double>(g_baked.Vertices()));
	SetNum(LUA, "groups", static_cast<double>(g_baked.Groups()));
	SetNum(LUA, "drawCallsOpaque", static_cast<double>(g_lastCalls[0]));
	SetNum(LUA, "drawCallsTranslucent", static_cast<double>(g_lastCalls[1]));
	SetNum(LUA, "drawnOpaque", static_cast<double>(g_lastVerts[0]));
	SetNum(LUA, "drawnTranslucent", static_cast<double>(g_lastVerts[1]));
	SetNum(LUA, "noMaterial", static_cast<double>(g_noMaterial));
	SetNum(LUA, "dynOk", g_dynOk);
	SetStr(LUA, "dynProbe", g_dynProbe.c_str());
	SetNum(LUA, "chunk", g_chunk);
	SetNum(LUA, "staticFallbackDraws", static_cast<double>(g_staticFallbackDraws));
	SetNum(LUA, "indexCuts", static_cast<double>(g_indexCuts));
	SetNum(LUA, "lightCells", static_cast<double>(g_light.Cells()));
	SetNum(LUA, "lightPending", static_cast<double>(g_light.Pending(NowMs())));
	SetNum(LUA, "lightSets", static_cast<double>(g_light.sets));
	SetNum(LUA, "lightDefault", g_light.defaultS);
	SetNum(LUA, "injectMobs", g_injectMobs);
	SetNum(LUA, "injectParticles", g_injectParticles);
	PushTimer(LUA, "prepareMs", g_tPrepare);
	PushTimer(LUA, "bakeMs", g_tBake);
	PushTimer(LUA, "uploadMs", g_tUpload);
	PushTimer(LUA, "drawOpaqueMs", g_tDraw[0]);
	PushTimer(LUA, "drawTranslucentMs", g_tDraw[1]);
	return 1;
}

// EntitiesDevInject(mobs, particles[, mcX, mcY, mcZ]) -> vertices. Dev only: a synthetic busy scene
// (box mobs on the atlas, translucent particles) drawn with the real one, for the frame-cost
// measurement. 0, 0 removes it.
LUA_FUNCTION_STATIC(EntitiesDevInject)
{
	if (!IsDevMode())
		return PushFail(LUA, "dev only (-gmodcraft_dev)");
	g_injectMobs = static_cast<std::uint32_t>(std::min(std::max(ArgNum(LUA, 1), 0.0), 512.0));
	g_injectParticles = static_cast<std::uint32_t>(std::min(std::max(ArgNum(LUA, 2), 0.0), 16384.0));
	BuildInject(ArgNum(LUA, 3), ArgNum(LUA, 4), ArgNum(LUA, 5));
	LUA->PushNumber(static_cast<double>(g_inject.verts.size()));
	return 1;
}

// EntitiesRetryDynamic(): forget the dynamic mesh check (the Render panel's retry).
LUA_FUNCTION_STATIC(EntitiesRetryDynamic)
{
	g_dynOk = -1;
	g_dynProbe.clear();
	g_chunk = 0;
	return 0;
}
}  // namespace

// ---- called from blocks.cpp / client.cpp ---------------------------------------------------------------
void EntitiesOnMessage(std::uint32_t type, const std::uint8_t *p, std::uint32_t n)
{
	g_store.OnMessage(type, p, n);
}

// Frame (client.cpp) hands the WorldEntities region once per frame, right after the ring drain:
// the snapshot is copied at once (the mapping can go away with the link).
void EntitiesReadWorld(const P::WorldEntities *w)
{
	if (w == nullptr)
		return;
	static P::WorldEntities tmp;
	const long long seq = SeqRead(w, &tmp);
	if (seq < 0)
	{
		++g_weFails;
		return;
	}
	std::memcpy(static_cast<void *>(&g_we), &tmp, sizeof tmp);
	++g_weReads;
}

// A new Minecraft or a closed link: its textures, model, scene and world entities go (texture ids
// restart at 1; Minecraft sends each texture again before using it). GPU textures stay and are
// refilled by id; Lua's materials follow their names.
void EntitiesReset()
{
	g_store.Clear();
	g_light.Clear();
	g_baked.Clear();
	g_world.Clear();
	std::memset(static_cast<void *>(&g_we), 0, sizeof g_we);
}

void RegisterEntities(ILua *L)
{
	struct Fn
	{
		const char *name;
		CFunc fn;
	};
	static const Fn fns[] = {
		{ "EntitiesPrepare", EntitiesPrepare },
		{ "EntitiesDraw", EntitiesDraw },
		{ "EntitiesTextures", EntitiesTextures },
		{ "EntitiesSetMaterials", EntitiesSetMaterials },
		{ "EntitiesShadowTexture", EntitiesShadowTexture },
		{ "EntitiesLightQuery", EntitiesLightQuery },
		{ "EntitiesLightSet", EntitiesLightSet },
		{ "EntitiesWorld", EntitiesWorld },
		{ "EntitiesInfo", EntitiesInfo },
		{ "EntitiesDevInject", EntitiesDevInject },
		{ "EntitiesRetryDynamic", EntitiesRetryDynamic },
	};
	for (const Fn &f : fns)
	{
		L->PushCFunction(f.fn);
		L->SetField(-2, f.name);
	}
}

void CloseEntities()
{
	EntitiesReset();
	g_mats.clear();
	g_dynOk = -1;
	g_dynProbe.clear();
	g_chunk = 0;
	g_inject = ent::MeshMsg{};
	g_injectMobs = g_injectParticles = 0;
	for (auto &kv : g_tex)
		if (kv.second != nullptr)
		{
			kv.second->Destroy();
			delete kv.second;
		}
	g_tex.clear();
	++g_texGen;
	if (g_blob.tex != nullptr)
	{
		g_blob.tex->SetTextureRegenerator(nullptr);
		g_blob.tex->DecrementReferenceCount();
		g_blob.tex = nullptr;
	}
}
}  // namespace gc

#endif  // GMODCRAFT_CLIENT
