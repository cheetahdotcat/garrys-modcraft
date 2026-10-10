-- gmodcraft_version: what this server runs, for server browsers and the launcher's server list
-- (A2S_RULES lists FCVAR_NOTIFY convars). Value "<link protocol>/<addon version>", e.g. "20/0.2.0".
-- The protocol is the loaded module's gmodcraft.PROTOCOL (derived from the protocol header, never
-- pinned here); "0/<version>" when the module isn't loaded. ADDON_VERSION is kept equal to
-- fabric/gradle.properties version by module/test/version_lua_test.py.
local ADDON_VERSION = "0.5.1"

local proto = 0
if not gmodcraft.missing and tonumber(gmodcraft.PROTOCOL) then
	proto = math.floor(tonumber(gmodcraft.PROTOCOL))
end
local value = tostring(proto) .. "/" .. ADDON_VERSION
gmodcraft.versionString = value

-- FCVAR_NOTIFY only: clients don't need it (no FCVAR_REPLICATED, see shared/log.lua).
local cv = CreateConVar("gmodcraft_version", value, FCVAR_NOTIFY,
	"Garry's Modcraft link protocol / addon version on this server (read-only information)")
-- CreateConVar keeps an existing value (a Lua refresh, a config that set it): always the real one.
if cv and cv:GetString() ~= value then cv:SetString(value) end
