// mapcol: Source map collision decoder + Minecraft region voxelizer (GmodCraft, D-009).
//
// Pure library: no file I/O, no globals, no engine or Lua dependency. The caller reads the map's
// bytes (Lua: file.Open("maps/<map>.bsp", "rb", "GAME")) and the models' .phy files and hands
// them in. Every offset and count in that input is bounds-checked; malformed input gives an
// error string, never a crash. Toolchain: C++17, GCC 8 (Steam Runtime soldier).
//
// ---- coordinates -------------------------------------------------------------------------------
// Decoded geometry is in SOURCE UNITS, WORLD SPACE (brush models 1..N: model-local space).
// IVP metres -> Source: (x, z, -y) / 0.0254. Every triangle is wound OUTWARD: (v1-v0)x(v2-v0)
// points out of the solid. Spatial queries and the voxelizer work in MINECRAFT BLOCK SPACE
// (docs/DESIGN.md section 3): mc = (x/40 + ox, (z + oy)/40, -y/40 + oz), (ox, oz) = the map's slot
// origin in blocks, oy its vertical offset in Source units (v21; McFrame). That map is a proper
// rotation, so winding stays outward.
//
// ---- typical use -------------------------------------------------------------------------------
//   BspLumps lumps;        SplitBsp(fileBytes, lumps, err)          // or fill lumps[] yourself
//   SurfaceProps sp;       ParseSurfaceProperties(text, sp, err)    // scripts/surfaceproperties*.txt
//   TexSurfaceProps tex;   tex["gm_construct/grass_13"] = {"grass", "sand"}  // only for the disp
//                                                                    // rebuild (from the VMTs)
//   MapCollision map;      DecodeMap(lumps, DecodeOptions{&tex}, map, err)
//   std::vector<StaticPropEntry> props;  ParseStaticProps(lumps, props, version, err)
//   PropModels models;     models["models/x.mdl"].phy = phyBytes  // per distinct model in props
//   AddStaticProps(map, props, models, propStats)
//   ResolveMaterials(map, sp)                                        // DigMaterial per name
//   RegionIndex index;     index.Build(map.world, McFrame{ox, oz, oyUnits})
//   RegionJob job;         GatherRegion(map, index, rx, ry, rz, epoch, GatherOptions{}, job)
//   BuildColTrisPayload(job, trisBytes); BuildColRegionPayload(job, regionBytes)  // any thread
//
// ---- what DecodeMap reads ------------------------------------------------------------------------
// LUMP_PHYSCOLLIDE (29), model 0, in vbsp's order: solids with a "contents" key are brushes
// (MASK_SOLID -> kSrcWorld; PLAYERCLIP -> kSrcPlayerClip, kept; MONSTERCLIP-only -> dropped unless
// keepMonsterClip); staticsolids without "contents" are displacement polysoups (each ledge is one
// triangle stored front and back; the second copy is the outward one, verified against the
// rebuild on gm_construct / gm_flatgrass); "fluid" solids are water. Each brush ledge (a compact
// convex hull) becomes one Convex with its outward planes and its triangles; its brush index is the
// ledge's clientData. Per-triangle materials come from the keydata "materialtable".
// Models 1..N (brush entities "*N"): every solid, as kSrcBrushModel convexes, model-local.
// Displacements are REBUILT from lumps 26/33/48 + faces/surfedges/edges/vertexes/texinfo/texdata
// (7, 13, 12, 3, 6, 2, 43, 44) when the PHYSCOLLIDE has no polysoups (or when forced): outward
// winding, NOHULL / NORAY from minTess, $surfaceprop / $surfaceprop2 (summed vertex alpha > 382.5)
// from the caller's TexSurfaceProps. Water: fluid solids (volume + "surfaceplane" z), plus the raw
// LEAFWATERDATA (36) entries.
//
// Static props: the sprp game lump (35), versions 4-11 (stride = bytes / count; only the
// version-stable 31-byte prefix is read). LZMA-compressed game lumps are rejected ("unsupported").
// SOLID_VPHYSICS (6): the model's .phy, solid 0 (as the engine's static prop code does), placed
// with Source's AngleMatrix(pitch, yaw, roll) + origin; prop scale is ignored (GMod does too).
// SOLID_BBOX (2): a box from caller-supplied bounds, rotated like the prop, marked kBitVerify.
// SOLID_NONE (0): skipped.
//
// Materials: surface-property names are interned in MaterialTable; Resolve() maps each to a
// proto::DigMaterial (D-009) by checking the name itself, then its "base" chain, against a fixed
// table; names that are no-dig (default_silent, player_control_clip, water, ladder...) become
// not diggable; unknown names are diggable kDigNone (stone).
//
// Threading: decoding fills plain structs. After that, everything taking `const` references
// (RegionIndex queries, GatherRegion, Build*Payload, WaterSurfaceAt) is safe to call from any
// number of threads at once.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <map>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include "gmodcraft_protocol.h"

