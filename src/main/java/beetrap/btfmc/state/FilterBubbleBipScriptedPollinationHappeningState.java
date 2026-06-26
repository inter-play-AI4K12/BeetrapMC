package beetrap.btfmc.state;

import static beetrap.btfmc.BeetrapGame.AMOUNT_OF_BUDS_RANKED;
import static beetrap.btfmc.BeetrapGame.AMOUNT_OF_BUDS_TO_PLACE_DEFAULT_MODE;

import beetrap.btfmc.BipFeatures;
import beetrap.btfmc.flower.Flower;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

/**
 * Pollinate-the-Garden activity. Java drives the timing and reports semantic beats
 * ("buds_ranked", "new_flowers", "why_dead", "clock_handoff", plus "time_travel" on rewind); the
 * Python service owns what Bip actually says/does for each beat (scripted vs improv per flags).
 *
 * After the bloom the first round runs a small <em>debrief phase machine</em> rather than firing
 * every line on a fixed timer: Bip points out the new flowers, asks why one died, then <em>waits
 * for the player to answer in chat</em> (so his refinement lands before he changes the subject),
 * and only then flies over and hands across the clocks. Later rounds skip the debrief entirely.
 */
public class FilterBubbleBipScriptedPollinationHappeningState extends BeetrapState {

    private static final int BUDS_TICK = 25;              // right after buds sprout + are numbered
    // Must stay AFTER the pollen-fly window (BeeNestController MAX_CIRCLE_TICKS = 220): the bloom
    // removes the bud entities, and the animation dereferences them until then.
    private static final int BLOOM_TICK = 240;            // flowers bloom / some wither (~12s)

    // Debrief pacing (ticks since the phase began; 20 ticks = 1s).
    private static final int POINT_BEFORE_ASK = 90;       // admire new flowers, then ask why one died
    private static final int ANSWER_NUDGE_DELAY = 180;    // ~9s of silence before Bip pressures for a guess
    private static final int MAX_ANSWER_NUDGES = 2;       // pressure twice, then move on rather than stall
    private static final int NO_QA_PAUSE = 120;           // when Q&A is off Bip just states the cause
    private static final int REFINE_DELAY = 40;           // brief beat so the personalized reply leads the clocks
    private static final int HANDOFF_AFTER_CLOCKS = 40;   // once clocks land, brief beat then enable time travel
    private static final int HANDOFF_MAX = 240;           // fallback: give + proceed if no clocks command arrives

    private static final int PHASE_PRE = 0;       // up to the bloom
    private static final int PHASE_POINT = 1;     // pointed out the new flowers; about to ask why
    private static final int PHASE_ASK = 2;       // asked why a flower died; awaiting the answer
    private static final int PHASE_REFINE = 3;    // answer in; letting Bip's refinement play
    private static final int PHASE_HANDOFF = 4;   // handing the clocks across
    private static final int PHASE_DONE = 5;

    private final Vec3d pollinationCenter;
    private final int stage;
    private Flower[] newFlowerCandidates;
    private Flower[] newFlowers;
    private int ticks;
    private boolean active;
    private boolean ended;
    private BeetrapState nextState;

    private int phase = PHASE_PRE;
    private long phaseEnteredTick;
    private boolean playerAnswered;
    private long clocksGivenTick = -1;
    private long lastPromptTick;   // when Bip last asked/pressed for the why-it-died answer
    private int answerNudges;       // how many times Bip has pressured for a guess

    private int rankOneFlowerId = -1;
    private int newFlowerId = -1;
    private int witheredFlowerId = -1;

    public FilterBubbleBipScriptedPollinationHappeningState(BeetrapState state,
            Vec3d pollinationCenter, int stage) {
        super(state);
        this.pollinationCenter = pollinationCenter;
        this.active = true;
        this.stage = stage;
    }

    /** Report a semantic beat; Python decides Bip's words/movement for it. */
    private void emitBeat(String beat) {
        this.stateManager.recordAgentEvent("activity_beat",
                Map.of("activity", "pollinate", "beat", beat));
    }

    private void emitBeat(String beat, int flowerId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("activity", "pollinate");
        details.put("beat", beat);
        if (flowerId >= 0) {
            details.put("flower_id", flowerId);
        }
        this.stateManager.recordAgentEvent("activity_beat", details);
    }

    private void onTick0() {
        if (this.ticks != 0) return;
        this.beeNestController.startPollination(this.pollinationCenter);
    }

    private void tickGrowBuds() {
        this.newFlowerCandidates = this.findFlowersWithinRadius(
                this.pollinationCenter, this.pollinationCircleRadius,
                AMOUNT_OF_BUDS_TO_PLACE_DEFAULT_MODE);
        this.flowerManager.placeBuds(this, this.newFlowerCandidates);
    }

