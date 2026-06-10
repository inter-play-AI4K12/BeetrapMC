package beetrap.btfmc.state;

import static beetrap.btfmc.BeetrapGame.AMOUNT_OF_BUDS_RANKED;
import static beetrap.btfmc.BeetrapGame.AMOUNT_OF_BUDS_TO_PLACE_DEFAULT_MODE;

import beetrap.btfmc.flower.Flower;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

public class MysteriousFifthPollinationHappeningState extends BeetrapState {

    private final Vec3d pollinationCenter;
    private final int stage;
    private Flower[] newFlowerCandidates;
    private Flower[] newFlowers;
    private int ticks;
    private boolean active;
    private BeetrapState nextState;

    public MysteriousFifthPollinationHappeningState(BeetrapState state, Vec3d pollinationCenter,
            int stage) {
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

    private void onPollinationEnd() {
        this.active = false;
        this.nextState = new ExploreFilterBubbleEffectPollinationReadyState(this, this.stage + 1);
        this.setBeeNestMinecraftPosition(this.beeNestController.getBeeNestPosition());
        for(ServerPlayerEntity player : this.world.getPlayers()) {
            this.interaction.giveTimeTravelItemsToPlayer(player);
        }
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
