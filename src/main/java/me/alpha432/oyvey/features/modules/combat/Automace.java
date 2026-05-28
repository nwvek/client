package me.alpha432.oyvey.features.modules.combat;

import me.alpha432.oyvey.features.modules.Module;
import me.alpha432.oyvey.features.settings.Setting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.Comparator;
import java.util.List;

public class Automace extends Module {

    // Speed: 1 = 20 bps, 3 = 60 bps, 5 = 100 bps (linear: bps = level * 20)
    // Minecraft runs at 20 ticks/sec, so blocks-per-tick = bps / 20
    // e.g. level 3 → 60 bps → 3 blocks/tick
    private final Setting<Float> speedLevel = num("Speed", 3f, 1f, 5f);
    private final Setting<Boolean> autoDive = bool("AutoDive", true);

    private enum State { IDLE, JUMP, WAIT_JUMP, RISE, ALIGN, PATHFIND, DIVE }
    private State  state         = State.IDLE;
    private Player target;
    private double startY;
    private double targetRiseY;
    private int    waitTicks     = 0;
    private Vec3   pathWaypoint  = null;

    public Automace() {
        super("Automace", "Auto-height mace dive", Category.COMBAT);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override
    public void onDisable() {
        resetState();
    }

    private void resetState() {
        state        = State.IDLE;
        target       = null;
        waitTicks    = 0;
        pathWaypoint = null;
    }

    // ── Speed helper ──────────────────────────────────────────────────────────
    // Returns blocks-per-tick from the 1-5 speed level setting
    private double bpt() {
        return (speedLevel.getValue() * 20.0) / 20.0; // level * 20 bps / 20tps
    }

    // ── Main tick ─────────────────────────────────────────────────────────────
    @Override
    public void onTick() {
        if (nullCheck()) return;

        target = getTarget();
        if (target == null) { resetState(); return; }

        // Ground recovery while mid-sequence
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

    // ── JUMP ──────────────────────────────────────────────────────────────────
    private void beginJump() {
        // Hard-zero all motion before jumping to prevent rubber-band
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
            waitTicks = 6; // wait 6 ticks — enough for jump velocity to register
        }
        // Not on ground yet (just landed and still bouncing): wait 1 tick
        else {
            waitTicks = 1;
        }
    }

    private void doWaitJump() {
        // waitTicks counted down by main tick guard; reach here when it hits 0
        if (!mc.player.onGround()) {
            targetRiseY = computeRequiredRiseY();
            state       = State.RISE;
        } else {
            // Still on ground (low ceiling etc.) — retry
            state     = State.JUMP;
            waitTicks = 2;
        }
    }

    // ── RISE ──────────────────────────────────────────────────────────────────
    private void doRise() {
        if (mc.player.getY() < targetRiseY) {
            mc.player.setDeltaMovement(0, bpt(), 0);
            mc.player.fallDistance = 0;
        } else {
            mc.player.setDeltaMovement(0, 0, 0);
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

        if (dist > 0.5) {
            if (isPathBlockedHorizontal(pos, tpos)) {
                Vec3 wp = findHorizontalWaypoint(pos, tpos);
                if (wp != null) {
                    pathWaypoint = wp;
                    state = State.PATHFIND;
                    return;
                }
                // Nowhere to go — hover and wait
                mc.player.setDeltaMovement(0, 0.05, 0);
                return;
            }
            mc.player.setDeltaMovement((dx / dist) * bpt(), 0.05, (dz / dist) * bpt());
        } else {
            startY = mc.player.getY();
            mc.player.setDeltaMovement(0, 0, 0);
            state = State.DIVE;
        }
    }

    // ── PATHFIND ──────────────────────────────────────────────────────────────
    private void doPathfind() {
        if (pathWaypoint == null) { state = State.ALIGN; return; }

        Vec3   pos  = mc.player.position();
        double dx   = pathWaypoint.x - pos.x;
        double dz   = pathWaypoint.z - pos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        mc.player.fallDistance = 0;

        if (dist > 0.5) {
            mc.player.setDeltaMovement((dx / dist) * bpt(), 0.05, (dz / dist) * bpt());
        } else {
            pathWaypoint = null;
            state = State.ALIGN;
        }
    }

    // ── DIVE ──────────────────────────────────────────────────────────────────
    private void doDive() {
        Vec3   pos  = mc.player.position();
        Vec3   tpos = target.position();
        double dx   = tpos.x - pos.x;
        double dz   = tpos.z - pos.z;

        // Force fallDistance to true blocks-fallen every tick.
        // The engine resets it on upward motion, so we overwrite manually.
        // startY is recorded at the moment we enter DIVE state.
        double fallen = startY - mc.player.getY();
        if (fallen > 0) mc.player.fallDistance = (float) fallen;

        // Steer toward target while diving at full bpt() downward speed
        double len = Math.sqrt(dx * dx + dz * dz);
        double cx  = len > 0.01 ? (dx / len) * 0.3 : 0;
        double cz  = len > 0.01 ? (dz / len) * 0.3 : 0;
        mc.player.setDeltaMovement(cx, -bpt(), cz);

        // Attack window: within 2 blocks above target vertically.
        // Don't wait until +0.5 — that's too late; hit registers before landing.
        if (mc.player.getY() <= target.getY() + 2.0) {
            mc.gameMode.attack(mc.player, target);
            beginJump();
        }
    }

    // ── HEIGHT CALCULATOR ─────────────────────────────────────────────────────
    /**
     * Mace damage formula (Minecraft 1.21+):
     *   fallBonus:
     *     blocks 1–3  → 4 damage each  (12 total)
     *     blocks 4–8  → 2 damage each  (10 total)
     *     blocks 9+   → 1 damage each
     *   densityBonus  = densityLevel * 0.5 * blocksFallen   (flat, not phased)
     *   baseDamage    = 6
     *   totalDamage   = (baseDamage + fallBonus + densityBonus) * 1.5  [critical]
     *
     * Rise target = target.getY() + requiredFallBlocks
     * We add a 20% overhead buffer so armour/resistance doesn't save them.
     */
    private double computeRequiredRiseY() {
        float  hp      = target.getMaxHealth();
        // 20% buffer over max HP to account for armour / resistance
        double needed  = hp * 1.2 + 10.0;
        int    density = getMaceDensityLevel();
        double blocks  = blocksForDamage(needed, density);
        // Hard cap 80, floor 5 — never dive less than 5 blocks
        blocks = Math.max(5, Math.min(blocks, 80));
        // Rise to (target feet + fall distance needed); ceiling-scan from there
        return findReachableCeiling(target.getY() + blocks);
    }

    /**
     * Solves for the minimum integer fall distance (blocks) such that
     * mace damage >= targetDmg, given density enchant level.
     *
     * Forward formula:
     *   fallBonus(n) = min(n,3)*4 + min(max(n-3,0),5)*2 + max(n-8,0)*1
     *   densityBonus(n) = density * 0.5 * n
     *   dmg(n) = (6 + fallBonus(n) + densityBonus(n)) * 1.5
     *
     * We iterate n upward (fast, caps at 80) to find the exact crossing point.
     * This avoids all the inversion arithmetic that was producing short heights.
     */
    private double blocksForDamage(double targetDmg, int density) {
        for (int n = 1; n <= 80; n++) {
            double fallBonus    = Math.min(n, 3) * 4.0
                                + Math.min(Math.max(n - 3, 0), 5) * 2.0
                                + Math.max(n - 8, 0) * 1.0;
            double densityBonus = density * 0.5 * n;
            double dmg          = (6.0 + fallBonus + densityBonus) * 1.5;
            if (dmg >= targetDmg) return n;
        }
        return 80; // fallback: maximum height
    }

    // ── ENCHANTMENT READER ────────────────────────────────────────────────────
    /**
     * Finds the mace in the player's hotbar + inventory and reads Density level.
     * Uses DataComponents (1.20.5+ / 1.21 NeoForge/Forge API).
     */
    private int getMaceDensityLevel() {
        // Check main hand first
        ItemStack mainHand = mc.player.getMainHandItem();
        int level = getDensityFromStack(mainHand);
        if (level >= 0) return level;

        // FIX: use getInventory().getItem(i) instead of .main.get(i)
        // Slots 0-35 cover the full player inventory (hotbar + main)
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            level = getDensityFromStack(stack);
            if (level >= 0) return level;
        }
        return 0; // no mace found, no density
    }

    private int getDensityFromStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        // Check if it's a mace by item id
        if (!stack.getItem().toString().contains("mace")) return -1;

        // FIX: use ItemEnchantments (Forge/NeoForge 1.21+) instead of
        // ItemEnchantmentsComponent (Fabric-only class that doesn't exist here)
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments == null) return 0;