    private boolean isNewFlowerCandidate(Flower f) {
        for (Flower g : this.newFlowerCandidates) {
            if (f.equals(g)) return true;
        }
        return false;
    }

    private void tickRankBuds() {
        FallingBlockEntity[] fbe = this.usingDiversifyingRankingMethod
                ? this.flowerManager.findAllFlowerEntitiesWithinRSortedByMostDistanceToCenter(
                        this.pollinationCenter, this.pollinationCircleRadius)
                : this.flowerManager.findAllFlowerEntitiesWithinRSortedByLeastDistanceToCenter(
                        this.pollinationCenter, this.pollinationCircleRadius);

        this.newFlowers = new Flower[AMOUNT_OF_BUDS_RANKED];
        int r = 0;
        for (int i = 0; i < fbe.length && r < AMOUNT_OF_BUDS_RANKED; ++i) {
            Flower f = this.flowerManager.getFlowerByEntityId(this, fbe[i].getId());
            if (!this.isNewFlowerCandidate(f)) continue;
            fbe[i].setCustomName(Text.of(String.valueOf(r + 1)));
            fbe[i].setCustomNameVisible(true);
            this.newFlowers[r] = f;
            if (r == 0) {
                this.rankOneFlowerId = f.getNumber();
            }
            ++r;
        }
        this.stateManager.recordBudsRanked(this.newFlowers, this.usingDiversifyingRankingMethod,
                this.pollinationCenter, this.pollinationCircleRadius);
    }

    private void onTick20() {
        if (this.ticks != 20) return;
        this.tickGrowBuds();
        this.tickRankBuds();
    }

    /** Once the buds have visibly sprouted and been numbered 1-5, explain that the numbers ARE
     * the ranking. A single beat (not two) so the line plays before the bloom without backing up. */
    private void onBudsRanked() {
        if (this.ticks != BUDS_TICK) return;
        if (this.stage == 0) {
            this.emitBeat("buds_ranked", this.rankOneFlowerId);
        }
    }

    private void tickPlaceNewFlowers() {
        this.flowerManager.removeFlowerEntities(this.newFlowerCandidates);
        this.flowerManager.placeFlowerEntities(this, this.newFlowers);
        for (Flower f : this.newFlowers) {
            if (f == null) return;
            this.setFlower(f.getNumber(), true);
            if (this.newFlowerId < 0) {
                this.newFlowerId = f.getNumber();
            }
        }
    }

    private void tickWitherFlowers() {
        FallingBlockEntity[] fbe = this.flowerManager
                .findAllFlowerEntitiesWithinRSortedByLeastDistanceToCenter(
                        this.pollinationCenter, Double.POSITIVE_INFINITY);
        int r = 0;
        for (int i = fbe.length - 1; i >= 0 && r < this.amountOfFlowersToWither; --i) {
            Flower f = this.flowerManager.getFlowerByEntityId(this, fbe[i].getId());
            if (f.hasWithered()) continue;
            f.setWithered(true);
            this.flowerManager.placeFlowerEntity(this, f);
            if (r == 0) {
                this.witheredFlowerId = f.getNumber();
            }
            ++r;
        }
    }

    /** The bloom: new flowers replace the buds, some flowers wither. */
    private void onBloom() {
        if (this.ticks != BLOOM_TICK) return;
        this.tickPlaceNewFlowers();
        this.tickWitherFlowers();
        if (this.stage == 0) {
            this.emitBeat("new_flowers", this.newFlowerId);
            this.enterPhase(PHASE_POINT);
        } else {
            // Later rounds: no debrief, end promptly once the garden has settled.
            this.finishRound();
        }
    }

    private void enterPhase(int phase) {
        this.phase = phase;
        this.phaseEnteredTick = this.ticks;
    }

