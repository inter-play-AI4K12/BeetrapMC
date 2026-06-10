package beetrap.btfmc.state;

import beetrap.btfmc.flower.Flower;
import net.minecraft.util.math.Vec3d;

public abstract class PollinationReadyState extends BeetrapState {

    protected boolean hasNextState;
    protected BeetrapState nextState;
    protected long ticks;
    protected int stage;

    public PollinationReadyState(BeetrapState parent, int stage) {
        super(parent);
        this.stage = stage;
    }

    @Override
    public void tick() {
        this.beeNestController.tickPollinationLines(this.ticks, this.pastPollinationLocations);

    }

    @Override
    public boolean hasNextState() {
        return this.hasNextState;
    }

    @Override
    public BeetrapState getNextState() {
        return this.nextState;
    }

    @Override
    public abstract void onPlayerPollinate(Flower flower, Vec3d flowerMinecraftPosition);
}
