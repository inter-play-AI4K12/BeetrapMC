package beetrap.btfmc.state;

import static beetrap.btfmc.BeetrapGame.AMOUNT_OF_FLOWERS_TO_WITHER_DEFAULT_MODE;
import static beetrap.btfmc.BeetrapGame.FLOWER_POOL_FLOWER_COUNT;
import static beetrap.btfmc.BeetrapGame.INITIAL_FLOWER_COUNT;
import static beetrap.btfmc.BeetrapGame.INITIAL_POLLINATION_CIRCLE_RADIUS;
import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_TIME_MACHINE_BACKWARD;
import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_TIME_MACHINE_FORWARD;

import beetrap.btfmc.BeeNestController;
import beetrap.btfmc.GardenInformationBossBar;
import beetrap.btfmc.PlayerInteractionService;
import beetrap.btfmc.flower.Flower;
import beetrap.btfmc.flower.FlowerManager;
import beetrap.btfmc.flower.FlowerPool;
import beetrap.btfmc.flower.FlowerValueScoreboardDisplayerService;
import beetrap.btfmc.networking.NetworkingService;
import beetrap.btfmc.networking.PlayerTimeTravelRequestC2SPayload.Operations;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector2d;

public class BeetrapStateManager {

    private static final Logger LOG = LogManager.getLogger(BeetrapState.class);
    private static final double PI_OVER_FOUR = Math.PI / 4;
    private static final double THREE_PI_OVER_FOUR = 3 * Math.PI / 4;
    private static final double FIVE_PI_OVER_FOUR = 5 * Math.PI / 4;
    private static final double SEVEN_PI_OVER_FOUR = 7 * Math.PI / 4;
    private final ServerWorld world;
    private final List<BeetrapState> oldBeetrapStates;
    private final FlowerManager flowerManager;
    private final GardenInformationBossBar gardenInformationBossBar;
    private final NetworkingService net;
    private int pointer;
    private BeetrapState state;
    private double initialDiversityScore;
    private boolean activityEnded;
    private final PlayerInteractionService interaction;
    private final BeeNestController beeNestController;

    public BeetrapStateManager(ServerWorld world, FlowerManager flowerManager,
            PlayerInteractionService interaction, BeeNestController beeNestController,
            GardenInformationBossBar gardenInformationBossBar,
            FlowerValueScoreboardDisplayerService flowerValueScoreboardDisplayerService) {
        this.world = world;
        this.flowerManager = flowerManager;
        this.interaction = interaction;
        this.gardenInformationBossBar = gardenInformationBossBar;
        this.pointer = -1;
        this.state = new SessionSetupState(world, this, new FlowerPool(FLOWER_POOL_FLOWER_COUNT),
                flowerManager, interaction, beeNestController, gardenInformationBossBar,
                flowerValueScoreboardDisplayerService, false,
                INITIAL_POLLINATION_CIRCLE_RADIUS, AMOUNT_OF_FLOWERS_TO_WITHER_DEFAULT_MODE);
        this.state = new ActivitySelectionState(world, this,
                new FlowerPool(FLOWER_POOL_FLOWER_COUNT), flowerManager, interaction,
                beeNestController, gardenInformationBossBar, flowerValueScoreboardDisplayerService,
                false,
                INITIAL_POLLINATION_CIRCLE_RADIUS, AMOUNT_OF_FLOWERS_TO_WITHER_DEFAULT_MODE);
        this.state.populateFlowers(INITIAL_FLOWER_COUNT);
        this.oldBeetrapStates = new ArrayList<>();
        flowerManager.placeFlowerEntities(this.state);
        this.initialDiversityScore = this.state.computeDiversityScore();
        this.recordState();

        this.net = new NetworkingService(this.world);
        this.beeNestController = beeNestController;
    }

    private void recordState() {
        this.oldBeetrapStates.add(this.state);
        LOG.info("Recording state {}...", this.pointer + 1);
        LOG.info("State: {}", this.state);
        LOG.info("StateType: {}", this.state.getClass());
        ++this.pointer;
    }

    public void endActivity() {
        this.activityEnded = true;
    }

    public boolean isActivityEnded() {
        return this.activityEnded;
    }

    public double getInitialDiversityScore() {
        return this.initialDiversityScore;
    }

    void setInitialDiversityScore(double initialDiversityScore) {
        this.initialDiversityScore = initialDiversityScore;
    }

