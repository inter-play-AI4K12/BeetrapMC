package beetrap.btfmc.state;

import static beetrap.btfmc.BeetrapGame.AMOUNT_OF_FLOWERS_TO_WITHER_DEFAULT_MODE;
import static beetrap.btfmc.BeetrapGame.AMOUNT_OF_FLOWERS_TO_WITHER_DIVERSIFYING_MODE;
import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_POLLINATION_INITIATED;

import beetrap.btfmc.Beetrapfabricmc;
import beetrap.btfmc.flower.Flower;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Game 4 — How do we break the filter bubble? (Python activity {@code diversify}), waiting for the
 * player to pollinate. The garden starts already stuck in a bubble (15 similar flowers). In round 0
 * Bip sets the mission (diversity above a target), recalls why the bubble happens, and hints that
 * the pollen circle is the key. The garden mechanics match {@link DiversificationPollinationReadyState}.
 */
public class DiversifyBipPollinationReadyState extends PollinationReadyState {

    static final String ACTIVITY = "diversify";

    private final double targetDiversityScore;
    private final BipScript script = this.bipScript(ACTIVITY, () -> this.ticks);
    private final HintEscalator reminders = new HintEscalator(400, 800);

    /** Later rounds. */
    DiversifyBipPollinationReadyState(BeetrapState parent, int stage, double targetDiversityScore) {
        super(parent, stage);
        this.targetDiversityScore = targetDiversityScore;
    }

    /** Round 0: build the bubbled garden and set the mission. */
    public DiversifyBipPollinationReadyState(BeetrapState parent) {
        super(parent, 0);
        this.flowerManager.destroyAll();
        Arrays.fill(this.flowers, false);

        Random r = new Random();
        Flower f = this.flowerPool.getFlowerByNumber(r.nextInt(this.flowers.length));
        Flower[] g = this.getNFlowersNotInGardenClosestToF(f, 15);
        for(Flower h : g) {
            this.flowers[h.getNumber()] = true;
        }
        this.flowerManager.placeFlowerEntities(this);

        this.targetDiversityScore = (Math.ceil(this.computeDiversityScore() / 100.0) + 7) * 100;
        this.stateManager.setInitialDiversityScore(this.computeDiversityScore());
        this.stateManager.setDiversityTarget(this.targetDiversityScore);
        this.stateManager.setBipActivity(ACTIVITY);

        this.script.then(BipScript.pause(5));
        if(!Beetrapfabricmc.BIP_INTRODUCED) {
            Beetrapfabricmc.BIP_INTRODUCED = true;
            this.script.then(BipScript.ask("hello", "hello_nudge_", Map::of,
                    "CONTEXT: you just asked the player's name. Greet them by name in one line."));
        }
        this.script.then(
                BipScript.say("welcome"),
                BipScript.say("look"),
                BipScript.say("recall"),
                BipScript.say("goal", this::targetDetails),
                BipScript.say("goal_bar"),
                BipScript.say("hint_circle"),
                BipScript.say("go"));
    }

    private Map<String, Object> targetDetails() {
        return Map.of("target", (int)this.targetDiversityScore);
    }

    private void emitBeat(String beat) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("activity", ACTIVITY);
        details.put("beat", beat);
        this.stateManager.recordAgentEvent("activity_beat", details);
    }

    /** Lever flipped: switch the hive's ranking method and let Bip react. */
    static void applyLever(BeetrapState state, boolean powered, Runnable onOn, Runnable onOff) {
        state.usingDiversifyingRankingMethod = powered;
        state.amountOfFlowersToWither = powered
                ? AMOUNT_OF_FLOWERS_TO_WITHER_DIVERSIFYING_MODE
                : AMOUNT_OF_FLOWERS_TO_WITHER_DEFAULT_MODE;
        (powered ? onOn : onOff).run();
    }

    @Override
    public void tick() {
        if(this.interaction.rankingMethodLeverChanged()) {
            applyLever(this, this.interaction.isChangeRankingMethodLeverPowered(),
                    () -> this.emitBeat("lever_on"), () -> this.emitBeat("lever_off"));
        }
        boolean talking = this.script.tick();
        if(!talking) {
            int tier = this.reminders.tick();
            if(tier >= 0) {
                this.emitBeat("pollinate_reminder_" + tier);
            }
        }
        this.beeNestController.tickPollinationLines(this.ticks, this.pastPollinationLocations);
        ++this.ticks;
    }

    @Override
    public void onPlayerTargetNewEntity(ServerPlayerEntity player, boolean exists, int id) {
        super.onPlayerTargetNewEntity(player, exists, id);
        this.reminders.reset();
    }

    @Override
    public void onPlayerChat(ServerPlayerEntity player, String message) {
        this.script.onPlayerChat();
    }

    @Override
    public String describeCurrentTaskForAgent() {
        String context = this.script.chatContext();
        return context != null ? context
                : "CONTEXT: mission: get the diversity score above " + (int)this.targetDiversityScore
                        + " by breaking the bubble. Hint at the pollen circle (press B near it) "
                        + "and the lever before giving the answer.";
    }

    @Override
    public boolean timeTravelAvailable() {
        return true;
    }

    @Override
    public void onPlayerPollinate(Flower flower, Vec3d flowerMinecraftPosition) {
        this.hasNextState = true;
        this.pastPollinationLocations.add(flowerMinecraftPosition);
        Vec3d pl = this.computeAveragePastPollinationPositions();
        this.nextState = new DiversifyBipPollinationHappeningState(this, pl, this.stage,
                this.targetDiversityScore);
        this.net.beetrapLog(BEETRAP_LOG_ID_POLLINATION_INITIATED, "");
    }

    @Override
    public float getGardenInformationBossBarPercent() {
        return (float)(this.computeDiversityScore() / this.targetDiversityScore);
    }
}
