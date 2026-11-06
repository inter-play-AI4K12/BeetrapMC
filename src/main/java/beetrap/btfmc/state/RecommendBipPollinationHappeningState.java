package beetrap.btfmc.state;

import static beetrap.btfmc.BeetrapGame.AMOUNT_OF_BUDS_RANKED;
import static beetrap.btfmc.BeetrapGame.AMOUNT_OF_BUDS_TO_PLACE_DEFAULT_MODE;
import static beetrap.btfmc.state.RecommendBipPollinationReadyState.ACTIVITY;

import beetrap.btfmc.BipFeatures;
import beetrap.btfmc.flower.Flower;
import beetrap.btfmc.networking.ShowMultipleChoiceScreenS2CPayload;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

/**
 * One pollination round of the Recommend activity. The garden mechanics match
 * {@link RecommendationSystemPollinationHappeningState} (buds sprout in the pollen circle, are
 * ranked by distance to the hive, the top ones bloom, the farthest flowers wither). On top of that
 * Bip runs a short ask-first debrief, as a queue of {@link Step}s:
 * <ul>
 *   <li>Round 0: the bud numbers, then what goes into the hive (while the buds are still up),
 *   then the clock hand-off after the bloom.</li>
 *   <li>Round 1: the pollen circle (while it is still drawn), then why a flower withered.</li>
 *   <li>Round 2: a Bip-led multiple-choice check (Bip asks aloud, the choice screen shows, Bip
 *   reacts).</li>
 *   <li>When the garden has lost half its diversity: why the colors faded, then the reveal.</li>
 * </ul>
 * Questions asked while the buds/circle are visible pause the animation clock just before the
 * bloom, so what Bip is talking about doesn't vanish while the player is still answering.
 */
public class RecommendBipPollinationHappeningState extends BeetrapState {

    private static final int BUDS_TICK = 25;      // buds sprout + are numbered at 20
    private static final int HOLD_TICK = 200;     // animation waits here while a question runs
    // Must stay AFTER the pollen-fly window (BeeNestController MAX_CIRCLE_TICKS = 220): the bloom
    // removes the bud entities, and the animation dereferences them until then.
    private static final int BLOOM_TICK = 240;
    private static final int HANDOFF_AFTER_CLOCKS = 40;
    private static final int HANDOFF_MAX = 240;
    private static final int QUIZ_ROUND = 2;

    /** The original activity's five check questions: id, question, options, correct option. */
    private record QuizQuestion(String id, String question, String[] options, int correct) { }

    private static final QuizQuestion[] QUIZ = {
            new QuizQuestion("user_profile", "What goes into the User Profile(Beehive)?",
                    new String[]{"The flowers pollinated by me.", "The flowers in the garden.",
                            "The flowers I didn't pollinate."}, 0),
            new QuizQuestion("flower_buds_on_ground",
                    "What do the flower buds on the ground represent?",
                    new String[]{"Available flowers to grow in the garden",
                            "The flowers that I like", "The flowers that I dislike"}, 0),
            new QuizQuestion("numbers_above_flower_buds",
                    "What do numbers above flower buds represent?",
                    new String[]{"The ranking of flowers' height.",
                            "The value of a flower feature",
                            "The ranking of the distance between a flower and the beehive"}, 2),
            new QuizQuestion("pollen_circle", "What does the pollen circle represent?",
                    new String[]{"The range for me the fly.",
                            "The range for new flowers to grow.",
                            "The range for flowers to die out."}, 1),
            new QuizQuestion("flowers_located_in_garden",
                    "How are the flowers located in the garden?",
                    new String[]{"Flowers with the same colors are close together.",
                            "Flowers with multiple similar features are close together.",
                            "Flowers with the same sweetness values are closer together."}, 1),
    };

    /** Ground truth handed to the chat LLM while each question is open (never read out). */
    private static final Map<String, String> FACTS = Map.of(
            "numbers", "The numbers rank the new buds by how close each one is to the beehive "
                    + "(1 = closest). The closest buds are the ones that grow.",
            "hive", "The pollen carries the flowers the player pollinates into the beehive. The "
                    + "beehive is like the player's profile: it learns what they like.",
            "circle", "The pollen circle is the area where new buds can sprout.",
            "wither", "The flowers that die are the ones farthest from the beehive, meaning the "
                    + "least like what the player has been picking.",
            "wrap", "The garden lost its colors because the hive (like a profile) keeps "
                    + "recommending flowers similar to what the player already picked, so "
                    + "different flowers die out. That is how a recommendation system can trap "
                    + "you in a filter bubble.");

