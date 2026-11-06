package beetrap.btfmc.state;

import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_ACTIVITY_BEGIN_0;
import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_ACTIVITY_BEGIN_1;
import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_ACTIVITY_BEGIN_2;
import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_ACTIVITY_BEGIN_3;
import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_ACTIVITY_BEGIN_4;

import beetrap.btfmc.BeeNestController;
import beetrap.btfmc.GardenInformationBossBar;
import beetrap.btfmc.PlayerInteractionService;
import beetrap.btfmc.Beetrapfabricmc;
import beetrap.btfmc.TutorialFlow;
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
    private static final int STAGE_INTRO = 4;   // hand over to Bip's introduction first

    private static final int CONSENT_YES = 0;
    private static final int CONSENT_NO = 1;

    private static final int NO_ACTIVITY = -1;
    // Every activity is Bip-led (Bip needs the game started at AI level 3).
    private static final int OBSERVE_FLOWERS = 0;
    private static final int FILTER_BUBBLE = 1;
    private static final int RECOMMENDATION_SYSTEM = 2;
    private static final int DIVERSIFICATION = 3;
    private static final int MYSTERIOUS_FIFTH_ACTIVITY = 4;

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
        this.init();
    }

    /** Back to the menu after another state (e.g. Bip's introduction). */
    public MenuState(BeetrapState parent) {
        super(parent);
        this.init();
    }

    private void init() {
        this.activityNumber = NO_ACTIVITY;
        this.textInput = Beetrapfabricmc.PARTICIPANT_ID != null
                ? Beetrapfabricmc.PARTICIPANT_ID : "";
        this.net.beetrapLog("session_id", Beetrapfabricmc.SESSION_ID);

        if (Beetrapfabricmc.CONSENT_ANSWERED) {
            // Consent + name already handled this session — go straight to activity selection
            this.afterSetup();
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
                this.afterSetup();
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
            this.afterSetup();
        }
    }

    /**
     * Consent handled. Base 2 flow (see {@link TutorialFlow}): Bip's introduction plays once, then
     * the tutorial runs stage after stage on its own (each portal sets the next pending stage), and
     * once it's finished the stage menu lets the player replay a stage or play the actual game.
     */
    private void afterSetup() {
        if (!Beetrapfabricmc.BIP_INTRO_DONE) {
            this.stage = STAGE_INTRO;
            return;
        }
        if (TutorialFlow.pendingActivity != TutorialFlow.NONE) {
            this.activityNumber = TutorialFlow.pendingActivity;
            this.stage = STAGE_DONE;
            return;
        }
        if (!TutorialFlow.tutorialComplete) {
            this.activityNumber = TutorialFlow.OBSERVE;
            this.stage = STAGE_DONE;
            return;
        }
        this.stage = STAGE_ACTIVITY;
        this.showActivitySelection();
    }

    private void showActivitySelection() {
        this.net.broadcastCustomPayload(new ShowMultipleChoiceScreenS2CPayload(
                ACTIVITY_SELECTION_SCREEN_ID,
                "Tutorial complete! Where to next?",
                "Replay 1: Observe the flowers",
                "Replay 2: What is the filter bubble effect?",
                "Replay 3: How does the garden recommend flowers?",
                "Replay 4: How do we break the filter bubble?",
                "Play the real game!"));
    }

    @Override
    public void tick() {
        // no periodic work required
    }

    @Override
    public boolean hasNextState() {
        return this.stage == STAGE_INTRO
                || (this.stage == STAGE_DONE && this.activityNumber != NO_ACTIVITY);
    }

    @Override
    public BeetrapState getNextState() {
        if (this.stage == STAGE_INTRO) {
            BeetrapGameHandler.getGame().newAgent();
            this.net.beetrapLog("activity_begin_intro", "Bip's game introduction");
            return new BipIntroState(this);
        }
        TutorialFlow.onActivityStarted(this.activityNumber);
        return switch (this.activityNumber) {
            case OBSERVE_FLOWERS -> {
                BeetrapGameHandler.getGame().newAgent();
                this.net.beetrapLog(BEETRAP_LOG_ID_ACTIVITY_BEGIN_0,
                        "The user have chosen to observe flowers with Bip!");
                yield new ObserveBipState(this);
            }
            case FILTER_BUBBLE -> {
                BeetrapGameHandler.getGame().newAgent();
                this.net.beetrapLog(BEETRAP_LOG_ID_ACTIVITY_BEGIN_1,
                        "The user have chosen to explore the filter bubble effect with Bip!");
                yield new FilterBubbleBipReadyState(this, 0);
            }
            case RECOMMENDATION_SYSTEM -> {
                BeetrapGameHandler.getGame().newAgent();
                this.net.beetrapLog(BEETRAP_LOG_ID_ACTIVITY_BEGIN_2,
                        "The user have chosen to explore the recommendation system with Bip!");
                yield new RecommendBipPollinationReadyState(this, 0);
            }
            case DIVERSIFICATION -> {
                BeetrapGameHandler.getGame().newAgent();
                this.net.beetrapLog(BEETRAP_LOG_ID_ACTIVITY_BEGIN_3,
                        "The user have chosen to explore diversifying the garden with Bip!");
                yield new DiversifyBipPollinationReadyState(this);
            }
            case MYSTERIOUS_FIFTH_ACTIVITY -> {
                BeetrapGameHandler.getGame().newAgent();
                this.net.beetrapLog(BEETRAP_LOG_ID_ACTIVITY_BEGIN_4,
                        "The user have chosen to explore mysterious fifth activity!");
                yield new ActualGameBipReadyState(this, 0);
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