namespace gmodcraft::mapcol
{
	// A borrowed byte range. Never owned by the library; must outlive the call that reads it.
	struct Bytes
	{
		const std::uint8_t* data = nullptr;
		std::size_t         size = 0;
	};

	struct Vec3
	{
		float x = 0, y = 0, z = 0;
	};

	// ============================================================================================
	// BSP lumps
	// ============================================================================================
	enum BspLump : int
	{
		kLumpEntities = 0,
		kLumpPlanes = 1,
		kLumpTexdata = 2,
		kLumpVertexes = 3,
		kLumpTexinfo = 6,
		kLumpFaces = 7,
		kLumpEdges = 12,
		kLumpSurfedges = 13,
		kLumpModels = 14,
		kLumpDispInfo = 26,
		kLumpPhysCollide = 29,
		kLumpDispVerts = 33,
		kLumpGame = 35,
		kLumpLeafWaterData = 36,
		kLumpTexdataStringData = 43,
		kLumpTexdataStringTable = 44,
		kLumpDispTris = 48,
		kLumpCount = 64,
	};

	struct BspLumps
	{
		Bytes         lump[kLumpCount];
		std::int64_t  fileOffset[kLumpCount] = {};  // where lump i starts in the .bsp: the game lump's
		                                            // directory holds absolute file offsets
		std::uint32_t version[kLumpCount] = {};
		std::int32_t  bspVersion = 0;
	};

	// Splits a whole .bsp ("VBSP", v19-21) into lump views. Lumps stored LZMA-compressed are left
	// empty and named in `err` as a warning (the call still succeeds); decoding then reports what
	// is missing. Returns false only for a bad header.
	bool SplitBsp(Bytes file, BspLumps& out, std::string& err);

	// ============================================================================================
	// Materials
	// ============================================================================================
	// surfaceproperties KeyValues: name (lowercase) -> base (lowercase, "" if none). Call
	// ParseSurfaceProperties once per file (manifest order); later files may reference earlier ones.
	struct SurfaceProps
	{
		std::unordered_map<std::string, std::string> base;
	};
	bool ParseSurfaceProperties(const std::string& text, SurfaceProps& out, std::string& err);

	struct DigInfo
	{
		proto::DigMaterial material = proto::kDigNone;
		bool               diggable = true;
	};
	// D-009's table, applied to `name` and then its base chain (first known name wins).
	DigInfo ClassifySurfaceProp(const SurfaceProps& props, const std::string& name);

	struct MaterialInfo
	{
		std::string        name;  // surface property, lowercase
		proto::DigMaterial dig = proto::kDigNone;
		bool               diggable = true;
	};

	class MaterialTable
	{
	public:
		std::uint16_t Intern(const std::string& name);  // lowercases; "" -> "default"
		void          Resolve(const SurfaceProps& props);  // (re)computes dig / diggable for all
		const MaterialInfo& operator[](std::uint16_t i) const { return list_[i]; }
		std::size_t   Size() const { return list_.size(); }

	private:
		std::vector<MaterialInfo>                       list_;
		std::unordered_map<std::string, std::uint16_t> index_;
	};

