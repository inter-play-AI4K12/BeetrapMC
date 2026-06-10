package beetrap.btfmc.state;

import static beetrap.btfmc.BeetrapGame.AMOUNT_OF_BUDS_RANKED;
import static beetrap.btfmc.BeetrapGame.AMOUNT_OF_BUDS_TO_PLACE_DEFAULT_MODE;
import static beetrap.btfmc.state.RecommendationSystemPollinationReadyState.RECOMMENDATION_SYSTEM_ACTIVITY_STAGE_POLLINATION_TRULY_READY;

import beetrap.btfmc.flower.Flower;
import beetrap.btfmc.networking.ShowMultipleChoiceScreenS2CPayload;
import beetrap.btfmc.networking.ShowTextScreenS2CPayload;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

public class RecommendationSystemPollinationHappeningState extends BeetrapState {

    private final Vec3d pollinationCenter;
    private final int stage;
    private Flower[] newFlowerCandidates;
    private Flower[] newFlowers;
    private int ticks;
    private boolean active;
    private BeetrapState nextState;

    public RecommendationSystemPollinationHappeningState(BeetrapState state,
            Vec3d pollinationCenter, int stage) {
        super(state);
        this.pollinationCenter = pollinationCenter;
        this.active = true;
        this.stage = stage;
    }

    private void onTick0() {
        if(this.ticks != 0) {
            return;
        }

        this.beeNestController.startPollination(this.pollinationCenter);
    }

