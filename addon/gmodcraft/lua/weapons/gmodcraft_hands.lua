-- Garry's Modcraft hybrid mode (v17): the "hands" a player holds while their Minecraft hand holds a
-- normal Minecraft item. No model, no attacks, no ammo: GMod draws nothing over Minecraft's hand
-- (no toolgun ghost, no physgun beam). Never mirrored into the Minecraft inventory, never dropped
-- (server/hybrid.lua gives it, selects it in MC mode, and strips it when hybrid mode ends).
AddCSLuaFile()

SWEP.PrintName = "Minecraft Hands"
SWEP.Author = "Garry's Modcraft"
SWEP.Instructions = "Minecraft's hand: the held Minecraft item is used, not a GMod weapon."
SWEP.Spawnable = false
SWEP.AdminOnly = false
SWEP.Category = "Garry's Modcraft"
SWEP.Slot = 0
SWEP.SlotPos = 0
SWEP.DrawAmmo = false
SWEP.DrawCrosshair = false
-- Not "": the player's gmod_hands entity follows viewmodel 0, and an empty viewmodel makes the engine
-- spam "MOVETYPE_FOLLOW entity (gmod_hands) is parented to entity (viewmodel) with no model".
-- c_arms is the stock bones-only arms rig (weapon_fists uses it); nothing is drawn anyway
-- (SWEP:PreDrawViewModel below, PreDrawViewModel in client/view.lua, PreDrawPlayerHands in client/hybrid.lua).
SWEP.ViewModel = "models/weapons/c_arms.mdl"
SWEP.WorldModel = ""
SWEP.HoldType = "normal"
SWEP.UseHands = false
SWEP.Primary.ClipSize = -1
SWEP.Primary.DefaultClip = -1
SWEP.Primary.Automatic = false
SWEP.Primary.Ammo = "none"
SWEP.Secondary.ClipSize = -1
SWEP.Secondary.DefaultClip = -1
SWEP.Secondary.Automatic = false
SWEP.Secondary.Ammo = "none"

function SWEP:Initialize()
	self:SetHoldType(self.HoldType)
end

function SWEP:PrimaryAttack() end
function SWEP:SecondaryAttack() end
function SWEP:Reload() end
function SWEP:ShouldDropOnDie() return false end

if CLIENT then
	function SWEP:DrawWorldModel() end
	function SWEP:PreDrawViewModel() return true end
end