    private final Vec3d pollinationCenter;
    private final int stage;
    private final int pollinatedFlowerId;
    private Flower[] newFlowerCandidates;
    private Flower[] newFlowers;
    private long ticks;       // wall clock for this round
    private long anim;        // animation clock; pauses at HOLD_TICK while a question runs
    private boolean active = true;
    private boolean ended;
    private BeetrapState nextState;
    private int newFlowerId = -1;
    private int witheredFlowerId = -1;
    private int quizRightCount;

    private final Deque<Step> steps = new ArrayDeque<>();
    private Step current;
    private boolean duringAnimQueued;
    private boolean afterBloomQueued;

    public RecommendBipPollinationHappeningState(BeetrapState state, Vec3d pollinationCenter,
            int stage, int pollinatedFlowerId) {
        super(state);
        this.pollinationCenter = pollinationCenter;
        this.stage = stage;
        this.pollinatedFlowerId = pollinatedFlowerId;
    }

    /** Chat context while Bip waits for an answer: react to the guess, don't explain yet. */
    static String askContext(String fact) {
        return "CONTEXT: you just asked the player a question and they are answering it. "
                + "FACT (for you to check their answer against, not to recite): " + fact + " "
                + "React to their guess in one short line: build on what's right, and give a "
                + "small hint if it's off. Do NOT give the full answer yet, the game will have you "
                + "explain it right after. Don't suggest any new action.";
    }

    private void emitBeat(String beat) {
        this.emitBeat(beat, -1);
    }

    private void emitBeat(String beat, int flowerId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("activity", ACTIVITY);
        details.put("beat", beat);
        if(flowerId >= 0) {
            details.put("flower_id", flowerId);
        }
        this.stateManager.recordAgentEvent("activity_beat", details);
    }

    // --- Steps ------------------------------------------------------------------------------

    /** One unit of Bip's debrief; runs until {@link #tick} returns true. */
    private abstract class Step {
        abstract void start();

        abstract boolean tick(boolean busy, boolean conversationDone);

        void onPlayerChat() { }

        void onChoice(String questionId, int option) { }

        String chatContext() {
            return null;
        }
    }

    /** Bip says one line; finished once it has been spoken. */
    private class Say extends Step {
        private final String beat;
        private final int flowerId;
        private final BipSpeechSettle speech = new BipSpeechSettle(20, 300);

        Say(String beat, int flowerId) {
            this.beat = beat;
            this.flowerId = flowerId;
        }

        @Override
        void start() {
            emitBeat(this.beat, this.flowerId);
            this.speech.begin(ticks);
        }

        @Override
        boolean tick(boolean busy, boolean conversationDone) {
            return this.speech.tick(ticks, busy);
        }
    }

    /** "{topic}_ask", wait for a chat answer (nudging "{topic}_nudge_N"). Skipped when Q&A is off. */
    private class Ask extends Step {
        private final String topic;
        private final int flowerId;
        private final BipQuestion question;

        Ask(String topic, int flowerId) {
            this.topic = topic;
            this.flowerId = flowerId;
            this.question = new BipQuestion(tier -> emitBeat(topic + "_nudge_" + tier));
        }

        @Override
        void start() {
            if(!BipFeatures.INTERACTIVE_QA) {
                return;
            }
            emitBeat(this.topic + "_ask", this.flowerId);
            this.question.begin(ticks);
        }

        @Override
        boolean tick(boolean busy, boolean conversationDone) {
            return !BipFeatures.INTERACTIVE_QA
                    || this.question.tick(ticks, busy, conversationDone);
        }

        @Override
        void onPlayerChat() {
            this.question.onPlayerChat(ticks);
        }

        @Override
        String chatContext() {
            return askContext(FACTS.get(this.topic));
        }
    }

    /** Bip asks a quiz question aloud, then the choice screen shows, then Bip reacts. */
    private class Quiz extends Step {
        private final QuizQuestion q;
        private final BipSpeechSettle speech = new BipSpeechSettle(10, 300);
        private boolean screenShown;
        private boolean answered;
        private boolean reacting;

        Quiz(QuizQuestion q) {
            this.q = q;
        }

        @Override
        void start() {
            emitBeat("quiz_q_" + this.q.id());
            this.speech.begin(ticks);
        }

        @Override
        boolean tick(boolean busy, boolean conversationDone) {
            if(!this.screenShown) {
                if(this.speech.tick(ticks, busy)) {
                    net.broadcastCustomPayload(new ShowMultipleChoiceScreenS2CPayload(
                            this.q.id(), this.q.question(), this.q.options()));
                    this.screenShown = true;
                }
                return false;
            }
            if(this.answered && !this.reacting) {
                this.reacting = true;
                this.speech.begin(ticks);
                return false;
            }
            return this.reacting && this.speech.tick(ticks, busy);
        }