    public void tick() {
        if(this.activityEnded) {
            this.world.getPlayers().forEach(
                    BeetrapStateManager.this.interaction::giveRestartGameItemToPlayer);
        }

        if(this.state.hasNextState()) {
            this.state = this.state.getNextState();
            this.recordState();
            this.gardenInformationBossBar.updateBossBar(state, this.pointer);
        }

        this.state.tick();
    }

    public BeetrapState getState() {
        return this.state;
    }

    private void setState(BeetrapState state) {
        this.state = state;
        this.flowerManager.destroyAll();
        this.flowerManager.placeFlowerEntities(state);
        this.gardenInformationBossBar.updateBossBar(state, this.pointer);
    }

    public ServerWorld getWorld() {
        return this.world;
    }

    public void onPlayerPollinate(FlowerManager flowerManager, boolean exists, int id) {
        if(!exists) {
            return;
        }

        Flower f = flowerManager.getFlowerByEntityId(this.state, id);

        if(f == null) {
            return;
        }

        Entity e = flowerManager.getFlowerEntity(f);

        if(e == null) {
            return;
        }

        this.state.onPlayerPollinate(f, e.getPos());
    }

    public void onPlayerTargetNewEntity(ServerPlayerEntity player, boolean exists, int id) {
        this.state.onPlayerTargetNewEntity(player, exists, id);
    }

    private PollinationReadyState getPreviousPollinationReadyState(int[] index) {
        for(int i = this.pointer - 1; i >= 0; --i) {
            if(this.oldBeetrapStates.get(i) instanceof PollinationReadyState prs) {
                index[0] = i;
                return prs;
            }
        }

        return null;
    }

    private PollinationReadyState getNextPollinationReadyState(int[] index) {
        for(int i = this.pointer + 1; i < this.oldBeetrapStates.size(); ++i) {
            if(this.oldBeetrapStates.get(i) instanceof PollinationReadyState prs) {
                index[0] = i;
                return prs;
            }
        }

        return null;
    }

    public void onPlayerRequestTimeTravel(ServerPlayerEntity player, int n, int operation) {
        if(!this.state.timeTravelAvailable()) {
            return;
        }

        if(operation == Operations.ADD) {
            if(n == 1) {
                int[] newPointer = new int[1];
                PollinationReadyState prs = this.getNextPollinationReadyState(newPointer);

                if(prs == null) {
                    this.world.getPlayers().forEach(
                            playerEntity -> playerEntity.sendMessage(
                                    Text.of("This is the newest garden!")));
                    return;
                }

                this.pointer = newPointer[0];

                if(this.pointer == this.oldBeetrapStates.size() - 1) {
                    this.setState(this.oldBeetrapStates.get(this.pointer));
                } else {
                    this.setState(new TimeTravelableBeetrapState(prs));
                }

                this.net.beetrapLog(BEETRAP_LOG_ID_TIME_MACHINE_FORWARD, "");
            }

            if(n == -1) {
                int[] newPointer = new int[1];
                PollinationReadyState prs = this.getPreviousPollinationReadyState(newPointer);

                if(prs == null) {
                    this.world.getPlayers().forEach(
                            playerEntity -> playerEntity.sendMessage(
                                    Text.of("This is the oldest garden!")));
                    return;
                }

                this.pointer = newPointer[0];
                this.setState(new TimeTravelableBeetrapState(prs));

                this.net.beetrapLog(BEETRAP_LOG_ID_TIME_MACHINE_BACKWARD, "");
            }
        }
    }

    public void onMultipleChoiceSelectionResultReceived(String questionId, int option) {
        this.state.onMultipleChoiceSelectionResultReceived(questionId, option);
    }

    public void onPollinationCircleRadiusIncreaseRequested(double a) {
        this.state.onPollinationCircleRadiusIncreaseRequested(a);
    }

    private Vector2d toPolarCoordinatesRelativeToPos(Vec3d pos, Vec3d flowerPos) {
        double ix = flowerPos.x - pos.x;
        double iz = flowerPos.z - pos.z;

        double nx = iz;
        double nz = -ix;

        double d = Math.hypot(nx, nz);

        if(nx >= 0 && nz >= 0) {
            return new Vector2d(
                    d,
                    Math.atan2(nz, nx)
            );
        } else if(nx <= 0 && nz >= 0) {
            return new Vector2d(
                    d,
                    Math.PI + Math.atan2(nz, nx)
            );
        } else if(nx <= 0 && nz <= 0) {
            return new Vector2d(
                    d,
                    Math.PI + Math.atan2(nz, nx)
            );
        }

        return new Vector2d(
                d,
                Math.TAU + Math.atan2(nz, nx)
        );
    }

