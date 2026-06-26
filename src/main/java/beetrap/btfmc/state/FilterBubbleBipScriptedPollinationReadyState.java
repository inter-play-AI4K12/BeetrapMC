package beetrap.btfmc.state;

import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_POLLINATION_INITIATED;

import beetrap.btfmc.flower.Flower;
import java.util.List;
import java.util.Map;
import net.minecraft.entity.ItemEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Vec3d;

/**
 * Filter Bubble activity — Bip narrates verbatim scripted lines instead of showing text screens.
 */
public class FilterBubbleBipScriptedPollinationReadyState extends PollinationReadyState {

    // Per-task escalation: gentle nudge ~10s of stalling, explicit help ~22s. The clock resets
    // whenever the player engages (moves their crosshair onto a flower), so it only fires for a
    // player who is genuinely stuck on the current sub-task.
    private final HintEscalator hints = new HintEscalator(200, 440);
    private boolean welcomed;
    private boolean hasTargetedFlower;
    private boolean lookHinted;       // a "look at a flower" nudge has fired at least once
    private boolean pollinateGuided;  // the "now pollinate" guidance has been given on first aim

    public FilterBubbleBipScriptedPollinationReadyState(BeetrapState parent, int stage) {
        super(parent, stage);
    }

    /** Report a semantic beat; Python owns Bip's words/movement for it. */
    private void emitBeat(String beat) {
        this.stateManager.recordAgentEvent("activity_beat",
                Map.of("activity", "pollinate", "beat", beat));
    }

    private void clearItems() {
        List<? extends ItemEntity> entities = this.world.getEntitiesByType(
                TypeFilter.instanceOf(ItemEntity.class), e -> true);
        for (ItemEntity ie : entities) {
            ie.kill(this.world);
        }
        if (!entities.isEmpty()) {
            for (ServerPlayerEntity spe : this.world.getPlayers()) {
                this.interaction.giveTimeTravelItemsToPlayer(spe);
            }
        }
    }

    @Override
    public void tick() {
        this.clearItems();
        if (this.stage == 0) {
            // Intro fires promptly — "pollinate any flower and let's see what happens".
            if (this.ticks == 20) {
                this.emitBeat("intro");
                this.welcomed = true;
            }

            if (beetrap.btfmc.BipFeatures.TIERED_HINTS) {
                // Escalate only after the intro, and only on the sub-task the player is stuck on.
                if (this.welcomed) {
                    int tier = this.hints.tick();
                    if (tier >= 0) {
                        String beat;
                        if (this.hasTargetedFlower) {
                            beat = "stuck_pollinate_" + tier;
                        } else {
                            beat = "stuck_look_" + tier;
                            this.lookHinted = true;
                        }
                        this.emitBeat(beat);
                    }
                }
            } else if (this.ticks == 60) {
                // Hints off: a single full instruction.
                this.emitBeat("instructions");
            }
        }

        this.beeNestController.tickPollinationLines(this.ticks, this.pastPollinationLocations);
        ++this.ticks;
    }

    @Override
    public void onPlayerTargetNewEntity(ServerPlayerEntity player, boolean exists, int id) {
        // Keep the default behaviour (auto-equips the nest when aiming at a valid flower)...
        super.onPlayerTargetNewEntity(player, exists, id);
        // ...and track the sub-task. Aiming at a real garden flower completes "look at a flower".
        boolean wasTargeted = this.hasTargetedFlower;
        Flower f = this.flowerManager.getFlowerByEntityId(this, id);
        if (exists && f != null && this.hasFlower(f.getNumber()) && !f.hasWithered()) {
            this.hasTargetedFlower = true;
        }
        // The moment a player who'd been stuck looking finally aims at a flower, jump straight to
        // the pollinate guidance instead of waiting out another idle timer (which felt like silence).
        if (!wasTargeted && this.hasTargetedFlower && this.lookHinted && !this.pollinateGuided) {
            this.emitBeat("stuck_pollinate_0");
            this.pollinateGuided = true;
        }
        // Moving the crosshair around the garden is active engagement — don't nag a busy player.
        this.hints.reset();
    }

    @Override
    public void onPlayerPollinate(Flower flower, Vec3d flowerMinecraftPosition) {
        this.hasNextState = true;
        this.pastPollinationLocations.add(flowerMinecraftPosition);
        Vec3d pl = this.computeAveragePastPollinationPositions();
        this.nextState = new FilterBubbleBipScriptedPollinationHappeningState(this, pl, this.stage);
        this.net.beetrapLog(BEETRAP_LOG_ID_POLLINATION_INITIATED, "");
    }

    @Override
    public boolean timeTravelAvailable() {
        return this.stage != 0;
    }
}
