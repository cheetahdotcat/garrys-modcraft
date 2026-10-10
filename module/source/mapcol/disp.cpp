// Displacement collision rebuilt from DISPINFO (26) / DISP_VERTS (33) / DISP_TRIS (48) and the
// face lumps, the engine's recipe (builddisp.cpp).
#include <algorithm>

#include "internal.hpp"

namespace gmodcraft::mapcol
{
	using namespace detail;

	namespace
	{
		constexpr std::size_t   kDispInfoBytes = 176;
		constexpr std::size_t   kDispVertBytes = 20;
		constexpr std::size_t   kFaceBytes = 56;
		constexpr std::size_t   kTexinfoBytes = 72;
		constexpr std::size_t   kTexdataBytes = 32;
		constexpr std::uint32_t kMaskSolidish = 0x1 | 0x2 | 0x8 | 0x4000 | 0x2000000;  // SOLID|WINDOW|GRATE|MOVEABLE|MONSTER
		constexpr std::uint32_t kDispFlagsValid = 0x80000000u;
		constexpr std::uint32_t kDispNoPhysics = 0x2, kDispNoHull = 0x4, kDispNoRay = 0x8;
	}

	bool RebuildDisplacements(const BspLumps& lumps, const TexSurfaceProps* texProps, MaterialTable& mats, std::vector<Tri>& out,
		MapStats& stats, std::string& err)
	{
		const Bytes di = lumps.lump[kLumpDispInfo];
		const Bytes dv = lumps.lump[kLumpDispVerts];
		const Bytes faces = lumps.lump[kLumpFaces];
		const Bytes verts = lumps.lump[kLumpVertexes];
		const Bytes edges = lumps.lump[kLumpEdges];
		const Bytes surf = lumps.lump[kLumpSurfedges];
		const Bytes texinfo = lumps.lump[kLumpTexinfo];
		const Bytes texdata = lumps.lump[kLumpTexdata];
		const Bytes strData = lumps.lump[kLumpTexdataStringData];
		const Bytes strTable = lumps.lump[kLumpTexdataStringTable];
		std::size_t nDisp = 0, nVerts = 0;
		if (!Count(di, kDispInfoBytes, nDisp) || !Count(dv, kDispVertBytes, nVerts)) {
			err = "disp: dispinfo / dispverts size not a record multiple";
			return false;
		}
		const std::uint16_t def = mats.Intern("default");
		struct TexMat
		{
			std::pair<std::uint16_t, std::uint16_t> mats;  // (sp, sp2 or 0xFFFF)
			bool                                    unknown;
		};
		std::unordered_map<std::int32_t, TexMat> texMats;  // texdata index ->

		auto materialsFor = [&](std::int32_t texdataIdx, bool& unknown) {
			auto it = texMats.find(texdataIdx);
			if (it != texMats.end()) {
				unknown = it->second.unknown;
				return it->second.mats;
			}
			std::pair<std::uint16_t, std::uint16_t> r{ def, 0xFFFF };
			std::int32_t nameId = 0, strOff = 0;
			std::string  name;
			if (Rd(texdata, std::int64_t(texdataIdx) * kTexdataBytes + 12, nameId) && Rd(strTable, std::int64_t(nameId) * 4, strOff) &&
				strOff >= 0 && static_cast<std::size_t>(strOff) < strData.size) {
				const auto* s = reinterpret_cast<const char*>(strData.data) + strOff;
				name.assign(s, std::find(s, reinterpret_cast<const char*>(strData.data) + strData.size, '\0'));
			}
			bool found = false;
			if (texProps && !name.empty()) {
				auto t = texProps->find(Lower(name));
				if (t != texProps->end()) {
					found = true;
					r.first = mats.Intern(t->second.prop);
					r.second = t->second.prop2.empty() ? 0xFFFF : mats.Intern(t->second.prop2);
				}
			}
			unknown = !found;
			texMats.emplace(texdataIdx, TexMat{ r, unknown });
			return r;
		};

		for (std::size_t k = 0; k < nDisp; ++k) {
			const std::int64_t base = std::int64_t(k) * kDispInfoBytes;
			float              start[3] = {};
			std::int32_t       vertStart = 0, power = 0;
			std::uint32_t      minTess = 0, contents = 0;
			std::uint16_t      mapFace = 0;
			Rd(di, base, start);
			Rd(di, base + 12, vertStart);
			Rd(di, base + 20, power);
			Rd(di, base + 24, minTess);
			Rd(di, base + 32, contents);
			Rd(di, base + 36, mapFace);
			const std::string where = "disp " + std::to_string(k) + ": ";
			if (power < 2 || power > 4) {
				err = where + "power " + std::to_string(power) + " out of range";
				return false;
			}
			std::uint8_t bits = 0;
			if (minTess & kDispFlagsValid) {
				if (minTess & kDispNoPhysics) {
					++stats.dispSkippedNoPhysics;
					continue;
				}
				bits |= (minTess & kDispNoHull) ? kBitNoHull : 0;
				bits |= (minTess & kDispNoRay) ? kBitNoRay : 0;
			}
			if (!(contents & kMaskSolidish)) {
				++stats.dispSkippedNoPhysics;
				continue;
			}
			const int n = (1 << power) + 1;
			if (vertStart < 0 || static_cast<std::size_t>(vertStart) + std::size_t(n) * n > nVerts) {
				err = where + "vertices out of range";
				return false;
			}
			// The face's four corners.
			std::int32_t firstEdge = 0;
			std::int16_t numEdges = 0, texinfoIdx = 0;
			const std::int64_t fb = std::int64_t(mapFace) * kFaceBytes;
			if (!InRange(faces, fb, kFaceBytes)) {
				err = where + "face out of range";
				return false;
			}
			Rd(faces, fb + 4, firstEdge);
			Rd(faces, fb + 8, numEdges);
			Rd(faces, fb + 10, texinfoIdx);
			if (numEdges != 4) {
				err = where + "face is not a quad";
				return false;
			}
			Vec3 pts[4];
			for (int e = 0; e < 4; ++e) {
				std::int32_t se = 0;
				std::uint16_t ev[2] = {};
				if (!Rd(surf, (std::int64_t(firstEdge) + e) * 4, se) || se == INT32_MIN ||
					!Rd(edges, std::int64_t(se >= 0 ? se : -se) * 4, ev)) {
					err = where + "edge out of range";
					return false;
				}
				const std::uint16_t vi = se >= 0 ? ev[0] : ev[1];
				float               p[3] = {};
				if (!Rd(verts, std::int64_t(vi) * 12, p)) {
					err = where + "vertex out of range";
					return false;
				}
				pts[e] = Vec3{ p[0], p[1], p[2] };
			}
			int   st = 0;
			float bestD = 0;
			for (int i = 0; i < 4; ++i) {
				const Vec3  d = Sub3(pts[i], Vec3{ start[0], start[1], start[2] });
				const float dd = Dot3(d, d);
				if (i == 0 || dd < bestD) {
					bestD = dd;
					st = i;
				}
			}
			Vec3 p[4];
			for (int i = 0; i < 4; ++i) {
				p[i] = pts[(st + i) % 4];
			}
			std::int32_t texdataIdx = -1;
			bool         unknown = true;
			std::pair<std::uint16_t, std::uint16_t> mp{ def, 0xFFFF };
			if (texinfoIdx >= 0 && Rd(texinfo, std::int64_t(texinfoIdx) * kTexinfoBytes + 68, texdataIdx)) {
				mp = materialsFor(texdataIdx, unknown);
			}
			if (unknown) {
				++stats.dispUnknownTexture;
			}

			// Vertex (i, j) = lerp(p0 + (p1-p0) i/(n-1), p3 + (p2-p3) i/(n-1), j/(n-1)) + vec * dist.
			std::vector<Vec3>  V(std::size_t(n) * n);
			std::vector<float> A(std::size_t(n) * n);
			const float        inv = 1.0f / float(n - 1);
			for (int i = 0; i < n; ++i) {
				const float fi = i * inv;
				const Vec3  a{ p[0].x + (p[1].x - p[0].x) * fi, p[0].y + (p[1].y - p[0].y) * fi, p[0].z + (p[1].z - p[0].z) * fi };
				const Vec3  b{ p[3].x + (p[2].x - p[3].x) * fi, p[3].y + (p[2].y - p[3].y) * fi, p[3].z + (p[2].z - p[3].z) * fi };
				for (int j = 0; j < n; ++j) {
					const float fj = j * inv;
					float       rec[5] = {};
					Rd(dv, (std::int64_t(vertStart) + i * n + j) * std::int64_t(kDispVertBytes), rec);
					Vec3 v{ a.x + (b.x - a.x) * fj + rec[0] * rec[3], a.y + (b.y - a.y) * fj + rec[1] * rec[3],
						a.z + (b.z - a.z) * fj + rec[2] * rec[3] };
					if (!Bounded3(v)) {
						err = where + "non-finite or out-of-range vertex";
						return false;
					}
					V[std::size_t(i) * n + j] = v;
					A[std::size_t(i) * n + j] = rec[4];
				}
			}
			auto emit = [&](int a, int b, int c) {
				Tri t;
				// Engine order is clockwise against the face normal: swap v1 / v2 for outward.
				t.v[0] = V[a];
				t.v[1] = V[c];
				t.v[2] = V[b];
				const bool second = mp.second != 0xFFFF && A[a] + A[b] + A[c] > 382.5f;
				t.material = second ? mp.second : mp.first;
				t.kind = kSrcDisplacement;
				t.bits = bits;
				t.owner = static_cast<std::int32_t>(k);
				out.push_back(t);
			};
			for (int iv = 0; iv < n - 1; ++iv) {
				for (int iu = 0; iu < n - 1; ++iu) {
					const int x = iv * n + iu;
					if (x % 2) {
						emit(x, x + n, x + 1);
						emit(x + 1, x + n, x + n + 1);
					} else {
						emit(x, x + n, x + n + 1);
						emit(x, x + n + 1, x + 1);
					}
				}
			}
			++stats.dispCount;
			stats.dispTris += static_cast<std::uint32_t>(2 * (n - 1) * (n - 1));
		}
		return true;
	}
	void DisplacementTextures(const BspLumps& lumps, std::vector<std::string>& out)
	{
		out.clear();
		const Bytes di = lumps.lump[kLumpDispInfo];
		const Bytes faces = lumps.lump[kLumpFaces];
		const Bytes texinfo = lumps.lump[kLumpTexinfo];
		const Bytes texdata = lumps.lump[kLumpTexdata];
		const Bytes strData = lumps.lump[kLumpTexdataStringData];
		const Bytes strTable = lumps.lump[kLumpTexdataStringTable];
		std::size_t nDisp = 0;
		Count(di, kDispInfoBytes, nDisp);
		std::vector<std::int32_t> seen;
		for (std::size_t k = 0; k < nDisp; ++k) {
			std::uint16_t mapFace = 0;
			std::int16_t  texinfoIdx = -1;
			std::int32_t  texdataIdx = -1, nameId = 0, strOff = 0;
			if (!Rd(di, std::int64_t(k) * kDispInfoBytes + 36, mapFace) || !Rd(faces, std::int64_t(mapFace) * kFaceBytes + 10, texinfoIdx) ||
				texinfoIdx < 0 || !Rd(texinfo, std::int64_t(texinfoIdx) * kTexinfoBytes + 68, texdataIdx) ||
				std::find(seen.begin(), seen.end(), texdataIdx) != seen.end()) {
				continue;
			}
			seen.push_back(texdataIdx);
			if (Rd(texdata, std::int64_t(texdataIdx) * kTexdataBytes + 12, nameId) && Rd(strTable, std::int64_t(nameId) * 4, strOff) &&
				strOff >= 0 && static_cast<std::size_t>(strOff) < strData.size) {
				const auto* str = reinterpret_cast<const char*>(strData.data) + strOff;
				std::string name(str, std::find(str, reinterpret_cast<const char*>(strData.data) + strData.size, '\0'));
				if (!name.empty()) {
					out.push_back(Lower(name));
				}
			}
		}
	}
}
