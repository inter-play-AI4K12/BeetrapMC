package beetrap.btfmc.agent.physical.state;

import beetrap.btfmc.agent.AgentCommand;
import beetrap.btfmc.agent.AgentState;
import beetrap.btfmc.agent.InstructionBuilder;
import beetrap.btfmc.agent.event.ChatEventMessage;
import beetrap.btfmc.agent.event.GameStartEventMessage;
import beetrap.btfmc.agent.physical.PhysicalAgent;
import beetrap.btfmc.flower.FlowerManager;
import beetrap.btfmc.flower.FlowerPool;
import beetrap.btfmc.state.BeetrapState;
import beetrap.btfmc.state.BeetrapStateManager;
import beetrap.btfmc.tts.SlopTextToSpeechUtil;
import beetrap.btfmc.util.TextUtil;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import net.minecraft.entity.passive.BeeEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Direction.Axis;
import net.minecraft.util.math.Vec3d;

public class PhysicalAgentState extends AgentState {
    private static final double EPSILON = 0.25;
    private static final Random IDLE_RANDOM = new Random();
    private static final int IDLE_WANDER_INTERVAL_TICKS = 160;  // new wander target every ~8 s
    private static final double IDLE_WANDER_RADIUS = 1.5;       // blocks from player — stays close
    private static final double IDLE_BOB_AMPLITUDE = 0.25;      // blocks
    private static final double IDLE_BOB_SPEED = 0.07;          // radians per tick (~4 s cycle)

    protected PhysicalAgent physicalAgent;
    protected BeeEntity beeEntity;
    protected ServerWorld world;
    protected String name;
    protected final Object currentCommandLock;
    protected AgentCommand currentCommand;
    protected long commandTick;
    protected Vec3d flyToPosition;
    protected boolean hasNextState;
    protected PhysicalAgentState nextState;

    // Proportional dialogue display system
    protected boolean isTextDisplayActive;
    protected String pendingDialogue;
    protected List<String> textChunks;
    protected int currentChunkIndex;
    protected long textDisplayStartTick;
    protected double audioDurationSeconds;
    protected String fullDialogue;
    
    // Display chunk size for readability
    protected static final int DISPLAY_CHUNK_LENGTH = 25;

    // Idle hover / wander state (not carried across state transitions by design)
    private long idleTick = 0;
    private Vec3d idleWanderTarget = null;

    public PhysicalAgentState() {
        super();
        this.currentCommandLock = new Object();
        this.isTextDisplayActive = false;
        this.pendingDialogue = null;
        this.textChunks = null;
        this.currentChunkIndex = 0;
        this.textDisplayStartTick = -1;
        this.audioDurationSeconds = 0;
        this.fullDialogue = null;
    }

    public PhysicalAgentState(PhysicalAgentState state) {
        this.physicalAgent = state.physicalAgent;
        this.beeEntity = state.beeEntity;
        this.world = state.world;
        this.name = state.name;
        this.currentCommandLock = state.currentCommandLock;
        this.commandTick = state.commandTick;
        this.flyToPosition = state.flyToPosition;
        this.hasNextState = false;
        this.nextState = null;
        this.isTextDisplayActive = state.isTextDisplayActive;
        this.pendingDialogue = state.pendingDialogue;
        this.textChunks = state.textChunks;
        this.currentChunkIndex = state.currentChunkIndex;
        this.textDisplayStartTick = state.textDisplayStartTick;
        this.audioDurationSeconds = state.audioDurationSeconds;
        this.fullDialogue = state.fullDialogue;
    }

    @Override
    public void onAttach() {
        super.onAttach();
        this.physicalAgent = (PhysicalAgent)this.agent;
        this.beeEntity = this.physicalAgent.getBeeEntity();
        this.world = this.agent.getWorld();
        this.name = this.agent.getName();
        this.commandTick = -1;
    }