	// texdata name (lowercase, as in TEXDATA_STRING_DATA) -> its VMT's $surfaceprop / $surfaceprop2.
	struct SurfacePropPair
	{
		std::string prop, prop2;
	};
	using TexSurfaceProps = std::unordered_map<std::string, SurfacePropPair>;

	// A material's (.vmt text) $surfaceprop / $surfaceprop2, lowercase ("" if unset). A "patch"
	// material that doesn't set $surfaceprop itself names the material it patches in `include`
	// ("materials/....vmt"): read that and call again. False if the text doesn't parse.
	bool ParseVmtSurfaceProps(const std::string& text, SurfacePropPair& out, std::string& include);

	// ============================================================================================
	// Decoded geometry
	// ============================================================================================
	enum SourceKind : std::uint8_t
	{
		kSrcWorld = 0,         // MASK_SOLID world brushes (convexes)
		kSrcPlayerClip = 1,    // PLAYERCLIP brushes (convexes); how they reach MC is GatherOptions'
		kSrcMonsterClip = 2,   // only with DecodeOptions::keepMonsterClip
		kSrcDisplacement = 3,  // terrain triangles (standalone)
		kSrcStaticProp = 4,    // convexes, world space
		kSrcBrushModel = 5,    // models 1..N, model-local
		kSrcWater = 6,         // only in WaterVolume
		kSrcDynamic = 7,       // moving entities (doors, func_brush, physics props): world space,
		                       // never diggable, no material bits (see PlaceDynamic)
	};

	enum TriBits : std::uint8_t
	{
		kBitNoHull = 0x1,  // displacement flagged NOHULL: no player collision
		kBitNoRay = 0x2,   // displacement flagged NORAY: no projectile / ray collision
		kBitVerify = 0x4,  // approximation (SOLID_BBOX prop box): check in game
		kBitThin = 0x8,    // (convex, kSrcDynamic) thinner than kThinBlocks: its faces are
		                   // voxelized as surfaces (a 2-unit door slab holds no sub-voxel centre)
	};

	struct Tri
	{
		Vec3          v[3];
		std::uint16_t material = 0;  // MaterialTable index
		std::uint8_t  kind = kSrcWorld;
		std::uint8_t  bits = 0;      // TriBits
		std::int32_t  convex = -1;   // owning Convex (Mesh::convexes index), -1 = standalone
		std::int32_t  owner = -1;    // brush index (world / clip), prop index (static props),
		                             // dispinfo index (rebuilt displacements), else -1
	};

	// n . p + d <= 0 inside; n is unit length and points out.
	struct Plane
	{
		float n[3];
		float d;
	};

	struct Convex
	{
		std::uint32_t firstTri = 0, triCount = 0;      // its faces, in Mesh::tris
		std::uint32_t firstPlane = 0, planeCount = 0;  // in Mesh::planes
		Vec3          lo, hi;                          // bounds
		std::uint16_t material = 0;  // the most common diggable face material (else most common)
		std::uint8_t  kind = kSrcWorld;
		std::uint8_t  bits = 0;
		std::int32_t  owner = -1;    // as Tri::owner
	};

	struct Mesh
	{
		std::vector<Tri>    tris;  // convex faces (contiguous per convex) and standalone triangles
		std::vector<Convex> convexes;
		std::vector<Plane>  planes;
	};

	struct WaterVolume
	{
		std::vector<Plane> planes;    // the fluid brush (n . p + d <= 0 inside)
		Vec3               lo, hi;
		float              surfaceZ;  // from the fluid's "surfaceplane" (else the brush top)
		std::uint16_t      material;
	};

	struct LeafWater  // LEAFWATERDATA (36), raw
	{
		float         surfaceZ, minZ;
		std::int16_t  texinfo;
	};

	enum class DispSource : std::uint8_t
	{
		kAuto,      // polysoups when the PHYSCOLLIDE has them, else the rebuild
		kPolysoup,  // polysoups only (none if absent)
		kRebuild,   // always rebuild from the disp lumps (polysoups are dropped)
		kNone,
	};

