package beetrap.btfmc.agent.physical;

import beetrap.btfmc.agent.Agent;
import beetrap.btfmc.agent.physical.state.PAS0Introduction;
import beetrap.btfmc.state.BeetrapStateManager;
import beetrap.btfmc.tts.SlopTextToSpeechUtil;
import java.util.Random;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.BeeEntity;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

public class PhysicalAgent extends Agent {

    /** Canned yells played instantly when the player punches Bip. */
    private static final String[] KICK_YELLS = {
            "OW!",
            "HEY!",
            "WHY'D YOU DO THAT?!",
            "STOP THAT!",
            "THAT HURTS!",
            "OUCH!",
            "YIKES!",
            "CUT IT OUT!",
            "NOT COOL!",
            "EASY THERE!"
    };
    private static final long KICK_REACTION_COOLDOWN_MS = 600L;

    private final BeeEntity beeEntity;
    private final Random random;
    private long lastKickReactionMs;

    public PhysicalAgent(ServerWorld world, BeetrapStateManager beetrapStateManager) {
        super(world, beetrapStateManager, new PAS0Introduction());

        this.beeEntity = new BeeEntity(EntityType.BEE, this.world);
        this.beeEntity.getGoalSelector().clear(goal -> true);
        this.beeEntity.setInvulnerable(true);
        this.beeEntity.setPos(0.5, 1, 0.5);
        this.beeEntity.setNoGravity(true);
        this.world.spawnEntity(this.beeEntity);
        this.random = new Random();
    }

    public BeeEntity getBeeEntity() {
        return this.beeEntity;
    }

    /**
     * Immediate, low-latency reaction to the player punching Bip: a random canned
     * yell (chat + voice) plus a quick shove away from the player. This deliberately
     * bypasses the LLM so the response feels instant.
     */
    public void reactToKick(ServerPlayerEntity player) {
        long now = System.currentTimeMillis();
        if(now - this.lastKickReactionMs < KICK_REACTION_COOLDOWN_MS) {
            return;
        }
        this.lastKickReactionMs = now;

        String yell = KICK_YELLS[this.random.nextInt(KICK_YELLS.length)];

        // Chat + voice, immediately.
        for(ServerPlayerEntity p : this.world.getPlayers()) {
            p.sendMessage(Text.of("<" + this.name + "> " + yell));
        }
        SlopTextToSpeechUtil.say(yell);

        // Shove Bip away from the player so the punch feels physical.
        Vec3d away = this.beeEntity.getPos().subtract(player.getPos());
        if(away.horizontalLengthSquared() < 1.0e-6) {
            away = new Vec3d(0.0, 0.0, 1.0);
        }
        away = away.normalize().multiply(0.9);
        this.beeEntity.setVelocity(away.x, 0.35, away.z);
        this.beeEntity.velocityModified = true;
        this.sendPacketToAllPlayers(new EntityVelocityUpdateS2CPacket(this.beeEntity));
    }

    @Override
    public void close() {
        this.beeEntity.kill(this.world);
    }
}