    private void handleSayCommand(String dialogue) {
        if(this.commandTick == 0) {
            // Send chat message as before
            this.world.getPlayers().forEach(
                    serverPlayerEntity -> serverPlayerEntity.sendMessage(
                            Text.of("<" + this.name + "> " + dialogue)));

            // Check if text is currently being displayed
            if (this.isTextDisplayActive) {
                // Queue the new dialogue instead of interrupting current display
                this.pendingDialogue = dialogue;
            } else {
                // Start the new proportional dialogue display system
                this.startProportionalDialogue(dialogue);
            }
        }
        // Note: updateTextDisplay() is now called in tick() method to run independently of command system
    }
    
    private void startProportionalDialogue(String dialogue) {
        this.fullDialogue = dialogue;

        // Get TTS with duration information
        SlopTextToSpeechUtil.sayWithDuration(dialogue).thenAccept(ttsResult -> {
            this.beginTextDisplay(dialogue, ttsResult.durationSeconds);

            // Handle completion when audio finishes
            ttsResult.playbackFuture.whenComplete((unused, throwable) ->
                    this.finishTextDisplay());
        }).exceptionally(throwable -> {
            // TTS failed (e.g. missing/invalid Typecast key or unreachable endpoint).
            // Show the text anyway and keep the command queue moving so Bip never freezes.
            double fallbackSeconds = Math.max(2.0, dialogue.length() / 15.0);
            this.beginTextDisplay(dialogue, fallbackSeconds);
            CompletableFuture
                    .delayedExecutor((long)(fallbackSeconds * 1000), TimeUnit.MILLISECONDS)
                    .execute(this::finishTextDisplay);
            return null;
        });
    }

    private void beginTextDisplay(String dialogue, double durationSeconds) {
        this.audioDurationSeconds = durationSeconds;

        // Break dialogue into display chunks
        this.textChunks = TextUtil.wrapText(dialogue, DISPLAY_CHUNK_LENGTH);
        this.currentChunkIndex = 0;
        this.textDisplayStartTick = this.world.getTime();
        this.isTextDisplayActive = true;

        // Start showing first chunk immediately
        if (!this.textChunks.isEmpty()) {
            this.beeEntity.setCustomName(Text.of(this.textChunks.get(0)));
            this.beeEntity.setCustomNameVisible(true);
        }
    }

    private void finishTextDisplay() {
        this.beeEntity.setCustomName(null);
        this.beeEntity.setCustomNameVisible(false);
        this.isTextDisplayActive = false;
        this.completeCommand();
    }

    private void updateTextDisplay() {
        // Handle proportional chunk display timing
        if (this.isTextDisplayActive && this.textChunks != null && !this.textChunks.isEmpty()) {
            long currentTick = this.world.getTime();
            long elapsedTicks = currentTick - this.textDisplayStartTick;
            double elapsedSeconds = elapsedTicks / 20.0; // Convert ticks to seconds
            
            // Calculate which chunk should be displayed based on proportional timing
            double totalChars = this.fullDialogue.length();
            int targetChunkIndex = 0;
            double accumulatedChars = 0;
            
            for (int i = 0; i < this.textChunks.size(); i++) {
                double chunkChars = this.textChunks.get(i).length();
                double chunkEndTime = ((accumulatedChars + chunkChars) / totalChars) * this.audioDurationSeconds;
                
                if (elapsedSeconds <= chunkEndTime) {
                    targetChunkIndex = i;
                    break;
                }
                
                accumulatedChars += chunkChars;
                targetChunkIndex = i + 1; // In case we're at the end
            }
            
            // Ensure we don't go beyond the last chunk
            targetChunkIndex = Math.min(targetChunkIndex, this.textChunks.size() - 1);
            
            // Update display if chunk changed
            if (targetChunkIndex != this.currentChunkIndex) {
                this.currentChunkIndex = targetChunkIndex;
                this.beeEntity.setCustomName(Text.of(this.textChunks.get(this.currentChunkIndex)));
                this.beeEntity.setCustomNameVisible(true);
            }
        }
        
        // Handle pending dialogue processing
        if (!this.isTextDisplayActive && this.pendingDialogue != null) {
            String nextDialogue = this.pendingDialogue;
            this.pendingDialogue = null;
            this.startProportionalDialogue(nextDialogue);
        }
    }

