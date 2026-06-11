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
import beetrap.btfmc.Beetrapfabricmc;
import beetrap.btfmc.handler.BeetrapGameHandler;
import beetrap.btfmc.flower.FlowerManager;
import beetrap.btfmc.flower.FlowerPool;
import beetrap.btfmc.flower.FlowerValueScoreboardDisplayerService;
import beetrap.btfmc.networking.ShowMultipleChoiceScreenS2CPayload;
import beetrap.btfmc.networking.ShowTextInputScreenS2CPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A combined state that first obtains consent from the player and then allows them to
 * select an activity.  This replaces {@link SessionSetupState} and
 * {@link ActivitySelectionState} and therefore embodies both responsibilities.
 */
public class MenuState extends BeetrapState {
    private static final Logger LOG = LogManager.getLogger(MenuState.class);

    private static final String CONSENT_SCREEN_ID = "consent_screen";
    private static final String TEXT_INPUT_SCREEN_ID = "text_input_screen";
    private static final String ACTIVITY_SELECTION_SCREEN_ID = "activity_selection";

    private static final int STAGE_CONSENT = 0;
    private static final int STAGE_TEXT_INPUT = 1;
    private static final int STAGE_ACTIVITY = 2;
    private static final int STAGE_DONE = 3;

    private static final int CONSENT_YES = 0;
    private static final int CONSENT_NO = 1;

    private static final int NO_ACTIVITY = -1;
    private static final int OBSERVE_FLOWERS_ONLY = 0;
    private static final int FILTER_BUBBLE = 1;
    private static final int RECOMMENDATION_SYSTEM = 2;
    private static final int DIVERSIFICATION = 3;
    private static final int MYSTERIOUS_FIFTH_ACTIVITY = 4;
    private static final int MEET_BIP_1 = 5;

    private int stage;
    private int activityNumber;
    private String textInput;

    public MenuState(ServerWorld world, BeetrapStateManager manager, FlowerPool flowerPool,
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

        this.activityNumber = NO_ACTIVITY;
        this.textInput = Beetrapfabricmc.PARTICIPANT_ID != null
                ? Beetrapfabricmc.PARTICIPANT_ID : "";
        this.net.beetrapLog("session_id", Beetrapfabricmc.SESSION_ID);

        if (Beetrapfabricmc.CONSENT_ANSWERED) {
            // Consent + name already handled this session — go straight to activity selection
            this.stage = STAGE_ACTIVITY;
            this.net.broadcastCustomPayload(new ShowMultipleChoiceScreenS2CPayload(
                    ACTIVITY_SELECTION_SCREEN_ID,
                    "Select an activity!",
                    "Observe flowers only",
                    "What is the filter bubble effect?",
                    "How does the garden recommend flowers?",
                    "How do we break the filter bubble?",
                    "Mysterious Fifth Activity",
                    "Meet Bip 1"));
        } else {
            // First time — start with consent screen
            this.stage = STAGE_CONSENT;
            this.net.broadcastCustomPayload(new ShowMultipleChoiceScreenS2CPayload(
                    CONSENT_SCREEN_ID,
                    "Do you consent to your data being recorded (for study purposes)?",
                    "YES",
                    "NO"));
        }
    }

    @Override
    public void onMultipleChoiceSelectionResultReceived(String questionId, int option) {
        if (this.stage == STAGE_CONSENT && questionId.equals(CONSENT_SCREEN_ID)) {
            Beetrapfabricmc.CONSENT_ANSWERED = true;
            if (option == CONSENT_YES) {
                Beetrapfabricmc.PLAYER_DATA_CONSENT = true;
                LOG.info("Player consented to data recording.");
                this.net.beetrapLog("data_consent", "yes");
                // Collect name before showing activity selection
                this.stage = STAGE_TEXT_INPUT;
                this.net.broadcastCustomPayload(new ShowTextInputScreenS2CPayload(
                        TEXT_INPUT_SCREEN_ID,
                        "Please enter your participant code:"));
            } else {
                LOG.info("Player did not consent to data recording.");
                // Skip name collection — go straight to activity selection
                this.stage = STAGE_ACTIVITY;
                this.net.broadcastCustomPayload(new ShowMultipleChoiceScreenS2CPayload(
                        ACTIVITY_SELECTION_SCREEN_ID,
                        "Select an activity!",
                        "Observe flowers only",
                        "What is the filter bubble effect?",
                        "How does the garden recommend flowers?",
                        "How do we break the filter bubble?",
                        "Mysterious Fifth Activity"));
            }
            return;
        }

        if (this.stage == STAGE_ACTIVITY && questionId.equals(ACTIVITY_SELECTION_SCREEN_ID)) {
            this.activityNumber = option;
            this.stage = STAGE_DONE;
        }
    }

    @Override
    public void onTextInputScreenResultReceived(String screenId, String textInput) {
        if (this.stage == STAGE_TEXT_INPUT && screenId.equals(TEXT_INPUT_SCREEN_ID)) {
            LOG.info("Player entered participant code: {}", textInput);
            this.textInput = textInput;
            Beetrapfabricmc.PARTICIPANT_ID = textInput;
            this.net.beetrapLog("participant_id", textInput);
            
            // advance to activity selection
            this.stage = STAGE_ACTIVITY;
            this.net.broadcastCustomPayload(new ShowMultipleChoiceScreenS2CPayload(
                    ACTIVITY_SELECTION_SCREEN_ID,
                    "Select an activity!",
                    "Observe flowers only",
                    "What is the filter bubble effect?",
                    "How does the garden recommend flowers?",
                    "How do we break the filter bubble?",
                    "Mysterious Fifth Activity",
                    "Meet Bip 1"));
        }
    }

    @Override
    public void tick() {
        // no periodic work required
    }

    @Override
    public boolean hasNextState() {
        return this.stage == STAGE_DONE && this.activityNumber != NO_ACTIVITY;
    }

    @Override
    public BeetrapState getNextState() {
        return switch (this.activityNumber) {
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
            case MEET_BIP_1 -> {
                this.net.beetrapLog("activity_begin_meetbip1",
                        "The user has chosen Meet Bip 1!");
                yield new MeetBip1PollinationReadyState(this, 0);
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
