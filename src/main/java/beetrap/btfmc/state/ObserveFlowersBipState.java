package beetrap.btfmc.state;

import beetrap.btfmc.BipFeatures;
import beetrap.btfmc.flower.Flower;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Observe the Flowers — Bip's gentle intro stage. He trades names with the player (first meeting
 * only), sets up the shared goal, points them at the data panel, then runs a short "find a flower
 * with this trait" search — repeated with a different trait each round — before handing off to the
 * Filter Bubble activity. Bip is an intelligent novice here (see the v4 persona): he never states
 * why a pick is right or wrong, only points the player back at their own data panel.
 *
 * Java drives the pacing and evaluates each pick; Python (activities.py "observe") owns Bip's
 * words for each beat, scripted or improvised per the BIP_LLM_IMPROV / BIP_INTERACTIVE_QA flags.
 */
public class ObserveFlowersBipState extends BeetrapState {

    /** The four traits that meaningfully vary and that the player can read on the sidebar.
     * Sunlight (z) is excluded: it's a near-constant tiny value in the data model. */
    private enum Feature { COLOR, SMELL, NECTAR, WATER }

    // Pacing in ticks (20 ticks = 1s).
    private static final int INTRO_TICK = 5;              // ask/greet as soon as the stage settles
    private static final int NAME_NUDGE_DELAY = 300;      // ~15s before nudging for a name
    private static final int NAME_PHASE_MAX = 700;        // ~35s: give up waiting, move on anyway
    private static final int SYNTHETIC_DWELL = 30;        // ~1.5s pause when no reply is expected at all
    // Chat replies and scripted beats share ONE serialized call queue to the agent service, so a
    // real "nice to meet you" reply can take far longer than a fixed few seconds under normal LLM
    // latency — and the reply might itself ask a follow-up, so going quiet doesn't mean it's over.
    // Prefers the model's "done" signal; falls back to settle+grace, then a hard timeout. See
    // ConversationWaiter.
    private static final int REPLY_SETTLE = 30;           // Bip must be silent this long (~1.5s) after
    private static final int REPLY_GRACE = 300;           // ~15s grace for a follow-up after settling
    private static final int REPLY_WAIT_MAX = 1200;       // hard fallback: move on anyway after ~60s
    // Same agentBusy()-based pattern as the name-reply wait: don't guess with a fixed delay for
    // how long the scripted role_intro line takes to actually finish (TTS duration varies) — wait
    // for it to go quiet, plus a short breather, then show the image.
    private static final int ROLE_SETTLE = 20;            // ~1s breather after role_intro finishes
    private static final int ROLE_MAX_WAIT = 200;         // ~10s safety net if detection never resolves
    private static final int SHOW_TO_SEARCH_DELAY = 50;   // ~2.5s glance at the panel, then acknowledge
    private static final int SEARCH_NUDGE_DELAY = 400;    // ~20s of silence before nudging
    private static final int MAX_SEARCH_NUDGES = 2;
    // Give real time to notice a reflective question and start typing before assuming no answer is
    // coming (a few seconds is nowhere near enough for a human to read, think, and start replying).
    // Once they DO start answering, be just as patient as the name-reply/clock-handoff waits — the
    // same serialized call queue + LLM latency risk applies here too.
    private static final int SUCCESS_NO_ANSWER_DWELL = 400;  // ~20s before assuming none is coming
    private static final int SUCCESS_ANSWER_WAIT_MAX = 1200; // ~60s once they've started replying
    private static final int RETRY_COOLDOWN = 40;         // debounce rapid repeat right-clicks
    private static final int ROUNDS = 2;                  // number of search rounds before wrapping up
    private static final int COMPLETE_DWELL = 100;        // after the closing line, end the activity

