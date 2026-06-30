package beetrap.btfmc.state;

import beetrap.btfmc.BipFeatures;
import beetrap.btfmc.flower.Flower;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Observe the Flowers — Bip's gentle intro stage. He greets the player, flies them around the
 * garden to look at a flower and its near-twin, then challenges them to find a very different one,
 * landing the idea that "different" means "far apart" — but as a shared discovery, never a lecture
 * (Bip is an intelligent novice here, see the v4 persona). No pollination happens in this stage.
 *
 * Java drives the pacing and reports semantic beats with the real flower's traits in the details;
 * Python (activities.py "observe") owns Bip's words for each beat, scripted or improvised per the
 * BIP_LLM_IMPROV / BIP_INTERACTIVE_QA flags.
 */
public class ObserveFlowersBipState extends BeetrapState {

    // Pacing in ticks (20 ticks = 1s).
    private static final int INTRO_TICK = 5;             // greet as soon as the stage settles
    private static final int EXAMINE_TICK = 150;         // ~7.5s later: fly to a flower and ask
    private static final int EXAMINE_NUDGE_DELAY = 400;  // ~20s of silence before nudging to look
    private static final int MAX_NUDGES = 2;             // nudge a couple of times, then move on
    private static final int NO_QA_PAUSE = 150;          // Q&A off: dwell after telling, then move on
    private static final int REPLY_LEAD = 90;            // let Bip's reply to the player land first
    private static final int COMPARE_DWELL = 190;        // admire the near-twin before the challenge
    private static final int CHALLENGE_NUDGE_DELAY = 300;// ~15s before nudging them to pick one
    private static final int LESSON_DWELL = 200;         // let the closing wonder play, then finish

    private static final int PHASE_INTRO = 0;
    private static final int PHASE_EXAMINE = 1;    // asked them to look; waiting for a chat answer
    private static final int PHASE_COMPARE = 2;    // pointed out the near-twin; brief dwell
    private static final int PHASE_CHALLENGE = 3;  // asked for a different flower; waiting for a pick
    private static final int PHASE_LESSON = 4;     // delivered the distance hunch; brief dwell
    private static final int PHASE_DONE = 5;

    private int ticks;
    private int phase = PHASE_INTRO;
    private long phaseEnteredTick;
    private long lastPromptTick;
    private int nudges;

    private boolean playerAnswered;
    private Flower focusFlower;
    private Flower neighborFlower;
    private Flower pickedFlower;

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

    // --- Flower selection --------------------------------------------------------------------

    private Vec3d playerPos() {
        return this.world.getPlayers().isEmpty()
                ? Vec3d.ZERO : this.world.getPlayers().getFirst().getPos();
    }

    /** The flower nearest the player — a short, easy-to-follow first flight. */
    private Flower chooseFocus() {
        Vec3d player = this.playerPos();
        Flower best = null;
        double bestDistance = Double.MAX_VALUE;
        for(Flower f : this) {
            if(f.hasWithered()) {
                continue;
            }
            Vec3d position = this.flowerManager.getFlowerMinecraftPosition(this, f);
            if(position == null) {
                continue;
            }
            double distance = position.distanceTo(player);
            if(distance < bestDistance) {
                bestDistance = distance;
                best = f;
            }
        }
        return best;
    }

    /** The in-garden flower most SIMILAR to the focus (smallest attribute distance). */
    private Flower chooseNeighbor(Flower focus) {
        Flower best = null;
        double bestDistance = Double.MAX_VALUE;
        for(Flower f : this) {
            if(f.hasWithered() || f.equals(focus)) {
                continue;
            }
            double distance = focus.distanceTo(f);
            if(distance < bestDistance) {
                bestDistance = distance;
                best = f;
            }
        }
        return best;
    }

    /** The in-garden flower most DIFFERENT from the focus (largest attribute distance). */
    private Flower chooseMostDifferent(Flower focus) {
        Flower best = null;
        double bestDistance = -1;
        for(Flower f : this) {
            if(f.hasWithered() || f.equals(focus)) {
                continue;
            }
            double distance = focus.distanceTo(f);
            if(distance > bestDistance) {
                bestDistance = distance;
                best = f;
            }
        }
        return best;
    }

    // --- Human-readable traits (fed into the beats so even scripted Bip names real values) -----

    private int level(double value) {
        if(value < 3.333) {
            return 0;
        }
        return value < 4.667 ? 1 : 2;
    }

    private String describeFlower(Flower f) {
        // Sunlight (z) is a near-constant tiny value in the data model, so we leave it out and
        // describe the four traits that actually vary and that the player can see/read.
        String color = this.flowerManager.getFlowerMinecraftColor(f);
        String nectar = switch(this.level(f.x)) {
            case 0 -> "mild";
            case 1 -> "fairly sweet";
            default -> "very sweet";
        };
        String smell = switch(this.level(f.w)) {
            case 0 -> "faint";
            case 1 -> "noticeable";
            default -> "strong";
        };
        String water = switch(this.level(f.y)) {
            case 0 -> "low";
            case 1 -> "medium";
            default -> "high";
        };
        return color + " petals, " + nectar + " nectar, a " + smell + " smell, and " + water
                + " water needs";
    }

    private String describeDifference(double distance) {
        if(distance < 2.0) {
            return "barely different";
        }
        return distance < 4.5 ? "quite different" : "really different";
    }

    // --- Phase machine -----------------------------------------------------------------------

