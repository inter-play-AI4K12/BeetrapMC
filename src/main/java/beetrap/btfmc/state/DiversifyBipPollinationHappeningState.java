package beetrap.btfmc.state;

import static beetrap.btfmc.BeetrapGame.AMOUNT_OF_BUDS_RANKED;
import static beetrap.btfmc.BeetrapGame.AMOUNT_OF_BUDS_TO_PLACE_DEFAULT_MODE;
import static beetrap.btfmc.BeetrapGame.CHANGE_RANKING_METHOD_LEVER_POSITION;
import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_POLLINATION_CIRCLE_RADIUS_INCREASED;
import static beetrap.btfmc.state.DiversifyBipPollinationReadyState.ACTIVITY;

import beetrap.btfmc.flower.Flower;
import beetrap.btfmc.networking.BeginSubActivityS2CPayload;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeverBlock;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Game 4 — one pollination round (Python activity {@code diversify}), following the Diversification
 * rows of the "Make Bip Great Again" sheet. Round 0: Bip coaches the player to grow the pollen
 * circle (press B near it); at radius 5 a lever pops up and Bip asks them to flick it (it switches
 * the hive to pick DIFFERENT flowers); only then do the buds bloom. Afterwards Bip checks the
 * diversity score, asks why it went up, and cheers them on until the target is beaten.
 */
public class DiversifyBipPollinationHappeningState extends BeetrapState {

    private static final int SUB_STAGE_GROW_CIRCLE = 0;
    private static final int SUB_STAGE_FLIP_LEVER = 1;
    private static final int SUB_STAGE_BLOOMING = 2;
    private static final double LEVER_RADIUS = 5;
    private static final long GROW_GIVE_UP = 2400;   // ~2 min: Bip grows the circle for them
    private static final long LEVER_GIVE_UP = 1200;  // ~1 min: bloom anyway
    private static final long BLOOM_DELAY = 200;     // buds bloom ~10s after the setup is done

    private final Vec3d pollinationCenter;
    private final int stage;
    private final double targetDiversityScore;
    private final BipScript script = this.bipScript(ACTIVITY, () -> this.ticks);
    private Flower[] newFlowerCandidates;
    private Flower[] newFlowers;
    private long ticks;
    private int subStage;
    private long bloomReadyTick = -1;
    private boolean active = true;
    private boolean ended;
    private boolean bloomed;
    private boolean leverPlaced;
    private double radiusAtPressB;
    private BeetrapState nextState;
    private int newFlowerId = -1;

    public DiversifyBipPollinationHappeningState(BeetrapState state, Vec3d pollinationCenter,
            int stage, double targetDiversityScore) {
        super(state);
        this.pollinationCenter = pollinationCenter;
        this.stage = stage;
        this.targetDiversityScore = targetDiversityScore;
        this.subStage = stage == 0 ? SUB_STAGE_GROW_CIRCLE : SUB_STAGE_BLOOMING;
    }

    private Map<String, Object> target() {
        return Map.of("target", (int)this.targetDiversityScore);
    }

