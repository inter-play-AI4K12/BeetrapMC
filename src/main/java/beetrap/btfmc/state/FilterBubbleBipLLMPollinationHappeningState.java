package beetrap.btfmc.state;

import static beetrap.btfmc.BeetrapGame.AMOUNT_OF_BUDS_RANKED;
import static beetrap.btfmc.BeetrapGame.AMOUNT_OF_BUDS_TO_PLACE_DEFAULT_MODE;

import beetrap.btfmc.flower.Flower;
import java.util.Map;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

/**
 * Filter Bubble activity — Bip receives contextual cues and improvises its own narration,
 * combining say and fly_to commands to physically demonstrate what is happening.
 */
public class FilterBubbleBipLLMPollinationHappeningState extends BeetrapState {

    private final Vec3d pollinationCenter;
    private final int stage;
    private Flower[] newFlowerCandidates;
    private Flower[] newFlowers;
    private int ticks;
    private boolean active;
    private BeetrapState nextState;

    public FilterBubbleBipLLMPollinationHappeningState(BeetrapState state,
            Vec3d pollinationCenter, int stage) {
        super(state);
        this.pollinationCenter = pollinationCenter;
        this.active = true;
        this.stage = stage;
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
            ++r;
        }
        this.stateManager.recordBudsRanked(this.newFlowers, this.usingDiversifyingRankingMethod,
                this.pollinationCenter, this.pollinationCircleRadius);
    }

    private void onTick20() {
        if (this.ticks != 20) return;
        this.tickGrowBuds();
        if (this.stage == 0) {
            // Bip decides how to explain the ranking — and should fly to the top-ranked bud.
            this.stateManager.recordAgentEvent("activity_context", Map.of(
                    "description",
                    "Flower buds numbered 1, 2, 3 just appeared around the garden. "
                    + "The beehive will pollinate them in ranked order. "
                    + "Fly to the bud ranked 1 and explain: "
                    + "buds closer to the beehive are ranked higher because they are more similar "
                    + "to flowers already pollinated."));
        }
        this.tickRankBuds();
    }

    private void onTick210() {
        if (this.ticks != 210) return;
        if (this.stage == 0) {
            // Brief heads-up before the bloom — Bip can fly to the beehive or a bud.
            this.stateManager.recordAgentEvent("activity_context", Map.of(
                    "description",
                    "The garden is about to bloom in a few seconds. "
                    + "Tell the player to watch closely — something is about to happen to flower diversity."));
        }
    }

    private void tickPlaceNewFlowers() {
        this.flowerManager.removeFlowerEntities(this.newFlowerCandidates);
        this.flowerManager.placeFlowerEntities(this, this.newFlowers);
        for (Flower f : this.newFlowers) {
            if (f == null) return;
            this.setFlower(f.getNumber(), true);
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
            ++r;
        }
    }

    private void onTick220() {
        if (this.ticks != 220) return;
        this.tickPlaceNewFlowers();
        this.tickWitherFlowers();
        this.onPollinationEnd();
    }

    private boolean activityShouldEnd() {
        return this.computeDiversityScore() < this.stateManager.getInitialDiversityScore() / 2;
    }

    private void onPollinationEnd() {
        this.active = false;
        // When the clocks are first handed over, optionally have Bip fly to the player and
        // physically present them instead of letting them appear silently.
        String handover = beetrap.btfmc.BipFeatures.TIME_TRAVEL_HANDOVER
                ? " Then fly directly to the player and hand them two clock tools: 'Back' rewinds "
                + "the garden and 'Forward' replays it. Tell them to right-click a clock to watch "
                + "how diversity changed over time."
                : " Mention the time-travel clock items in the player's inventory.";
        if (this.activityShouldEnd()) {
            this.stateManager.endActivity();
            this.nextState = new TimeTravelableBeetrapState(this);
            // Bip explains what happened — should fly to withered flowers to show the effect.
            this.stateManager.recordAgentEvent("activity_context", Map.of(
                    "description",
                    "Diversity just dropped below 50% — the filter bubble effect has happened! "
                    + "Fly to a withered (grey) flower and explain: "
                    + "repeatedly choosing similar flowers made diverse ones die off."
                    + handover));
        } else {
            this.nextState = new FilterBubbleBipLLMPollinationReadyState(this, this.stage + 1);
            if (this.stage == 0) {
                // First round done — Bip reflects on what changed and hands over the clocks.
                this.stateManager.recordAgentEvent("activity_context", Map.of(
                        "description",
                        "The first pollination round just finished. New flowers grew and some died. "
                        + "Ask the player what they notice about flower diversity."
                        + handover));
            }
        }
        this.setBeeNestMinecraftPosition(this.beeNestController.getBeeNestPosition());
        for (ServerPlayerEntity player : this.world.getPlayers()) {
            this.interaction.giveTimeTravelItemsToPlayer(player);
        }
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
        this.onTick210();
        this.onTick220();
        this.beeNestController.tickPollinationLines(this.ticks, this.pastPollinationLocations);
        ++this.ticks;
    }

    @Override
    public boolean hasNextState() { return !this.active; }

    @Override
    public BeetrapState getNextState() { return this.nextState; }

    @Override
    public void onPlayerTargetNewEntity(ServerPlayerEntity player, boolean exists, int id) {
        super.onPlayerTargetNewEntity(player, false, id);
    }

    @Override
    public boolean timeTravelAvailable() { return false; }
}
