-- Spawnmenu tab "Garry's Modcraft": "Maps" (v34 control centre, admins). The GMod server's maps (its own
-- maps/*.bsp, workshop ones marked) with their thumbnails, each map's Minecraft slot and vertical offset
-- (map_slots.json on the Minecraft server, asked through kAdminSlotInfo; "new" = no slot yet, it gets one on
-- its first visit) and the current map marked. Clicking a map changes level (asks first). The server
-- checks admin rights and the map name (shared/serveradmin.lua).

local D = gmodcraft.debug
local SA = gmodcraft.serverAdmin

local TILE_W, TILE_H, THUMB = 136, 168, 128
local thumbs = {}  -- name -> IMaterial | false (none)

local function thumbOf(name)
	local t = thumbs[name]
	if t ~= nil then return t or nil end
	t = false
	for _, path in ipairs({ "maps/thumb/" .. name .. ".png", "maps/" .. name .. ".png" }) do
		if file.Exists(path, "GAME") then
			local m = Material(path, "smooth")
			if m and not m:IsError() then t = m break end
		end
	end
	thumbs[name] = t
	return t or nil
end

-- "slot (x, z), offset y units" for a map's slot entry (nil: not answered yet, false: no slot).
function SA.SlotText(slot)
	if slot == nil then return "slot: asking..." end
	if slot == false then return "no slot yet (first visit)" end
	return string.format("slot (%d, %d)  oy %d (%.1f bl)", slot.slotX or 0, slot.slotZ or 0, slot.oy or 0, (slot.oy or 0) / 40)
end

local function build(body)
	local top = vgui.Create("DPanel", body)
	top:Dock(TOP)
	top:DockMargin(8, 4, 8, 0)
	top:SetTall(24)
	top.Paint = function() end
	local filter = vgui.Create("DTextEntry", top)
	filter:Dock(LEFT)
	filter:SetWide(200)
	filter:SetPlaceholderText("filter maps")
	local refresh = vgui.Create("DButton", top)
	refresh:Dock(LEFT)
	refresh:DockMargin(6, 0, 0, 0)
	refresh:SetWide(80)
	refresh:SetText("Refresh")
	local status = vgui.Create("DLabel", top)
	status:Dock(FILL)
	status:DockMargin(10, 0, 0, 0)
	status:SetDark(true)
	status:SetText("asking the server...")

	local scroll = vgui.Create("DScrollPanel", body)
	scroll:Dock(FILL)
	scroll:DockMargin(8, 6, 8, 8)
	local grid = vgui.Create("DIconLayout", scroll)
	grid:Dock(FILL)
	grid:SetSpaceX(6)
	grid:SetSpaceY(6)

	local function tile(m, current)
		local b = grid:Add("DButton")
		b:SetSize(TILE_W, TILE_H)
		b:SetText("")
		b:SetTooltip(m.name .. (m.source == "workshop" and " (workshop)" or "") .. "\n" .. SA.SlotText(m.slot))
		b.Paint = function(self, w, h)
			local hot = self:IsHovered()
			surface.SetDrawColor(current and Color(60, 140, 60) or (hot and Color(90, 90, 110) or Color(55, 55, 60)))
			surface.DrawRect(0, 0, w, h)
			local mat = thumbOf(m.name)
			local x, y = (w - THUMB) / 2, 4
			if mat then
				surface.SetDrawColor(255, 255, 255)
				surface.SetMaterial(mat)
				surface.DrawTexturedRect(x, y, THUMB, THUMB)
			else
				surface.SetDrawColor(30, 30, 34)
				surface.DrawRect(x, y, THUMB, THUMB)
				draw.SimpleText("no picture", "DermaDefault", w / 2, y + THUMB / 2, Color(150, 150, 150), TEXT_ALIGN_CENTER, TEXT_ALIGN_CENTER)
			end
			if m.source == "workshop" then
				draw.SimpleText("workshop", "DermaDefault", x + 3, y + 2, Color(255, 220, 120))
			end
			if current then
				draw.SimpleText("current", "DermaDefaultBold", x + THUMB - 3, y + 2, Color(160, 255, 160), TEXT_ALIGN_RIGHT)
			end
			draw.SimpleText(m.name, "DermaDefault", w / 2, y + THUMB + 3, color_white, TEXT_ALIGN_CENTER)
			local s = m.slot
			local txt = s == nil and "..." or (s == false and "no slot yet" or string.format("(%d,%d) oy %d", s.slotX or 0, s.slotZ or 0, s.oy or 0))
			draw.SimpleText(txt, "DermaDefault", w / 2, y + THUMB + 17, Color(200, 200, 200), TEXT_ALIGN_CENTER)
		end
		b.DoClick = function()
			if current then return end
			Derma_Query("Change the map to " .. m.name .. "? Everyone on the server goes with it.", "Change map", "Change map",
				function() SA.Request("changelevel", { map = m.name }) end, "Cancel")
		end
	end

	local function fill(data)
		if not IsValid(grid) then return end
		data = data or SA.maps or { maps = {} }
		grid:Clear()
		local want = tostring(filter:GetValue() or ""):lower()
		local current = (data.current or game.GetMap()):lower()
		local shown, slots = 0, 0
		for _, m in ipairs(data.maps or {}) do
			if type(m.slot) == "table" then slots = slots + 1 end
			if want == "" or m.name:find(want, 1, true) then
				tile(m, m.name == current)
				shown = shown + 1
			end
		end
		status:SetText(string.format("%d maps (%d shown), %d with a Minecraft slot%s", #(data.maps or {}), shown, slots,
			(data.asking or 0) > 0 and "; asking Minecraft for the slots..." or ""))
		grid:Layout()
	end
	filter.OnChange = function() fill() end
	refresh.DoClick = function() status:SetText("asking the server...") SA.Request("maps") end
	SA.OnMaps = fill
	if SA.maps then fill(SA.maps) end
	SA.Request("maps")
end

D.RegisterPanel("maps", {
	title = "Maps",
	order = 3,
	icon = "icon16/map.png",
	build = function(p) D.AdminPage(p, "Maps (admins only): click a map to change level", build, false) end,
})
