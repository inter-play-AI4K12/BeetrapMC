package beetrap.btfmc.state;

import static beetrap.btfmc.BeetrapGame.AMOUNT_OF_BUDS_RANKED;
import static beetrap.btfmc.BeetrapGame.AMOUNT_OF_BUDS_TO_PLACE_DEFAULT_MODE;

import beetrap.btfmc.flower.Flower;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

/**
 * One pollination round narrated by Bip: the hive flies over, buds sprout in the pollen circle and
 * get ranked, pollen flies in, the top buds bloom and the farthest flowers fade. Subclasses queue
 * Bip's lines at three moments ({@link #onStart}, {@link #onBudsShown}, {@link #onBloom}). The
 * animation waits just before the bloom while Bip is still talking, so he never narrates something
 * that has already vanished, and the round ends only after his last line.
 */
abstract class BipPollinationHappeningState extends BeetrapState {

    protected static final int BUDS_TICK = 25;    // buds sprout + are numbered at 20
    protected static final int HOLD_TICK = 200;   // the animation waits here while Bip talks
    // Must stay AFTER the pollen-fly window (BeeNestController MAX_CIRCLE_TICKS = 220): the bloom
    // removes the bud entities, and the animation dereferences them until then.
    protected static final int BLOOM_TICK = 240;

    protected final Vec3d pollinationCenter;
    protected final int stage;
    protected final int pollinatedFlowerId;
    protected final BipScript script;
    protected Flower[] newFlowerCandidates;
    protected Flower[] newFlowers;
    protected long ticks;     // wall clock for this round
    protected long anim;      // animation clock, paused at HOLD_TICK while Bip talks
    protected int newFlowerId = -1;
    protected int witheredFlowerId = -1;
    private boolean active = true;
    private boolean ended;
    private boolean budsShown;
    private boolean bloomed;
    private BeetrapState nextState;

    protected BipPollinationHappeningState(BeetrapState state, Vec3d pollinationCenter, int stage,
            int pollinatedFlowerId, String activity) {
        super(state);
        this.pollinationCenter = pollinationCenter;
        this.stage = stage;
        this.pollinatedFlowerId = pollinatedFlowerId;
        this.script = this.bipScript(activity, () -> this.ticks);
    }

    /** Right after the player pollinated (the hive starts flying). */
    protected void onStart() { }

    /** The buds are up and numbered. */
    protected void onBudsShown() { }

    /** The bloom just happened; {@code ending} = the garden's diversity fell below half. */
    protected abstract void onBloom(boolean ending);

    /** The ready state for the next round. */
    protected abstract BeetrapState nextRoundState();

    protected static Map<String, Object> flower(int flowerId) {
        Map<String, Object> details = new LinkedHashMap<>();
        if(flowerId >= 0) {
            details.put("flower_id", flowerId);
        }
        return details;
    }

    protected boolean activityShouldEnd() {
        return this.computeDiversityScore() < this.stateManager.getInitialDiversityScore() / 2;
    }

    // --- Garden mechanics ---------------------------------------------------------------------

    private void growBuds() {
        this.newFlowerCandidates = this.findFlowersWithinRadius(this.pollinationCenter,
                this.pollinationCircleRadius, AMOUNT_OF_BUDS_TO_PLACE_DEFAULT_MODE);
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

    private void rankBuds() {
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

    private void bloom() {
        this.flowerManager.removeFlowerEntities(this.newFlowerCandidates);
        this.flowerManager.placeFlowerEntities(this, this.newFlowers);
        for(Flower f : this.newFlowers) {
            if(f == null) {
                break;
            }
            this.setFlower(f.getNumber(), true);
            if(this.newFlowerId < 0) {
                this.newFlowerId = f.getNumber();
            }
        }
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

    private void finishRound() {
        if(this.ended) {
            return;
        }
        this.ended = true;
        this.active = false;
        this.stateManager.giveTimeTravelItemsToAllPlayers();
        if(this.activityShouldEnd()) {
            this.stateManager.completeBipStage();
            this.nextState = new TimeTravelableBeetrapState(this);
        } else {
            this.nextState = this.nextRoundState();
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
        if(this.anim == 0 && this.ticks == 0) {
            this.beeNestController.startPollination(this.pollinationCenter);
            this.onStart();
        }
        this.beeNestController.tickMovementAnimation(this.anim);
        if(this.anim == 20 && this.newFlowerCandidates == null) {
            this.growBuds();
            this.rankBuds();
        }
        if(this.anim == BUDS_TICK && !this.budsShown) {
            this.budsShown = true;
            this.onBudsShown();
        }

        boolean talking = this.script.tick();
        boolean holding = this.anim == HOLD_TICK && talking;

        // While holding, keep the circle and pollen alive by cycling a visual clock in [20, 220).
        long visual = holding ? 20 + this.ticks % 200 : this.anim;
        this.beeNestController.tickCircle(visual, this.pollinationCircleRadius);
        if(this.newFlowerCandidates != null) {
            this.beeNestController.tickSpawnPollensThatFlyTowardsNest(
                    visual, this.flowerManager, this.newFlowerCandidates);
        }

        if(this.anim == BLOOM_TICK && !this.bloomed) {
            this.bloomed = true;
            this.bloom();
            this.onBloom(this.activityShouldEnd());
            talking = this.script.tick();
        }
        if(this.bloomed && !talking) {
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
        return this.script.chatContext();
    }

    @Override
    public void onPlayerChat(ServerPlayerEntity player, String message) {
        this.script.onPlayerChat();
    }

    @Override
    public void onMultipleChoiceSelectionResultReceived(String questionId, int option) {
        this.script.onChoice(questionId, option);
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
