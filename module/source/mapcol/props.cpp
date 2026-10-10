// Static props: the sprp game lump, .phy decoding, placement.
#include <algorithm>

#include "internal.hpp"

namespace gmodcraft::mapcol
{
	using namespace detail;

	namespace
	{
		constexpr std::int32_t kSprpId = ('s' << 24) | ('p' << 16) | ('r' << 8) | 'p';
		constexpr std::int32_t kMaxGameLumps = 4096;
		constexpr std::int32_t kMaxPhySolids = 1024;
		constexpr float        kDegToRad = 3.14159265358979323846f / 180.0f;
	}

	namespace detail
	{
		Vec3 Rotate(const float m[3][3], const Vec3& p, const Vec3& o)
		{
			return Vec3{ m[0][0] * p.x + m[0][1] * p.y + m[0][2] * p.z + o.x, m[1][0] * p.x + m[1][1] * p.y + m[1][2] * p.z + o.y,
				m[2][0] * p.x + m[2][1] * p.y + m[2][2] * p.z + o.z };
		}

		void BoxTris(const Vec3& mins, const Vec3& maxs, const float m[3][3], const Vec3& o, Tri proto, std::vector<Tri>& out)
		{
			Vec3 c[8];
			for (int i = 0; i < 8; ++i) {
				c[i] = Rotate(m, Vec3{ (i & 1) ? maxs.x : mins.x, (i & 2) ? maxs.y : mins.y, (i & 4) ? maxs.z : mins.z }, o);
			}
			const Vec3 centre = Rotate(m, Vec3{ (mins.x + maxs.x) / 2, (mins.y + maxs.y) / 2, (mins.z + maxs.z) / 2 }, o);
			static constexpr int kQuads[6][4] = { { 0, 2, 6, 4 }, { 1, 3, 7, 5 }, { 0, 1, 5, 4 }, { 2, 3, 7, 6 }, { 0, 1, 3, 2 }, { 4, 5, 7, 6 } };
			auto emit = [&](int a, int b, int d) {
				Tri t = proto;
				t.v[0] = c[a];
				t.v[1] = c[b];
				t.v[2] = c[d];
				const Vec3 n = Cross3(Sub3(t.v[1], t.v[0]), Sub3(t.v[2], t.v[0]));
				const Vec3 mid{ (t.v[0].x + t.v[1].x + t.v[2].x) / 3 - centre.x, (t.v[0].y + t.v[1].y + t.v[2].y) / 3 - centre.y,
					(t.v[0].z + t.v[1].z + t.v[2].z) / 3 - centre.z };
				if (Dot3(n, mid) < 0) {
					std::swap(t.v[1], t.v[2]);
				}
				out.push_back(t);
			};
			for (const auto& q : kQuads) {
				emit(q[0], q[1], q[2]);
				emit(q[0], q[2], q[3]);
			}
		}
	}

	void AngleMatrix(const Vec3& angles, float m[3][3])
	{
		const float sp = std::sin(angles.x * kDegToRad), cp = std::cos(angles.x * kDegToRad);
		const float sy = std::sin(angles.y * kDegToRad), cy = std::cos(angles.y * kDegToRad);
		const float sr = std::sin(angles.z * kDegToRad), cr = std::cos(angles.z * kDegToRad);
		m[0][0] = cp * cy;
		m[1][0] = cp * sy;
		m[2][0] = -sp;
		m[0][1] = sp * sr * cy - cr * sy;
		m[1][1] = sp * sr * sy + cr * cy;
		m[2][1] = sr * cp;
		m[0][2] = sp * cr * cy + sr * sy;
		m[1][2] = sp * cr * sy - sr * cy;
		m[2][2] = cr * cp;
	}

