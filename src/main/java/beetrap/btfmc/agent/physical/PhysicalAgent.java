package beetrap.btfmc.agent.physical;

import beetrap.btfmc.agent.Agent;
import beetrap.btfmc.agent.physical.state.PAS0Introduction;
import beetrap.btfmc.state.BeetrapStateManager;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.BeeEntity;
import net.minecraft.server.world.ServerWorld;

public class PhysicalAgent extends Agent {

    private final BeeEntity beeEntity;

    public PhysicalAgent(ServerWorld world, BeetrapStateManager beetrapStateManager) {
        super(world, beetrapStateManager, new PAS0Introduction());

        this.beeEntity = new BeeEntity(EntityType.BEE, this.world);
        this.beeEntity.getGoalSelector().clear(goal -> true);
        this.beeEntity.setInvulnerable(true);
        this.beeEntity.setPos(0.5, 1, 0.5);
        this.beeEntity.setNoGravity(true);
        this.world.spawnEntity(this.beeEntity);
    }

    public BeeEntity getBeeEntity() {
        return this.beeEntity;
    }

    @Override
    public void close() {
        this.beeEntity.kill(this.world);
    }
}
