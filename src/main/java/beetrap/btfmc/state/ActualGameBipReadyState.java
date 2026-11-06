package beetrap.btfmc.state;

import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_POLLINATION_INITIATED;

import beetrap.btfmc.flower.Flower;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Game 5 — the actual game (Python activity {@code actual}), after the tutorial. No lessons and no
 * end: the player pollinates however they like, Bip is a buddy who reacts and helps when asked.
 */
public class ActualGameBipReadyState extends PollinationReadyState {

    static final String ACTIVITY = "actual";

    private final BipScript script = this.bipScript(ACTIVITY, () -> this.ticks);

    public ActualGameBipReadyState(BeetrapState parent, int stage) {
        super(parent, stage);
        if(stage == 0) {
            this.stateManager.setBipActivity(ACTIVITY);
            // The real game never ends by itself; the restart item takes the player back to the
            // stage menu whenever they want.
            for(ServerPlayerEntity player : this.world.getPlayers()) {
                this.interaction.giveRestartGameItemToPlayer(player);
            }
            this.script.then(
                    BipScript.pause(5),
                    BipScript.say("welcome"),
                    BipScript.say("goal"),
                    BipScript.say("help"));
        }
    }

    @Override
    public void tick() {
        this.script.tick();
        this.beeNestController.tickPollinationLines(this.ticks, this.pastPollinationLocations);
        ++this.ticks;
    }

    @Override
    public void onPlayerChat(ServerPlayerEntity player, String message) {
        this.script.onPlayerChat();
    }

    @Override
    public String describeCurrentTaskForAgent() {
        return "CONTEXT: this is the real game after the tutorial. The player leads. Answer their "
                + "questions and give a hint only if they ask or seem stuck.";
    }

    @Override
    public void onPlayerPollinate(Flower flower, Vec3d flowerMinecraftPosition) {
        this.hasNextState = true;
        this.pastPollinationLocations.add(flowerMinecraftPosition);
        Vec3d pl = this.computeAveragePastPollinationPositions();
        this.nextState = new ActualGameBipHappeningState(this, pl, this.stage, flower.getNumber());
        this.net.beetrapLog(BEETRAP_LOG_ID_POLLINATION_INITIATED, "");
    }

    @Override
    public boolean timeTravelAvailable() {
        return this.stage != 0;
    }
}
