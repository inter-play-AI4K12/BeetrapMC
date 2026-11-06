package beetrap.btfmc.agent.physical;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.control.FlightMoveControl;
import net.minecraft.entity.ai.control.LookControl;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.BeeEntity;
import net.minecraft.world.World;

/**
 * The bee that plays Bip. It is a normal bee that flies the same way, except that nothing in the
 * bee's own AI is allowed to rotate it: {@link beetrap.btfmc.agent.physical.state.PhysicalAgentState}
 * is the only code that sets Bip's yaw and pitch (through {@link BipHeading}).
 *
 * <p>Without this the vanilla move control turned the bee toward every flight target (up to 90
 * degrees in one tick) and the look control pulled the head and pitch back, fighting our own
 * facing code. That fight was the spontaneous head flicking.
 */
public class BipBeeEntity extends BeeEntity {

    public BipBeeEntity(EntityType<? extends BeeEntity> entityType, World world) {
        super(entityType, world);
        this.moveControl = new HeadingPreservingMoveControl(this);
        this.lookControl = new InertLookControl(this);
    }

    /** The vanilla bee flight control (same speed, climb and hover), minus its rotation changes. */
    private static final class HeadingPreservingMoveControl extends FlightMoveControl {
        private final MobEntity bee;

        HeadingPreservingMoveControl(MobEntity bee) {
            super(bee, 20, true);
            this.bee = bee;
        }

        @Override
        public void tick() {
            float yaw = this.bee.getYaw();
            float pitch = this.bee.getPitch();
            super.tick();
            this.bee.setYaw(yaw);
            this.bee.setPitch(pitch);
        }
    }

    /** A look control that never turns the head: Bip's head is steered by {@link BipHeading}. */
    private static final class InertLookControl extends LookControl {
        InertLookControl(MobEntity bee) {
            super(bee);
        }

        @Override
        public void tick() {
        }
    }
}
