package beetrap.btfmc;

/**
 * Feature flags for Bip behaviours.  Each flag is read once from the environment
 * at class-load time so you can toggle features without touching Java code.
 *
 * Add the following lines to run/.env (defaults shown — omitting a line = enabled):
 *
 *   BIP_IDLE_WANDER=true
 *   BIP_EMOTION_PARTICLES=true
 *   BIP_KICK_REACTION=true
 *
 * Set any flag to "false" (case-insensitive) to disable that feature.
 */
public final class BipFeatures {

    /** Bip gently wanders and glides when it has no queued commands. */
    public static final boolean IDLE_WANDER = flag("BIP_IDLE_WANDER");

    /** Hearts on pollination, angry puffs on kick, smoke when diversity drops. */
    public static final boolean EMOTION_PARTICLES = flag("BIP_EMOTION_PARTICLES");

    /** Instant yell + shove when the player punches Bip. */
    public static final boolean KICK_REACTION = flag("BIP_KICK_REACTION");

    private BipFeatures() {}

    private static boolean flag(String envKey) {
        String val = System.getenv(envKey);
        // Default is enabled; only "false" (any case) turns a flag off.
        return val == null || !val.equalsIgnoreCase("false");
    }
}