    private double clampDegrees(double deg) {
        while(deg >= 360) {
            deg = deg - 360;
        }
        while(deg < 0) {
            deg = deg + 360;
        }
        return deg;
    }

    private double clampRadians(double rad) {
        while(rad >= Math.TAU) {
            rad = rad - Math.TAU;
        }
        while(rad < 0) {
            rad = rad + Math.TAU;
        }
        return rad;
    }

    private boolean tInClosedIntervalAB(double t, double a, double b) {
        return a <= t && t <= b;
    }

    private String[] getStringifiedPolarCoordinates(double r, double theta) {
        String distance;

        if(r < 0.5) {
            distance = "close";
        } else if(0.5 <= r && r < 4) {
            distance = "within_reach";
        } else if(4 <= r && r < 8) {
            distance = "big_in_view";
        } else if(8 <= r && r < 16) {
            distance = "need_to_walk_a_bit";
        } else {
            distance = "far";
        }

        String angle;

        if(this.tInClosedIntervalAB(theta, 0, PI_OVER_FOUR) || this.tInClosedIntervalAB(theta,
                SEVEN_PI_OVER_FOUR, Math.TAU)) {
            angle = "front";
        } else if(this.tInClosedIntervalAB(theta, PI_OVER_FOUR, THREE_PI_OVER_FOUR)) {
            angle = "left";
        } else if(this.tInClosedIntervalAB(theta, THREE_PI_OVER_FOUR, FIVE_PI_OVER_FOUR)) {
            angle = "behind";
        } else {
            angle = "right";
        }

        return new String[]{distance, angle};
    }

    public void getJsonReadyDataForGpt(Entity agentEntity, ServerPlayerEntity serverPlayerEntity,
            StringBuilder sb) {
        sb.append("Player position: ").append(this.world.getPlayers().getFirst().getPos())
                .append(System.lineSeparator());
        sb.append(
                        "Flowers (Note that in terms of distance, close < within_reach < big_in_view < need_to_walk_a_bit < far): ")
                .append(System.lineSeparator());

        Vec3d playerPos = serverPlayerEntity.getPos();
        Vec3d agentPos = agentEntity.getPos();

        LOG.info("Player head yaw: {}", serverPlayerEntity.getHeadYaw());
        LOG.info("Agent head yaw: {}", agentEntity.getHeadYaw());

        for(Flower f : this.state) {
            Vec3d flowerPos = this.flowerManager.getFlowerMinecraftPosition(this.state, f);

            Vector2d pc = this.toPolarCoordinatesRelativeToPos(playerPos, flowerPos);
            double r = pc.x;
            double theta = pc.y;
            theta = this.clampRadians(theta + Math.toRadians(serverPlayerEntity.getHeadYaw()));
            String[] pcp = this.getStringifiedPolarCoordinates(r, theta);

            LOG.info("Flower {} angle to player: {}", f.getNumber(), Math.toDegrees(theta));

            pc = this.toPolarCoordinatesRelativeToPos(agentPos, flowerPos);
            r = pc.x;
            theta = pc.y;
            theta = this.clampRadians(theta + Math.toRadians(agentEntity.getHeadYaw()));

            LOG.info("Flower {} angle to agent: {}", f.getNumber(), Math.toDegrees(theta));

            String[] pcb = this.getStringifiedPolarCoordinates(r, theta);

            sb.append("'Flower ").append(f.getNumber()).append("': {")
                    .append("'distance_to_player': '").append(pcp[0]).append("', ")
                    .append("'angle_to_player': ").append(pcp[1]).append("', ")
                    .append("'distance_to_you': '").append(pcb[0]).append("', ")
                    .append("'angle_to_you': ").append(pcb[1]).append("', ")
                    .append(", 'color': '").append(this.flowerManager.getFlowerMinecraftColor(f))
                    .append("'")
                    .append(System.lineSeparator());
        }
    }

    public FlowerManager getFlowerManager() {
        return this.flowerManager;
    }

    public BeeNestController getBeeNestController() {
        return this.beeNestController;
    }
}
