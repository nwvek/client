package me.alpha432.oyvey.features.modules.combat;

import me.alpha432.oyvey.features.modules.Module;
import me.alpha432.oyvey.features.settings.Setting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Automace extends Module {

    private final Setting<Float>   speed       = num("Speed",        2.5f, 0.1f, 10f);
    private final Setting<Float> densityLevel = num("Density", 5f, 0f, 5f);
    private final Setting<Boolean> autoDive    = bool("AutoDive",    true);

    // ── States ──────────────────────────────────────────────────────────────
    private enum State { IDLE, JUMP, WAIT_JUMP, RISE, ALIGN, PATHFIND, DIVE }
    private State  state          = State.IDLE;
    private Player target;
    private double startY;           // Y when dive began
    private double targetRiseY;      // computed ceiling for this dive cycle
    private int    waitTicks       = 0;
    private Vec3   pathWaypoint    = null; // intermediate point when blocked

    public Automace() {
        super("Automace", "Auto-height mace dive with pathfinding", Category.COMBAT);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────
    @Override
    public void onDisable() {
        resetState();
    }

    private void resetState() {
        state       = State.IDLE;
        target      = null;
        waitTicks   = 0;
        pathWaypoint = null;
    }

    // ── Main tick ────────────────────────────────────────────────────────────
    @Override
    public void onTick() {
        if (nullCheck()) return;

        target = getTarget();
        if (target == null) { resetState(); return; }

        // Ground recovery — only when we shouldn't be grounded
        if (mc.player.onGround()
                && state != State.IDLE
                && state != State.JUMP
                && state != State.WAIT_JUMP) {
            beginJump();
            return;
        }

        if (waitTicks > 0) { waitTicks--; return; }

        switch (state) {
            case IDLE      -> beginJump();
            case JUMP      -> doJump();
            case WAIT_JUMP -> doWaitJump();
            case RISE      -> doRise();
            case ALIGN     -> doAlign();
            case PATHFIND  -> doPathfind();
            case DIVE      -> doDive();
        }
    }

    // ── JUMP ─────────────────────────────────────────────────────────────────
    private void beginJump() {
        mc.player.setDeltaMovement(0, 0, 0);
        pathWaypoint = null;
        state        = State.JUMP;
        waitTicks    = 0;
    }

    private void doJump() {
        if (mc.player.onGround()) {
            mc.player.setDeltaMovement(0, 0, 0);
            mc.player.jumpFromGround();
            state     = State.WAIT_JUMP;
            waitTicks = 5;
        }
    }

    private void doWaitJump() {
        if (!mc.player.onGround()) {
            targetRiseY = computeRequiredRiseY();
            startY      = mc.player.getY();
            state       = State.RISE;
        } else if (waitTicks == 0) {
            state = State.JUMP; // retry
        }
    }

    // ── RISE ─────────────────────────────────────────────────────────────────
    private void doRise() {
        if (mc.player.getY() < targetRiseY) {
            mc.player.setDeltaMovement(0, speed.getValue(), 0);
            mc.player.fallDistance = 0;
        } else {
            state = State.ALIGN;
        }
    }

    // ── ALIGN ─────────────────────────────────────────────────────────────────
    private void doAlign() {
        Vec3   pos  = mc.player.position();
        Vec3   tpos = target.position();
        double dx   = tpos.x - pos.x;
        double dz   = tpos.z - pos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        mc.player.fallDistance = 0;

        if (dist > 0.6) {
            // Check if there is a solid block between us and the target at our Y
            if (isPathBlockedHorizontal(pos, tpos)) {
                // Try to find a waypoint around the obstacle
                Vec3 wp = findHorizontalWaypoint(pos, tpos);
                if (wp != null) {
                    pathWaypoint = wp;
                    state = State.PATHFIND;
                    return;
                }
                // Can't pathfind — hold altitude and wait for target to move
                mc.player.setDeltaMovement(0, 0.08, 0);
                return;
            }

            double len = dist;
            mc.player.setDeltaMovement(
                (dx / len) * speed.getValue(),
                0.08,
                (dz / len) * speed.getValue()
            );
        } else {
            startY = mc.player.getY();
            mc.player.setDeltaMovement(0, 0, 0);
            state = State.DIVE;
        }
    }

    // ── PATHFIND ─────────────────────────────────────────────────────────────
    // Move toward the intermediate waypoint, then return to ALIGN
    private void doPathfind() {
        if (pathWaypoint == null) { state = State.ALIGN; return; }

        Vec3   pos  = mc.player.position();
        double dx   = pathWaypoint.x - pos.x;
        double dz   = pathWaypoint.z - pos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        mc.player.fallDistance = 0;

        if (dist > 0.8) {
            mc.player.setDeltaMovement(
                (dx / dist) * speed.getValue(),
                0.08,
                (dz / dist) * speed.getValue()
            );
        } else {
            // Reached waypoint — back to ALIGN
            pathWaypoint = null;
            state = State.ALIGN;
        }
    }

    // ── DIVE ─────────────────────────────────────────────────────────────────
    private void doDive() {
        Vec3   pos  = mc.player.position();
        Vec3   tpos = target.position();
        double dx   = tpos.x - pos.x;
        double dz   = tpos.z - pos.z;

        double fallen = startY - mc.player.getY();
        if (fallen > 0) mc.player.fallDistance = (float) fallen;

        if (autoDive.getValue()) {
            double len = Math.sqrt(dx * dx + dz * dz);
            double cx  = len > 0.01 ? (dx / len) * 0.4 : 0;
            double cz  = len > 0.01 ? (dz / len) * 0.4 : 0;
            mc.player.setDeltaMovement(cx, -speed.getValue(), cz);
        }

        // Hit window passed — relaunch
        if (mc.player.getY() <= target.getY() + 0.5) {
            beginJump();
        }
    }

    // ── HEIGHT CALCULATOR ────────────────────────────────────────────────────
    /**
     * Mace smash damage formula (vanilla Java, no crit multiplier applied here
     * since we assume a crit is almost guaranteed):
     *   base  = 6
     *   bonus = first 3 blocks → 4 dmg each  (total 12 at h=3)
     *           next  5 blocks → 2 dmg each  (total 10 at h=8)
     *           remaining      → 1 dmg each
     *   density bonus = densityLevel * 0.5 * blocksfallen  (additional per block)
     *   critical multiplier = ×1.5
     *
     * We solve for the height that produces (targetMaxHp + 10) damage.
     */
    private double computeRequiredRiseY() {
        float  hp      = target.getMaxHealth();
        double needed  = hp + 10.0;          // overkill buffer
        int    density = densityLevel.getValue().intValue();

        double blocks = blocksNeededForDamage(needed, density);

        // Cap at 100 blocks to avoid absurd heights, add target Y as base
        double desiredAbsolute = target.getY() + Math.min(blocks, 100);

        // Roof check — find the actual reachable ceiling above the player
        return findReachableCeiling(desiredAbsolute);
    }

    /**
     * Invert the mace damage formula to get required fall height in blocks.
     */
    private double blocksNeededForDamage(double targetDmg, int density) {
        // base damage before any fall
        double dmg = 6.0;
        // density per-block bonus
        double densityPerBlock = density * 0.5;
        // critical multiplier baked into the target damage estimate
        // We want: (base + fallBonus) * 1.5 >= targetDmg
        // so fallBonus >= targetDmg/1.5 - base
        double fallBonus = (targetDmg / 1.5) - dmg;
        if (fallBonus <= 0) return 2.0; // already enough with crit alone

        // First 3 blocks give 4 dmg each + density bonus
        double perBlock0 = 4.0 + densityPerBlock;
        // Next 5 blocks give 2 dmg each + density bonus
        double perBlock1 = 2.0 + densityPerBlock;
        // Remaining give 1 dmg each + density bonus
        double perBlock2 = 1.0 + densityPerBlock;

        double accum  = 0;
        double blocks = 0;

        // Phase 1: blocks 1–3
        for (int i = 0; i < 3 && accum < fallBonus; i++) {
            accum  += perBlock0;
            blocks += 1;
        }
        if (accum >= fallBonus) return blocks;

        // Phase 2: blocks 4–8
        for (int i = 0; i < 5 && accum < fallBonus; i++) {
            accum  += perBlock1;
            blocks += 1;
        }
        if (accum >= fallBonus) return blocks;

        // Phase 3: remaining
        double remaining = fallBonus - accum;
        blocks += Math.ceil(remaining / perBlock2);
        return blocks;
    }

    // ── ROOF / CEILING SCAN ──────────────────────────────────────────────────
    /**
     * Walk upward from the player toward desiredY.
     * Returns the highest Y the player can actually reach (≥2 consecutive air).
     * If a solid block is found with a ≥2-air gap before it, the scan continues
     * through the gap (opening in the roof).
     */
    private double findReachableCeiling(double desiredY) {
        double px = mc.player.getX();
        double pz = mc.player.getZ();
        int from  = (int) Math.ceil(mc.player.getY()) + 1;
        int to    = (int) Math.ceil(desiredY);

        int    consecutiveAir = 0;
        double lastOpenY      = mc.player.getY();

        for (int y = from; y <= to; y++) {
            BlockState bs = mc.level.getBlockState(new BlockPos((int) px, y, (int) pz));
            if (bs.isAir()) {
                consecutiveAir++;
                if (consecutiveAir >= 2) lastOpenY = y;
            } else {
                if (consecutiveAir < 2) {
                    // Solid with no passable gap — cap 2 below obstruction
                    return Math.max(mc.player.getY(), y - 2);
                }
                consecutiveAir = 0; // reset after solid after an opening
            }
        }
        return desiredY;
    }

    // ── HORIZONTAL PATHFINDING ───────────────────────────────────────────────
    /**
     * Check if there is a solid block at player height between pos and tpos.
     */
    private boolean isPathBlockedHorizontal(Vec3 pos, Vec3 tpos) {
        int steps = (int) Math.ceil(pos.distanceTo(tpos));
        if (steps < 1) return false;
        for (int i = 1; i <= steps; i++) {
            double t  = (double) i / steps;
            double ix = pos.x + (tpos.x - pos.x) * t;
            double iz = pos.z + (tpos.z - pos.z) * t;
            int    iy = (int) Math.floor(pos.y);
            // check 2-block tall corridor
            BlockState b1 = mc.level.getBlockState(new BlockPos((int) Math.floor(ix), iy,     (int) Math.floor(iz)));
            BlockState b2 = mc.level.getBlockState(new BlockPos((int) Math.floor(ix), iy + 1, (int) Math.floor(iz)));
            if (!b1.isAir() || !b2.isAir()) return true;
        }
        return false;
    }

    /**
     * Try 8 cardinal + diagonal offset waypoints at ±5 blocks to find
     * a clear corridor around the obstacle.
     */
    private Vec3 findHorizontalWaypoint(Vec3 pos, Vec3 tpos) {
        double[] offsets = { -5, 0, 5 };
        Vec3 best = null;
        double bestDist = Double.MAX_VALUE;

        for (double ox : offsets) {
            for (double oz : offsets) {
                if (ox == 0 && oz == 0) continue;
                Vec3 candidate = new Vec3(pos.x + ox, pos.y, pos.z + oz);
                if (!isPathBlockedHorizontal(pos, candidate)
                        && !isPathBlockedHorizontal(candidate, tpos)) {
                    double d = candidate.distanceTo(tpos);
                    if (d < bestDist) {
                        bestDist = d;
                        best     = candidate;
                    }
                }
            }
        }
        return best;
    }

    // ── TARGET ───────────────────────────────────────────────────────────────
    private Player getTarget() {
        List<AbstractClientPlayer> players = mc.level.players();
        return players.stream()
            .filter(p -> p != mc.player)
            .min(Comparator.comparingDouble(p -> p.distanceTo(mc.player)))
            .orElse(null);
    }
}