	struct MapStats
	{
		std::uint32_t physModels = 0;           // PHYSCOLLIDE model entries
		std::uint32_t worldConvexes = 0, worldTris = 0;
		std::uint32_t clipConvexes = 0, clipTris = 0;
		std::uint32_t monsterClipDroppedTris = 0;
		std::uint32_t polysoupLedges = 0, polysoupTris = 0;  // tris after de-duplication
		std::uint32_t polysoupOddLedges = 0;                 // ledges not in front/back pairs (kept whole)
		std::uint32_t flatLedgesDropped = 0;                 // brush ledges with < 4 face planes (no volume)
		std::uint32_t dispCount = 0, dispTris = 0, dispSkippedNoPhysics = 0, dispUnknownTexture = 0;
		std::uint32_t fluidSolids = 0;
		bool          dispRebuilt = false;      // which source the displacement triangles came from
	};

	struct MapCollision
	{
		MaterialTable              materials;
		Mesh                       world;        // world space
		std::map<int, Mesh>        brushModels;  // PHYSCOLLIDE model index (1..N) -> model-local mesh
		std::vector<WaterVolume>   water;
		std::vector<LeafWater>     leafWater;
		MapStats                   stats;
	};

	struct DecodeOptions
	{
		const TexSurfaceProps* texProps = nullptr;  // for the displacement rebuild ("default" if absent)
		DispSource             disp = DispSource::kAuto;
		bool                   keepMonsterClip = false;
		bool                   brushModels = true;
	};

	// Decodes model 0 (+ displacements, water) and, optionally, models 1..N. `out` should be fresh;
	// on failure it may hold a partial result.
	bool DecodeMap(const BspLumps& lumps, const DecodeOptions& opts, MapCollision& out, std::string& err);

	// map.materials.Resolve(props), then re-picks every convex's material (most common DIGGABLE
	// face material) for world and brush models. Call after DecodeMap and AddStaticProps.
	void ResolveMaterials(MapCollision& map, const SurfaceProps& props);

	// The displacement rebuild on its own (for cross-checks): appends kSrcDisplacement triangles.
	bool RebuildDisplacements(const BspLumps& lumps, const TexSurfaceProps* texProps, MaterialTable& mats,
		std::vector<Tri>& out, MapStats& stats, std::string& err);

	// The texdata names (lowercase) of every displacement's face: the materials whose VMTs the
	// rebuild needs in DecodeOptions::texProps.
	void DisplacementTextures(const BspLumps& lumps, std::vector<std::string>& out);

	// One IVP solid (a PHYSCOLLIDE / .phy blob, with or without the 28-byte "VPHY" header).
	// ivpMaterial[i] is the MaterialTable index for the 7-bit IVP material i. Convex ledges become
	// Convex entries; with `polysoup`, front/back ledge pairs become single standalone triangles.
	bool DecodeIvpSolid(Bytes blob, const std::uint16_t (&ivpMaterial)[128], std::uint8_t kind, bool polysoup,
		Mesh& out, MapStats* stats, std::string& err);

	// ============================================================================================
	// Static props
	// ============================================================================================
	struct StaticPropEntry
	{
		std::uint32_t index;   // position in the sprp lump
		std::string   model;   // as in the dictionary, lowercase, '/' separators ("models/x.mdl")
		Vec3          origin;  // Source units
		Vec3          angles;  // (pitch, yaw, roll) degrees
		std::uint8_t  solid;   // 0 none, 2 bbox, 6 vphysics (others: unsupported, skipped)
	};
	// `version` receives the sprp version. Errors: no sprp lump is NOT an error (empty list).
	bool ParseStaticProps(const BspLumps& lumps, std::vector<StaticPropEntry>& out, int& version, std::string& err);

