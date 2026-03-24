package beetrap.btfmc.state;
import beetrap.btfmc.BeeNestController;
import beetrap.btfmc.GardenInformationBossBar;
import beetrap.btfmc.PlayerInteractionService;
import beetrap.btfmc.flower.FlowerManager;
import beetrap.btfmc.flower.FlowerPool;
import beetrap.btfmc.flower.FlowerValueScoreboardDisplayerService;
import beetrap.btfmc.networking.ShowMultipleChoiceScreenS2CPayload;
// import beetrap.btfmc.networking.ShowTextInputScreenS2CPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import beetrap.btfmc.Beetrapfabricmc;

/**
 * @deprecated functionality has been folded into {@link MenuState};
 *             use that class instead.
 */
@Deprecated
public class SessionSetupState extends BeetrapState {
    private static final Logger LOG = LogManager.getLogger(SessionSetupState.class);

    private static final String SESSION_SETUP_SCREEN_ID = "session_setup_screen";

    private static final int STAGE_CONSENT = 0;
    private static final int STAGE_DONE = 1;

    private static final int CONSENT_YES = 0;
    private static final int CONSENT_NO = 1;

    private int stage;

    public SessionSetupState(ServerWorld world, BeetrapStateManager manager, FlowerPool flowerPool,
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
        throw new UnsupportedOperationException("SessionSetupState is deprecated; use MenuState instead");
    }

    @Override
    public void onMultipleChoiceSelectionResultReceived(String questionId, int option) {
        if(questionId.equals(SESSION_SETUP_SCREEN_ID)) {
            if(option == CONSENT_YES) {
                LOG.info("Player consented to data recording.");
                this.net.beetrapLog("DATA_CONSENT", "yes");
            } else {
                LOG.info("Player did not consent to data recording.");
                this.net.beetrapLog("DATA_CONSENT", "no");
            }
        }
    }

    @Override
    public void tick() {

    }

    @Override
    public boolean hasNextState() {
        return this.stage == STAGE_DONE;
    }

    @Override
    public BeetrapState getNextState() {
        return new ActivitySelectionState(this.world, null , this.flowerPool,
                this.flowerManager, this.interaction, this.beeNestController,
                this.gardenInformationBossBar, this.flowerValueScoreboardDisplayerService,
                this.usingDiversifyingRankingMethod, this.pollinationCircleRadius,
                this.amountOfFlowersToWither);
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
