package beetrap.btfmc.agent;

import beetrap.btfmc.agent.event.EventMessage;
import beetrap.btfmc.openai.OpenAiUtil;
import beetrap.btfmc.state.BeetrapStateManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
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
    private static final ObjectMapper om;

    static {
        om = new ObjectMapper();
        SimpleModule sm = new SimpleModule();
        sm.addDeserializer(AgentCommand.class, new AgentCommandDeserializer());
        sm.addDeserializer(GptResponse.class, new GptResponseDeserializer());
        om.registerModule(sm);
    }

    protected final ServerWorld world;
    protected final String name;
    protected final OpenAIClient openAiClient;
    private final BeetrapStateManager beetrapStateManager;
    protected Response previousResponse;
    protected Deque<AgentCommand> agentCommandQueue;
    protected AgentState currentState;
    protected InstructionBuilder instructionBuilder;

    public Agent(ServerWorld world, BeetrapStateManager beetrapStateManager, String name,
            AgentState initialAgentState) {
        this.world = world;
        this.beetrapStateManager = beetrapStateManager;
        this.name = name;
        this.setCurrentAgentState(initialAgentState);
        this.openAiClient = OpenAiUtil.getClient();
        this.agentCommandQueue = new ArrayDeque<>();
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

    public void onGptResponseReceived(Response response, Throwable throwable) {
        String string = response.output().getFirst().asMessage().content().getFirst().asOutputText()
                .text();

        LOG.info("Raw response: {}", string);

        try {
            GptResponse gptResponse = om.readValue(string, GptResponse.class);

            for(AgentCommand agentCommand : gptResponse.getAgentCommands()) {
                this.agentCommandQueue.addLast(agentCommand);
            }
        } catch(JsonProcessingException e) {
            LOG.error(e);
        }

    }

    public void sendGptEventMessage(EventMessage eventMessage) {
        this.getGpt4oLatestResponseAsync(eventMessage.toJsonString()).whenCompleteAsync(this::onGptResponseReceived);
    }

    private CompletableFuture<Response> getGpt4oLatestResponseAsync(
            String input) {
        String instructions = this.instructionBuilder.build();
        LOG.info("The complete instruction: {}", instructions);
        ResponseCreateParams params;

        if(previousResponse != null) {
            params = ResponseCreateParams.builder()
                    .instructions(instructions)
                    .input(input)
                    .model("gpt-5.2")
                    .previousResponseId(previousResponse.id())
                    .build();
        } else {
            params = ResponseCreateParams.builder()
                    .instructions(instructions)
                    .input(input)
                    .model("gpt-5.2")
                    .build();
        }

        CompletableFuture<Response> response = this.openAiClient.async().responses().create(params);
        return response.whenComplete((response1, throwable) -> {
            this.previousResponse = response1;

            if(throwable != null) {
                LOG.error(throwable);
            }
        });
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