    private static final int PHASE_NAME = 0;         // trading names (first meeting only)
    private static final int PHASE_ROLE = 1;         // "you're a bee like me..." goal-setting line
    private static final int PHASE_RECOMMENDER = 2;  // "this is like a recommender system" check-in
    private static final int PHASE_SHOW = 3;         // hover-to-see-features text screen shown
    private static final int PHASE_SEARCH = 4;       // repeated "find a flower with X" rounds
    private static final int PHASE_COMPLETE = 5;     // closing line + next-steps text screen
    private static final int PHASE_DONE = 6;

    private final Random random = new Random();

    private int ticks;
    private int phase = PHASE_NAME;
    private long phaseEnteredTick;

    private boolean playerAnswered;      // replied during PHASE_NAME (their name), or nothing to wait for
    private boolean awaitingRealReply;   // true only when a real chat reply's LLM greeting is pending
    private boolean nameNudged;
    // Waits for the "nice to meet you" reply to genuinely finish (see ConversationWaiter).
    private final ConversationWaiter nameReplyWaiter =
            new ConversationWaiter(REPLY_SETTLE, REPLY_GRACE, REPLY_WAIT_MAX);
    // PHASE_ROLE bookkeeping: role_intro is scripted (not an LLM reply), so this just waits for the
    // spoken line itself to finish — no "done" signal or follow-up-question concern applies here.
    private boolean roleSawBusy;
    private long roleQuietSinceTick = -1;

    // PHASE_RECOMMENDER: "do you know what a recommender system is?" — same shape as PHASE_NAME's
    // question/wait/nudge pattern.
    private boolean recommenderAnswered;      // replied, or nothing to wait for
    private boolean awaitingRecommenderReply; // true only when a real chat reply is pending
    private boolean recommenderNudged;
    private final ConversationWaiter recommenderReplyWaiter =
            new ConversationWaiter(REPLY_SETTLE, REPLY_GRACE, REPLY_WAIT_MAX);

    private List<Feature> featureOrder;
    private int round;
    private Feature currentFeature;
    private int currentTargetLevel;
    private int searchNudges;
    private boolean searchSolved;
    private long solvedAtTick;
    private boolean successAnswered; // replied to the search_success reflection question
    // Waits for the reflection reply to genuinely finish (see ConversationWaiter).
    private final ConversationWaiter successReplyWaiter =
            new ConversationWaiter(REPLY_SETTLE, REPLY_GRACE, SUCCESS_ANSWER_WAIT_MAX);
    private Flower lastCorrectFlower; // for fact-checking a chat claim about its actual value
    private long lastPickEvalTick = -1;
    private int failureAttempts;

    public ObserveFlowersBipState(BeetrapState parent) {
        super(parent);
    }

    private void emitBeat(String beat) {
        this.stateManager.recordAgentEvent("activity_beat",
                Map.of("activity", "observe", "beat", beat));
    }

    private void emitBeat(String beat, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("activity", "observe");
        details.put("beat", beat);
        details.putAll(extra);
        this.stateManager.recordAgentEvent("activity_beat", details);
    }

    private void enterPhase(int next) {
        this.phase = next;
        this.phaseEnteredTick = this.ticks;
    }

    // --- Feature levels & phrasing -------------------------------------------------------------

    private int level(double value) {
        if(value < 3.333) {
            return 0;
        }
        return value < 4.667 ? 1 : 2;
    }

    private int levelOf(Flower f, Feature feature) {
        return switch(feature) {
            case COLOR -> (int)Math.round(f.v);
            case SMELL -> this.level(f.w);
            case NECTAR -> this.level(f.x);
            case WATER -> this.level(f.y);
        };
    }

    private double rawValueOf(Flower f, Feature feature) {
        return switch(feature) {
            case COLOR -> f.v;
            case SMELL -> f.w;
            case NECTAR -> f.x;
            case WATER -> f.y;
        };
    }

    /** The exact sidebar row label (see FlowerValueScoreboardDisplayerService), so Bip's
     * "look at the {feature_label} row" always points at something the player can actually find. */
    private String featureLabel(Feature feature) {
        return switch(feature) {
            case COLOR -> "Color";
            case SMELL -> "Smell strength";
            case NECTAR -> "Nectar sweetness";
            case WATER -> "Water needed";
        };
    }

