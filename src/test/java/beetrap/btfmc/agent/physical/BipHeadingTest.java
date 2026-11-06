package beetrap.btfmc.agent.physical;

/**
 * Plain-Java checks for {@link BipHeading} (no test framework needed: run main()).
 * Each check reproduces one way Bip's head used to flick.
 */
public final class BipHeadingTest {

    /** Hard-coded on purpose: a turn bigger than this in one tick reads as a flick. */
    private static final float MAX_VISIBLE_TURN_PER_TICK = 15.0f;

    private static int failures;

    public static void main(String[] args) {
        neverTurnsFasterThanTheStepLimit();
        takesTheShortWayAroundTheCircle();
        settlesOnTheTargetAndStaysStill();
        ignoresSmallTargetJitter();
        doesNotSpinWhenTheTargetIsDirectlyAboveOrBelow();
        speedHoveringAroundTheThresholdDoesNotToggleTheHeading();
        faceTheFlightPathThenReturnToThePlayerSmoothly();
        ignoresNonFiniteInput();
        if(failures > 0) {
            System.out.println(failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("All BipHeading checks passed");
    }

    private static void neverTurnsFasterThanTheStepLimit() {
        BipHeading h = new BipHeading();
        float yaw = 0f;
        float pitch = 0f;
        for(int i = 0; i < 60; i++) {
            // Target is straight behind Bip (yaw -180 while he faces 0): the worst case for a snap.
            h.update(yaw, pitch, 0, 0, true, 0, 0, -5);
            float turned = Math.abs(BipHeading.wrapDegrees(h.yaw() - yaw));
            check(turned <= MAX_VISIBLE_TURN_PER_TICK, "yaw step " + turned + " at tick " + i);
            yaw = h.yaw();
            pitch = h.pitch();
        }
    }

    private static void takesTheShortWayAroundTheCircle() {
        BipHeading h = new BipHeading();
        // Facing 170 degrees, target at -170 degrees: the short turn is +20, never -340.
        double rad = Math.toRadians(-170 + 90);
        h.update(170f, 0f, 0, 0, true, Math.cos(rad) * 4, 0, Math.sin(rad) * 4);
        float turned = BipHeading.wrapDegrees(h.yaw() - 170f);
        check(turned > 0f && turned <= MAX_VISIBLE_TURN_PER_TICK, "short way, turned " + turned);
    }

    private static void settlesOnTheTargetAndStaysStill() {
        BipHeading h = new BipHeading();
        float yaw = 90f;
        float pitch = 0f;
        for(int i = 0; i < 60; i++) {
            h.update(yaw, pitch, 0, 0, true, 3, 1, 0);
            yaw = h.yaw();
            pitch = h.pitch();
        }
        // dx=3 -> atan2(0,3)=0 deg, minus 90 = -90.
        check(Math.abs(BipHeading.wrapDegrees(yaw + 90f)) <= BipHeading.DEAD_ZONE + 1e-3f, "settled yaw " + yaw);
        float settledYaw = yaw;
        float settledPitch = pitch;
        for(int i = 0; i < 40; i++) {
            h.update(yaw, pitch, 0, 0, true, 3, 1, 0);
            yaw = h.yaw();
            pitch = h.pitch();
        }
        check(yaw == settledYaw && pitch == settledPitch, "kept moving after settling");
    }

    private static void ignoresSmallTargetJitter() {
        BipHeading h = new BipHeading();
        float yaw = -90f;
        float pitch = 0f;
        for(int i = 0; i < 100; i++) {
            // The player's eye wobbles by a few centimetres as they walk.
            double wobble = (i % 2 == 0 ? 1 : -1) * 0.02;
            h.update(yaw, pitch, 0, 0, true, 4, wobble, wobble);
            check(h.yaw() == yaw, "yaw twitched on tiny target jitter at tick " + i);
        }
    }

    private static void doesNotSpinWhenTheTargetIsDirectlyAboveOrBelow() {
        BipHeading h = new BipHeading();
        java.util.Random random = new java.util.Random(7);
        float yaw = 33f;
        float pitch = 0f;
        for(int i = 0; i < 200; i++) {
            // Almost exactly above Bip: the horizontal offset is noise, so its angle is meaningless.
            double dx = (random.nextDouble() - 0.5) * 0.1;
            double dz = (random.nextDouble() - 0.5) * 0.1;
            h.update(yaw, pitch, 0, 0, true, dx, 2.0, dz);
            check(h.yaw() == 33f, "yaw spun to " + h.yaw() + " at tick " + i);
            yaw = h.yaw();
            pitch = h.pitch();
        }
        check(pitch <= BipHeading.MAX_PITCH + 1e-3f && pitch >= -BipHeading.MAX_PITCH - 1e-3f, "pitch out of range " + pitch);
    }

    private static void speedHoveringAroundTheThresholdDoesNotToggleTheHeading() {
        BipHeading h = new BipHeading();
        float yaw = 0f;
        float pitch = 0f;
        // Drift along +x while the player stands on the -z side: the two headings are ~90 degrees apart.
        double[] speeds = {0.04, 0.06, 0.045, 0.055, 0.05, 0.04, 0.06, 0.048};
        float previous = yaw;
        int reversals = 0;
        float lastTurn = 0f;
        for(int i = 0; i < 400; i++) {
            double speed = speeds[i % speeds.length];
            h.update(yaw, pitch, speed, 0, true, 0, 0, -4);
            float turn = BipHeading.wrapDegrees(h.yaw() - previous);
            if(i > 40 && turn * lastTurn < 0f) {
                reversals++;
            }
            if(turn != 0f) {
                lastTurn = turn;
            }
            previous = h.yaw();
            yaw = h.yaw();
            pitch = h.pitch();
        }
        check(reversals == 0, "heading reversed direction " + reversals + " times while hovering near the threshold");
    }

    private static void faceTheFlightPathThenReturnToThePlayerSmoothly() {
        BipHeading h = new BipHeading();
        float yaw = 0f;
        float pitch = 0f;
        // Fly along +x (yaw -90) while the player is on the +z side (yaw 0).
        for(int i = 0; i < 40; i++) {
            h.update(yaw, pitch, 0.2, 0, true, 0, 0, 5);
            yaw = h.yaw();
            pitch = h.pitch();
        }
        check(Math.abs(BipHeading.wrapDegrees(yaw + 90f)) <= BipHeading.DEAD_ZONE + 1e-3f, "should face flight path, yaw " + yaw);
        // Arrive and stop: turn back to the player without overshooting past it.
        float previous = yaw;
        for(int i = 0; i < 60; i++) {
            h.update(yaw, pitch, 0, 0, true, 0, 0, 5);
            float turned = BipHeading.wrapDegrees(h.yaw() - previous);
            check(turned >= -1e-3f, "overshot while turning back, step " + turned);
            check(Math.abs(turned) <= MAX_VISIBLE_TURN_PER_TICK, "turn back too abrupt " + turned);
            previous = h.yaw();
            yaw = h.yaw();
            pitch = h.pitch();
        }
        check(Math.abs(BipHeading.wrapDegrees(yaw)) <= BipHeading.DEAD_ZONE + 1e-3f, "should face player again, yaw " + yaw);
    }

    private static void ignoresNonFiniteInput() {
        BipHeading h = new BipHeading();
        h.update(40f, 5f, Double.NaN, 0, true, Double.NaN, 0, 1);
        check(h.yaw() == 40f && h.pitch() == 5f, "NaN input changed the heading");
    }

    private static void check(boolean ok, String message) {
        if(!ok) {
            failures++;
            System.out.println("FAIL: " + message);
        }
    }

    private BipHeadingTest() {
    }
}
