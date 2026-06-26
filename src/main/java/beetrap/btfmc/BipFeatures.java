package beetrap.btfmc;

/**
 * Feature flags for Bip behaviours.  Each flag is read once from the environment
 * at class-load time so you can toggle features without touching Java code.
 *
 * Add the following lines to run/.env (defaults shown — omitting a line = enabled):
 *
 *   BIP_IDLE_WANDER=true
 *   BIP_LOOK_AT_PLAYER=true
 *   BIP_EMOTION_PARTICLES=true
 *   BIP_KICK_REACTION=true
 *   BIP_TIERED_HINTS=true
 *   BIP_TIME_TRAVEL_HANDOVER=true
 *   BIP_INTERACTIVE_QA=true
 *
 * Set any flag to "false" (case-insensitive) to disable that feature.
 */
public final class BipFeatures {

    /** Bip gently hovers near the player when it has no queued commands. */
    public static final boolean IDLE_WANDER = flag("BIP_IDLE_WANDER");

    /** Bip turns to face the player whenever it is not flying somewhere. */
    public static final boolean LOOK_AT_PLAYER = flag("BIP_LOOK_AT_PLAYER");

    /** Hearts on pollination, angry puffs on kick, smoke when diversity drops. */
    public static final boolean EMOTION_PARTICLES = flag("BIP_EMOTION_PARTICLES");

    /** Instant yell + shove when the player punches Bip. */
    public static final boolean KICK_REACTION = flag("BIP_KICK_REACTION");

    /**
     * Hints escalate (nudge → hint → answer) only while the player lingers, instead of
     * firing full instructions on a fixed timer. Off = the old single up-front instruction.
     */
    public static final boolean TIERED_HINTS = flag("BIP_TIERED_HINTS");

    /**
     * Bip flies to the player and announces the time-travel clocks when they are first given,
     * instead of the clocks appearing silently. Off = silent give with a short text cue.
     */
    public static final boolean TIME_TRAVEL_HANDOVER = flag("BIP_TIME_TRAVEL_HANDOVER");

    /**
     * Bip asks the player a reflective question (e.g. "why did this flower die?") and waits for a
     * chat answer before moving on. Mirrors the Python BIP_INTERACTIVE_QA flag so the mod knows
     * whether a question was actually asked — when off, Bip just states the answer and the debrief
     * does not pause for a reply. Keep this value in sync with the Python feature of the same name.
     */
    public static final boolean INTERACTIVE_QA = flag("BIP_INTERACTIVE_QA");

    private BipFeatures() {}

    private static boolean flag(String envKey) {
        // run/.env is loaded into System properties (see Beetrapfabricmc.loadEnv), so check
        // there first; fall back to a real OS environment variable if present.
        String val = System.getProperty(envKey);
        if(val == null) {
            val = System.getenv(envKey);
        }
        // Default is enabled; only "false" (any case) turns a flag off.
        return val == null || !val.equalsIgnoreCase("false");
    }
}