	struct PhySolid
	{
		Mesh          mesh;         // model space, Source units
		std::uint16_t material = 0; // the text section's "surfaceprop" for this solid
	};
	struct PhyModel
	{
		std::vector<PhySolid> solids;
	};
	// A model's .phy file: phyheader_t (16 bytes) + per solid {int size; VPHY blob} + KeyValues text.
	bool DecodePhy(Bytes phy, MaterialTable& mats, PhyModel& out, std::string& err);

	struct PropModelSource
	{
		Bytes phy;                      // SOLID_VPHYSICS: the .phy bytes (empty -> prop skipped)
		bool  hasBounds = false;        // SOLID_BBOX: the model's hull (studiohdr hull_min / hull_max)
		Vec3  mins, maxs;
	};
	using PropModels = std::unordered_map<std::string, PropModelSource>;  // key: StaticPropEntry::model

	struct PropStats
	{
		std::uint32_t placed = 0, skippedNone = 0, skippedUnsupported = 0, missingModel = 0, badPhy = 0;
		std::uint32_t bboxVerify = 0, tris = 0, convexes = 0;
		std::vector<std::string> errors;  // "model: reason", one per failed model
	};
	// Appends kSrcStaticProp convexes to map.world. Each .phy is decoded once per model.
	void AddStaticProps(MapCollision& map, const std::vector<StaticPropEntry>& props, const PropModels& models,
		PropStats& stats);

	// Source's AngleMatrix: (pitch, yaw, roll) degrees -> 3x3 rotation, m[row][col]; world = m * p.
	void AngleMatrix(const Vec3& angles, float m[3][3]);

	// ============================================================================================
	// Dynamic entities (P2c): per-model collision in model space, placed per entity
	// ============================================================================================
	// Every builder below fills `out` (cleared first) with kSrcDynamic convexes in model space
	// (Source units). The mesh never carries materials (Tri::material = 0): dynamic geometry is
	// not diggable, so it needs no MaterialTable entry.
	//
	// A .phy (prop_physics, prop_door_rotating, prop_dynamic, ...): solid 0, as the engine's
	// physics props collide with it. `surfaceprop` receives that solid's surface property
	// (lowercase, "" if unset; informational).
	bool DynamicFromPhy(Bytes phy, Mesh& out, std::string& surfaceprop, std::string& err);
	// A brush model's decoded mesh (MapCollision::brushModels[N] for "*N"): copied.
	void DynamicFromMesh(const Mesh& model, Mesh& out);
	// A box [mins, maxs] (the OBB fallback). False for an empty or non-finite box.
	bool DynamicFromBox(const Vec3& mins, const Vec3& maxs, Mesh& out);
	// Convex triangle lists (Lua's PhysObj:GetMeshConvexes, 3 vertices per triangle): one convex per
	// list, winding ignored (each face is turned to point away from its convex's centre).
	// Degenerate lists are dropped; false if nothing is left.
	bool DynamicFromTriangles(const std::vector<std::vector<Vec3>>& convexes, Mesh& out, std::string& err);

	// Below this thickness (blocks) a dynamic convex gets kBitThin.
	inline constexpr float kThinBlocks = 0.25f;

	// Appends the model-space mesh `model` at (origin, angles) to `out` (world space): triangles
	// rotated + translated, planes carried over exactly (n' = R n, d' = d - n'.origin), bounds
	// recomputed, Tri::owner / Convex::owner = owner, kBitThin set where it applies. False (and
	// nothing appended) if the transform is not finite or puts a vertex beyond +-1e6 units.
	bool PlaceDynamic(const Mesh& model, const Vec3& origin, const Vec3& angles, std::int32_t owner, Mesh& out);

	// The rotation angle (degrees, 0..180) between two (pitch, yaw, roll) orientations.
	float AngleBetween(const Vec3& a, const Vec3& b);

	// ============================================================================================
	// Water
	// ============================================================================================
	// The surface z of the water volume whose footprint holds (x, y), choosing the volume whose
	// surface is nearest nearZ. False if none.
	bool WaterSurfaceAt(const MapCollision& map, float x, float y, float nearZ, float& surfaceZ);

