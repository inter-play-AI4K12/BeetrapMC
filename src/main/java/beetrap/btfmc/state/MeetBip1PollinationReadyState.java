package beetrap.btfmc.state;

import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_POLLINATION_INITIATED;

import beetrap.btfmc.flower.Flower;
import java.util.Map;
import net.minecraft.entity.ItemEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Vec3d;
import java.util.List;

public class MeetBip1PollinationReadyState extends PollinationReadyState {

    public MeetBip1PollinationReadyState(BeetrapState parent, int stage) {
        super(parent, stage);
    }

    private void clearItems() {
        List<? extends ItemEntity> entities = this.world.getEntitiesByType(
                TypeFilter.instanceOf(ItemEntity.class),
                itemEntity -> true);

        for (ItemEntity ie : entities) {
            ie.kill(this.world);
        }

        if (!entities.isEmpty()) {
            for (ServerPlayerEntity spe : this.world.getPlayers()) {
                this.interaction.giveTimeTravelItemsToPlayer(spe);
            }
        }
    }

    private void onTick20() {
        if (this.ticks != 20) {
            return;
        }

        this.stateManager.recordAgentEvent("activity_narration", Map.of(
                "text", "Hi! Welcome to the magic garden. Pollinate flowers you like!"));
    }

    @Override
    public void tick() {
        this.clearItems();
        if (this.stage == 0) {
            this.onTick20();
        }

        this.beeNestController.tickPollinationLines(this.ticks, this.pastPollinationLocations);

        ++this.ticks;
    }

    @Override
    public void onPlayerPollinate(Flower flower, Vec3d flowerMinecraftPosition) {
        this.hasNextState = true;
        this.pastPollinationLocations.add(flowerMinecraftPosition);
        Vec3d pl = this.computeAveragePastPollinationPositions();
        this.nextState = new MeetBip1PollinationHappeningState(this, pl, this.stage);
        this.net.beetrapLog(BEETRAP_LOG_ID_POLLINATION_INITIATED, "");
    }

    @Override
    public boolean timeTravelAvailable() {
        return this.stage != 0;
    }
}
