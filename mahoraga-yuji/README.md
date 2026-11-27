# Mahoraga & Yuji: Cursed Combat

Fabric mod for **Minecraft Java 1.21.8**. Requires Fabric Loader, Fabric API, and Java 21. The mod must be installed on the server and on each client in multiplayer.

## Abilities

### Mahoraga
- Press **G** to toggle the Eight-Handled Adaptation Wheel.
- The first hit teaches the wheel the incoming damage category: melee, projectile, fire, explosion, fall, magic, or other.
- Further hits of the same category build adaptation over time, up to 80% damage reduction.
- Changing the incoming damage category replaces the current adaptation.
- A rotating particle ring above the player's head shows that the wheel is active.

### Yuji
- Press **H** to toggle Yuji's enhanced physical mode (Strength + Speed).
- **R — Divergent Fist:** an immediate punch followed by a delayed second impact.
- **V — Black Flash:** a heavy cursed-energy strike with a strong impact effect.
- **B — Shrine / Dismantle:** slashes targets in a forward cone.
- **N — Reverse Cursed Technique:** restores health and removes poison and wither.
- **P — Piercing Blood:** a focused long-range strike.

## Commands

`/jjk help`, `/jjk mahoraga`, `/jjk yuji`, `/jjk divergent`, `/jjk blackflash`, `/jjk shrine`, `/jjk rct`, `/jjk blood`.

## Notes

The mechanics are an original Minecraft adaptation inspired by the named Jujutsu Kaisen abilities, not a frame-perfect port of a specific Roblox/Versus moveset. Damage values and cooldowns are intentionally exposed in `TechniqueManager.java` for later balancing. Adaptation and temporary combat state are session-based and reset when the server restarts.
