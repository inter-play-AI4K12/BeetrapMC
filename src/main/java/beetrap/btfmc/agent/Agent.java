package beetrap.btfmc.agent;

import beetrap.btfmc.agent.event.EventMessage;
import beetrap.btfmc.state.BeetrapStateManager;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public abstract class Agent implements AutoCloseable {
    public static final int AGENT_LEVEL_NO_AGENT = 0;
    public static final int AGENT_LEVEL_CHAT_ONLY = 1;
    public static final int AGENT_LEVEL_CHAT_WITH_VOICE_TO_TEXT = 2;
    public static final int AGENT_LEVEL_PHYSICAL = 3;
    public static final String AGENT_NAME = "Bip Buzzley";
    private static final Logger LOG = LogManager.getLogger(Agent.class);

    protected final ServerWorld world;
    protected final String name;
    private final BeetrapStateManager beetrapStateManager;
    protected Deque<AgentCommand> agentCommandQueue;
    protected AgentState currentState;
    protected InstructionBuilder instructionBuilder;

    public Agent(ServerWorld world, BeetrapStateManager beetrapStateManager, String name,
            AgentState initialAgentState) {
        this.world = world;
        this.beetrapStateManager = beetrapStateManager;
        this.name = name;
        this.setCurrentAgentState(initialAgentState);
        this.agentCommandQueue = new ConcurrentLinkedDeque<>();
        this.instructionBuilder = new InstructionBuilder();
    }

    public Agent(ServerWorld world, BeetrapStateManager beetrapStateManager,
            AgentState initialState) {
        this(world, beetrapStateManager, AGENT_NAME, initialState);
    }

    private void setCurrentAgentState(AgentState currentAgentState) {
        this.currentState = currentAgentState;
        this.currentState.onAttach(this);
    }

    public BeetrapStateManager getBeetrapStateManager() {
        return this.beetrapStateManager;
    }

    public void onPlayerPollinate(ServerPlayerEntity serverPlayerEntity) {

    }

    public void tick() {
        if(this.currentState.hasNextState()) {
            this.setCurrentAgentState(this.currentState.getNextState());
        }

        this.tickCustom();
        this.currentState.tick();
    }

    protected void tickCustom() {

    }

    public void onChatMessageReceived(ServerPlayerEntity serverPlayerEntity, String message) {
        this.currentState.onChatMessageReceived(serverPlayerEntity, message);
    }

    public void onGameStart() {
        this.currentState.onGameStart();
    }

    public void close() {

    }

    public void sendGptEventMessage(EventMessage eventMessage) {
        LOG.debug("Agent event ignored by base implementation: {}", eventMessage);
    }

    public void sendPacketToAllPlayers(Packet<?> packet) {
        for(ServerPlayerEntity player : this.world.getPlayers()) {
            player.networkHandler.sendPacket(packet);
        }
    }

    public void sendCustomPayloadToAllPlayers(CustomPayload payload) {
        for(ServerPlayerEntity player : this.world.getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    public ServerWorld getWorld() {
        return this.world;
    }

    public String getName() {
        return this.name;
    }

    public void addCommand(AgentCommand agentCommand) {
        this.agentCommandQueue.addLast(agentCommand);
    }

    public AgentCommand getNextCommand() {
        return this.agentCommandQueue.getFirst();
    }

    public AgentCommand removeNextCommand() {
        return this.agentCommandQueue.removeFirst();
    }

    public boolean hasNextCommand() {
        return !this.agentCommandQueue.isEmpty();
    }

    public InstructionBuilder getInstructionBuilder() {
        return this.instructionBuilder;
    }
}
