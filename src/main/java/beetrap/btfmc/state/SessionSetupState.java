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

public class SessionSetupState extends BeetrapState {
    private static final Logger LOG = LogManager.getLogger(SessionSetupState.class);

    private static final String SESSION_SETUP_CONSENT_ID = "session_setup_consent";

    private static final int STAGE_CONSENT = 1;
    private static final int STAGE_DONE = 2;

    private static final int CONSENT_YES = 0;
    private static final int CONSENT_NO = 1;

    private int stage;
    private String sessionCode;
    private boolean consented;

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
        this.stage = STAGE_CONSENT;
        this.sessionCode = null;
        this.consented = false;
        this.net.broadcastCustomPayload(new ShowMultipleChoiceScreenS2CPayload(SESSION_SETUP_CONSENT_ID, "Do you consent to your data being recorded (for study purposes)?", "YES", "NO"));
        this.net.beetrapLog("SESSION_ID", Beetrapfabricmc.sessionId);
    }

    @Override
    public void onMultipleChoiceSelectionResultReceived(String questionId, int option) {
        if(questionId.equals(SESSION_SETUP_CONSENT_ID) && this.stage == STAGE_CONSENT) {
            if(option == CONSENT_YES) {
                Beetrapfabricmc.PLAYER_DATA_CONSENT = true;
                this.consented = true;
                LOG.info("Player consented to data recording.");
                this.net.beetrapLog("DATA_CONSENT", "yes");
                this.stage = STAGE_DONE;
                System.out.println("SANITY CHECK: CONSENTED = " + Beetrapfabricmc.PLAYER_DATA_CONSENT);
            } else {
                LOG.info("Player did not consent to data recording.");
                this.net.beetrapLog("DATA_CONSENT", "no");
                this.stage = STAGE_DONE;
                System.out.println("SANITY CHECK: CONSENTED = " + Beetrapfabricmc.PLAYER_DATA_CONSENT);
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

    public String getSessionCode() {
        return this.sessionCode;
    }

    public boolean hasConsented() {
        return this.consented;
    }
}