    private void handleFlyToFlowerCommand(String number) {
        if(this.commandTick == 0) {
            BeetrapStateManager bsm = this.agent.getBeetrapStateManager();
            BeetrapState bs = bsm.getState();
            FlowerManager fm = bsm.getFlowerManager();
            FlowerPool fp = bs.getFlowerPool();
            this.flyToPosition = fm.getFlowerMinecraftPosition(bsm.getState(), fp.getFlowerByNumber(
                    Integer.parseInt(number)));

            if(this.flyToPosition == null) {
                this.flyToPosition = new Vec3d(0, 0, 0);
            }

            this.beeEntity.getMoveControl()
                    .moveTo(this.flyToPosition.x, this.flyToPosition.y + 1, this.flyToPosition.z,
                            1);
            return;
        }

        this.beeEntity.getMoveControl()
                .moveTo(this.flyToPosition.x, this.flyToPosition.y + 1, this.flyToPosition.z, 1);

        if(this.beeEntity.getPos().withAxis(Axis.Y, 0).distanceTo(this.flyToPosition.withAxis(
                Axis.Y, 0)) < EPSILON) {
            this.beeEntity.setMovementSpeed(0);
            this.completeCommand();
        }
    }

    private void handleFlyToPlayerCommand() {
        if(this.commandTick == 0) {
            this.flyToPosition = this.world.getPlayers().getFirst().getPos();
            this.beeEntity.getMoveControl()
                    .moveTo(this.flyToPosition.x, this.flyToPosition.y + 1, this.flyToPosition.z,
                            1);
            return;
        }

        this.beeEntity.getMoveControl()
                .moveTo(this.flyToPosition.x, this.flyToPosition.y + 1, this.flyToPosition.z, 1);

        if(this.beeEntity.getPos().withAxis(Axis.Y, 0).distanceTo(this.flyToPosition.withAxis(
                Axis.Y, 0)) < EPSILON) {
            this.completeCommand();
        }
    }

    private void handleFlyToBeehiveCommand() {
        if(this.commandTick == 0) {
            BeetrapStateManager bsm = this.agent.getBeetrapStateManager();
            this.flyToPosition = bsm.getBeeNestController().getBeeNestPosition();
            this.beeEntity.getMoveControl()
                    .moveTo(this.flyToPosition.x, this.flyToPosition.y + 1, this.flyToPosition.z,
                            1);
            return;
        }

        this.beeEntity.getMoveControl()
                .moveTo(this.flyToPosition.x, this.flyToPosition.y + 1, this.flyToPosition.z, 1);

        if(this.beeEntity.getPos().withAxis(Axis.Y, 0).distanceTo(this.flyToPosition.withAxis(
                Axis.Y, 0)) < EPSILON) {
            this.completeCommand();
        }
    }

    private void handleFlyToCommand(String[] args) {
        String entityType = args[0];

        if(entityType.equalsIgnoreCase("flower")) {
            this.handleFlyToFlowerCommand(args[1]);
        } else if(entityType.equalsIgnoreCase("player")) {
            this.handleFlyToPlayerCommand();
        } else if(entityType.equalsIgnoreCase("beehive")) {
            this.handleFlyToBeehiveCommand();
        }
    }

    private void handleCurrentCommand() {
        if(this.currentCommand.type().equalsIgnoreCase("say")) {
            String dialogue = this.currentCommand.args()[0];
            this.handleSayCommand(dialogue);
            return;
        }

        if(this.currentCommand.type().equalsIgnoreCase("fly_to")) {
            this.handleFlyToCommand(this.currentCommand.args());
            return;
        }

        this.completeCommand();
    }

    public void completeCommand() {
        synchronized(this.currentCommandLock) {
            this.agent.completeNextCommand();
            this.currentCommand = null;
            this.commandTick = -1;
        }
    }

