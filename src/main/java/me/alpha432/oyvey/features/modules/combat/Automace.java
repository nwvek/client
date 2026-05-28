package me.alpha432.oyvey.features.modules.combat;

import me.alpha432.oyvey.features.modules.Module;
import me.alpha432.oyvey.features.settings.Setting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.Comparator;
import java.util.List;

public class Automace extends Module {

    private final Setting<Float>   speed      = num("Speed",       2.5f, 0.1f, 10f);
    private final Setting<Float>   riseHeight = num("RiseHeight",  25f,  5f,  120f);
    private final Setting<Boolean> autoDive   = bool("AutoDive",   true);

    // ── States ──────────────────────────────────────────────────────────────
    private enum State { IDLE, JUMP, WAIT_JUMP, RISE, ALIGN, DIVE }
    private State  state        = State.IDLE;
    private Player target;
    private double startY;          // Y we started the dive from
    private double effectiveRiseY;  // actual Y ceiling we can reach (roof-aware)
    private int    waitTicks    = 0; // general-purpose tick timer

    public Automace() {
        super("Automace", "Smart mace dive – roof-aware, reliable relaunch", Category.COMBAT);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────
    @Override
    public void onDisable() {
        resetState();
    }

    private void resetState() {
        state     = State.IDLE;
        target    = null;
        waitTicks = 0;
    }

    // ── Main tick ────────────────────────────────────────────────────────────
    @Override
    public void onTick() {
        if (nullCheck()) return;

        target = getTarget();
        if (target == null) { resetState(); return; }

        // ── Ground-landing recovery ──────────────────────────────────────────
        // Only trigger when we should NOT be on the ground (i.e. mid-sequence).
        // Using WAIT_JUMP so we don't interfere with the intentional jump phase.
        if (mc.player.onGround() && state != State.IDLE && state != State.JUMP && state != State.WAIT_JUMP) {
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
            case DIVE      -> doDive();
        }
    }

    // ── JUMP phase ───────────────────────────────────────────────────────────
    // Called to initiate a (re)launch. We always make sure we're on the ground
    // before jumping so there's no rubber-band from spamming movement while grounded.
    private void beginJump() {
        // Stop all movement so we don't slide / rubber-band
        mc.player.setDeltaMovement(0, 0, 0);
        state     = State.JUMP;
        waitTicks = 0;
    }

    private void doJump() {
        if (mc.player.onGround()) {
            // Zero velocity, then jump cleanly
            mc.player.setDeltaMovement(0, 0, 0);
            mc.player.jumpFromGround();
            state     = State.WAIT_JUMP;
            waitTicks = 4; // wait 4 ticks so the jump velocity registers
        }
        // If somehow not on ground yet (just landed) keep waiting
    }

    private void doWaitJump() {
        // waitTicks is counting down via the main tick guard above.
        // Once it hits 0 and we're airborne, begin rising.
        if (!mc.player.onGround()) {
            effectiveRiseY = computeEffectiveRiseY();
            startY         = mc.player.getY();
            state          = State.RISE;
        }
        // If still on ground after wait (e.g. ceiling above), retry
        else if (waitTicks == 0) {
            state = State.JUMP;
        }
    }

    // ── RISE phase ───────────────────────────────────────────────────────────
    private void doRise() {
        if (mc.player.getY() < effectiveRiseY) {
            mc.player.setDeltaMovement(0, speed.getValue(), 0);
            mc.player.fallDistance = 0;
        } else {
            state = State.ALIGN;
        }
    }

    // ── ALIGN phase ──────────────────────────────────────────────────────────
    private void doAlign() {
        Vec3   pos  = mc.player.position();
        Vec3   tpos = target.position();
        double dx   = tpos.x - pos.x;
        double dz   = tpos.z - pos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        // Hold altitude while moving horizontally
        mc.player.fallDistance = 0;
        mc.player.setDeltaMovement(
            mc.player.getDeltaMovement().x,
            0.08,
            mc.player.getDeltaMovement().z
        );

        if (dist > 0.6) {
            double len = Math.sqrt(dx * dx + dz * dz);
            mc.player.setDeltaMovement(
                (dx / len) * speed.getValue(),
                0.08,
                (dz / len) * speed.getValue()
            );
        } else {
            // Above target — commit to dive
            startY = mc.player.getY();
            mc.player.setDeltaMovement(0, 0, 0);
            state = State.DIVE;
        }
    }

    // ── DIVE phase ───────────────────────────────────────────────────────────
    private void doDive() {
        Vec3   pos  = mc.player.position();
        Vec3   tpos = target.position();
        double dx   = tpos.x - pos.x;
        double dz   = tpos.z - pos.z;

        // Accumulate fall distance for mace damage bonus
        double fallen = startY - mc.player.getY();
        if (fallen > 0) mc.player.fallDistance = (float) fallen;

        if (autoDive.getValue()) {
            double len = Math.sqrt(dx * dx + dz * dz);
            double cx  = len > 0.01 ? (dx / len) * 0.4 : 0;
            double cz  = len > 0.01 ? (dz / len) * 0.4 : 0;
            mc.player.setDeltaMovement(cx, -speed.getValue(), cz);
        }

        // If we've passed the target's Y level the hit window is over — relaunch
        if (mc.player.getY() <= target.getY() + 0.5) {
            beginJump();
        }
    }

    // ── Roof-aware ceiling calculation ───────────────────────────────────────
    /**
     * Walk upward from the player's current position toward the desired rise
     * ceiling. If we hit a solid block (roof), stop there and leave a 2-block
     * gap so the player can still move. If there's an opening (gap of ≥2 air
     * blocks), allow rising through it to continue higher.
     */
    private double computeEffectiveRiseY() {
        double desiredY  = target.getY() + riseHeight.getValue();
        double playerX   = mc.player.getX();
        double playerZ   = mc.player.getZ();
        double currentY  = mc.player.getY();

        int scanFrom = (int) Math.ceil(currentY) + 1;
        int scanTo   = (int) Math.ceil(desiredY);

        int consecutiveAir = 0;
        double lastOpenY   = currentY; // highest confirmed-open position

        for (int y = scanFrom; y <= scanTo; y++) {
            BlockPos   bp = new BlockPos((int) playerX, y, (int) playerZ);
            BlockState bs = mc.level.getBlockState(bp);

            if (bs.isAir()) {
                consecutiveAir++;
                // Two consecutive air blocks = passable gap; update open ceiling
                if (consecutiveAir >= 2) {
                    lastOpenY = y;
                }
            } else {
                // Solid block hit — stop just below it (1-block clearance)
                // But only if we haven't already found enough room to pass
                if (consecutiveAir < 2) {
                    // No opening — cap rise 2 blocks below the obstruction
                    return Math.max(currentY, y - 2);
                }
                // There was a ≥2-air opening before this; reset counter
                consecutiveAir = 0;
            }
        }

        // No obstruction found — use desired height
        return desiredY;
    }

    // ── Target selection ─────────────────────────────────────────────────────
    private Player getTarget() {
        List<AbstractClientPlayer> players = mc.level.players();
        return players.stream()
            .filter(p -> p != mc.player)
            .min(Comparator.comparingDouble(p -> p.distanceTo(mc.player)))
            .orElse(null);
    }
}