    private static final String[] SMELL_NAMES = {"faint", "noticeable", "strong"};
    private static final String[] NECTAR_NAMES = {"mild", "fairly sweet", "very sweet"};
    private static final String[] WATER_NAMES = {"low", "medium", "high"};

    /** What to ask the player to go find, e.g. "a Purple flower" / "a flower with a strong smell". */
    private String describeTarget(Feature feature, int level) {
        if(feature == Feature.COLOR) {
            for(Flower f : this) {
                if(!f.hasWithered() && this.levelOf(f, Feature.COLOR) == level) {
                    return "a " + this.flowerManager.getFlowerMinecraftColor(f) + " flower";
                }
            }
            return "a different colored flower";
        }
        return switch(feature) {
            case SMELL -> "a flower with a " + SMELL_NAMES[level] + " smell";
            case NECTAR -> "a flower with " + NECTAR_NAMES[level] + " nectar";
            case WATER -> "a flower with " + WATER_NAMES[level] + " water needs";
            default -> "a different flower";
        };
    }

    /** Pick a level for this feature that at least one flower in the garden actually has. */
    private int pickAchievableTarget(Feature feature) {
        Set<Integer> levels = new LinkedHashSet<>();
        for(Flower f : this) {
            if(f.hasWithered()) {
                continue;
            }
            levels.add(this.levelOf(f, feature));
        }
        if(levels.isEmpty()) {
            return 0;
        }
        // Prefer the unambiguous extremes (low/high) over the vague middle bucket ("medium" /
        // "noticeable" / "fairly sweet") — its boundaries aren't shown anywhere, so the player has
        // no way to be confident a borderline flower actually qualifies. Color has no such
        // "middle" (each value is a distinct named color), so this only applies to the others.
        if(feature != Feature.COLOR) {
            Set<Integer> extremes = new LinkedHashSet<>(levels);
            extremes.remove(1);
            if(!extremes.isEmpty()) {
                levels = extremes;
            }
        }
        List<Integer> options = new ArrayList<>(levels);
        return options.get(this.random.nextInt(options.size()));
    }

    // --- Phase machine: PHASE_NAME -------------------------------------------------------------

    private void tickName() {
        if(this.ticks == INTRO_TICK) {
            boolean returning = beetrap.btfmc.Beetrapfabricmc.BIP_INTRODUCED;
            beetrap.btfmc.Beetrapfabricmc.BIP_INTRODUCED = true;
            this.phaseEnteredTick = this.ticks;
            if(returning) {
                // Already met Bip in an earlier activity — skip the name exchange entirely.
                this.emitBeat("intro_returning");
                this.playerAnswered = true; // nothing to wait for; the dwell below still applies
            } else {
                this.emitBeat("intro"); // Python swaps to intro_no_qa when interactive_qa is off
                this.playerAnswered = !BipFeatures.INTERACTIVE_QA; // no question was actually asked
            }
            return;
        }
        if(this.phase != PHASE_NAME) {
            return; // already moved on above, this same tick or an earlier one
        }
        long since = this.ticks - this.phaseEnteredTick;
        if(this.playerAnswered) {
            if(this.awaitingRealReply) {
                // A real chat reply is expected — wait for the LLM's "nice to meet you" to actually
                // finish (preferring its own "done" signal over guessing from silence alone).
                if(this.nameReplyWaiter.tick(this.ticks, this.agentBusy(),
                        this.consumeConversationDone())) {
                    this.enterRolePhase();
                }
            } else if(since >= SYNTHETIC_DWELL) {
                // Nothing to wait for (returning, or Q&A off) — just a brief natural pause.
                this.enterRolePhase();
            }
            return;
        }
        if(since >= NAME_PHASE_MAX) {
            this.enterRolePhase(); // give up waiting, move on anyway
        } else if(since >= NAME_NUDGE_DELAY && !this.nameNudged) {
            this.emitBeat("ask_name_nudge");
            this.nameNudged = true;
        }
    }