    @Override
    public void tick() {
        if(this.beeEntity == null) {
            this.beeEntity = this.physicalAgent.getBeeEntity();
        }

        // Update text display independently of command system
        // This ensures text continues to update even after commands complete
        this.updateTextDisplay();

        if(!this.agent.hasNextCommand()) {
            this.tickIdle();
        } else {
            this.idleTick = 0;
            this.idleWanderTarget = null;
            synchronized(this.currentCommandLock) {
                if(this.currentCommand == null) {
                    this.currentCommand = this.agent.getNextCommand();
                    this.agent.markCommandStarted(this.currentCommand);
                }

                ++this.commandTick;
                this.handleCurrentCommand();
            }
        }

        if(!(this instanceof PAS1EndGame)) {
            if(this.agent.getBeetrapStateManager().isActivityEnded()) {
                this.hasNextState = true;
                this.nextState = new PAS1EndGame(this);
            }
        }
    }

    private void tickIdle() {
        this.idleTick++;

        if(this.world.getPlayers().isEmpty()) {
            return;
        }
        Vec3d playerPos = this.world.getPlayers().getFirst().getPos();

        // Anchor wander targets to the player so Bip never drifts away.
        // Pick a new target on first idle tick and every IDLE_WANDER_INTERVAL_TICKS.
        if(this.idleWanderTarget == null || this.idleTick % IDLE_WANDER_INTERVAL_TICKS == 1) {
            double dx = (IDLE_RANDOM.nextDouble() - 0.5) * 2 * IDLE_WANDER_RADIUS;
            double dz = (IDLE_RANDOM.nextDouble() - 0.5) * 2 * IDLE_WANDER_RADIUS;
            // Hover just above the player's head regardless of where Bip was before
            this.idleWanderTarget = new Vec3d(
                    playerPos.x + dx, playerPos.y + 1.5, playerPos.z + dz);
        }

        // Gentle sinusoidal bob while slowly drifting to the wander target.
        // MoveControl handles turning naturally — no manual lookAt() here to avoid twitching.
        double bobY = (playerPos.y + 1.5) + Math.sin(this.idleTick * IDLE_BOB_SPEED) * IDLE_BOB_AMPLITUDE;
        this.beeEntity.getMoveControl().moveTo(
                this.idleWanderTarget.x, bobY, this.idleWanderTarget.z, 0.25);
    }

    public void updateStateInstruction(InstructionBuilder ib) {
        ib.resetStateInstructionBuilder();
    }

    private void updateContextInstruction(InstructionBuilder ib, ServerPlayerEntity serverPlayerEntity) {
        ib.resetContextInstructionBuilder();
        StringBuilder contextInstructionBuilder = ib.contextInstructionBuilder();

        contextInstructionBuilder.append("Your position: ")
                .append(this.physicalAgent.getBeeEntity().getPos()).append(System.lineSeparator());

        this.agent.getBeetrapStateManager()
                .getJsonReadyDataForGpt(this.physicalAgent.getBeeEntity(), serverPlayerEntity,
                        contextInstructionBuilder);
    }

    public void updateInstructions(ServerPlayerEntity serverPlayerEntity) {
        InstructionBuilder ib = this.agent.getInstructionBuilder();
        this.updateStateInstruction(ib);
        this.updateContextInstruction(ib, serverPlayerEntity);
    }

    @Override
    public void onChatMessageReceived(ServerPlayerEntity serverPlayerEntity, String message) {
        this.updateInstructions(serverPlayerEntity);
        this.agent.sendGptEventMessage(new ChatEventMessage(message));
    }

    @Override
    public void onGameStart() {
        this.updateInstructions(this.world.getPlayers().getFirst());
        this.agent.sendGptEventMessage(new GameStartEventMessage());
    }

    @Override
    public boolean hasNextState() {
        return this.hasNextState;
    }

    @Override
    public AgentState getNextState() {
        return this.nextState;
    }
}