	// ============================================================================================
	// Minecraft space and region queries
	// ============================================================================================
	// mc.x = src.x / 40 + originX, mc.y = (src.z + originYUnits) / 40, mc.z = -src.y / 40 + originZ
	// (v21: originYUnits is the slot's vertical offset in SOURCE units, so it can be a fraction of a block).
	struct McFrame
	{
		std::int32_t originX = 0, originZ = 0;  // slot origin (ox, oz), blocks
		std::int32_t originYUnits = 0;          // slot vertical offset (oyUnits), Source units
		bool operator==(const McFrame& o) const { return originX == o.originX && originZ == o.originZ && originYUnits == o.originYUnits; }
		bool operator!=(const McFrame& o) const { return !(*this == o); }
	};
	void ToMc(const McFrame& f, const Vec3& src, float out[3]);
	Vec3 FromMc(const McFrame& f, const float mc[3]);
	Plane PlaneToMc(const McFrame& f, const Plane& p);  // same half-space, MC coordinates

	// Grid of kColRegionSize^3-block regions over one Mesh (normally map.world), in MC space.
	// Keeps a pointer to the mesh: it must outlive the index and not change after Build().
	class RegionIndex
	{
	public:
		void Build(const Mesh& mesh, const McFrame& frame);
		// Standalone triangles (Tri::convex == -1) whose MC bounds overlap the inclusive block-space
		// box [lo, hi]. Indices into mesh.tris, ascending, no duplicates.
		void TrianglesInBox(const float lo[3], const float hi[3], std::vector<std::uint32_t>& out) const;
		// Convexes whose MC bounds overlap [lo, hi]. Indices into mesh.convexes, ascending.
		void ConvexesInBox(const float lo[3], const float hi[3], std::vector<std::uint32_t>& out) const;
		const McFrame& Frame() const { return frame_; }
		const Mesh*    Source() const { return mesh_; }

	private:
		struct Cell
		{
			std::vector<std::uint32_t> tris, convexes;
		};
		static std::uint64_t Key(int rx, int ry, int rz);
		void Query(const float lo[3], const float hi[3], bool convexes, std::vector<std::uint32_t>& out) const;

		const Mesh*                             mesh_ = nullptr;
		McFrame                                 frame_;
		std::unordered_map<std::uint64_t, Cell> cells_;
		std::vector<float>                      triBox_, convexBox_;  // 6 floats each, MC lo/hi
	};

	// D6: does the index's map geometry (brushes, player clips, static props, displacement
	// triangles; no monster clips) reach into the SOURCE-unit box [lo, hi]? Conservative (bounds,
	// then one separating plane per convex / the triangle's plane). Outside the map (the BSP's
	// solid void, where Minecraft may have its own terrain) it is false.
	bool SolidInBox(const RegionIndex& index, const float lo[3], const float hi[3]);

	// ============================================================================================
	// Voxelizer (port of SkyCraft Collision.cpp Triangulate / SendTriangles / Voxelize)
	// ============================================================================================
	struct McConvex
	{
		std::vector<std::array<float, 4>> planes;  // n . p + d <= 0 inside, MC space, unit n
		float                             lo[3], hi[3];
		std::uint32_t                     flags = 0;  // proto::ColTriFlags + material bits
	};

	struct RegionJob
	{
		std::int32_t                rx = 0, ry = 0, rz = 0;  // region coords (blocks / 8)
		std::uint32_t               epoch = 0;
		std::vector<proto::ColTri>  tris;         // standalone triangles: SAT-voxelized + sent
		std::vector<McConvex>       convexes;     // filled by containment, sent as faces
		std::vector<proto::ColTri>  convexFaces;  // exact faces of `convexes` (if empty for a job
		                                          // with convexes, faces come from the planes)
		std::vector<proto::ColTri>  helperTris;   // sent as kTriStairHelper only, never voxelized
		// Minecraft blocks dug out of the host's world (min corners, MC block coords) within one
		// block of the region (the caller's dig state, see DugCellsNear). Diggable geometry in them
		// is gone: the payload builders cut it out (BuildColTrisPayload also sends the original
		// triangles flagged kTriGhost, which MC's SkyDig reads to tell inside from outside).
		std::vector<std::array<int, 3>> dug;
	};

