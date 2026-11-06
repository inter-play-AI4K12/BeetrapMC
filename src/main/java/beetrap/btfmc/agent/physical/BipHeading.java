package beetrap.btfmc.agent.physical;

/**
 * Decides where Bip's head points and how fast it turns. Plain math with no Minecraft types, so the
 * rules can be checked by {@code BipHeadingTest} without starting the game.
 *
 * <p>Why this exists: Bip's rotation used to have several writers. The bee's own move control
 * snapped the yaw toward its flight direction (up to 90 degrees in one tick), the look control and
 * body control pulled the head and pitch back each tick, and our "face the player" code overwrote
 * all of it. Which writer won depended on a hard cut-off on Bip's speed, so the head flicked
 * whenever his speed crossed that line or a move started. Now this class is the only writer (see
 * {@code BipBeeEntity}) and it follows three rules:
 * <ul>
 *   <li>A turn is never larger than {@link #MAX_YAW_STEP} degrees per tick, so nothing can snap.</li>
 *   <li>Switching between "follow the flight path" and "look at the target" uses two speeds
 *       ({@link #TRAVEL_ENTER_SPEED_SQ} and {@link #TRAVEL_EXIT_SPEED_SQ}), so a speed hovering near
 *       one line cannot toggle it.</li>
 *   <li>Tiny corrections inside {@link #DEAD_ZONE} are ignored, and a target that is almost directly
 *       above or below Bip (where its compass angle is meaningless) never changes the yaw.</li>
 * </ul>
 */
public final class BipHeading {

    /** Largest yaw change in one tick, in degrees (about 280 degrees per second). */
    public static final float MAX_YAW_STEP = 14.0f;
    /** Largest pitch change in one tick, in degrees. */
    public static final float MAX_PITCH_STEP = 8.0f;
    /** Bip never tilts his head further than this up or down. */
    public static final float MAX_PITCH = 35.0f;
    /** Yaw errors smaller than this are left alone so the head stays still. */
    public static final float DEAD_ZONE = 1.5f;
    /** Pitch errors smaller than this are left alone. */
    public static final float PITCH_DEAD_ZONE = 1.0f;
    /** Squared horizontal speed (blocks per tick) above which Bip starts facing where he flies. */
    public static final double TRAVEL_ENTER_SPEED_SQ = 0.0025;
    /** Squared horizontal speed below which he stops doing so; lower than the enter speed on purpose. */
    public static final double TRAVEL_EXIT_SPEED_SQ = 0.0006;
    /** Closer than this horizontally, the angle to a target is noise, so the yaw is kept. */
    public static final double MIN_LOOK_DISTANCE = 0.35;
    /** How much of the remaining angle is covered each tick (before the step limit applies). */
    private static final float EASING = 0.35f;

    private boolean travelling;
    private float yaw;
    private float pitch;

    /** Yaw to apply this tick, in degrees. */
    public float yaw() {
        return this.yaw;
    }

    /** Pitch to apply this tick, in degrees. */
    public float pitch() {
        return this.pitch;
    }

    /**
     * Computes the next yaw and pitch (read them with {@link #yaw()} and {@link #pitch()}).
     *
     * @param currentYaw   Bip's yaw now
     * @param currentPitch Bip's pitch now
     * @param velX         horizontal velocity, blocks per tick
     * @param velZ         horizontal velocity, blocks per tick
     * @param hasTarget    whether there is something to look at when he is not flying
     * @param dx           vector from Bip's eyes to the target (ignored when there is no target)
     * @param dy           vector from Bip's eyes to the target
     * @param dz           vector from Bip's eyes to the target
     */
    public void update(float currentYaw, float currentPitch, double velX, double velZ,
            boolean hasTarget, double dx, double dy, double dz) {
        this.yaw = currentYaw;
        this.pitch = currentPitch;
        if(!isFinite(currentYaw, currentPitch, velX, velZ) || (hasTarget && !isFinite(dx, dy, dz))) {
            return;
        }

        double speedSq = velX * velX + velZ * velZ;
        if(this.travelling) {
            if(speedSq < TRAVEL_EXIT_SPEED_SQ) {
                this.travelling = false;
            }
        } else if(speedSq > TRAVEL_ENTER_SPEED_SQ) {
            this.travelling = true;
        }

        float desiredYaw = currentYaw;
        float desiredPitch = 0.0f;
        if(this.travelling) {
            desiredYaw = (float)(Math.toDegrees(Math.atan2(velZ, velX)) - 90.0);
        } else if(hasTarget) {
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if(horizontal >= MIN_LOOK_DISTANCE) {
                desiredYaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            }
            desiredPitch = (float)(-Math.toDegrees(Math.atan2(dy, Math.max(horizontal, MIN_LOOK_DISTANCE))));
            desiredPitch = clamp(desiredPitch, -MAX_PITCH, MAX_PITCH);
        }

        this.yaw = currentYaw + ease(wrapDegrees(desiredYaw - currentYaw), DEAD_ZONE, MAX_YAW_STEP);
        this.pitch = currentPitch + ease(desiredPitch - currentPitch, PITCH_DEAD_ZONE, MAX_PITCH_STEP);
    }

    /** One eased, rate-limited step toward an error of {@code delta} degrees. */
    private static float ease(float delta, float deadZone, float maxStep) {
        if(Math.abs(delta) <= deadZone) {
            return 0.0f;
        }
        return clamp(delta * EASING, -maxStep, maxStep);
    }

    /** Maps any angle to the range [-180, 180). */
    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if(wrapped >= 180.0f) {
            wrapped -= 360.0f;
        }
        if(wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isFinite(double... values) {
        for(double value : values) {
            if(Double.isNaN(value) || Double.isInfinite(value)) {
                return false;
            }
        }
        return true;
    }
}
