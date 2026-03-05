package beetrap.btfmc;

import static beetrap.btfmc.agent.Agent.AGENT_LEVEL_PHYSICAL;

import beetrap.btfmc.agent.Agent;
import beetrap.btfmc.agent.empty.EmptyAgent;
import beetrap.btfmc.agent.physical.PhysicalAgent;
import beetrap.btfmc.flower.FlowerManager;
import beetrap.btfmc.flower.FlowerValueScoreboardDisplayerService;
import beetrap.btfmc.networking.NetworkingService;
import beetrap.btfmc.state.BeetrapStateManager;
import net.minecraft.block.Blocks;
import net.minecraft.network.message.MessageType.Parameters;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3i;

public class BeetrapGame {

    public static final int FLOWER_POOL_FLOWER_COUNT = 200;
    public static final int INITIAL_FLOWER_COUNT = 20;
    public static final int AMOUNT_OF_BUDS_RANKED = 3;
    public static final int AMOUNT_OF_BUDS_TO_PLACE_DEFAULT_MODE = 40;
    public static final int AMOUNT_OF_BUDS_TO_PLACE_DIVERSIFYING_MODE = 50;
    public static final double INITIAL_POLLINATION_CIRCLE_RADIUS = 3;
    public static final int AMOUNT_OF_FLOWERS_TO_WITHER_DEFAULT_MODE = 3;
    public static final int AMOUNT_OF_FLOWERS_TO_WITHER_DIVERSIFYING_MODE = 1;
    public static final BlockPos CHANGE_RANKING_METHOD_LEVER_POSITION = new BlockPos(0, 1, 0);
    public static final int MAX_POLLINATION_COUNT = 6;
    private static final long TICK_INTERVAL_NANO = 50_000_000L;
    private final MinecraftServer server;
    private final ServerWorld world;
    private final BeetrapStateManager stateManager;
    private final FlowerManager flowerManager;
    private final BeeNestController beeNestController;
    private final PlayerInteractionService interaction;
    private final NetworkingService net;
    private final GardenInformationBossBar gardenInformationBossBar;
    private final FlowerValueScoreboardDisplayerService flowerValueScoreboardDisplayerService;
    private final Vector3i bottomLeft;
    private final Vector3i topRight;
    private final int amountOfFlowersToWither;
    private final int aiLevel;
    private long lastTickTime;
    private Agent agent;

    public BeetrapGame(MinecraftServer server, Vector3i bottomLeft, Vector3i topRight,
            int aiLevel) {
        this.server = server;
        this.bottomLeft = bottomLeft;
        this.topRight = topRight;
        this.world = this.server.getOverworld();
        this.net = new NetworkingService(this.world);
        this.flowerManager = new FlowerManager(this.world, FLOWER_POOL_FLOWER_COUNT, bottomLeft,
                topRight);
        this.beeNestController = new BeeNestController(this.world, this.net,
                this.calculateNestBase(bottomLeft, topRight));
        this.flowerValueScoreboardDisplayerService = new FlowerValueScoreboardDisplayerService(
                server);
        this.interaction = new PlayerInteractionService(this.world);
        this.gardenInformationBossBar = new GardenInformationBossBar(this.server);
        this.stateManager = new BeetrapStateManager(this.world, this.flowerManager,
                this.interaction, this.beeNestController, this.gardenInformationBossBar,
                this.flowerValueScoreboardDisplayerService);
        this.gardenInformationBossBar.updateBossBar(this.stateManager.getState(), 0);
        this.beeNestController.spawnNest();
        this.amountOfFlowersToWither = AMOUNT_OF_FLOWERS_TO_WITHER_DEFAULT_MODE;
        this.aiLevel = aiLevel;
        this.agent = new EmptyAgent(this.world, this.stateManager);
    }

    public void newAgent() {
        switch(this.aiLevel) {
            case AGENT_LEVEL_PHYSICAL -> {
                this.agent = new PhysicalAgent(this.world, this.stateManager);
            }

            default -> {
                this.agent = new EmptyAgent(this.world, this.stateManager);
            }
        }

        this.agent.onGameStart();
    }

