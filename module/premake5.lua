-- GmodCraft binary modules (gmcl_gmodcraft / gmsv_gmodcraft), built via garrysmod_common.
-- Linux: use module/build.sh; it runs premake on the host and compiles inside the Steam Runtime
-- (soldier) SDK container so the result matches the runtime GMod is launched in.
-- Windows (win64 only): `premake5 vs2022`, then msbuild projects/windows/vs2022/gmodcraft.sln
-- /p:Configuration=Release /p:Platform=x64 (.github/workflows/build-windows.yml does this).

PROJECT_GENERATOR_VERSION = 3 -- x86-64 support

local gmcommon = assert(_OPTIONS.gmcommon or os.getenv("GARRYSMOD_COMMON") or "third_party/garrysmod_common",
	"garrysmod_common path missing")
include(gmcommon)

CreateWorkspace({ name = "gmodcraft", abi_compatible = true, path = "projects/" .. os.target() .. "/" .. _ACTION })
	-- generator.lua defaults Windows to x86; GmodCraft ships win64 only.
	filter("system:windows")
		defaultplatform("x86_64")
	filter({})

-- Shared settings for both realms.
local function CommonSettings()
	-- The protocol header (protocol/gmodcraft_protocol.h) is the contract; include it directly.
	includedirs({ path.join(_SCRIPT_DIR, "../protocol") })
	-- garrysmod_common globs source/*.cpp only (not recursive): the mapcol library and the OS layer
	-- are subdirectories. os_posix.cpp / os_win.cpp each compile to nothing on the other system.
	files({ path.join(_SCRIPT_DIR, "source/mapcol/*.cpp"), path.join(_SCRIPT_DIR, "source/os/*.cpp") })
	includedirs({ path.join(_SCRIPT_DIR, "source/mapcol") })

	filter("system:linux")
		-- Old libstdc++ ABI comes from abi_compatible; keep the C++ runtime private to our .dll.
		linkoptions({ "-static-libstdc++", "-static-libgcc", "-Wl,--exclude-libs,ALL" })
		-- Export only gmod13_open/close.
		visibility("Hidden")
		buildoptions({ "-fvisibility-inlines-hidden" })
		linkoptions({ "-Wl,--version-script=" .. path.join(_SCRIPT_DIR, "exports.map") })
		-- shm_open lives in librt on glibc < 2.34 (soldier = 2.28).
		links({ "rt", "dl" })
		buildoptions({ "-Wall", "-Wno-unused-parameter" })
		-- The collision worker threads (soldier's glibc 2.28 keeps pthread in libpthread).
		buildoptions({ "-pthread" })
		linkoptions({ "-pthread" })
		links({ "pthread" })

	filter("system:windows")
		-- Static CRT (/MT) and C++17 come from garrysmod_common. Only gmod13_open/close are exported
		-- (__declspec(dllexport) in GarrysMod/Lua/Interface.h): nothing else to hide.
		warnings("Default")  -- /W3 (garrysmod_common asks for "Extra" = /W4)
		links({ "bcrypt", "shell32" })  -- BCryptGenRandom, CommandLineToArgvW (source/os/os_win.cpp)

	filter({})
end

-- Server realm: Lua + OS only. Deliberately no tier0/vstdlib link: a listen server runs
-- inside the client process (server_client.so -> libtier0_client.so), while dedicated srcds
-- uses libtier0.so. Linking either would be wrong for the other.
CreateProject({ serverside = true, source_path = "source" })
	CommonSettings()

-- Client realm: also SDK headers (IVEngineClient, IMaterialSystem). Interfaces are fetched at
-- runtime via the loaded engine's CreateInterface, so no Source libs are linked here either.
CreateProject({ serverside = false, source_path = "source" })
	CommonSettings()
	IncludeSDKCommon()
	-- IncludeSDKCommon leaves a "system:linux or macosx" filter active: these apply everywhere.
	filter({})
	-- Headers only (IncludeSDKTier0/1 would also link tier0_client/vstdlib_client, not needed yet).
	externalincludedirs({
		gmcommon .. "/sourcesdk-minimal/public/tier0",
		gmcommon .. "/sourcesdk-minimal/public/tier1"
	})
