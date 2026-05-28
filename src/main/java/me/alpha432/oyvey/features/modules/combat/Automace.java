package me.alpha432.oyvey.features.modules.combat;

import me.alpha432.oyvey.features.modules.Module;
import me.alpha432.oyvey.features.settings.Setting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

public class Automace extends Module {

    private final Setting<Float> speed = num("Speed", 2.5f, 0.1f, 10f);
    private final Setting<Float> riseHeight = num("RiseHeight", 25f, 5f, 120f);
    private final Setting<Boolean> autoDive = bool("AutoDive", true);

    private enum State { IDLE, RISE, ALIGN, DIVE }
    private State state = State.IDLE;

    private Player target;
    private double startY;

    public Automace() {
        super("Automace", "Mace dive without built-in attacking", Category.COMBAT);
    }

    @Override
    public void onTick() {
        if (nullCheck()) return;

        target = getTarget();
        if (target == null) return;

        switch (state) {
            case IDLE -> {
                startY = mc.player.getY();
                state = State.RISE;
            }

            case RISE -> rise();

            case ALIGN -> align();

            case DIVE -> dive();
        }
    }

    // ────────────────────────────────
    // RISE: instant vertical climb
    // ────────────────────────────────
    private void rise() {
        double targetY = target.getValueY() + riseHeight.getValue();

        if (mc.player.getY() < targetY) {
            mc.player.setDeltaMovement(0, speed.getValue(), 0);
            mc.player.fallDistance = 0;
        } else {
            state = State.ALIGN;
        }
    }

    // ────────────────────────────────
    // ALIGN: instant horizontal positioning
    // ────────────────────────────────
    private void align() {
        Vec3 pos = mc.player.position();
        Vec3 tpos = target.position();

        Vec3 diff = new Vec3(tpos.x - pos.x, 0, tpos.z - pos.z);
        double dist = diff.length();

        if (dist > 0.6) {
            Vec3 dir = diff.normalize();
            mc.player.setDeltaMovement(dir.x * speed.getValue(), 0, dir.z * speed.getValue());
            mc.player.fallDistance = 0;
        } else {
            startY = mc.player.getY();
            state = State.DIVE;
        }
    }

    // ────────────────────────────────
    // DIVE: let gravity + optional assist
    // ────────────────────────────────
    private void dive() {
        Vec3 pos = mc.player.position();
        Vec3 tpos = target.position();

        if (autoDive.getValue()) {
            Vec3 dir = new Vec3(tpos.x - pos.x, 0, tpos.z - pos.z).normalize();
            mc.player.setDeltaMovement(dir.x * speed.getValue(), -2.8, dir.z * speed.getValue());
        } else {
            mc.player.setDeltaMovement(0, -2.8, 0);
        }

        mc.player.fallDistance = (float) (startY - mc.player.getY());

        if (mc.player.distanceTo(target) < 3.5) {
            state = State.IDLE;
        }
    }

    // ────────────────────────────────
    private Player getTarget() {
        List<Player> list = mc.level.players().stream()
            .filter(p -> p != mc.player && p.isAlive())
            .sorted(Comparator.comparingDouble(p -> p.distanceTo(mc.player)))
            .toList();

        return list.isEmpty() ? null : list.getValue(0);
    }
}      