    private void tickIntro() {
        if(this.ticks == INTRO_TICK) {
            // Greet only the first time Bip meets the player this run; otherwise jump in.
            this.emitBeat(beetrap.btfmc.Beetrapfabricmc.BIP_INTRODUCED
                    ? "intro_returning" : "intro");
            beetrap.btfmc.Beetrapfabricmc.BIP_INTRODUCED = true;
        }
        if(this.ticks == EXAMINE_TICK) {
            this.focusFlower = this.chooseFocus();
            if(this.focusFlower == null) {
                // Empty garden (shouldn't happen) — nothing to examine; just wrap up.
                this.enterPhase(PHASE_DONE);
                return;
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("flower_id", this.focusFlower.getNumber());
            extra.put("attributes", this.describeFlower(this.focusFlower));
            this.emitBeat("examine", extra);
            this.lastPromptTick = this.ticks;
            this.enterPhase(PHASE_EXAMINE);
        }
    }

    private void advanceToCompare() {
        this.neighborFlower = this.chooseNeighbor(this.focusFlower);
        if(this.neighborFlower == null) {
            // Only one flower — skip the comparison and go straight to the challenge.
            this.startChallenge();
            return;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("flower_id", this.neighborFlower.getNumber());
        extra.put("neighbor_attributes", this.describeFlower(this.neighborFlower));
        this.emitBeat("compare_neighbor", extra);
        this.enterPhase(PHASE_COMPARE);
    }

    private void tickExamine() {
        long since = this.ticks - this.phaseEnteredTick;
        if(!BipFeatures.INTERACTIVE_QA) {
            // Q&A off: Bip already told them the traits; dwell briefly, then compare.
            if(since >= NO_QA_PAUSE) {
                this.advanceToCompare();
            }
            return;
        }
        if(this.playerAnswered) {
            // They replied. The chat already reached the LLM for a personalized "that's right..."
            // reply; let that land before Bip flies to the near-twin.
            if(since >= REPLY_LEAD) {
                this.advanceToCompare();
            }
            return;
        }
        if(this.ticks - this.lastPromptTick >= EXAMINE_NUDGE_DELAY) {
            if(this.nudges < MAX_NUDGES) {
                this.emitBeat("examine_nudge");
                this.nudges++;
                this.lastPromptTick = this.ticks;
            } else {
                this.advanceToCompare();
            }
        }
    }

    private void startChallenge() {
        this.nudges = 0;
        Flower different = this.chooseMostDifferent(this.focusFlower);
        Map<String, Object> extra = new LinkedHashMap<>();
        String requested = different == null
                ? "a really different one"
                : "a " + this.flowerManager.getFlowerMinecraftColor(different) + " one";
        extra.put("requested", requested);
        this.emitBeat("challenge", extra);
        this.lastPromptTick = this.ticks;
        this.enterPhase(PHASE_CHALLENGE);
    }

    private void tickCompare() {
        if(this.ticks - this.phaseEnteredTick >= COMPARE_DWELL) {
            this.startChallenge();
        }
    }

    private void tickChallenge() {
        if(this.pickedFlower != null) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("flower_id", this.pickedFlower.getNumber());
            extra.put("attributes", this.describeFlower(this.pickedFlower));
            extra.put("distance_desc",
                    this.describeDifference(this.focusFlower.distanceTo(this.pickedFlower)));
            this.emitBeat("distance_lesson", extra);
            this.enterPhase(PHASE_LESSON);
            return;
        }
        if(this.ticks - this.lastPromptTick >= CHALLENGE_NUDGE_DELAY) {
            if(this.nudges < MAX_NUDGES) {
                this.emitBeat("challenge_nudge");
                this.nudges++;
                this.lastPromptTick = this.ticks;
            }
            // No hard time-out here: without a pick there's nothing to teach, so keep waiting
            // (but stop nagging after MAX_NUDGES).
        }
    }

    private void tickLesson() {
        if(this.ticks - this.phaseEnteredTick >= LESSON_DWELL) {
            this.enterPhase(PHASE_DONE);
            // Hand the player a restart item so they can move on when they're ready.
            this.stateManager.endActivity();
        }
    }

    @Override
    public void tick() {
        switch(this.phase) {
            case PHASE_INTRO -> this.tickIntro();
            case PHASE_EXAMINE -> this.tickExamine();
            case PHASE_COMPARE -> this.tickCompare();
            case PHASE_CHALLENGE -> this.tickChallenge();
            case PHASE_LESSON -> this.tickLesson();
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
    public void onPlayerTargetNewEntity(ServerPlayerEntity player, boolean exists, int id) {
        // Keep the default behaviour: show the aimed flower's values on the sidebar and hand over
        // the nest so the player can right-click to "pick" a flower during the challenge.
        super.onPlayerTargetNewEntity(player, exists, id);
    }

    @Override
    public void onPlayerPollinate(Flower flower, Vec3d flowerMinecraftPosition) {
        // No pollination in this stage — a right-click is how the player picks their answer to the
        // "find a different flower" challenge. Record it; the phase machine reacts on the next tick.
        if(this.phase == PHASE_CHALLENGE && this.pickedFlower == null) {
            this.pickedFlower = flower;
        }
    }

    @Override
    public void onPlayerChat(ServerPlayerEntity player, String message) {
        if(this.phase == PHASE_EXAMINE) {
            this.playerAnswered = true;
        }
    }

    @Override
    public boolean timeTravelAvailable() {
        return false;
    }
}