    // --- Phase machine: PHASE_ROLE / PHASE_SHOW ------------------------------------------------

    private void enterRolePhase() {
        this.emitBeat("role_intro");
        this.enterPhase(PHASE_ROLE);
        this.roleSawBusy = false;
        this.roleQuietSinceTick = -1;
    }

    private void tickRole() {
        long since = this.ticks - this.phaseEnteredTick;
        boolean busy = this.agentBusy();
        if(busy) {
            this.roleSawBusy = true; // role_intro has started (or is still) playing
            this.roleQuietSinceTick = -1;
        } else if(this.roleSawBusy && this.roleQuietSinceTick < 0) {
            this.roleQuietSinceTick = this.ticks;
        }
        boolean spokeAndSettled = this.roleSawBusy && this.roleQuietSinceTick >= 0
                && this.ticks - this.roleQuietSinceTick >= ROLE_SETTLE;
        if(spokeAndSettled || since >= ROLE_MAX_WAIT) {
            this.enterRecommenderPhase();
        }
    }

    // --- Phase machine: PHASE_RECOMMENDER --------------------------------------------------------

    private void enterRecommenderPhase() {
        this.enterPhase(PHASE_RECOMMENDER);
        if(!BipFeatures.INTERACTIVE_QA) {
            this.emitBeat("recommender_intro_no_qa");
            this.recommenderAnswered = true; // nothing to wait for; the dwell below still applies
        } else {
            this.emitBeat("recommender_intro");
            this.recommenderAnswered = false;
        }
    }

    private void tickRecommender() {
        long since = this.ticks - this.phaseEnteredTick;
        if(this.recommenderAnswered) {
            if(this.awaitingRecommenderReply) {
                // A real chat reply is expected — wait for it to actually finish (preferring its
                // own "done" signal over guessing from silence alone).
                if(this.recommenderReplyWaiter.tick(this.ticks, this.agentBusy(),
                        this.consumeConversationDone())) {
                    this.enterShowPhase();
                }
            } else if(since >= SYNTHETIC_DWELL) {
                // Nothing to wait for (Q&A off) — just a brief natural pause.
                this.enterShowPhase();
            }
            return;
        }
        if(since >= NAME_PHASE_MAX) {
            this.enterShowPhase(); // give up waiting, move on anyway
        } else if(since >= NAME_NUDGE_DELAY && !this.recommenderNudged) {
            this.emitBeat("recommender_nudge");
            this.recommenderNudged = true;
        }
    }

    private void enterShowPhase() {
        this.showTextScreenToAllPlayers(
                "Hover over a flower to see its underlying features show up on the right!",
                "gui/observe_hover_example");
        this.enterPhase(PHASE_SHOW);
    }

    private void tickShow() {
        if(this.ticks - this.phaseEnteredTick >= SHOW_TO_SEARCH_DELAY) {
            this.startSearchRound(false); // round 0 always uses "search_task"; the flag is unused
        }
    }

    // --- Phase machine: PHASE_SEARCH -----------------------------------------------------------

    private void startSearchRound(boolean previousRoundAnswered) {
        if(this.featureOrder == null) {
            this.featureOrder = new ArrayList<>(Arrays.asList(Feature.values()));
            Collections.shuffle(this.featureOrder, this.random);
        }
        this.currentFeature = this.featureOrder.get(this.round % this.featureOrder.size());
        this.currentTargetLevel = this.pickAchievableTarget(this.currentFeature);
        this.searchNudges = 0;
        this.searchSolved = false;
        this.failureAttempts = 0;

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("requested", this.describeTarget(this.currentFeature, this.currentTargetLevel));
        String beat;
        if(this.round == 0) {
            beat = "search_task";
        } else if(previousRoundAnswered) {
            beat = "search_task_next";
        } else {
            // No "Nice!" opener — that would falsely imply they'd just explained their pick.
            beat = "search_task_next_no_reply";
        }
        this.emitBeat(beat, extra);
        this.enterPhase(PHASE_SEARCH);
    }

