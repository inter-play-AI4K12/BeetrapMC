package beetrap.btfmc.state;

import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_POLLINATION_INITIATED;

import beetrap.btfmc.BipFeatures;
import beetrap.btfmc.Beetrapfabricmc;
import beetrap.btfmc.flower.Flower;
import java.util.Map;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * "How does the garden recommend flowers?" with Bip as an ask-first guide (Python activity
 * {@code recommend}). Round 0 opens with Bip calling the player to the beehive and asking why some
 * flowers sit close together; pollination stays locked until that exchange is over. Later rounds
 * are plain "pollinate when ready". The per-round debrief lives in
 * {@link RecommendBipPollinationHappeningState}.
 */
public class RecommendBipPollinationReadyState extends PollinationReadyState {

    static final String ACTIVITY = "recommend";

    private static final int INTRO_TICK = 5;

    private static final int PHASE_INTRO = 0;       // greeting being spoken
    private static final int PHASE_FIND_HIVE = 1;   // Bip at the hive, waiting for the player to look
    private static final int PHASE_DISTANCE = 2;    // asked why flowers sit close together
    private static final int PHASE_EXPLAIN = 3;     // explanation being spoken
    private static final int PHASE_READY = 4;       // pollination unlocked

    private final BipSpeechSettle speech = new BipSpeechSettle(20, 300);
    private final BipQuestion distanceQuestion = new BipQuestion(
            tier -> this.emitBeat("distance_nudge_" + tier));
    // Nudges toward the hive (~20s, ~40s) and, once unlocked, toward pollinating (~15s, ~30s).
    private final HintEscalator hiveHints = new HintEscalator(400, 800);
    private final HintEscalator pollinateHints = new HintEscalator(300, 600);

    private int phase;
    private boolean lookedAtHive;

    public RecommendBipPollinationReadyState(BeetrapState parent, int stage) {
        super(parent, stage);
        this.phase = stage == 0 ? PHASE_INTRO : PHASE_READY;
        if(stage == 0) {
            this.stateManager.setBipActivity(ACTIVITY);
        }
    }

    private void emitBeat(String beat) {
        this.stateManager.recordAgentEvent("activity_beat",
                Map.of("activity", ACTIVITY, "beat", beat));
    }

    private boolean unlocked() {
        return this.phase == PHASE_READY;
    }

    private void askAboutDistance() {
        if(BipFeatures.INTERACTIVE_QA) {
            this.emitBeat("distance_ask");
            this.distanceQuestion.begin(this.ticks);
            this.phase = PHASE_DISTANCE;
        } else {
            this.explainDistance();
        }
    }

    private void explainDistance() {
        this.emitBeat("distance_explain");
        this.speech.begin(this.ticks);
        this.phase = PHASE_EXPLAIN;
    }

    @Override
    public void tick() {
        boolean busy = this.agentBusy();
        switch(this.phase) {
            case PHASE_INTRO -> {
                if(this.ticks == INTRO_TICK) {
                    // Greet only the first time Bip meets the player this run; otherwise jump in.
                    this.emitBeat(Beetrapfabricmc.BIP_INTRODUCED ? "intro_returning" : "intro");
                    Beetrapfabricmc.BIP_INTRODUCED = true;
                    this.speech.begin(this.ticks);
                }
                if(this.speech.tick(this.ticks, busy)) {
                    if(this.lookedAtHive) {
                        this.askAboutDistance();
                    } else {
                        this.emitBeat("find_hive");
                        this.phase = PHASE_FIND_HIVE;
                    }
                }
            }
            case PHASE_FIND_HIVE -> {
                if(this.lookedAtHive) {
                    this.askAboutDistance();
                    break;
                }
                int tier = this.hiveHints.tick();
                if(tier >= 0) {
                    this.emitBeat("find_hive_nudge_" + tier);
                }
            }
            case PHASE_DISTANCE -> {
                if(this.distanceQuestion.tick(this.ticks, busy, this.consumeConversationDone())) {
                    this.explainDistance();
                }
            }
            case PHASE_EXPLAIN -> {
                if(this.speech.tick(this.ticks, busy)) {
                    this.emitBeat("go_pollinate");
                    this.phase = PHASE_READY;
                }
            }
            case PHASE_READY -> {
                int tier = this.pollinateHints.tick();
                if(tier >= 0) {
                    this.emitBeat("pollinate_reminder_" + tier);
                }
            }
            default -> { }
        }

        this.beeNestController.tickPollinationLines(this.ticks, this.pastPollinationLocations);
        ++this.ticks;
    }

    @Override
    public void onPlayerTargetNewEntity(ServerPlayerEntity player, boolean exists, int id) {
        if(this.beeNestController.getBeeNest().getId() == id) {
            this.lookedAtHive = true;
        }
        // While locked, never treat a flower as pollinatable (keeps the nest item from equipping).
        super.onPlayerTargetNewEntity(player, this.unlocked() && exists, id);
        this.pollinateHints.reset();
    }

    @Override
    public void onPlayerChat(ServerPlayerEntity player, String message) {
        if(this.phase == PHASE_DISTANCE) {
            this.distanceQuestion.onPlayerChat(this.ticks);
        }
    }

    @Override
    public void onPlayerPollinate(Flower flower, Vec3d flowerMinecraftPosition) {
        if(!this.unlocked()) {
            return;
        }
        this.hasNextState = true;
        this.pastPollinationLocations.add(flowerMinecraftPosition);
        Vec3d pl = this.computeAveragePastPollinationPositions();
        this.nextState = new RecommendBipPollinationHappeningState(
                this, pl, this.stage, flower.getNumber());
        this.net.beetrapLog(BEETRAP_LOG_ID_POLLINATION_INITIATED, "");
    }

    @Override
    public String describeCurrentTaskForAgent() {
        if(this.phase != PHASE_DISTANCE) {
            return null;
        }
        return RecommendBipPollinationHappeningState.askContext(
                "Flowers are placed so that ones with many similar traits (color, smell, nectar, "
                        + "water) sit close together, and very different ones sit far apart.");
    }

    @Override
    public boolean timeTravelAvailable() {
        return this.unlocked() && this.stage > 0;
    }
}
