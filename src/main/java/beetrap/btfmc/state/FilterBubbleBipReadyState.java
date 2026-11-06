package beetrap.btfmc.state;

import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_POLLINATION_INITIATED;

import beetrap.btfmc.Beetrapfabricmc;
import beetrap.btfmc.flower.Flower;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.entity.ItemEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Vec3d;

/**
 * Game 2 — What is the filter bubble effect? (Python activity {@code pollinate}), waiting for the
 * player to pollinate. Round 0: Bip recaps game 1 and teaches pollinating himself, one step at a
 * time (aim at a flower, hold the hive from slot 5, right-click), with no picture instructions.
 * Later rounds: a short prompt to try a different flower. See {@link FilterBubbleBipHappeningState}.
 */
public class FilterBubbleBipReadyState extends PollinationReadyState {

    static final String ACTIVITY = "pollinate";

    private final BipScript script = this.bipScript(ACTIVITY, () -> this.ticks);
    // Reminders once teaching is done, if the player still hasn't pollinated (~20s, ~40s).
    // Re-aiming at a flower is active engagement and resets them.
    private final HintEscalator reminders = new HintEscalator(400, 800);
    private int targetCount;
    private int targetCountAtTeach;
    private Flower teachFlower;

    public FilterBubbleBipReadyState(BeetrapState parent, int stage) {
        super(parent, stage);
        if(stage == 0) {
            this.stateManager.setBipActivity(ACTIVITY);
            this.buildTeaching();
        } else if(stage == 1) {
            this.script.then(BipScript.pause(20), BipScript.say("again_pick"));
        }
    }

    private void buildTeaching() {
        this.script.then(BipScript.pause(5));
        if(!Beetrapfabricmc.BIP_INTRODUCED) {
            Beetrapfabricmc.BIP_INTRODUCED = true;
            this.script.then(BipScript.ask("hello", "hello_nudge_", Map::of,
                    "CONTEXT: you just asked the player's name. Greet them by name in one line."));
        }
        this.script.then(
                BipScript.say("welcome"),
                BipScript.say("recap"),
                BipScript.run(() -> {
                    this.teachFlower = this.nearestFlowerToPlayer();
                    this.targetCountAtTeach = this.targetCount;
                }),
                BipScript.say("teach_point", this::teachDetails),
                BipScript.waitFor(() -> this.targetCount > this.targetCountAtTeach, 400, 2,
                        tier -> this.emitBeat("teach_point_nudge_" + tier, this.teachDetails()),
                        1200,
                        "CONTEXT: you're teaching the player to pollinate. Step 1: point the "
                                + "crosshair at a flower. Help them with that step only."),
                BipScript.say("teach_slot"),
                BipScript.say("teach_click"));
    }

    private Map<String, Object> teachDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        if(this.teachFlower != null) {
            details.put("flower_id", this.teachFlower.getNumber());
            details.put("color", this.flowerManager.getFlowerMinecraftColor(this.teachFlower));
        }
        return details;
    }

    private void emitBeat(String beat, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("activity", ACTIVITY);
        details.put("beat", beat);
        details.putAll(extra);
        this.stateManager.recordAgentEvent("activity_beat", details);
    }

    private Flower nearestFlowerToPlayer() {
        Vec3d player = this.world.getPlayers().isEmpty()
                ? Vec3d.ZERO : this.world.getPlayers().getFirst().getPos();
        Flower best = null;
        double bestDistance = Double.MAX_VALUE;
        for(Flower f : this) {
            if(f.hasWithered()) {
                continue;
            }
            Vec3d position = this.flowerManager.getFlowerMinecraftPosition(this, f);
            if(position != null && position.distanceTo(player) < bestDistance) {
                bestDistance = position.distanceTo(player);
                best = f;
            }
        }
        return best;
    }

    /** Dropped items vanish and the clocks come back, so the hotbar stays as Bip describes it. */
    private void clearItems() {
        List<? extends ItemEntity> entities = this.world.getEntitiesByType(
                TypeFilter.instanceOf(ItemEntity.class), e -> true);
        for(ItemEntity ie : entities) {
            ie.kill(this.world);
        }
        if(!entities.isEmpty() && this.stateManager.timeTravelItemsGiven()) {
            for(ServerPlayerEntity spe : this.world.getPlayers()) {
                this.interaction.giveTimeTravelItemsToPlayer(spe);
            }
        }
    }

    @Override
    public void tick() {
        this.clearItems();
        boolean talking = this.script.tick();
        if(!talking) {
            int tier = this.reminders.tick();
            if(tier >= 0) {
                this.emitBeat("pollinate_reminder_" + tier, Map.of());
            }
        }
        this.beeNestController.tickPollinationLines(this.ticks, this.pastPollinationLocations);
        ++this.ticks;
    }

    @Override
    public void onPlayerTargetNewEntity(ServerPlayerEntity player, boolean exists, int id) {
        if(exists && this.flowerManager.getFlowerByEntityId(this, id) != null) {
            ++this.targetCount;
        }
        super.onPlayerTargetNewEntity(player, exists, id);
        this.reminders.reset();
    }

    @Override
    public void onPlayerChat(ServerPlayerEntity player, String message) {
        this.script.onPlayerChat();
    }

    @Override
    public String describeCurrentTaskForAgent() {
        String context = this.script.chatContext();
        if(context != null) {
            return context;
        }
        return "CONTEXT: the player is about to pollinate a flower (aim at it, hold the hive from "
                + "slot 5, right-click). Help with that if they ask.";
    }

    @Override
    public void onPlayerPollinate(Flower flower, Vec3d flowerMinecraftPosition) {
        this.hasNextState = true;
        this.pastPollinationLocations.add(flowerMinecraftPosition);
        Vec3d pl = this.computeAveragePastPollinationPositions();
        this.nextState = new FilterBubbleBipHappeningState(this, pl, this.stage,
                flower.getNumber());
        this.net.beetrapLog(BEETRAP_LOG_ID_POLLINATION_INITIATED, "");
    }

    @Override
    public boolean timeTravelAvailable() {
        return this.stage != 0;
    }
}