	bool ParseStaticProps(const BspLumps& lumps, std::vector<StaticPropEntry>& out, int& version, std::string& err)
	{
		version = 0;
		const Bytes gl = lumps.lump[kLumpGame];
		if (!gl.size) {
			return true;
		}
		std::int32_t count = 0;
		if (!Rd(gl, 0, count) || count < 0 || count > kMaxGameLumps || !InRange(gl, 4, std::int64_t(count) * 16)) {
			err = "gamelump: bad directory";
			return false;
		}
		for (std::int32_t k = 0; k < count; ++k) {
			std::int32_t  id = 0, fileofs = 0, filelen = 0;
			std::uint16_t flags = 0, ver = 0;
			const std::int64_t e = 4 + std::int64_t(k) * 16;
			Rd(gl, e, id);
			Rd(gl, e + 4, flags);
			Rd(gl, e + 6, ver);
			Rd(gl, e + 8, fileofs);
			Rd(gl, e + 12, filelen);
			if (id != kSprpId) {
				continue;
			}
			version = ver;
			if (flags & 1) {
				err = "sprp: LZMA-compressed game lump unsupported";
				return false;
			}
			if (ver < 4 || ver > 11) {
				err = "sprp: unsupported version " + std::to_string(ver);
				return false;
			}
			// Directory offsets are absolute file offsets; fall back to lump-relative ones.
			std::int64_t rel = std::int64_t(fileofs) - lumps.fileOffset[kLumpGame];
			if (!InRange(gl, rel, filelen)) {
				rel = fileofs;
			}
			if (filelen < 0 || !InRange(gl, rel, filelen)) {
				err = "sprp: lump outside the game lump";
				return false;
			}
			const Bytes d = Sub(gl, rel, filelen);
			if (d.size >= 4 && std::memcmp(d.data, "LZMA", 4) == 0) {
				err = "sprp: LZMA-compressed game lump unsupported";
				return false;
			}
			std::int64_t p = 0;
			std::int32_t nDict = 0, nLeaf = 0, nProps = 0;
			if (!Rd(d, p, nDict) || nDict < 0 || !InRange(d, 4, std::int64_t(nDict) * 128)) {
				err = "sprp: bad model dictionary";
				return false;
			}
			std::vector<std::string> dict;
			for (std::int32_t i = 0; i < nDict; ++i) {
				const auto* s = reinterpret_cast<const char*>(d.data) + 4 + std::int64_t(i) * 128;
				std::string name(s, std::find(s, s + 128, '\0'));
				std::replace(name.begin(), name.end(), '\\', '/');
				dict.push_back(Lower(name));
			}
			p = 4 + std::int64_t(nDict) * 128;
			if (!Rd(d, p, nLeaf) || nLeaf < 0 || !InRange(d, p + 4, std::int64_t(nLeaf) * 2)) {
				err = "sprp: bad leaf list";
				return false;
			}
			p += 4 + std::int64_t(nLeaf) * 2;
			if (!Rd(d, p, nProps) || nProps < 0) {
				err = "sprp: bad prop count";
				return false;
			}
			p += 4;
			if (nProps == 0) {
				return true;
			}
			const std::int64_t remaining = std::int64_t(d.size) - p;
			if (remaining < 0 || remaining % nProps != 0 || remaining / nProps < 31) {
				err = "sprp: prop records do not divide the lump (" + std::to_string(remaining) + " bytes / " + std::to_string(nProps) + ")";
				return false;
			}
			const std::int64_t stride = remaining / nProps;
			for (std::int32_t i = 0; i < nProps; ++i) {
				const std::int64_t q = p + stride * i;
				StaticPropEntry    e2{};
				float              f[6] = {};
				std::uint16_t      type = 0;
				Rd(d, q, f);
				Rd(d, q + 24, type);
				Rd(d, q + 30, e2.solid);
				if (type >= dict.size()) {
					err = "sprp: prop " + std::to_string(i) + " model index out of range";
					return false;
				}
				e2.index = static_cast<std::uint32_t>(i);
				e2.model = dict[type];
				e2.origin = Vec3{ f[0], f[1], f[2] };
				e2.angles = Vec3{ f[3], f[4], f[5] };
				if (!Finite3(e2.origin) || !Finite3(e2.angles)) {
					err = "sprp: prop " + std::to_string(i) + " non-finite transform";
					return false;
				}
				out.push_back(std::move(e2));
			}
			return true;
		}
		return true;  // no sprp
	}