    /** First-round debrief: point out the deaths, ask why, wait for the answer, hand over clocks. */
    private void tickDebrief() {
        if (this.phase == PHASE_PRE || this.phase == PHASE_DONE) return;
        long since = this.ticks - this.phaseEnteredTick;
        switch (this.phase) {
            case PHASE_POINT:
                if (since >= POINT_BEFORE_ASK) {
                    if (this.witheredFlowerId >= 0) {
                        this.emitBeat("why_dead", this.witheredFlowerId);
                    }
                    this.lastPromptTick = this.ticks;
                    this.enterPhase(PHASE_ASK);
                }
                break;
            case PHASE_ASK:
                if (!BipFeatures.INTERACTIVE_QA) {
                    // Bip stated the cause rather than asking; brief pause, then move on.
                    if (since >= NO_QA_PAUSE) {
                        this.enterPhase(PHASE_REFINE);
                    }
                    break;
                }
                if (this.playerAnswered) {
                    // They responded. The answer is already on its way to the LLM (chat path) for
                    // a personalized reply; let that lead before the clocks.
                    this.enterPhase(PHASE_REFINE);
                    break;
                }
                // No answer yet: wait, then pressure for a guess; after a couple of nudges, move
                // on rather than stalling the game forever.
                if (this.ticks - this.lastPromptTick >= ANSWER_NUDGE_DELAY) {
                    if (this.answerNudges < MAX_ANSWER_NUDGES) {
                        this.emitBeat("why_dead_nudge");
                        this.answerNudges++;
                        this.lastPromptTick = this.ticks;
                    } else {
                        this.enterPhase(PHASE_REFINE);
                    }
                }
                break;
            case PHASE_REFINE:
                // If they answered, hold briefly so the personalized reply (already queued ahead of
                // us on the single command channel) plays before the clock hand-off.
                if (since >= (this.playerAnswered ? REFINE_DELAY : 0)) {
                    this.emitBeat("clock_handoff");
                    this.enterPhase(PHASE_HANDOFF);
                }
                break;
            case PHASE_HANDOFF:
                // Hand-off ON: the give_clocks command (sequenced after Bip's spoken line) drops
                // the clocks exactly when he says "take these". Hand-off OFF: no beat fires, so
                // give them silently.
                if (!BipFeatures.TIME_TRAVEL_HANDOVER && since == 1) {
                    this.giveClocks();
                }
                if (this.clocksGivenTick < 0 && this.stateManager.timeTravelItemsGiven()) {
                    this.clocksGivenTick = this.ticks;
                }
                if (this.clocksGivenTick >= 0) {
                    // Enable time travel a beat after the clocks actually land — independent of how
                    // long the LLM/voice took, so there's never a big gap or an early jump.
                    if (this.ticks - this.clocksGivenTick >= HANDOFF_AFTER_CLOCKS) {
                        this.finishRound();
                    }
                } else if (since >= HANDOFF_MAX) {
                    this.giveClocks(); // fallback: the clocks command never arrived
                    this.finishRound();
                }
                break;
            default:
                break;
        }
    }

    private void giveClocks() {
        // Idempotent at the state-manager level, shared with the agent's give_clocks command, so
        // the player gets the clocks exactly once however they were triggered.
        this.stateManager.giveTimeTravelItemsToAllPlayers();
    }

    private boolean activityShouldEnd() {
        return this.computeDiversityScore() < this.stateManager.getInitialDiversityScore() / 2;
    }

    /** Wrap up the round: give clocks (idempotent), arm the time-travel beat, pick the next state. */
    private void finishRound() {
        if (this.ended) return;
        this.ended = true;
        this.enterPhase(PHASE_DONE);
        this.giveClocks();
        if (this.activityShouldEnd()) {
            this.stateManager.endActivity();
            this.nextState = new TimeTravelableBeetrapState(this);
        } else {
            this.nextState = new FilterBubbleBipScriptedPollinationReadyState(this, this.stage + 1);
        }
        // From here the player can rewind the garden; let Bip react the first time they do.
        this.stateManager.armBipTimeTravelNarration();
        this.setBeeNestMinecraftPosition(this.beeNestController.getBeeNestPosition());
        this.active = false;
    }

    @Override
    public void tick() {
        if (!this.active) return;
        this.onTick0();
        this.beeNestController.tickMovementAnimation(this.ticks);
        this.onTick20();
        this.beeNestController.tickCircle(this.ticks, this.pollinationCircleRadius);
        this.beeNestController.tickSpawnPollensThatFlyTowardsNest(
                this.ticks, this.flowerManager, this.newFlowerCandidates);
        this.onBudsRanked();
        this.onBloom();
        this.tickDebrief();
        this.beeNestController.tickPollinationLines(this.ticks, this.pastPollinationLocations);
        ++this.ticks;
    }

    @Override
    public boolean hasNextState() { return this.ended; }

    @Override
    public BeetrapState getNextState() { return this.nextState; }

    @Override
    public void onPlayerTargetNewEntity(ServerPlayerEntity player, boolean exists, int id) {
        super.onPlayerTargetNewEntity(player, false, id);
    }

    @Override
    public void onPlayerChat(ServerPlayerEntity player, String message) {
        // The player's reply to "why did this flower die?" — note it so the debrief can stop
        // waiting and let Bip refine their answer before handing over the clocks.
        if (this.phase == PHASE_ASK) {
            this.playerAnswered = true;
        }
    }

    @Override
    public boolean timeTravelAvailable() { return false; }
}