    public Agent getAgent() {
        return this.agent;
    }

    public MinecraftServer getServer() {
        return this.server;
    }

    public void onWorldTick() {
        if(System.nanoTime() - this.lastTickTime < TICK_INTERVAL_NANO) {
            return;
        }
        this.stateManager.tick();
        this.lastTickTime = System.nanoTime();
        this.agent.tick();
    }

    public void onPlayerTargetNewEntity(ServerPlayerEntity player, boolean exists, int id) {
        this.stateManager.onPlayerTargetNewEntity(player, exists, id);
    }

    public void onPlayerPollinate(ServerPlayerEntity player, boolean exists, int id) {
        this.stateManager.onPlayerPollinate(this.flowerManager, exists, id);
    }

    public void onPlayerRequestTimeTravel(ServerPlayerEntity player, int n, int operation) {
        this.stateManager.onPlayerRequestTimeTravel(player, n, operation);
    }

    private Vec3d calculateNestBase(Vector3i tl, Vector3i br) {
        double x = (tl.x + br.x) / 2.0 + 0.5;
        double z = (tl.z + br.z) / 2.0 + 0.5;
        double y = Math.min(tl.y, br.y) + 3;
        return new Vec3d(x, y, z);
    }

    public double getBaseY() {
        return Math.min(this.bottomLeft.y, this.topRight.y);
    }

    public Vec3d getCenterXZBaseY() {
        double x = (this.bottomLeft.x + this.topRight.x) / 2.0 + 0.5;
        double z = (this.bottomLeft.z + this.topRight.z) / 2.0 + 0.5;
        return new Vec3d(x, this.getBaseY(), z);
    }

    public void onPollinationCircleRadiusIncreaseRequested(double a) {
        this.stateManager.onPollinationCircleRadiusIncreaseRequested(a);
    }

    public int getAmountOfFlowersToWither() {
        return this.amountOfFlowersToWither;
    }

    public void onMultipleChoiceSelectionResultReceived(String questionId, int option) {
        this.stateManager.onMultipleChoiceSelectionResultReceived(questionId, option);
    }

    public void onTextInputResultReceived(String screenId, String textInput) {
        this.stateManager.onTextInputResultReceived(screenId, textInput);
    }

    public void onChatMessageMessage(SignedMessage signedMessage,
            ServerPlayerEntity serverPlayerEntity, Parameters parameters) {
        this.agent.onChatMessageReceived(serverPlayerEntity, signedMessage.getSignedContent());
    }

    public void dispose() {
        this.world.setBlockState(new BlockPos(CHANGE_RANKING_METHOD_LEVER_POSITION.getX(),
                        CHANGE_RANKING_METHOD_LEVER_POSITION.getY() - 1,
                        CHANGE_RANKING_METHOD_LEVER_POSITION.getZ() - 1),
                Blocks.AIR.getDefaultState());
        this.world.setBlockState(new BlockPos(CHANGE_RANKING_METHOD_LEVER_POSITION.getX(),
                        CHANGE_RANKING_METHOD_LEVER_POSITION.getY(),
                        CHANGE_RANKING_METHOD_LEVER_POSITION.getZ() - 1),
                Blocks.AIR.getDefaultState());
        this.world.setBlockState(new BlockPos(CHANGE_RANKING_METHOD_LEVER_POSITION.getX(),
                        CHANGE_RANKING_METHOD_LEVER_POSITION.getY(),
                        CHANGE_RANKING_METHOD_LEVER_POSITION.getZ()),
                Blocks.AIR.getDefaultState());

        this.flowerManager.destroyAll();
        this.beeNestController.dispose();
        this.gardenInformationBossBar.dispose();
        this.flowerValueScoreboardDisplayerService.dispose();
        this.interaction.dispose();
        try {
            this.agent.close();
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }
}