	bool DecodePhy(Bytes phy, MaterialTable& mats, PhyModel& out, std::string& err)
	{
		out.solids.clear();
		std::int32_t hdr = 0, solidCount = 0;
		if (!Rd(phy, 0, hdr) || !Rd(phy, 8, solidCount) || hdr < 16 || !InRange(phy, 0, hdr) || solidCount < 0 ||
			solidCount > kMaxPhySolids) {
			err = "phy: bad header";
			return false;
		}
		std::vector<Bytes> blobs;
		std::int64_t       p = hdr;
		for (std::int32_t j = 0; j < solidCount; ++j) {
			std::int32_t sz = 0;
			if (!Rd(phy, p, sz) || sz < 0 || !InRange(phy, p + 4, sz)) {
				err = "phy: solid " + std::to_string(j) + " out of range";
				return false;
			}
			blobs.push_back(Sub(phy, p + 4, sz));
			p += 4 + std::int64_t(sz);
		}
		// Text section: solid { "index" "0" "surfaceprop" "metal" ... } ...
		std::vector<std::string> surfaceprop(blobs.size());
		if (static_cast<std::size_t>(p) < phy.size) {
			const auto* t = reinterpret_cast<const char*>(phy.data) + p;
			const auto* e = std::find(t, reinterpret_cast<const char*>(phy.data) + phy.size, '\0');
			KvNode      root;
			std::string kverr;
			if (ParseKeyValues(t, static_cast<std::size_t>(e - t), root, kverr)) {
				for (const auto& b : root.children) {
					std::int64_t idx = -1;
					if (b.block && Lower(b.key) == "solid" && ParseInt(b.Get("index", "-1"), idx) && idx >= 0 &&
						static_cast<std::size_t>(idx) < surfaceprop.size()) {
						surfaceprop[static_cast<std::size_t>(idx)] = b.Get("surfaceprop");
					}
				}
			}
		}
		for (std::size_t j = 0; j < blobs.size(); ++j) {
			PhySolid s;
			s.material = mats.Intern(surfaceprop[j].empty() ? "default" : surfaceprop[j]);
			std::uint16_t ivpMat[128];
			std::fill(std::begin(ivpMat), std::end(ivpMat), s.material);
			if (!DecodeIvpSolid(blobs[j], ivpMat, kSrcStaticProp, false, s.mesh, nullptr, err)) {
				err = "phy solid " + std::to_string(j) + ": " + err;
				return false;
			}
			out.solids.push_back(std::move(s));
		}
		return true;
	}

	namespace
	{
		// Every vertex of tris[first..] within +-1e6 units (a corrupt origin or bounds otherwise).
		bool TrisBounded(const std::vector<Tri>& tris, std::uint32_t first)
		{
			for (std::size_t i = first; i < tris.size(); ++i) {
				for (const auto& v : tris[i].v) {
					if (!Bounded3(v)) {
						return false;
					}
				}
			}
			return true;
		}
	}

	void AddStaticProps(MapCollision& map, const std::vector<StaticPropEntry>& props, const PropModels& models, PropStats& stats)
	{
		struct Cached
		{
			bool     ok = false;
			PhyModel model;
		};
		std::unordered_map<std::string, Cached> cache;
		Mesh&                                   world = map.world;
		for (const auto& prop : props) {
			if (prop.solid == 0) {
				++stats.skippedNone;
				continue;
			}
			if (prop.solid != 2 && prop.solid != 6) {
				++stats.skippedUnsupported;
				continue;
			}
			auto src = models.find(prop.model);
			float m[3][3];
			AngleMatrix(prop.angles, m);
			const auto owner = static_cast<std::int32_t>(prop.index);
			if (prop.solid == 2) {
				if (src == models.end() || !src->second.hasBounds) {
					++stats.missingModel;
					continue;
				}
				Tri proto;
				proto.material = map.materials.Intern("default");
				proto.kind = kSrcStaticProp;
				proto.bits = kBitVerify;
				proto.owner = owner;
				const auto first = static_cast<std::uint32_t>(world.tris.size());
				BoxTris(src->second.mins, src->second.maxs, m, prop.origin, proto, world.tris);
				if (TrisBounded(world.tris, first) && FinishConvex(world, &map.materials, first, kSrcStaticProp, kBitVerify, owner)) {
					++stats.placed;
					++stats.bboxVerify;
					++stats.convexes;
					stats.tris += 12;
				} else {
					world.tris.resize(first);
				}
				continue;
			}
			if (src == models.end() || !src->second.phy.size) {
				++stats.missingModel;
				continue;
			}
			auto it = cache.find(prop.model);
			if (it == cache.end()) {
				Cached      c;
				std::string err;
				c.ok = DecodePhy(src->second.phy, map.materials, c.model, err);
				if (c.ok && c.model.solids.empty()) {
					c.ok = false;
					err = "no solids";
				}
				if (!c.ok) {
					stats.errors.push_back(prop.model + ": " + err);
				}
				it = cache.emplace(prop.model, std::move(c)).first;
			}
			if (!it->second.ok) {
				++stats.badPhy;
				continue;
			}
			// The engine's static props collide with solid 0 of the model's collision.
			const Mesh& local = it->second.model.solids[0].mesh;
			for (const auto& c : local.convexes) {
				const auto first = static_cast<std::uint32_t>(world.tris.size());
				for (std::uint32_t i = c.firstTri; i < c.firstTri + c.triCount; ++i) {
					Tri t = local.tris[i];
					for (auto& v : t.v) {
						v = Rotate(m, v, prop.origin);
					}
					t.owner = owner;
					world.tris.push_back(t);
				}
				if (TrisBounded(world.tris, first) && FinishConvex(world, &map.materials, first, kSrcStaticProp, 0, owner)) {
					++stats.convexes;
					stats.tris += c.triCount;
				} else {
					world.tris.resize(first);
				}
			}
			++stats.placed;
		}
	}
}