    private void tickSearch() {
        if(this.searchSolved) {
            long sinceSolved = this.ticks - this.solvedAtTick;
            if(this.successAnswered) {
                // They started answering "what told you this was right" — wait for Bip's reply to
                // genuinely finish (preferring its own "done" signal over guessing from silence).
                if(this.successReplyWaiter.tick(this.ticks, this.agentBusy(),
                        this.consumeConversationDone())) {
                    this.advanceSearch();
                }
            } else if(sinceSolved >= SUCCESS_NO_ANSWER_DWELL) {
                // No reply after a real amount of time to notice and start typing — move on. This
                // is an optional reflection, not a required answer, so there's no nudge here.
                this.advanceSearch();
            }
            return;
        }
        long since = this.ticks - this.phaseEnteredTick;
        if(since >= (long)SEARCH_NUDGE_DELAY * (this.searchNudges + 1)
                && this.searchNudges < MAX_SEARCH_NUDGES) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("requested", this.describeTarget(this.currentFeature, this.currentTargetLevel));
            this.emitBeat("search_nudge", extra);
            this.searchNudges++;
        }
    }

    private void advanceSearch() {
        boolean answered = this.successAnswered;
        this.round++;
        if(this.round >= ROUNDS) {
            this.enterCompletePhase();
        } else {
            this.startSearchRound(answered);
        }
    }

    // --- Phase machine: PHASE_COMPLETE ---------------------------------------------------------

    private void enterCompletePhase() {
        this.emitBeat("complete");
        this.showTextScreenToAllPlayers(
                "Right-click the item in slot 7 and select \"Experience The Filter Bubble Effect\" "
                        + "to continue!");
        this.enterPhase(PHASE_COMPLETE);
    }

    private void tickComplete() {
        if(this.ticks - this.phaseEnteredTick >= COMPLETE_DWELL) {
            this.enterPhase(PHASE_DONE);
            this.stateManager.endActivity();
        }
    }

    @Override
    public void tick() {
        switch(this.phase) {
            case PHASE_NAME -> this.tickName();
            case PHASE_ROLE -> this.tickRole();
            case PHASE_RECOMMENDER -> this.tickRecommender();
            case PHASE_SHOW -> this.tickShow();
            case PHASE_SEARCH -> this.tickSearch();
            case PHASE_COMPLETE -> this.tickComplete();
            default -> { }
        }
        ++this.ticks;
    }

    @Override
    public boolean hasNextState() {
        return false;
    }

    @Override
    public BeetrapState getNextState() {
        return null;
    }

    @Override
    public String describeCurrentTaskForAgent() {
        if(this.phase == PHASE_RECOMMENDER) {
            return "CONTEXT: you just asked the player if they know what a recommender system is. "
                    + "If their reply indicates they DO know, respond with brief enthusiasm in one "
                    + "short line and don't over-explain (e.g. \"Nice, I bet you'll spot how this "
                    + "works then!\"). If their reply indicates they DON'T know or are unsure, give "
                    + "a short, simple explanation in one or two sentences: a recommender system "
                    + "suggests things based on what you already like, and they show up everywhere "
                    + "in real life (e.g. video recommendations, social media feeds, shopping "
                    + "suggestions). Keep it brief and age-appropriate for a middle schooler.";
        }
        // Covers the whole active search round: the player might ask what the target means
        // BEFORE picking (e.g. "what do u mean mild?"), or state a specific value or level AFTER
        // correctly picking. Without real grounding the model would just make something up (e.g.
        // "mild nectar means it looks plain") or conversationally agree with a wrong claim (e.g.
        // affirming "2.14 is high" when it's actually low) — these traits are numbers on the data
        // panel, not something visually judged.
        if(this.phase != PHASE_SEARCH) {
            return null;
        }
        String label = this.featureLabel(this.currentFeature);
        StringBuilder sb = new StringBuilder("FACT CHECK: the current search target is ")
                .append(this.describeTarget(this.currentFeature, this.currentTargetLevel))
                .append(". This is about the exact ").append(label)
                .append(" number on the data panel");
        if(this.currentFeature != Feature.COLOR) {
            sb.append(" (below 3.33 is low/faint/mild, 3.33 to 4.67 is medium/noticeable/fairly "
                    + "sweet, above 4.67 is high/strong/very sweet)");
        }
        sb.append(", NOT how the flower looks. If the player asks what this means or states a "
                + "specific value, answer using this real definition — never invent a visual "
                + "description or agree with an incorrect claim.");
        if(this.searchSolved && this.lastCorrectFlower != null) {
            String actual = this.currentFeature == Feature.COLOR
                    ? this.flowerManager.getFlowerMinecraftColor(this.lastCorrectFlower)
                    : String.format(java.util.Locale.ROOT, "%.2f",
                            this.rawValueOf(this.lastCorrectFlower, this.currentFeature));
            sb.append(" The flower they just correctly picked has ").append(label).append(": ")
                    .append(actual).append(" — verify any claim about it against this real reading.");
        }
        return sb.toString();
    }

    @Override
    public void onPlayerPollinate(Flower flower, Vec3d flowerMinecraftPosition) {
        // No real pollination in this stage — a right-click is how the player picks their answer
        // to the current search round.
        if(this.phase != PHASE_SEARCH || this.searchSolved) {
            return;
        }
        if(this.lastPickEvalTick >= 0 && this.ticks - this.lastPickEvalTick < RETRY_COOLDOWN) {
            return; // debounce rapid repeat clicks so Bip doesn't spam back-to-back lines
        }
        this.lastPickEvalTick = this.ticks;

        boolean correct = this.levelOf(flower, this.currentFeature) == this.currentTargetLevel;
        if(correct) {
            this.emitBeat("search_success");
            this.searchSolved = true;
            this.solvedAtTick = this.ticks;
            this.successAnswered = false;
            this.lastCorrectFlower = flower;
        } else {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("feature_label", this.featureLabel(this.currentFeature));
            this.failureAttempts++;
            this.emitBeat(this.failureAttempts % 2 == 1 ? "search_failure_0" : "search_failure_1",
                    extra);
        }
    }

    @Override
    public void onPlayerChat(ServerPlayerEntity player, String message) {
        if(this.phase == PHASE_NAME && !this.playerAnswered) {
            this.playerAnswered = true;
            this.awaitingRealReply = true;
            this.nameReplyWaiter.beginWaiting(this.ticks);
        } else if(this.phase == PHASE_NAME && this.nameReplyWaiter.isWaiting()) {
            // Kept talking while already waiting on the greeting reply — extend the wait.
            this.nameReplyWaiter.extendWaiting();
        } else if(this.phase == PHASE_RECOMMENDER && !this.recommenderAnswered) {
            this.recommenderAnswered = true;
            this.awaitingRecommenderReply = true;
            this.recommenderReplyWaiter.beginWaiting(this.ticks);
        } else if(this.phase == PHASE_RECOMMENDER && this.recommenderReplyWaiter.isWaiting()) {
            this.recommenderReplyWaiter.extendWaiting();
        } else if(this.phase == PHASE_SEARCH && this.searchSolved) {
            if(!this.successAnswered) {
                this.successAnswered = true;
                this.successReplyWaiter.beginWaiting(this.ticks);
            } else if(this.successReplyWaiter.isWaiting()) {
                // E.g. answering a follow-up Bip's own reply just asked — extend the wait instead
                // of letting the grace window from the PREVIOUS reply cut this one off.
                this.successReplyWaiter.extendWaiting();
            }
        }
    }

    @Override
    public boolean timeTravelAvailable() {
        return false;
    }
}
