package beetrap.btfmc.state;

import static beetrap.btfmc.state.ActualGameBipReadyState.ACTIVITY;

import net.minecraft.util.math.Vec3d;

/**
 * Game 5 — one pollination round of the actual game. Bip reacts to what changed (an improvised line
 * that reads the live game state). The game never ends on its own here: it's the player's garden.
 */
public class ActualGameBipHappeningState extends BipPollinationHappeningState {

    public ActualGameBipHappeningState(BeetrapState state, Vec3d pollinationCenter, int stage,
            int pollinatedFlowerId) {
        super(state, pollinationCenter, stage, pollinatedFlowerId, ACTIVITY);
    }

    @Override
    protected void onBloom(boolean ending) {
        this.script.then(BipScript.say("round_react"));
    }

    @Override
    protected boolean activityShouldEnd() {
        return false;
    }

    @Override
    protected BeetrapState nextRoundState() {
        return new ActualGameBipReadyState(this, this.stage + 1);
    }
}
