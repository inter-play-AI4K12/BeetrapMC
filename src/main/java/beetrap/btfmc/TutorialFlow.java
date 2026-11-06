package beetrap.btfmc;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

/**
 * Base 2's continuous experience: Introduction -> Tutorial (games 1-4, one after another) -> the
 * stage menu (replay any tutorial stage, or play the actual game, game 5).
 *
 * Each stage runs in a fresh garden: when one ends, a {@link StagePortal} opens in front of the
 * player; walking into it restarts the game (keeping Bip's session) and the menu state starts
 * whatever {@link #pendingActivity} says. The restart item does the same, so it's a safe fallback.
 * Static because it must survive the game being torn down and rebuilt between stages.
 */
public final class TutorialFlow {

    // Activity numbers, matching MenuState's menu order.
    public static final int OBSERVE = 0;
    public static final int FILTER_BUBBLE = 1;
    public static final int RECOMMEND = 2;
    public static final int DIVERSIFY = 3;
    public static final int ACTUAL_GAME = 4;
    public static final int NONE = -1;

    /** What the menu should start next; NONE = show the stage menu (or begin the tutorial). */
    public static int pendingActivity = NONE;
    public static int currentActivity = NONE;
    public static boolean tutorialComplete;

    private static StagePortal portal;
    private static boolean advanceRequested;

    private TutorialFlow() {
        throw new AssertionError();
    }

    /** /game new: start over from the introduction. */
    public static void reset() {
        pendingActivity = NONE;
        currentActivity = NONE;
        tutorialComplete = false;
        advanceRequested = false;
        removePortal();
    }

    /** A stage begins. During the tutorial a restart replays it; afterwards a restart goes to the menu. */
    public static void onActivityStarted(int activity) {
        currentActivity = activity;
        pendingActivity = tutorialComplete ? NONE : activity;
    }

    /**
     * The current stage is done: decide where the portal leads.
     * @return true if this just finished the whole tutorial.
     */
    public static boolean onStageComplete() {
        if(tutorialComplete || currentActivity == NONE) {
            pendingActivity = NONE;
            return false;
        }
        if(currentActivity >= DIVERSIFY) {
            tutorialComplete = true;
            pendingActivity = NONE;
            return true;
        }
        pendingActivity = currentActivity + 1;
        return false;
    }

    public static Vec3d openPortal(ServerWorld world, ServerPlayerEntity player) {
        removePortal();
        portal = StagePortal.build(world, player);
        return portal.center();
    }

    public static boolean portalOpen() {
        return portal != null;
    }

    /** Call every game tick: walking into the portal requests the move to the next stage. */
    public static void tickPortal(ServerWorld world) {
        if(portal == null) {
            return;
        }
        for(ServerPlayerEntity player : world.getPlayers()) {
            if(portal.contains(player)) {
                removePortal();
                advanceRequested = true;
                return;
            }
        }
    }

    /** True once per portal entry; the game handler then restarts into the next stage. */
    public static boolean consumeAdvanceRequest() {
        boolean requested = advanceRequested;
        advanceRequested = false;
        return requested;
    }

    public static void removePortal() {
        if(portal != null) {
            portal.remove();
            portal = null;
        }
    }
}
