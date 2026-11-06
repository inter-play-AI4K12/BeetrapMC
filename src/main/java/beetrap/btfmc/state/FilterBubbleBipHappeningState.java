package beetrap.btfmc.state;

import static beetrap.btfmc.state.FilterBubbleBipReadyState.ACTIVITY;

import net.minecraft.util.math.Vec3d;

/**
 * Game 2 — one pollination round (Python activity {@code pollinate}). The first round is narrated
 * step by step in the order it happens on screen: the hive flies over, buds pop up, their numbers,
 * the pollen circle, the pollen, then the bloom and a flower fading. Bip asks why it faded (a hint,
 * the full answer is game 3's), introduces the diversity score, and hands over the clocks. Round 2
 * is shorter; later rounds are quiet. When diversity falls below half, Bip wraps up and bridges to
 * game 3 ("I'll show you in the next stage").
 */
public class FilterBubbleBipHappeningState extends BipPollinationHappeningState {

    public FilterBubbleBipHappeningState(BeetrapState state, Vec3d pollinationCenter, int stage,
            int pollinatedFlowerId) {
        super(state, pollinationCenter, stage, pollinatedFlowerId, ACTIVITY);
    }

    @Override
    protected void onStart() {
        if(this.stage == 0) {
            this.script.then(BipScript.say("first_pollinated"), BipScript.say("hive_moved"));
        }
    }

    @Override
    protected void onBudsShown() {
        if(this.stage == 0) {
            this.script.then(
                    BipScript.say("buds", () -> flower(this.pollinatedFlowerId)),
                    BipScript.say("numbers"),
                    BipScript.say("circle"),
                    BipScript.say("pollen"));
        } else if(this.stage == 1) {
            this.script.then(BipScript.say("buds_again", () -> flower(this.pollinatedFlowerId)));
        }
    }

    @Override
    protected void onBloom(boolean ending) {
        if(this.stage == 0) {
            this.script.then(
                    BipScript.say("new_flowers", () -> flower(this.newFlowerId)),
                    BipScript.say("wither", () -> flower(this.witheredFlowerId)),
                    BipScript.ask("why_dead",
                            "CONTEXT: you asked why that flower faded. React to their idea in one "
                                    + "short line and give a small hint (look at where it was, far "
                                    + "from the flower they picked). Don't give the full answer, "
                                    + "the next stage is all about that."),
                    BipScript.say("why_dead_hint"),
                    BipScript.say("diversity_intro"),
                    BipScript.say("diversity_meaning"),
                    BipScript.say("diversity_dropped"),
                    BipScript.clocks(this.stateManager),
                    BipScript.say("try_clocks"));
            if(!ending) {
                this.script.then(BipScript.say("again"));
            }
        } else if(this.stage == 1) {
            this.script.then(
                    BipScript.say("new_flowers_again", () -> flower(this.newFlowerId)),
                    BipScript.say("diversity_check"));
        } else if(!ending) {
            this.script.then(BipScript.say("keep_going_" + (this.stage % 2)));
        }

        if(ending) {
            this.script.then(
                    BipScript.say("garden_died"),
                    BipScript.say("diversity_end"),
                    BipScript.say("bridge"),
                    BipScript.say("lets_go"));
        }
    }

    @Override
    protected BeetrapState nextRoundState() {
        return new FilterBubbleBipReadyState(this, this.stage + 1);
    }
}