        @Override
        void onChoice(String questionId, int option) {
            if(!this.screenShown || this.answered || !this.q.id().equals(questionId)) {
                return;
            }
            this.answered = true;
            boolean correct = option == this.q.correct();
            net.beetrapLog("recommend_quiz_answer", this.q.id() + "=" + option
                    + (correct ? " (correct)" : " (incorrect)"));
            emitBeat(correct ? "quiz_right_" + (quizRightCount++ % 3) : "quiz_wrong_" + this.q.id());
        }

        @Override
        String chatContext() {
            return "CONTEXT: a quiz question is on the player's screen. Don't answer it in chat. "
                    + "If they ask, tell them to pick their answer on the screen.";
        }
    }

    /** Bip flies over and hands across the time-travel clocks (round 0). */
    private class Clocks extends Step {
        private long startTick;
        private long clocksGivenTick = -1;

        @Override
        void start() {
            this.startTick = ticks;
            if(BipFeatures.TIME_TRAVEL_HANDOVER) {
                emitBeat("clock_handoff");
            } else {
                giveClocks();
            }
        }

        @Override
        boolean tick(boolean busy, boolean conversationDone) {
            if(this.clocksGivenTick < 0 && stateManager.timeTravelItemsGiven()) {
                this.clocksGivenTick = ticks;
            }
            if(this.clocksGivenTick >= 0) {
                return ticks - this.clocksGivenTick >= HANDOFF_AFTER_CLOCKS;
            }
            if(ticks - this.startTick >= HANDOFF_MAX) {
                giveClocks(); // fallback: the give_clocks command never arrived
                return true;
            }
            return false;
        }
    }

    private void queueDuringAnimation() {
        if(this.stage == 0) {
            this.steps.add(new Ask("numbers", this.pollinatedFlowerId));
            this.steps.add(new Say("numbers_explain", -1));
            this.steps.add(new Ask("hive", -1));
            this.steps.add(new Say("hive_explain", -1));
        } else if(this.stage == 1) {
            this.steps.add(new Ask("circle", -1));
            this.steps.add(new Say("circle_explain", -1));
        }
    }

    private void queueAfterBloom() {
        boolean ending = this.activityShouldEnd();
        if(this.stage == 0) {
            this.steps.add(new Clocks());
        } else if(this.stage == 1 && this.witheredFlowerId >= 0) {
            this.steps.add(new Ask("wither", this.witheredFlowerId));
            this.steps.add(new Say("wither_explain", -1));
        } else if(this.stage > QUIZ_ROUND && !ending && this.newFlowerId >= 0) {
            this.steps.add(new Say("new_flowers", this.newFlowerId));
        }
        // The quiz runs once, in round 2 — or before the wrap-up if the garden collapses earlier.
        if(this.stage == QUIZ_ROUND || (ending && this.stage < QUIZ_ROUND)) {
            this.steps.add(new Say("quiz_intro", -1));
            for(QuizQuestion q : QUIZ) {
                this.steps.add(new Quiz(q));
            }
        }
        if(ending) {
            this.steps.add(new Ask("wrap", -1));
            this.steps.add(new Say("wrap_reveal", -1));
            this.steps.add(new Say("wrap_recommender", -1));
            this.steps.add(new Say("wrap_bubble", -1));
            this.steps.add(new Say("complete", -1));
        }
    }

    /** @return true while steps are still queued or running. */
    private boolean runSteps() {
        boolean busy = this.agentBusy();
        boolean done = this.consumeConversationDone();
        while(true) {
            if(this.current == null) {
                this.current = this.steps.pollFirst();
                if(this.current == null) {
                    return false;
                }
                this.current.start();
            }
            if(!this.current.tick(busy, done)) {
                return true;
            }
            this.current = null;
        }
    }

    // --- Garden mechanics (as RecommendationSystemPollinationHappeningState) ----------------

    private void tickGrowBuds() {
        this.newFlowerCandidates = this.findFlowersWithinRadius(
                this.pollinationCenter, this.pollinationCircleRadius,
                AMOUNT_OF_BUDS_TO_PLACE_DEFAULT_MODE);
        this.flowerManager.placeBuds(this, this.newFlowerCandidates);
    }

