-- I1: when Minecraft gets the keyboard and mouse (client/input.lua runs this every frame). Pure: no
-- GMod calls, so module/test/capture_test.py can walk the transition table.
--
--  * capture (keys to Minecraft, the mouse turns its look) only while Minecraft wants it (linked,
--    McState in a world, MC mode, alive, not seated, or a GMod-mode shortcut screen) AND GMod has the
--    window focus AND no GMod menu is up (an MC screen counts as ours, the popup);
--  * the MC-screen popup (MakePopup + a cursor warp to the screen centre) is never opened while GMod
--    is unfocused: X11 moves the system cursor even for an unfocused window, which yanked the mouse
--    out of whatever window the user had alt-tabbed to while things were still setting up. It opens
--    once focus is back; an open one stays (closing and reopening would warp again);
--  * focus lost: capture off (Minecraft gets a release-all); focus back: the first DROP_FRAMES mouse
--    deltas are dropped (the jump the engine's re-centring makes), then capture resumes;
--  * S1 (v35): while a panel hosts the MC screen (the spawnmenu's Minecraft tab), there is no popup
--    (an open one closes) and capture runs only while the screen is up: the spawnmenu is the
--    host, not a GMod menu in the way.

local CAP = gmodcraft.capture or {}
gmodcraft.capture = CAP

CAP.DROP_FRAMES = 2       -- mouse frames dropped after the focus comes back
CAP.MENU_GRACE = 0.3      -- s after the popup closed when a visible cursor isn't a GMod menu yet

-- s: { want, mcScreen, focused, wasFocused, menu, popupOpen, popupClosedAgo, hosted }
-- Returns { active, screen, openPopup, closePopup, focus ("lost" | "regained" | nil), dropMouse (frames | nil), deferred }
function CAP.Step(s)
	local r = {}
	if s.wasFocused ~= nil and s.focused ~= s.wasFocused then r.focus = s.focused and "regained" or "lost" end
	local screen = s.want and s.mcScreen or false
	r.screen = screen
	if s.hosted then
		r.openPopup, r.deferred = false, false
		r.closePopup = s.popupOpen or false
		r.active = (screen and s.focused) and true or false
		if r.focus == "regained" and r.active then r.dropMouse = CAP.DROP_FRAMES end
		return r
	end
	r.openPopup = screen and s.focused and not s.popupOpen or false
	r.deferred = screen and not s.focused and not s.popupOpen or false
	r.closePopup = not screen and s.popupOpen or false
	local menu = s.menu and (s.popupClosedAgo or math.huge) > CAP.MENU_GRACE
	r.active = (s.want and s.focused and (screen or not menu)) and true or false
	if r.focus == "regained" and r.active then r.dropMouse = CAP.DROP_FRAMES end  -- only for a capture that resumes
	return r
end

-- One InputMouseApply frame against the pending drops after a refocus. Every frame uses one up
-- (also with the popup open or capture off, so none are left over to swallow the mouse later, on a
-- screen close or F6); it is only consumed (true) while the mouse turns Minecraft's look.
-- Returns consumed, the drops left.
function CAP.MouseFrame(drop, capturing, popupOpen)
	if (drop or 0) <= 0 then return false, 0 end
	drop = drop - 1
	return (capturing and not popupOpen) and true or false, drop
end
