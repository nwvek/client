package me.alpha432.oyvey.features.modules.combat;
import me.alpha432.oyvey.features.modules.Module;
import me.alpha432.oyvey.features.settings.Setting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.player.AbstractClientPlayer;
import java.util.Comparator;
import java.util.List;

public class Automace extends Module {
    private final Setting<Float> speed      = num("Speed",       2.5f,  0.1f, 10f);
    private final Setting<Float> riseHeight = num("RiseHeight",  25f,   5f,  120f);
    private final Setting<Boolean> autoDive = bool("AutoDive",   true);

    private enum State { JUMP, RISE, ALIGN, DIVE }
    private State state = State.JUMP;
    private Player target;
    private double startY;
    private int jumpCooldown = 0;

    public Automace() {
        super("Automace", "Mace dive without built-in attacking", Category.COMBAT);
    }

    @Override
    public void onDisable() {
        state = State.JUMP;
        target = null;
        jumpCooldown = 0;
    }

    @Override
    public void onTick() {
        if (nullCheck()) return;
        target = getTarget();
        if (target == null) return;

        // If we're on the ground at any point after initial launch, relaunch
        if (state != State.JUMP && mc.player.onGround()) {
            state = State.JUMP;
            jumpCooldown = 0;
        }

        if (jumpCooldown > 0) jumpCooldown--;

        switch (state) {
            case JUMP -> jump();
            case RISE -> rise();
            case ALIGN -> align();
            case DIVE -> dive();
        }
    }

    // ────────────────────────────────
    // JUMP: get off the ground first
    // ────────────────────────────────
    private void jump() {
        if (mc.player.onGround()) {
            if (jumpCooldown == 0) {
                mc.player.jumpFromGround();
                jumpCooldown = 3; // wait 3 ticks before rising
            }
        } else {
            // We're airborne, start rising
            startY = mc.player.getY();
            state = State.RISE;
        }
    }

    // ────────────────────────────────
    // RISE: fly straight up
    // ────────────────────────────────
    private void rise() {
        double targetY = target.getY() + riseHeight.getValue();
        if (mc.player.getY() < targetY) {
            mc.player.setDeltaMovement(0, speed.getValue(), 0);
            mc.player.fallDistance = 0;
        } else {
            state = State.ALIGN;
        }
    }

    // ────────────────────────────────
    // ALIGN: move above target horizontally
    // ────────────────────────────────
    private void align() {
        Vec3 pos  = mc.player.position();
        Vec3 tpos = target.position();
        double dx = tpos.x - pos.x;
        double dz = tpos.z - pos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        // Keep altitude while aligning
        mc.player.fallDistance = 0;

        if (dist > 0.8) {
            double len = Math.sqrt(dx * dx + dz * dz);
            mc.player.setDeltaMovement(
                (dx / len) * speed.getValue(),
                0.1, // slight upward push to hold altitude
                (dz / len) * speed.getValue()
            );
        } else {
            // Directly above target — start dive
            startY = mc.player.getY();
            mc.player.setDeltaMovement(0, 0, 0); // stop horizontal drift
            state = State.DIVE;
        }
    }

    // ────────────────────────────────
    // DIVE: fall straight down onto target
    // ────────────────────────────────
    private void dive() {
        Vec3 pos  = mc.player.position();
        Vec3 tpos = target.position();
        double dx = tpos.x - pos.x;
        double dz = tpos.z - pos.z;

        // Accumulate fall distance for mace damage calculation
        mc.player.fallDistance = (float) (startY - mc.player.getY());

        if (autoDive.getValue()) {
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.01) {
                // Slight horizontal correction to stay on target
                mc.player.setDeltaMovement(
                    (dx / len) * 0.5,
                    -2.8,
                    (dz / len) * 0.5
                );
            } else {
                // Directly on target, fall straight down
                mc.player.setDeltaMovement(0, -2.8, 0);
            }
        }
    }

    private Player getTarget() {
        List<AbstractClientPlayer> players = mc.level.players();
        return players.stream()
            .filter(p -> p != mc.player)
            .min(Comparator.comparingDouble(p -> p.distanceTo(mc.player)))
            .orElse(null);
    }
}