        // Iterate all enchantments and find Density by registry key name
        for (var entry : enchantments.entrySet()) {
            String key = entry.getKey().getRegisteredName();
            if (key.contains("density")) {
                return entry.getIntValue();
            }
        }
        return 0;
    }

    // ── ROOF SCAN ─────────────────────────────────────────────────────────────
    private double findReachableCeiling(double desiredY) {
        double px   = mc.player.getX();
        double pz   = mc.player.getZ();
        int    from = (int) Math.ceil(mc.player.getY()) + 1;
        int    to   = (int) Math.ceil(desiredY);

        int    consAir  = 0;

        for (int y = from; y <= to; y++) {
            BlockState bs = mc.level.getBlockState(new BlockPos((int) px, y, (int) pz));
            if (bs.isAir()) {
                consAir++;
                if (consAir >= 2 && y >= to) return desiredY;
            } else {
                if (consAir < 2) return Math.max(mc.player.getY(), y - 2);
                consAir = 0;
            }
        }
        return desiredY;
    }

    // ── HORIZONTAL PATHFINDING ────────────────────────────────────────────────
    private boolean isPathBlockedHorizontal(Vec3 pos, Vec3 tpos) {
        int steps = (int) Math.ceil(pos.distanceTo(tpos));
        if (steps < 1) return false;
        for (int i = 1; i <= steps; i++) {
            double t  = (double) i / steps;
            int    bx = (int) Math.floor(pos.x + (tpos.x - pos.x) * t);
            int    by = (int) Math.floor(pos.y);
            int    bz = (int) Math.floor(pos.z + (tpos.z - pos.z) * t);
            if (!mc.level.getBlockState(new BlockPos(bx, by,     bz)).isAir()) return true;
            if (!mc.level.getBlockState(new BlockPos(bx, by + 1, bz)).isAir()) return true;
        }
        return false;
    }

    private Vec3 findHorizontalWaypoint(Vec3 pos, Vec3 tpos) {
        double[] offsets = { -5, -3, 3, 5 };
        Vec3 best = null;
        double bestDist = Double.MAX_VALUE;
        for (double ox : offsets) {
            for (double oz : offsets) {
                Vec3 cand = new Vec3(pos.x + ox, pos.y, pos.z + oz);
                if (!isPathBlockedHorizontal(pos, cand) && !isPathBlockedHorizontal(cand, tpos)) {
                    double d = cand.distanceTo(tpos);
                    if (d < bestDist) { bestDist = d; best = cand; }
                }
            }
        }
        return best;
    }

    // ── TARGET ────────────────────────────────────────────────────────────────
    private Player getTarget() {
        List<AbstractClientPlayer> players = mc.level.players();
        return players.stream()
            .filter(p -> p != mc.player)
            .min(Comparator.comparingDouble(p -> p.distanceTo(mc.player)))
            .orElse(null);
    }
}
