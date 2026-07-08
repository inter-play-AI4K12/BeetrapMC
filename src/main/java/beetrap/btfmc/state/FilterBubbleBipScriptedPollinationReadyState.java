package beetrap.btfmc.state;

import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_POLLINATION_INITIATED;

import beetrap.btfmc.flower.Flower;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.entity.ItemEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Vec3d;

/**
 * Filter Bubble activity — Bip narrates verbatim scripted lines instead of showing text screens.
 */
public class FilterBubbleBipScriptedPollinationReadyState extends PollinationReadyState {

    private static final int INTRO_TICK = 5;              // greet as soon as the stage settles
    // Chat replies and scripted beats share one serialized call queue to the agent service, so a
    // fixed delay can't reliably predict when a spoken line actually finishes (TTS duration
    // varies). Wait for it to actually go quiet via agentBusy(), same pattern as elsewhere.
    private static final int INTRO_SETTLE = 20;           // ~1s breather after intro finishes
    private static final int INTRO_MAX_WAIT = 300;        // ~15s safety net
    private static final int HOW_TO_TO_SUGGEST_DELAY = 50; // ~2.5s glance at the dialogue box

    // Two escalating, unconditional reminders once a flower has been suggested — ~15s and ~30s
    // after suggest_flower if the player still hasn't pollinated. Resets on active engagement
    // (re-aiming at a flower), so it only fires for a player who is genuinely stalled.
    private final HintEscalator hints = new HintEscalator(300, 600);
    private boolean welcomed;
    private Flower suggestedFlower;

    private boolean introEmitted;
    private boolean introSawBusy;
    private long introQuietSinceTick = -1;
    private boolean howToShown;
    private long howToShownTick = -1;
    private boolean suggestFlowerAttempted;

    public FilterBubbleBipScriptedPollinationReadyState(BeetrapState parent, int stage) {
        super(parent, stage);
    }

    /** Report a semantic beat; Python owns Bip's words/movement for it. */
    private void emitBeat(String beat) {
        this.stateManager.recordAgentEvent("activity_beat",
                Map.of("activity", "pollinate", "beat", beat));
    }

    private void emitBeat(String beat, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("activity", "pollinate");
        details.put("beat", beat);
        details.putAll(extra);
        this.stateManager.recordAgentEvent("activity_beat", details);
    }

    private void clearItems() {
        List<? extends ItemEntity> entities = this.world.getEntitiesByType(
                TypeFilter.instanceOf(ItemEntity.class), e -> true);
        for (ItemEntity ie : entities) {
            ie.kill(this.world);
        }
        if (!entities.isEmpty()) {
            for (ServerPlayerEntity spe : this.world.getPlayers()) {
                this.interaction.giveTimeTravelItemsToPlayer(spe);
            }
        }
    }

    private Vec3d playerPos() {
        return this.world.getPlayers().isEmpty()
                ? Vec3d.ZERO : this.world.getPlayers().getFirst().getPos();
    }

    /** The flower nearest the player — an easy, obvious first suggestion. */
    private Flower chooseSuggestionFlower() {
        Vec3d player = this.playerPos();
        Flower best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Flower f : this) {
            if (f.hasWithered()) {
                continue;
            }
            Vec3d position = this.flowerManager.getFlowerMinecraftPosition(this, f);
            if (position == null) {
                continue;
            }
            double distance = position.distanceTo(player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = f;
            }
        }
        return best;
    }

    @Override
    public void tick() {
        this.clearItems();
        if (this.stage == 0) {
            if (this.ticks == INTRO_TICK) {
                // Greet only the first time Bip meets the player this run; otherwise jump in.
                this.emitBeat(beetrap.btfmc.Beetrapfabricmc.BIP_INTRODUCED
                        ? "intro_returning" : "intro");
                beetrap.btfmc.Beetrapfabricmc.BIP_INTRODUCED = true;
                this.welcomed = true;
                this.introEmitted = true;
            }

            if (this.introEmitted && !this.howToShown) {
                boolean busy = this.agentBusy();
                if (busy) {
                    this.introSawBusy = true;
                    this.introQuietSinceTick = -1;
                } else if (this.introSawBusy && this.introQuietSinceTick < 0) {
                    this.introQuietSinceTick = this.ticks;
                }
                boolean introSettled = this.introSawBusy && this.introQuietSinceTick >= 0
                        && this.ticks - this.introQuietSinceTick >= INTRO_SETTLE;
                if (introSettled || this.ticks - INTRO_TICK >= INTRO_MAX_WAIT) {
                    // Gives "Did you see that?" (suggest_flower) something concrete to refer to —
                    // without this, the player has seen nothing happen yet and the line is a non
                    // sequitur.
                    this.showTextScreenToAllPlayers(
                            "To pollinate, point at a flower. A hive will show up in slot 5. "
                                    + "Holding it, right-click on any flower to pollinate it!",
                            "gui/how_to_pollinate", 140, 133);
                    this.howToShown = true;
                    this.howToShownTick = this.ticks;
                }
            }

            if (this.howToShown && !this.suggestFlowerAttempted
                    && this.ticks - this.howToShownTick >= HOW_TO_TO_SUGGEST_DELAY) {
                this.suggestFlowerAttempted = true;
                this.suggestedFlower = this.chooseSuggestionFlower();
                if (this.suggestedFlower != null) {
                    Map<String, Object> extra = new LinkedHashMap<>();
                    extra.put("flower_id", this.suggestedFlower.getNumber());
                    extra.put("color", this.flowerManager.getFlowerMinecraftColor(this.suggestedFlower));
                    this.emitBeat("suggest_flower", extra);
                }
            }

            if (this.suggestedFlower != null) {
                int tier = this.hints.tick();
                if (tier >= 0) {
                    this.emitBeat("pollinate_reminder_" + tier);
                }
            }
        }

        this.beeNestController.tickPollinationLines(this.ticks, this.pastPollinationLocations);
        ++this.ticks;
    }

    @Override
    public void onPlayerTargetNewEntity(ServerPlayerEntity player, boolean exists, int id) {
        // Keep the default behaviour (auto-equips the nest when aiming at a valid flower)...
        super.onPlayerTargetNewEntity(player, exists, id);
        // ...moving the crosshair around the garden is active engagement — don't nag a busy player.
        this.hints.reset();
    }

    @Override
    public void onPlayerPollinate(Flower flower, Vec3d flowerMinecraftPosition) {
        this.hasNextState = true;
        this.pastPollinationLocations.add(flowerMinecraftPosition);
        Vec3d pl = this.computeAveragePastPollinationPositions();
        this.nextState = new FilterBubbleBipScriptedPollinationHappeningState(
                this, pl, this.stage, flower.getNumber());
        this.net.beetrapLog(BEETRAP_LOG_ID_POLLINATION_INITIATED, "");
    }

    @Override
    public boolean timeTravelAvailable() {
        return this.stage != 0;
    }
}
