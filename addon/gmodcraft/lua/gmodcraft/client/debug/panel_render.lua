-- Spawnmenu tab "Garry's Modcraft", Debug group: "Render" (DESIGN.md section 12). P3a: Minecraft's blocks (sections, vertices,
-- the atlas and its upload cost, the render ring, frame cost, a hide toggle per pass); P1: the
-- HUD/hand overlay texture and its upload cost; P3c: entities, particles, items, F5 model.

local D = gmodcraft.debug

local function timer(t)
	t = t or {}
	return string.format("%.3f/%.3f/%.3f", t.last or 0, t.avg or 0, t.max or 0)
end

D.Register{
	id = "render",
	title = "Render",
	group = "debug",
	order = 30,
	icon = "icon16/picture.png",
	desc = "Block passes and mesh paths, entity meshes, the HUD overlay texture",
	build = function(p)
		local lVer = D.AddLabel(p)
		-- the player-facing toggles (draw blocks / entities, outline, shadows, torch light, HUD) are on
		-- the Settings page
		local ctl = D.Section(p, "Block passes and mesh paths")
		D.AddCheck(ctl, "Opaque + cutout pass (gmodcraft_blocks_opaque)", "gmodcraft_blocks_opaque")
		D.AddCheck(ctl, "Translucent pass (gmodcraft_blocks_translucent)", "gmodcraft_blocks_translucent")
		D.AddCheck(ctl, "Lua Mesh() fallback (gmodcraft_blocks_lua; off = the module's IMesh path, default)", "gmodcraft_blocks_lua")
		D.AddCheck(ctl, "Vertex colour in linear light (gmodcraft_blocks_linear_vc; default off, measured)", "gmodcraft_blocks_linear_vc")
		D.AddCheck(ctl, "Vertex colour bytes R,G,B,A (gmodcraft_blocks_rgba; default on, measured)", "gmodcraft_blocks_rgba")
		D.AddButton(ctl, "Retry the IMesh check (rebuilds every section; a failed check otherwise stays until the path is switched)", function()
			if gmodcraft.BlocksRetryMesh then gmodcraft.BlocksRetryMesh() end
			if gmodcraft.blocks then gmodcraft.blocks.forceLua = nil end
		end)
		D.AddButton(ctl, "Retry the dynamic mesh check (entities)", function()
			if gmodcraft.EntitiesRetryDynamic then gmodcraft.EntitiesRetryDynamic() end
		end)
		D.AddNote(ctl, "Draw blocks / entities, outline, shadows, torch light and the HUD: Play > Settings.")
		local blSec = D.Section(p, "Stats: Minecraft blocks (render ring -> static IMesh per section and pass)", true)
		local bl = D.AddStats(blSec, 11)
		local elSec = D.Section(p, "Stats: Minecraft entities, particles, items, F5 model (baked per frame -> dynamic mesh)", true)
		local el = D.AddStats(elSec, 6)
		local ovSec = D.Section(p, "Stats: HUD/hand overlay (procedural BGRA texture, straight from the overlay slot)", true)
		local lines = D.AddStats(ovSec, 4)
		local nextUpdate = 0
		p.Think = function()
			if RealTime() < nextUpdate then return end
			nextUpdate = RealTime() + 0.25
			lVer:SetText(gmodcraft.missing and "gmcl module not loaded" or ("gmcl " .. gmodcraft.Version()))
			if gmodcraft.missing then return end
			local st = gmodcraft.Stats() or {}
			local b = gmodcraft.BlocksInfo and gmodcraft.BlocksInfo() or {}
			local a = b.atlas or {}
			local m = b.messages or {}
			bl[1]:SetText(string.format("sections %d, vertices %d opaque + %d translucent (raw %d), meshes %d (live %d), rebuild queue %d, draw calls %d + %d",
				b.sections or 0, b.opaqueVertices or 0, b.translucentVertices or 0, b.rawVertices or 0, b.meshes or 0, b.meshesLive or 0, b.pendingRebuild or 0,
				b.drawCallsOpaque or 0, b.drawCallsTranslucent or 0))
			bl[2]:SetText(string.format("mesh path %s: %s%s", b.meshOk == 1 and "ok" or b.meshOk == 0 and "FAILED" or "not checked yet", b.meshProbe or "",
				b.luaMeshes and " (Lua Mesh() fallback on)" or ""))
			bl[3]:SetText(string.format("atlas %s %dx%d: received %d, uploads %d, upload ms last/avg/max %s, animated regions %d (%d rejected), %.0f Hz cap%s%s",
				a.name or "-", a.w or 0, a.h or 0, a.received or 0, a.downloads or 0, timer(a.downloadMs), a.regions or 0, a.regionRejects or 0, a.hz or 0,
				(a.error or "") ~= "" and (" error: " .. a.error) or "", (a.resampled or 0) > 0 and " (resampled)" or ""))
			bl[4]:SetText(string.format("frame ms last/avg/max: drain %s, prepare %s, draw opaque %s, translucent %s", timer(b.drainMs), timer(b.prepareMs),
				timer(b.drawOpaqueMs), timer(b.drawTranslucentMs)))
			local rr = (((st.linkStats or {}).host or {}).rings or {}).render or {}
			local mr = (((st.linkStats or {}).mc or {}).rings or {}).render or {}
			bl[5]:SetText(string.format("render ring: %d messages, %.1f MiB, %.1f KiB/s; Minecraft side fill %.1f KiB, high water %.1f MiB, drops %d",
				rr.messages or 0, (rr.bytes or 0) / 1048576, (rr.bytesPerSec or 0) / 1024, (mr.fill or 0) / 1024, (mr.highWater or 0) / 1048576, mr.drops or 0))
			local function n(k) return (m[k] or {}).messages or 0 end
			bl[6]:SetText(string.format("by type: section %d, clearAll %d, atlas %d, region %d, dug %d, solids %d; scene %d, avatar %d, ragdoll %d, texture %d; not drawn yet: lights %d; malformed %d, key aliases %d",
				n("section"), n("clearAll"), n("atlas"), n("atlasRegion"), n("dug"), n("solids"), n("scene"), n("avatar"), n("ragdoll"), n("texture"), n("lights"),
				b.malformed or 0, b.aliased or 0))
			local mats = b.materials or {}
			local function mat(x) return x and (x.found and "ok" or ("missing " .. (x.error or ""))) or "-" end
			bl[7]:SetText(string.format("materials: opaque %s, translucent %s", mat(mats.opaque), mat(mats.translucent)))
			local bs = (gmodcraft.blocks or {}).stats or {}
			local others = {}
			for k, v in pairs(bs.rtOther or {}) do others[#others + 1] = k .. " " .. v end
			bl[8]:SetText(string.format("reflection/refraction views skipped %d; other render targets drawn into: %s", bs.rtSkips or 0,
				#others > 0 and table.concat(others, ", ") or "none") .. (function()
					local se = gmodcraft.solidEnts and gmodcraft.solidEnts.stats
					if not se or se.frames == 0 then return "" end
					return string.format("; eye in solid (P5d): %d frames, last %d entities drawn by us, %d dormant nearby, %.2f ms", se.frames, se.drawn,
						se.dormant, se.lastMs)
				end)())
			local cfg = (gmodcraft.blocks or {}).config or {}
			bl[9]:SetText(string.format("bake: linear vertex colour %s, RGBA order %s, slot origin %d, %d, vertical offset %d units", tostring(cfg.linearVertexColor), tostring(cfg.rgbaOrder),
				cfg.originX or 0, cfg.originZ or 0, cfg.originYUnits or 0))
			local an = b.anim or {}
			bl[10]:SetText(string.format("animated sprites (mode %d, %.0f Hz cap): texture %s %dx%d, %d sprites, %d uploads, ms %s%s; sub-rect: %d batches, %d rects (%d pending), per rect %s; regions unchanged %d",
				an.mode or -1, an.hz or 0, an.name or "-", an.w or 0, an.h or 0, an.sprites or 0, an.downloads or 0, timer(an.downloadMs),
				(an.error or "") ~= "" and (" error: " .. an.error) or "", a.animBatches or 0, a.rectUploads or 0, a.pendingRects or 0, timer(a.rectMs),
				a.regionsUnchanged or 0))
			local li, ls = b.light or {}, (gmodcraft.blocks or {}).lightStats or {}
			bl[11]:SetText(string.format("GMod light %s: %d cells (%d pending), %d samples, %d re-bakes, %d refreshes; lum seen %.3f..%.3f%s",
				li.on and "on" or "off", li.cells or 0, li.pending or 0, li.sets or 0, li.rebakes or 0, li.refreshes or 0, ls.minLum or 0, ls.maxLum or 0,
				(gmodcraft.blocks or {}).forceLua and "; IMesh check failed: Lua fallback in use" or ""))
			local e = gmodcraft.EntitiesInfo and gmodcraft.EntitiesInfo() or {}
			local function mm(x) x = x or {} return string.format("%d v / %d b%s", x.vertices or 0, x.batches or 0, (x.dropped or 0) > 0 and (" (" .. x.dropped .. " over cap)") or "") end
			el[1]:SetText(string.format("avatar %s, scene %s, ragdoll %s; messages: %d avatar, %d scene, %d ragdoll; malformed %d, bad batches %d, over cap %d msgs / %d v",
				mm(e.avatar), mm(e.scene), mm(e.ragdoll), e.avatars or 0, e.scenes or 0, e.ragdolls or 0, e.malformed or 0, e.badBatches or 0, e.overflowMsgs or 0,
				e.overflowVerts or 0))
			el[2]:SetText(string.format("world entities %d: items %d, block items %d, arrows %d, cracks %d, shadows %d, outline %s; snapshot reads %d (gave up %d)",
				e.worldEntities or 0, e.items or 0, e.blocks or 0, e.arrows or 0, e.cracks or 0, e.shadows or 0, tostring(e.selection), e.worldReads or 0, e.worldReadFails or 0))
			el[3]:SetText(string.format("textures %d (received %d, rejected %d), materials %d%s; baked %d vertices in %d groups; drawn %d + %d vertices in %d + %d calls; no material %d",
				e.textures or 0, e.texturesReceived or 0, e.textureRejects or 0, e.materials or 0, (e.textureError or "") ~= "" and (" error: " .. e.textureError) or "",
				e.bakedVertices or 0, e.groups or 0, e.drawnOpaque or 0, e.drawnTranslucent or 0, e.drawCallsOpaque or 0, e.drawCallsTranslucent or 0, e.noMaterial or 0))
			el[4]:SetText(string.format("dynamic mesh %s (chunk %d): %s; static fallback draws %d", e.dynOk == 1 and "ok" or e.dynOk == 0 and "FAILED" or "not checked yet",
				e.chunk or 0, e.dynProbe or "", e.staticFallbackDraws or 0))
			el[5]:SetText(string.format("frame ms last/avg/max: prepare %s (bake %s), draw opaque %s, translucent %s, texture uploads %s", timer(e.prepareMs),
				timer(e.bakeMs), timer(e.drawOpaqueMs), timer(e.drawTranslucentMs), timer(e.uploadMs)))
			el[6]:SetText(string.format("GMod light: %d cells (%d pending), %d samples, S at the eye %.2f%s", e.lightCells or 0, e.lightPending or 0, e.lightSets or 0,
				e.lightDefault or 0, (e.injectMobs or 0) + (e.injectParticles or 0) > 0 and string.format("; dev inject %d mobs, %d particles", e.injectMobs, e.injectParticles) or ""))
			local o = st.overlay or {}
			lines[1]:SetText(string.format("matsys ok %s (%s) %s", tostring(o.matsysOk), tostring(o.matsysProbe), o.error ~= "" and ("error: " .. tostring(o.error)) or ""))
			lines[2]:SetText(o.name and string.format("%s %dx%d, frame flags 0x%x (bottom-up %s, BGRA %s, sRGB %s)", o.name, o.w, o.h, o.flags,
				tostring(bit.band(o.flags, 1) ~= 0), tostring(bit.band(o.flags, 2) ~= 0), tostring(bit.band(o.flags, 4) ~= 0)) or "no overlay frame yet")
			lines[3]:SetText(o.name and string.format("downloads %d, Download %.3f ms (avg %.3f), regenerations %d, converted (fallback path) %d, mismatches %d %s",
				o.downloads, o.lastMs, o.avgMs, o.regen, o.converted, o.mismatch, o.mismatchInfo or "") or "")
		end
	end,
}