    private boolean isNewFlowerCandidate(Flower f) {
        for(Flower g : this.newFlowerCandidates) {
            if(f.equals(g)) {
                return true;
            }
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
        for(int i = 0; i < fbe.length && r < AMOUNT_OF_BUDS_RANKED; ++i) {
            Flower f = this.flowerManager.getFlowerByEntityId(this, fbe[i].getId());
            if(!this.isNewFlowerCandidate(f)) {
                continue;
            }
            fbe[i].setCustomName(Text.of(String.valueOf(r + 1)));
            fbe[i].setCustomNameVisible(true);
            this.newFlowers[r] = f;
            ++r;
        }
        this.stateManager.recordBudsRanked(this.newFlowers, this.usingDiversifyingRankingMethod,
                this.pollinationCenter, this.pollinationCircleRadius);
    }

    private void tickPlaceNewFlowers() {
        this.flowerManager.removeFlowerEntities(this.newFlowerCandidates);
        this.flowerManager.placeFlowerEntities(this, this.newFlowers);
        for(Flower f : this.newFlowers) {
            if(f == null) {
                return;
            }
            this.setFlower(f.getNumber(), true);
            if(this.newFlowerId < 0) {
                this.newFlowerId = f.getNumber();
            }
        }
    }

    private void tickWitherFlowers() {
        FallingBlockEntity[] fbe = this.flowerManager
                .findAllFlowerEntitiesWithinRSortedByLeastDistanceToCenter(
                        this.pollinationCenter, Double.POSITIVE_INFINITY);
        int r = 0;
        for(int i = fbe.length - 1; i >= 0 && r < this.amountOfFlowersToWither; --i) {
            Flower f = this.flowerManager.getFlowerByEntityId(this, fbe[i].getId());
            if(f.hasWithered()) {
                continue;
            }
            f.setWithered(true);
            this.flowerManager.placeFlowerEntity(this, f);
            if(r == 0) {
                this.witheredFlowerId = f.getNumber();
            }
            ++r;
        }
    }

    private void giveClocks() {
        // Idempotent at the state-manager level, shared with the agent's give_clocks command.
        this.stateManager.giveTimeTravelItemsToAllPlayers();
    }

    private boolean activityShouldEnd() {
        return this.computeDiversityScore() < this.stateManager.getInitialDiversityScore() / 2;
    }

    private void finishRound() {
        if(this.ended) {
            return;
        }
        this.ended = true;
        this.active = false;
        this.giveClocks();
        if(this.activityShouldEnd()) {
            this.stateManager.completeBipStage();
            this.nextState = new TimeTravelableBeetrapState(this);
        } else {
            this.nextState = new RecommendBipPollinationReadyState(this, this.stage + 1);
        }
        // From here the player can rewind the garden; let Bip react the first time they do.
        this.stateManager.armBipTimeTravelNarration();
        this.setBeeNestMinecraftPosition(this.beeNestController.getBeeNestPosition());
    }

    @Override
    public void tick() {
        if(!this.active) {
            return;
        }
        if(this.anim == 0) {
            this.beeNestController.startPollination(this.pollinationCenter);
        }
        this.beeNestController.tickMovementAnimation(this.anim);
        if(this.anim == 20) {
            this.tickGrowBuds();
            this.tickRankBuds();
        }
        if(this.anim == BUDS_TICK && !this.duringAnimQueued) {
            this.duringAnimQueued = true;
            this.queueDuringAnimation();
        }

        boolean stepsRunning = this.runSteps();
        boolean holding = this.anim == HOLD_TICK && !this.afterBloomQueued && stepsRunning;

        // While holding, keep the circle and pollen alive by cycling a visual clock in [20, 220).
        long visual = holding ? 20 + this.ticks % 200 : this.anim;
        this.beeNestController.tickCircle(visual, this.pollinationCircleRadius);
        this.beeNestController.tickSpawnPollensThatFlyTowardsNest(
                visual, this.flowerManager, this.newFlowerCandidates);

        if(this.anim == BLOOM_TICK && !this.afterBloomQueued) {
            this.afterBloomQueued = true;
            this.tickPlaceNewFlowers();
            this.tickWitherFlowers();
            this.queueAfterBloom();
            stepsRunning = this.runSteps();
        }
        if(this.afterBloomQueued && !stepsRunning) {
            this.finishRound();
        }

        this.beeNestController.tickPollinationLines(this.ticks, this.pastPollinationLocations);
        if(!holding && this.anim < BLOOM_TICK) {
            ++this.anim;
        }
        ++this.ticks;
    }

    @Override
    public boolean hasNextState() {
        return this.ended;
    }

    @Override
    public BeetrapState getNextState() {
        return this.nextState;
    }

    @Override
    public String describeCurrentTaskForAgent() {
        return this.current == null ? null : this.current.chatContext();
    }

    @Override
    public void onPlayerChat(ServerPlayerEntity player, String message) {
        if(this.current != null) {
            this.current.onPlayerChat();
        }
    }

    @Override
    public void onMultipleChoiceSelectionResultReceived(String questionId, int option) {
        if(this.current != null) {
            this.current.onChoice(questionId, option);
        }
    }

    @Override
    public void onPlayerTargetNewEntity(ServerPlayerEntity player, boolean exists, int id) {
        super.onPlayerTargetNewEntity(player, false, id);
    }

    @Override
    public boolean timeTravelAvailable() {
        return false;
    }
}