    private void emitBeat(String beat) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("activity", ACTIVITY);
        details.put("beat", beat);
        this.stateManager.recordAgentEvent("activity_beat", details);
    }

    // --- Round 0 coaching -------------------------------------------------------------------

    private void queueCoaching() {
        String circleContext = "CONTEXT: the player needs to make the pollen circle bigger: stand "
                + "near the glowing circle and press B a few times. Coach them to do it.";
        this.script.then(
                BipScript.run(() -> this.radiusAtPressB = this.pollinationCircleRadius),
                BipScript.say("press_b"),
                BipScript.waitFor(() -> this.pollinationCircleRadius > this.radiusAtPressB, 400, 2,
                        tier -> this.emitBeat("press_b_nudge_" + tier), GROW_GIVE_UP,
                        circleContext),
                BipScript.say("circle_grew"),
                BipScript.waitFor(() -> this.subStage != SUB_STAGE_GROW_CIRCLE, 600, 1,
                        tier -> this.emitBeat("press_b_nudge_1"), GROW_GIVE_UP, circleContext),
                BipScript.run(() -> {
                    if(this.subStage == SUB_STAGE_GROW_CIRCLE) {
                        // Never let a stuck player stall the activity: grow it for them.
                        this.pollinationCircleRadius = Math.max(this.pollinationCircleRadius,
                                LEVER_RADIUS);
                        this.onRadiusChanged();
                    }
                }),
                BipScript.say("lever_appeared"),
                BipScript.say("lever_ask"),
                BipScript.waitFor(() -> this.usingDiversifyingRankingMethod, 400, 2,
                        tier -> this.emitBeat("lever_nudge_" + tier), LEVER_GIVE_UP,
                        "CONTEXT: a lever just popped up near the garden. Ask the player to flick "
                                + "it and guess what it does. Don't explain it yet."),
                BipScript.run(this::startBlooming));
    }

    private void placeLever() {
        if(this.leverPlaced) {
            return;
        }
        this.leverPlaced = true;
        BlockPos lever = CHANGE_RANKING_METHOD_LEVER_POSITION;
        this.world.setBlockState(new BlockPos(lever.getX(), lever.getY() - 1, lever.getZ() - 1),
                Blocks.STONE.getDefaultState());
        this.world.setBlockState(new BlockPos(lever.getX(), lever.getY(), lever.getZ() - 1),
                Blocks.STONE.getDefaultState());
        BlockState bs = Blocks.LEVER.getDefaultState().with(LeverBlock.FACING, Direction.SOUTH);
        this.world.setBlockState(lever, bs);
    }

    private void onRadiusChanged() {
        if(this.subStage == SUB_STAGE_GROW_CIRCLE && this.pollinationCircleRadius >= LEVER_RADIUS) {
            this.subStage = SUB_STAGE_FLIP_LEVER;
            this.placeLever();
        }
    }

    private void startBlooming() {
        this.subStage = SUB_STAGE_BLOOMING;
        this.bloomReadyTick = this.ticks;
    }

    // --- After the bloom --------------------------------------------------------------------

    private boolean targetBeaten() {
        return this.computeDiversityScore() > this.targetDiversityScore;
    }

    private void queueAfterBloom() {
        boolean success = this.targetBeaten();
        this.script.then(BipScript.say("bloom_check"));
        if(this.stage == 0 && this.usingDiversifyingRankingMethod) {
            this.script.then(
                    BipScript.ask("why",
                            "CONTEXT: the diversity score just went up after the circle got bigger "
                                    + "and the lever made the hive pick different flowers. React "
                                    + "to the player's idea in one short line, don't explain it "
                                    + "fully yet."),
                    BipScript.say("why_explain"));
        }
        if(success) {
            this.script.then(
                    BipScript.say("success"),
                    BipScript.say("lesson"),
                    BipScript.say("complete"));
        } else if(this.computeDiversityScore() >= this.targetDiversityScore * 0.85) {
            this.script.then(BipScript.say("almost", this::target));
        } else {
            this.script.then(BipScript.say("keep_going_" + (this.stage % 2)));
        }
    }

    private void finishRound() {
        if(this.ended) {
            return;
        }
        this.ended = true;
        this.active = false;
        this.stateManager.giveTimeTravelItemsToAllPlayers();
        if(this.targetBeaten()) {
            this.stateManager.completeBipStage();
            this.nextState = new TimeTravelableBeetrapState(this);
        } else {
            this.nextState = new DiversifyBipPollinationReadyState(this, this.stage + 1,
                    this.targetDiversityScore);
        }
        this.stateManager.armBipTimeTravelNarration();
        this.setBeeNestMinecraftPosition(this.beeNestController.getBeeNestPosition());
    }

    // --- Garden mechanics (as DiversificationPollinationHappeningState) ---------------------

    private void growBuds() {
        this.newFlowerCandidates = this.findFlowersWithinRadius(this.pollinationCenter,
                this.pollinationCircleRadius, AMOUNT_OF_BUDS_TO_PLACE_DEFAULT_MODE);
        this.flowerManager.placeBuds(this, this.newFlowerCandidates);
    }

    private boolean isNewFlowerCandidate(Flower f) {
        if(f == null || this.newFlowerCandidates == null) {
            return false;
        }
        for(Flower g : this.newFlowerCandidates) {
            if(g == null) {
                return false;
            }
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
        for(FallingBlockEntity e : fbe) {
            e.setCustomName(Text.of(""));
            e.setCustomNameVisible(false);
        }
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
            ++r;
        }
    }

    @Override
    public void tick() {
        if(!this.active) {
            return;
        }
        if(this.ticks == 0) {
            this.beeNestController.startPollination(this.pollinationCenter);
            if(this.subStage == SUB_STAGE_BLOOMING) {
                this.bloomReadyTick = 20;
            }
        }
        this.beeNestController.tickMovementAnimation(this.ticks);

        if(this.ticks == 20) {
            this.growBuds();
            if(this.stage == 0) {
                this.net.broadcastCustomPayload(new BeginSubActivityS2CPayload(
                        BeginSubActivityS2CPayload.SUB_ACTIVITY_PRESS_B_TO_INCREASE_POLLINATION_RADIUS));
                this.queueCoaching();
            }
        }
        this.beeNestController.tickCircleBetweenAAndInfinity(this.ticks,
                this.pollinationCircleRadius, 20);

        if(this.interaction.rankingMethodLeverChanged()) {
            DiversifyBipPollinationReadyState.applyLever(this,
                    this.interaction.isChangeRankingMethodLeverPowered(),
                    () -> this.emitBeat("lever_on"), () -> this.emitBeat("lever_off"));
        }

        boolean talking = this.script.tick();

        if(this.subStage == SUB_STAGE_BLOOMING && !this.bloomed && this.ticks >= 20) {
            this.rankBuds();
            this.beeNestController.tickSpawnPollensThatFlyTowardsNest(
                    20 + (this.ticks % 200), this.flowerManager, this.newFlowerCandidates);
            if(this.ticks - this.bloomReadyTick >= BLOOM_DELAY && !talking) {
                this.bloomed = true;
                this.bloom();
                this.queueAfterBloom();
                talking = this.script.tick();
            }
        }
        if(this.bloomed && !talking) {
            this.finishRound();
        }

        this.beeNestController.tickPollinationLines(this.ticks, this.pastPollinationLocations);
        ++this.ticks;
    }

    @Override
    public void onPollinationCircleRadiusIncreaseRequested(double a) {
        if(this.subStage == SUB_STAGE_GROW_CIRCLE) {
            this.pollinationCircleRadius = this.pollinationCircleRadius + a;
            this.growBuds();
            this.rankBuds();
            this.net.beetrapLog(BEETRAP_LOG_ID_POLLINATION_CIRCLE_RADIUS_INCREASED,
                    "Current radius: " + this.pollinationCircleRadius);
            this.onRadiusChanged();
        }
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
    public void onPlayerTargetNewEntity(ServerPlayerEntity player, boolean exists, int id) {
        super.onPlayerTargetNewEntity(player, false, id);
    }

    @Override
    public boolean timeTravelAvailable() {
        return false;
    }

    @Override
    public float getGardenInformationBossBarPercent() {
        return (float)(this.computeDiversityScore() / this.targetDiversityScore);
    }
}
