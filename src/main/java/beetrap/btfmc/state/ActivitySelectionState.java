package beetrap.btfmc.state;

import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_ACTIVITY_BEGIN_0;
import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_ACTIVITY_BEGIN_1;
import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_ACTIVITY_BEGIN_2;
import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_ACTIVITY_BEGIN_3;
import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_ACTIVITY_BEGIN_4;
import static beetrap.btfmc.state.RecommendationSystemPollinationReadyState.RECOMMENDATION_SYSTEM_ACTIVITY_STAGE_BEFORE_PLAYER_LOOK_AT_BEE_NEST;

import beetrap.btfmc.BeeNestController;
import beetrap.btfmc.GardenInformationBossBar;
import beetrap.btfmc.PlayerInteractionService;
import beetrap.btfmc.flower.FlowerManager;
import beetrap.btfmc.flower.FlowerPool;
import beetrap.btfmc.flower.FlowerValueScoreboardDisplayerService;
import beetrap.btfmc.handler.BeetrapGameHandler;
import beetrap.btfmc.networking.ShowMultipleChoiceScreenS2CPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * @deprecated functionality has been folded into {@link MenuState};
 *             use that class instead.
 */
@Deprecated
public class ActivitySelectionState extends BeetrapState {

    private static final String ACTIVITY_SELECTION_SCREEN_ID = "activity_selection";
    private static final int NO_ACTIVITY = -1;
    private static final int OBSERVE_FLOWERS_ONLY = 0;
    private static final int FILTER_BUBBLE = 1;
    private static final int RECOMMENDATION_SYSTEM = 2;
    private static final int DIVERSIFICATION = 3;
    private static final int MYSTERIOUS_FIFTH_ACTIVITY = 4;
    private int activityNumber;

    public ActivitySelectionState(ServerWorld world, BeetrapStateManager manager,
            FlowerPool flowerPool,
            FlowerManager flowerManager, PlayerInteractionService interaction,
            BeeNestController beeNestController,
            GardenInformationBossBar gardenInformationBossBar,
            FlowerValueScoreboardDisplayerService flowerValueScoreboardDisplayerService,
            boolean usingDiversifyingRankingMethod, double pollinationCircleRadius,
            int amountOfFlowersToWither) {
        super(world, manager, flowerPool, flowerManager, interaction, beeNestController,
                gardenInformationBossBar, flowerValueScoreboardDisplayerService,
                usingDiversifyingRankingMethod, pollinationCircleRadius,
                amountOfFlowersToWither);
        throw new UnsupportedOperationException("ActivitySelectionState is deprecated; use MenuState instead");
    }

    @Override
    public void onMultipleChoiceSelectionResultReceived(String questionId, int option) {
        if(questionId.equals(ACTIVITY_SELECTION_SCREEN_ID)) {
            this.activityNumber = option;
        }
    }

    @Override
    public void tick() {

    }

    @Override
    public boolean hasNextState() {
        return this.activityNumber != NO_ACTIVITY;
    }

    @Override
    public BeetrapState getNextState() {
        return switch(this.activityNumber) {
            case OBSERVE_FLOWERS_ONLY -> {
                this.net.beetrapLog(BEETRAP_LOG_ID_ACTIVITY_BEGIN_0,
                        "The user have chosen to observe flowers only!");
                this.stateManager.endActivity();
                yield new ObserveFlowersOnlyState(this);
            }
            case FILTER_BUBBLE -> {
                this.net.beetrapLog(BEETRAP_LOG_ID_ACTIVITY_BEGIN_1,
                        "The user have chosen to explore the filter bubble effect!");
                yield new ExploreFilterBubbleEffectPollinationReadyState(this, 0);
            }
            case RECOMMENDATION_SYSTEM -> {
                this.net.beetrapLog(BEETRAP_LOG_ID_ACTIVITY_BEGIN_2,
                        "The user have chosen to explore the recommendation system!");
                yield new RecommendationSystemPollinationReadyState(this, 0,
                        RECOMMENDATION_SYSTEM_ACTIVITY_STAGE_BEFORE_PLAYER_LOOK_AT_BEE_NEST);
            }
            case DIVERSIFICATION -> {
                this.net.beetrapLog(BEETRAP_LOG_ID_ACTIVITY_BEGIN_3,
                        "The user have chosen to explore diversifying the garden!");
                yield new DiversificationPollinationReadyState(this);
            }

            case MYSTERIOUS_FIFTH_ACTIVITY -> {
                BeetrapGameHandler.getGame().newAgent();
                this.net.beetrapLog(BEETRAP_LOG_ID_ACTIVITY_BEGIN_4,
                        "The user have chosen to explore mysterious fifth activity!");
                yield new MysteriousFifthPollinationReadyState(this, 0);
            }
            default -> null;
        };
    }

    @Override
    public boolean timeTravelAvailable() {
        return false;
    }

    @Override
    public void onPlayerTargetNewEntity(ServerPlayerEntity player, boolean exists, int id) {
        super.onPlayerTargetNewEntity(player, false, id);
    }
}
