package beetrap.btfmc.agent;

import beetrap.btfmc.agent.event.EventMessage;
import beetrap.btfmc.state.BeetrapStateManager;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Set<String> knownCommandIds;
    private final Deque<String> completedCommandIds;
    private final Deque<String> failedCommandIds;
    private volatile String currentCommandId;
    protected AgentState currentState;
    protected InstructionBuilder instructionBuilder;

    public Agent(ServerWorld world, BeetrapStateManager beetrapStateManager, String name,
            AgentState initialAgentState) {
        this.world = world;
        this.beetrapStateManager = beetrapStateManager;
        this.name = name;
        this.setCurrentAgentState(initialAgentState);
        this.agentCommandQueue = new ConcurrentLinkedDeque<>();
        this.knownCommandIds = ConcurrentHashMap.newKeySet();
        this.completedCommandIds = new ConcurrentLinkedDeque<>();
        this.failedCommandIds = new ConcurrentLinkedDeque<>();
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

    public boolean addCommand(AgentCommand agentCommand) {
        if(!this.knownCommandIds.add(agentCommand.commandId())) {
            return false;
        }
        this.agentCommandQueue.addLast(agentCommand);
        return true;
    }

    public AgentCommand getNextCommand() {
        return this.agentCommandQueue.getFirst();
    }

    public void markCommandStarted(AgentCommand command) {
        this.currentCommandId = command.commandId();
        if(command.type().equalsIgnoreCase("fly_to")) {
            this.beetrapStateManager.recordAgentEvent("agent_moving", Map.of(
                    "command_id", command.commandId(),
                    "target", List.of(command.args())
            ));
        }
    }

    public AgentCommand completeNextCommand() {
        AgentCommand command = this.agentCommandQueue.removeFirst();
        this.currentCommandId = null;
        this.completedCommandIds.addLast(command.commandId());
        return command;
    }

    public AgentCommand failNextCommand() {
        AgentCommand command = this.agentCommandQueue.removeFirst();
        this.currentCommandId = null;
        this.failedCommandIds.addLast(command.commandId());
        return command;
    }

    public boolean hasNextCommand() {
        return !this.agentCommandQueue.isEmpty();
    }

    public String getCurrentCommandId() {
        return this.currentCommandId;
    }

    public List<String> getQueuedCommandIds() {
        List<String> commandIds = new ArrayList<>();
        for(AgentCommand command : this.agentCommandQueue) {
            if(!command.commandId().equals(this.currentCommandId)) {
                commandIds.add(command.commandId());
            }
        }
        return commandIds;
    }

    public List<String> getCompletedCommandIds() {
        return List.copyOf(this.completedCommandIds);
    }

    public List<String> getFailedCommandIds() {
        return List.copyOf(this.failedCommandIds);
    }

    public void acknowledgeCommandStatuses(List<String> completed, List<String> failed) {
        this.completedCommandIds.removeAll(completed);
        this.failedCommandIds.removeAll(failed);
    }

    public InstructionBuilder getInstructionBuilder() {
        return this.instructionBuilder;
    }
}
