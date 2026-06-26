package beetrap.btfmc;

import beetrap.btfmc.entity.PollenEntity;
import beetrap.btfmc.factories.FallingBlockFactory;
import beetrap.btfmc.flower.Flower;
import beetrap.btfmc.flower.FlowerManager;
import beetrap.btfmc.networking.EntityPositionUpdateS2CPayload;
import beetrap.btfmc.networking.NetworkingService;
import beetrap.btfmc.util.TicksUtil;
import java.util.List;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;

public class BeeNestController {

    private static final double EPSILON = 0.01;
    private static final long MAX_ANIMATION_TICKS = 20;
    private static final long MAX_CIRCLE_TICKS = 20 + 10 * 20;
    private final ServerWorld world;
    private final Vec3d basePos;
    private final NetworkingService net;
    private FallingBlockEntity nest;
    private Vec3d destination;
    private boolean beeNestMoving;

    public BeeNestController(ServerWorld world, NetworkingService net, Vec3d basePosition) {
        this.world = world;
        this.net = net;
        this.basePos = basePosition;
    }

    public void spawnNest() {
        nest = FallingBlockFactory.createNoGravity(world,
                basePos.x, basePos.y, basePos.z,
                Blocks.BEE_NEST.getDefaultState());
        world.spawnEntity(nest);
    }

    public void startPollination(Vec3d target) {
        this.destination = new Vec3d(target.x, basePos.y, target.z);
        Vec3d delta = destination.subtract(nest.getPos()).multiply(1 / 20.0, 0, 1 / 20.0);
        nest.setVelocity(delta.x, delta.y, delta.z);
        net.broadcastPacket(new EntityVelocityUpdateS2CPacket(nest));
        this.beeNestMoving = true;
    }

    public void tickMovementAnimation(long ticks) {
        if(!this.beeNestMoving) {
            return;
        }

        if(TicksUtil.inInterval(ticks, 0, MAX_ANIMATION_TICKS)) {
            return;
        }

        this.nest.setVelocity(0, 0, 0);
        this.net.broadcastPacket(new EntityVelocityUpdateS2CPacket(nest));
        this.nest.requestTeleport(destination.x, destination.y, destination.z);
        this.net.broadcastCustomPayload(
                beetrap.btfmc.networking.EntityPositionUpdateS2CPayload.create(nest));
        this.beeNestMoving = false;
    }

    private void spawnCircleParticles(double r) {
        for(double t = 0; t < Math.TAU; t = t + EPSILON) {
            this.world.spawnParticles(ParticleTypes.FALLING_HONEY,
                    this.nest.getX() + r * Math.cos(t), this.nest.getY(),
                    this.nest.getZ() + r * Math.sin(t), 1, 0, 0, 0, 0);
        }
    }

    private void spawnLineParticles(Vec3d start, Vec3d end) {
        Vec3d direction = end.subtract(start);
        double distance = direction.length();
        Vec3d normalizedDirection = direction.normalize();

        // Spawn particles along the line with small intervals
        double particleSpacing = 0.01; // Distance between particles
        for(double d = 0; d <= distance; d += particleSpacing) {
            Vec3d particlePos = start.add(normalizedDirection.multiply(d));
            // Use yellow particles (FALLING_NECTAR for yellow color)
            this.world.spawnParticles(ParticleTypes.FALLING_NECTAR,
                    particlePos.x, particlePos.y - 0.9, particlePos.z,
                    1, 0, 0, 0, 0);
        }
    }

    public void tickCircle(long ticks, double r) {
        if(!(ticks % 20 == 0 && TicksUtil.inInterval(ticks, MAX_ANIMATION_TICKS,
                MAX_CIRCLE_TICKS))) {
            return;
        }

        this.spawnCircleParticles(r);
    }

    private void spawnPollen(Vec3d startingPosition, Vec3d destination, double speed) {
        Vec3d nv = destination.subtract(startingPosition);
        ExperienceOrbEntity eoe = new PollenEntity(this.world, startingPosition.x,
                startingPosition.y, startingPosition.z, 0, nv.multiply(speed));
        eoe.setNoGravity(true);
        this.world.spawnEntity(eoe);
    }

    public void tickSpawnPollensThatFlyTowardsNest(long ticks, FlowerManager flowerManager,
            Flower[] positions) {
        if(!(ticks % 15 == 0 && TicksUtil.inInterval(ticks, MAX_ANIMATION_TICKS,
                MAX_CIRCLE_TICKS))) {
            return;
        }

        if(positions.length == 0) {
            return;
        }

        Flower f = positions[(int)(Math.random() * positions.length)];

        if(f == null) {
            return;
        }

        var entity = flowerManager.getFlowerEntity(f);
        if(entity == null) {
            // The bud's entity can be gone (e.g. already bloomed/removed); skip this frame.
            return;
        }

        this.spawnPollen(entity.getPos(), this.nest.getPos(), 1 / 20f);
    }

    public void tickCircleBetweenAAndInfinity(long ticks, double r, long a) {
        if(!(ticks % 20 == 0 && ticks >= a)) {
            return;
        }

        this.spawnCircleParticles(r);
    }

    public void tickPollinationLines(long ticks, List<Vec3d> pollinationLocations) {
        // Only draw lines every 10 ticks for performance and visual effect
        if(ticks % 3 != 0) {
            return;
        }

        // Don't show lines when beenest is moving
        if(this.beeNestMoving) {
            return;
        }

        Vec3d nestPos = this.getBeeNestPosition();
        // Adjust the Y position to be on the ground
        Vec3d groundNestPos = new Vec3d(nestPos.x, 1.0, nestPos.z);

        // Draw lines to pollinated flowers using pastPollinationLocations
        for(Vec3d pollinationLocation : pollinationLocations) {
            // Adjust the Y position to be on the ground
            Vec3d groundFlowerPos = new Vec3d(pollinationLocation.x, 1.0, pollinationLocation.z);

            this.spawnLineParticles(groundNestPos, groundFlowerPos);
        }
    }

    public void dispose() {
        if(nest != null) {
            nest.kill(world);
        }
    }

    public Vec3d getBeeNestPosition() {
        return this.nest.getPos();
    }

    public void setBeeNestPosition(Vec3d position) {
        this.nest.teleportTo(new TeleportTarget(this.world, position, new Vec3d(0, 0, 0), 0, 0,
                TeleportTarget.NO_OP));
        this.net.broadcastCustomPayload(
                new EntityPositionUpdateS2CPayload(this.nest.getId(), this.nest.getX(),
                        this.nest.getY(), this.nest.getZ()));
    }

    public FallingBlockEntity getBeeNest() {
        return this.nest;
    }
}
