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
 * Filter Bubble activity — Bip receives contextual cues and improvises its own narration,
 * combining say and fly_to commands to physically demonstrate what is happening.
 */
public class FilterBubbleBipLLMPollinationReadyState extends PollinationReadyState {

    // Per-task escalation: gentle nudge ~10s of stalling, explicit help ~22s. The clock resets
    // whenever the player engages (moves their crosshair onto a flower), so it only fires for a
    // player who is genuinely stuck on the current sub-task.
    private final HintEscalator hints = new HintEscalator(200, 440);
    private boolean welcomed;
    private boolean hasTargetedFlower;

    public FilterBubbleBipLLMPollinationReadyState(BeetrapState parent, int stage) {
        super(parent, stage);
    }

    // Sub-task 1 = look at a flower (which auto-equips the nest); sub-task 2 = pollinate it.
    private String lookHint(int tier) {
        return tier == 0
                ? "The player hasn't aimed at any flower yet. Briefly encourage them to point "
                + "their crosshair at a flower they like — do NOT explain the full steps yet."
                : "The player still hasn't aimed at a flower. Tell them clearly to look directly "
                + "at any flower in the garden.";
    }

    private String pollinateHint(int tier) {
        return tier == 0
                ? "The player is aiming at a flower but hasn't pollinated. The bee nest is now in "
                + "their hotbar slot 5 — nudge them to use it, without over-explaining."
                : "The player still hasn't pollinated. Clearly tell them: press 5 to hold the bee "
                + "nest, then right-click the flower to pollinate it.";
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
            if (this.ticks == 20) {
                // Welcome fires promptly — it's an intro, not a hint.
                this.stateManager.recordAgentEvent("activity_context", Map.of(
                        "description",
                        "The player has just entered the magic garden for the first time. "
                        + "Welcome them warmly and tell them to pollinate flowers they like. "
                        + "Fly to a nearby flower to show them where to go."));
                this.welcomed = true;
            }

            if (beetrap.btfmc.BipFeatures.TIERED_HINTS && this.welcomed) {
                // Escalate only on the sub-task the player is actually stuck on.
                int tier = this.hints.tick();
                if (tier >= 0) {
                    String description = this.hasTargetedFlower
                            ? this.pollinateHint(tier) : this.lookHint(tier);
                    this.stateManager.recordAgentEvent("activity_context",
                            Map.of("description", description));
                }
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
        Flower f = this.flowerManager.getFlowerByEntityId(this, id);
        if (exists && f != null && this.hasFlower(f.getNumber()) && !f.hasWithered()) {
            this.hasTargetedFlower = true;
        }
        // Moving the crosshair around the garden is active engagement — don't nag a busy player.
        this.hints.reset();
    }

    @Override
    public void onPlayerPollinate(Flower flower, Vec3d flowerMinecraftPosition) {
        this.hasNextState = true;
        this.pastPollinationLocations.add(flowerMinecraftPosition);
        Vec3d pl = this.computeAveragePastPollinationPositions();
        this.nextState = new FilterBubbleBipLLMPollinationHappeningState(this, pl, this.stage);
        this.net.beetrapLog(BEETRAP_LOG_ID_POLLINATION_INITIATED, "");
    }

    @Override
    public boolean timeTravelAvailable() {
        return this.stage != 0;
    }
}