	enum class ClipMode : std::uint8_t
	{
		kSolid,       // PLAYERCLIP is voxelized and sent like world brushes (never diggable)
		kHelperTris,  // PLAYERCLIP faces go out as kTriStairHelper triangles only
		kSkip,
	};

	struct GatherOptions
	{
		ClipMode clip = ClipMode::kSolid;
		bool     skipNoHull = true;  // drop NOHULL displacement triangles (player-first collision)
	};

	// proto::ColTri flags for a source triangle / convex: kTriDiggable + material when diggable
	// (never for clips or kSrcDynamic), kTriTerrain for displacements, kTriDynamic (only) for
	// kSrcDynamic.
	std::uint32_t ColFlags(const MaterialTable& mats, std::uint16_t material, std::uint8_t kind);

	// Everything of index.Source() (with materials from `map`) that the region (rx, ry, rz) needs,
	// in MC space: geometry within half a block of the region. Resets `job` first.
	void GatherRegion(const MapCollision& map, const RegionIndex& index, int rx, int ry, int rz, std::uint32_t epoch,
		const GatherOptions& opts, RegionJob& job);
	// The same for a second layer (the dynamic entities' mesh and its own index): appended to a job
	// GatherRegion filled, so the payload builders see one merged region. Convexes with kBitThin
	// go out as standalone triangles (voxelized as surfaces), the others like static convexes.
	void GatherAppend(const MaterialTable& mats, const RegionIndex& index, const GatherOptions& opts, RegionJob& job);

	// Convex hull from planes -> outward triangles (SkyCraft's clip-a-big-square method).
	void TriangulateConvex(const McConvex& cvx, std::vector<proto::ColTri>& out);

	// Source's walkable limit (sv_standable_normal): a surface whose normal's up component is below
	// this is too steep to stand on. The one steepness threshold of the voxelizer.
	inline constexpr float kWalkableNormalY = 0.7f;
	// Below this |n.y| a surface is a wall: kept fine-grained (never coarsened).
	inline constexpr float kWallNormalY = 0.1f;

	// kColTris payload: ColRegion header (count = triangles) + ColTri[]: every triangle of the job
	// that comes within half a block of the region. Diggable triangles touching job.dug cells are
	// sent as what is left of them outside those cells, plus the original flagged kTriGhost.
	void BuildColTrisPayload(const RegionJob& job, std::vector<std::uint8_t>& out);
	// kColRegion payload: ColRegion header + ColBlock[] (non-empty blocks of the region, 8x8x8
	// sub-voxels each). Steep surfaces (|n.y| in [kWallNormalY, kWalkableNormalY)), triangles and
	// convex faces alike, are coarsened to whole-block columns so Minecraft's 0.6 step height
	// refuses them. Diggable voxels in job.dug cells are removed.
	void BuildColRegionPayload(const RegionJob& job, std::vector<std::uint8_t>& out);

	// ============================================================================================
	// Map anchor (P8 WP1, protocol v21): the map's main floor, for the slot's vertical offset
	// (oyUnits = kAnchorFloorY * 40 - floorZ, chosen by the MC server). anchor.cpp.
	// ============================================================================================
	// Walkable surfaces (normal z >= kWalkableNormalY) of world brushes and displacements, by area
	// in 1-unit z bins (a brush floor is one plane: its triangles share a bin).
	struct FloorStats
	{
		float minZ = 0, maxZ = 0;                                  // z range of world + displacement geometry
		float footMinX = 0, footMinY = 0, footMaxX = 0, footMaxY = 0;  // its xy footprint
		float modeZ = 0;                                           // the bin with the most walkable area
		float lowestZ = 0;                                         // the lowest bin with > 5 % of it
		double walkableArea = 0;                                   // square units (inside the window)
		std::vector<std::pair<long, double>> bins;                 // (z, area), largest area first
		std::uint32_t excludedSky = 0, excludedTop = 0, excludedCovered = 0;  // walkable triangles left out (see AnchorOptions)
	};

