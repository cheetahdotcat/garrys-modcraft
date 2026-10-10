-- Spawnmenu tab "Garry's Modcraft", Debug group: "Blocks/Dig" (docs/DESIGN.md section 12, P5). Block collision for GMod entities
-- (P5a): collision entities and boxes near players/NPCs, rebuilds and their cost, the module's
-- section store and merge times, client box sync, nav areas blocked for NextBots. Server data comes
-- with the admin stats relay (st.blocks, server/blockcol.lua); the dug-cell stencil (P5b) adds here.

local D = gmodcraft.debug

D.Register{
	id = "blocks",
	title = "Blocks/Dig",
	group = "debug",
	order = 60,
	icon = "icon16/brick.png",
	desc = "Block collision entities for GMod props and NPCs (server stats, admins)",
	build = function(p)
		local sec = D.Section(p, "Stats: block collision entities (server, gmodcraft_blockcol)")
		local l = D.AddStats(sec, 7)
		local nextUpdate = 0
		p.Think = function()
			if RealTime() < nextUpdate then return end
			nextUpdate = RealTime() + 0.25
			D.RequestServerStats()
			local b = (D.serverStats or {}).blocks
			if not b then
				l[1]:SetText("no server data (admins only; the server relays it with the link stats)")
				for i = 2, #l do l[i]:SetText("") end
				return
			end
			local s, m = b.stats or {}, b.module or {}
			l[1]:SetText(string.format("%s | entities %d (box %d / cap %d, multi-convex sections %d) | sections %d | boxes %d | pending %d | range %d u",
				b.enabled and "on" or "OFF", b.entities or 0, b.boxEnts or 0, b.cap or 0, b.multiSections or 0, b.sections or 0, b.boxes or 0, b.pending or 0, b.range or 0))
			l[2]:SetText(string.format("created %d, removed %d, rebuilds %d, failed %d, deferred (0.25 s limit) %d, props woken %d",
				s.created or 0, s.removed or 0, s.rebuilds or 0, s.failed or 0, s.deferred or 0, s.woken or 0))
			l[3]:SetText(string.format("last update %.2f ms (%d boxes changed, merge %.3f ms) | worst %.2f ms (%d) | worst tick %.2f ms | %.3f ms per box (%d created, %d removed) | scan %.2f ms",
				s.lastMs or 0, s.lastBoxes or 0, s.mergeMs or 0, s.maxMs or 0, s.maxMsBoxes or 0, s.maxTickMs or 0, b.msPerBox or 0, s.boxesCreated or 0,
				s.boxesRemoved or 0, s.scanMs or 0))
			l[4]:SetText(string.format("module: sections %d, dirty %d, messages %d (unchanged %d), clears %d, merges %d (column fallback %d), merge last %.3f ms / max %.3f ms",
				m.sections or 0, m.dirty or 0, m.solidsMsgs or 0, m.unchanged or 0, m.clears or 0, m.merges or 0, m.fallbacks or 0, m.lastMergeMs or 0, m.maxMergeMs or 0))
			l[5]:SetText(string.format("client sync (multi-convex sections only): %d messages, %d bytes (last %d)", s.netMsgs or 0, s.netBytes or 0, s.lastNetBytes or 0))
			l[6]:SetText("NextBots path on the navmesh and don't see these entities; they bump and use their stuck handling")
			local n = 0
			for _, e in ipairs(ents.FindByClass("gmodcraft_blocks")) do n = n + 1 end
			l[7]:SetText(string.format("this client: %d block entities in its PVS", n))
		end
	end,
}