    private void tickGrowBuds() {
        // Only place buds within the pollination circle radius
        this.newFlowerCandidates = this.findFlowersWithinRadius(
                this.pollinationCenter,
                this.pollinationCircleRadius,
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
        FallingBlockEntity[] fbe;

        if(this.usingDiversifyingRankingMethod) {
            fbe = this.flowerManager.findAllFlowerEntitiesWithinRSortedByMostDistanceToCenter(
                    this.pollinationCenter,
                    this.pollinationCircleRadius);
        } else {
            fbe = this.flowerManager.findAllFlowerEntitiesWithinRSortedByLeastDistanceToCenter(
                    this.pollinationCenter,
                    this.pollinationCircleRadius);
        }

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
        this.stateManager.recordBudsRanked(
                this.newFlowers,
                this.usingDiversifyingRankingMethod,
                this.pollinationCenter,
                this.pollinationCircleRadius
        );
    }

    private void onTick20() {
        if(this.ticks != 20) {
            return;
        }

        this.tickGrowBuds();
        this.tickRankBuds();
    }

    private void tickPlaceNewFlowers() {
        this.flowerManager.removeFlowerEntities(this.newFlowerCandidates);
        this.flowerManager.placeFlowerEntities(this, this.newFlowers);

        for(Flower f : this.newFlowers) {
            if(f == null) {
                return;
            }

            this.setFlower(f.getNumber(), true);
        }
    }

    private void tickWitherFlowers() {
        FallingBlockEntity[] fbe = this.flowerManager.findAllFlowerEntitiesWithinRSortedByLeastDistanceToCenter(
                this.pollinationCenter, Double.POSITIVE_INFINITY);

        int r = 0;
        for(int i = fbe.length - 1; i >= 0 && r < this.amountOfFlowersToWither; --i) {
            Flower f = this.flowerManager.getFlowerByEntityId(this, fbe[i].getId());

            if(f.hasWithered()) {
                continue;
            }

            f.setWithered(true);
            this.flowerManager.placeFlowerEntity(this, f);
            ++r;
        }
    }

    private void onTick220() {
        if(this.ticks != 220) {
            return;
        }

        this.tickPlaceNewFlowers();
        this.tickWitherFlowers();
        this.onPollinationEnd();
    }

    public void tick() {
        if(!this.active) {
            return;
        }

        // this.ticks == 0
        this.onTick0();
        // this.ticks is in 0..19
        this.beeNestController.tickMovementAnimation(this.ticks);
        // this.ticks == 20
        this.onTick20();
        // this.ticks == 40
        this.onTick40();
        // this.ticks is in 20..219
        this.beeNestController.tickCircle(this.ticks, this.pollinationCircleRadius);
        this.beeNestController.tickSpawnPollensThatFlyTowardsNest(this.ticks, this.flowerManager,
                this.newFlowerCandidates);
        // this.ticks == 220
        this.onTick220();

        // Draw yellow lines from beehive to pollinated flowers
        this.beeNestController.tickPollinationLines(this.ticks, this.pastPollinationLocations);

        ++this.ticks;
    }

    private void onTick40() {
        if(this.ticks != 40) {
            return;
        }

        if(this.stage < 2) {
            this.showTextScreenToAllPlayers("What goes into the beehive?");
            this.showTextScreenToAllPlayers("What do flowers on the ground represent?");
            this.showTextScreenToAllPlayers("What do numbers above the flower buds represent?");
            this.showTextScreenToAllPlayers("What does the pollen circle represent?");
            this.showTextScreenToAllPlayers("How are the flowers placed in the garden?");
        } else {
            this.net.broadcastCustomPayload(new ShowMultipleChoiceScreenS2CPayload("user_profile",
                    "What goes into the User Profile(Beehive)?", "The flowers pollinated by me.",
                    "The flowers in the garden.", "The flowers I didn't pollinate."));
        }
    }

    @Override
    public void onMultipleChoiceSelectionResultReceived(String questionId, int option) {
        switch(questionId) {
            case "user_profile" -> {
                if(option == 0) {
                    this.showTextScreenToAllPlayers("Correct!");
                } else {
                    this.showTextScreenToAllPlayers("That's incorrect, try again next time.");
                }

                this.net.broadcastCustomPayload(
                        new ShowMultipleChoiceScreenS2CPayload("flower_buds_on_ground",
                                "What do the flower buds on the ground represent?",
                                "Available flowers to grow in the garden",
                                "The flowers that I like", "The flowers that I dislike"));
            }
            case "flower_buds_on_ground" -> {
                if(option == 0) {
                    this.showTextScreenToAllPlayers("Correct!");
                } else {
                    this.showTextScreenToAllPlayers("That's incorrect, try again next time.");
                }

                this.net.broadcastCustomPayload(
                        new ShowMultipleChoiceScreenS2CPayload("numbers_above_flower_buds",
                                "What do numbers above flower buds represent?",
                                "The ranking of flowers' height.", "The value of a flower feature",
                                "The ranking of the distance between a flower and the beehive"));
            }
            case "numbers_above_flower_buds" -> {
                if(option == 2) {
                    this.showTextScreenToAllPlayers("Correct!");
                } else {
                    this.showTextScreenToAllPlayers("That's incorrect, try again next time.");
                }

                this.net.broadcastCustomPayload(
                        new ShowMultipleChoiceScreenS2CPayload("pollen_circle",
                                "What does the pollen circle represent?",
                                "The range for me the fly.", "The range for new flowers to grow.",
                                "The range for flowers to die out."));
            }
            case "pollen_circle" -> {
                if(option == 1) {
                    this.showTextScreenToAllPlayers("Correct!");
                } else {
                    this.showTextScreenToAllPlayers("That's incorrect, try again next time.");
                }

                this.net.broadcastCustomPayload(
                        new ShowMultipleChoiceScreenS2CPayload("flowers_located_in_garden",
                                "How are the flowers located in the garden?",
                                "Flowers with the same colors are close together.",
                                "Flowers with multiple similar features are close together.",
                                "Flowers with the same sweetness values are closer together."));
            }
            case "flowers_located_in_garden" -> {
                if(option == 1) {
                    this.showTextScreenToAllPlayers("Correct!");
                } else {
                    this.showTextScreenToAllPlayers("That's incorrect, try again next time.");
                }
            }
        }
    }

    private boolean activityShouldEnd() {
        return this.computeDiversityScore() < this.stateManager.getInitialDiversityScore() / 2;
    }

    private void onPollinationEnd() {
        this.active = false;

        if(this.activityShouldEnd()) {
            this.stateManager.endActivity();
            this.nextState = new TimeTravelableBeetrapState(this);
            this.net.broadcastCustomPayload(new ShowTextScreenS2CPayload(
                    ShowTextScreenS2CPayload.lineWrap(
                            "You just experienced the filter bubble effect!", 50)));
        } else {
            this.nextState = new RecommendationSystemPollinationReadyState(this, this.stage + 1,
                    RECOMMENDATION_SYSTEM_ACTIVITY_STAGE_POLLINATION_TRULY_READY);
        }

        this.setBeeNestMinecraftPosition(this.beeNestController.getBeeNestPosition());

        for(ServerPlayerEntity player : this.world.getPlayers()) {
            this.interaction.giveTimeTravelItemsToPlayer(player);
        }

        this.showTextScreenToAllPlayers(
                "Look around! Some new flowers appeared and others died, use the time travel feature to compare the diversity score before and after pollination!");
    }

    @Override
    public boolean hasNextState() {
        return !this.active;
    }

    @Override
    public BeetrapState getNextState() {
        return this.nextState;
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