	// What isn't a floor anyone stands on (P8 WP2 nit: a spawn-less map's mode picked skybox faces):
	// covered faces (a downward solid face right on top: two brushes back to back), faces with no
	// downward face anywhere above them and the map's very top (the hull's outside, counted as
	// "top"), and, with a sky_camera that floats
	// in a room of its own (no floor within kSkyRoomFloor right under it), every walkable face within
	// kSkyRoomRadius of it (the 3D skybox room).
	struct AnchorOptions
	{
		bool haveSky = false;
		Vec3 sky;  // the sky_camera's origin
	};
	inline constexpr float kSkyRoomRadius = 2048.0f, kSkyRoomFloor = 16.0f, kCoveredGap = 2.0f;

	// The walkable triangles of a mesh, worked out once (index into mesh.tris, area, centroid z).
	struct WalkableSet
	{
		struct Item
		{
			std::uint32_t tri;
			float area, cx, cy, cz;
			bool covered;  // a downward solid face right on top of it: inside solid (never a floor)
			bool open;     // no downward face anywhere above it: outside the map's hull
		};
		std::vector<Item> items;
		float minZ = 0, maxZ = 0, footMinX = 0, footMinY = 0, footMaxX = 0, footMaxY = 0;
		bool any = false;
	};
	WalkableSet BuildWalkable(const Mesh& world);

	// window: optional xy box {minX, minY, maxX, maxY} for the histogram (triangle centroids); the
	// z range and footprint always cover the whole map.
	void MapFloorStats(const Mesh& world, const float* window, FloorStats& out, const AnchorOptions& opt = {});
	void MapFloorStats(const Mesh& world, const WalkableSet& ws, const float* window, FloorStats& out, const AnchorOptions& opt = {});

	// A spawn point's floor: the highest walkable world / displacement surface under (x, y) between
	// kSpawnBelow below and kSpawnAbove above z. False if there is none.
	inline constexpr float kSpawnAbove = 32.0f, kSpawnBelow = 512.0f;
	bool FloorUnder(const Mesh& world, float x, float y, float z, float& floorZ);
	bool FloorUnder(const Mesh& world, const WalkableSet& ws, float x, float y, float z, float& floorZ);

	struct AnchorResult
	{
		std::uint32_t source = 0;  // proto::AnchorSource: kAnchorSpawns, kAnchorMapMode or kAnchorNone
		float floorZ = 0;
		float minZ = 0, maxZ = 0, footMinX = 0, footMinY = 0, footMaxX = 0, footMaxY = 0;
		std::vector<float> spawnFloors;  // one per spawn point that has a floor (for the debug page)
		FloorStats stats;                // the whole map's histogram (sky / top faces left out)
	};
	// The policy (user answer 2026-10-06: the floor = the mode near the spawns): the floor most spawn
	// points stand on; ties go to the one with more navmesh areas (navZ: area heights, may be empty),
	// then more walkable area, then the lower. No spawn floor: the map's most common walkable floor
	// (without the skybox room and the map's top faces, AnchorOptions). One pass over the triangles.
	AnchorResult ChooseAnchor(const Mesh& world, const std::vector<Vec3>& spawns, const std::vector<float>& navZ, const AnchorOptions& opt = {});

	// The sky_camera's origin from the entity lump (headless measurements), if there is one.
	bool ParseSkyCamera(const BspLumps& lumps, Vec3& out);

	// Player spawn entities in the entity lump (for headless measurements; the game uses its own
	// entity list): their origins.
	inline constexpr const char* kSpawnClasses[] = { "info_player_start", "info_player_deathmatch", "info_player_combine",
		"info_player_rebel", "info_player_counterterrorist", "info_player_terrorist", "gmod_player_start" };
	std::vector<Vec3> ParseSpawnPoints(const BspLumps& lumps);
}
