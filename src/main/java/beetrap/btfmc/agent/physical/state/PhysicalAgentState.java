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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class PhysicalAgentState extends AgentState {
    private static final Random IDLE_RANDOM = new Random();
    private static final double IDLE_HOVER_RADIUS = 2.5;       // blocks: how far Bip hovers from player
    private static final double IDLE_FOLLOW_DISTANCE = 3.5;    // blocks: player moves this far -> Bip follows
    private static final double FACE_VELOCITY_EPSILON = 0.002; // only face player when essentially still
    private static final double FLY_TO_SPEED = 4.5;           // MoveControl multiplier for fly_to commands
    private static final double ARRIVAL_EPSILON = 0.6;         // 3D distance at which a fly_to is "arrived"
    private static final long LOOK_AT_TICKS = 7;              // hold a "look_at" this long so the turn reads
    // "Presentation pose" offsets: stop short of and above a target (on the player's side) so Bip
    // clearly indicates it from beside-and-above rather than diving into the block.
    private static final double FLOWER_STANDOFF = 1.5;         // hover this far toward the player from a flower
    private static final double FLOWER_HEIGHT = 1.1;           // ...and this high (clears the ~1-block bloom)
    private static final double HIVE_STANDOFF = 1.8;
    private static final double HIVE_HEIGHT = 1.0;
    private static final double PLAYER_STANDOFF = 2.0;         // stop this far from the player (don't fly into them)
    private static final double PLAYER_HEIGHT = 1.2;           // ...at about face height

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

    // Idle hover state (not carried across state transitions by design)
    private Vec3d idleWanderTarget = null;
    private Vec3d idleAnchor = null;
    // When set (by a look_at command), Bip faces this point instead of the player until he next
    // flies somewhere. Lets him turn to a flower, react, then dart over.
    private Vec3d faceOverride = null;

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

        // Show the line IMMEDIATELY with an estimated duration so Bip "talks" without waiting on
        // the Typecast download (which can take a few seconds). The voice plays when it is ready,
        // and we refine the chunk pacing once the real audio length is known. This is the single
        // biggest win against perceived lag — the text no longer trails the network round-trip.
        double estimatedSeconds = Math.max(1.5, dialogue.length() / 15.0);
        this.beginTextDisplay(dialogue, estimatedSeconds);

        SlopTextToSpeechUtil.sayWithDuration(dialogue).thenAccept(ttsResult -> {
            // Refine the on-screen pacing to the real audio length, and finish the command only
            // when playback actually ends (so back-to-back lines don't talk over each other).
            this.audioDurationSeconds = ttsResult.durationSeconds;
            ttsResult.playbackFuture.whenComplete((unused, throwable) ->
                    this.finishTextDisplay());
        }).exceptionally(throwable -> {
            // TTS failed (e.g. missing/invalid Typecast key or unreachable endpoint). The text is
            // already showing; just clear it after the estimate so the queue keeps moving.
            CompletableFuture
                    .delayedExecutor((long)(estimatedSeconds * 1000), TimeUnit.MILLISECONDS)
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

    /**
     * A "presentation pose" near a target: hovers a little above it and offset toward the player,
     * so Bip clearly indicates the thing without flying inside it. Falls back to straight-above
     * when there is no player to orient toward.
     */
    private Vec3d presentationPose(Vec3d target, double height, double standoff) {
        if(this.world.getPlayers().isEmpty()) {
            return new Vec3d(target.x, target.y + height, target.z);
        }
        Vec3d player = this.world.getPlayers().getFirst().getPos();
        double dx = player.x - target.x;
        double dz = player.z - target.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        if(h < 1.0e-4) {
            return new Vec3d(target.x, target.y + height, target.z);
        }
        return new Vec3d(target.x + (dx / h) * standoff, target.y + height,
                target.z + (dz / h) * standoff);
    }

    private boolean arrivedAtFlyTarget() {
        return this.beeEntity.getPos().distanceTo(this.flyToPosition) < ARRIVAL_EPSILON;
    }

    private void driveTowardFlyTarget() {
        this.beeEntity.getMoveControl()
                .moveTo(this.flyToPosition.x, this.flyToPosition.y, this.flyToPosition.z,
                        FLY_TO_SPEED);
        if(this.arrivedAtFlyTarget()) {
            this.beeEntity.setMovementSpeed(0);
            this.completeCommand();
        }
    }

    private void handleFlyToFlowerCommand(String number) {
        if(this.commandTick == 0) {
            BeetrapStateManager bsm = this.agent.getBeetrapStateManager();
            BeetrapState bs = bsm.getState();
            FlowerManager fm = bsm.getFlowerManager();
            FlowerPool fp = bs.getFlowerPool();
            Vec3d flower = fm.getFlowerMinecraftPosition(bsm.getState(),
                    fp.getFlowerByNumber(Integer.parseInt(number)));
            if(flower == null) {
                flower = new Vec3d(0, 0, 0);
            }
            // Drop down close to the bloom (not a block above it) so "this one" is unambiguous.
            this.flyToPosition = this.presentationPose(flower, FLOWER_HEIGHT, FLOWER_STANDOFF);
        }
        this.driveTowardFlyTarget();
    }

    private void handleFlyToPlayerCommand() {
        if(this.commandTick == 0) {
            Vec3d player = this.world.getPlayers().getFirst().getPos();
            Vec3d bee = this.beeEntity.getPos();
            double dx = bee.x - player.x;
            double dz = bee.z - player.z;
            double h = Math.sqrt(dx * dx + dz * dz);
            if(h < 1.0e-4) {
                dx = 0;
                dz = 1;
                h = 1;
            }
            // Stop a couple of blocks in front of the player at face height — never inside them.
            this.flyToPosition = new Vec3d(
                    player.x + (dx / h) * PLAYER_STANDOFF,
                    player.y + PLAYER_HEIGHT,
                    player.z + (dz / h) * PLAYER_STANDOFF);
        }
        this.driveTowardFlyTarget();
    }

    private void handleFlyToBeehiveCommand() {
        if(this.commandTick == 0) {
            BeetrapStateManager bsm = this.agent.getBeetrapStateManager();
            Vec3d hive = bsm.getBeeNestController().getBeeNestPosition();
            // Hover in front of the hive on the player's side rather than diving into the block.
            this.flyToPosition = this.presentationPose(hive, HIVE_HEIGHT, HIVE_STANDOFF);
        }
        this.driveTowardFlyTarget();
    }

    private void handleFlyToCommand(String[] args) {
        // Starting a flight cancels any look_at override so Bip faces the player again on arrival.
        this.faceOverride = null;

        String entityType = args[0];

        if(entityType.equalsIgnoreCase("flower")) {
            this.handleFlyToFlowerCommand(args[1]);
        } else if(entityType.equalsIgnoreCase("player")) {
            this.handleFlyToPlayerCommand();
        } else if(entityType.equalsIgnoreCase("beehive")) {
            this.handleFlyToBeehiveCommand();
        }
    }

    /** Resolve a look_at target (same arg shape as fly_to) to the point Bip should face. */
    private Vec3d resolveLookTarget(String[] args) {
        String entityType = args[0];
        if(entityType.equalsIgnoreCase("flower") && args.length > 1) {
            BeetrapStateManager bsm = this.agent.getBeetrapStateManager();
            FlowerPool fp = bsm.getState().getFlowerPool();
            Vec3d flower = bsm.getFlowerManager().getFlowerMinecraftPosition(bsm.getState(),
                    fp.getFlowerByNumber(Integer.parseInt(args[1])));
            return flower == null ? null : flower.add(0, 0.5, 0);
        }
        if(entityType.equalsIgnoreCase("player") && !this.world.getPlayers().isEmpty()) {
            return this.world.getPlayers().getFirst().getEyePos();
        }
        if(entityType.equalsIgnoreCase("beehive")) {
            return this.agent.getBeetrapStateManager().getBeeNestController().getBeeNestPosition();
        }
        return null;
    }

    /** Turn (without moving) to face a target, holding briefly so the glance reads before the next line. */
    private void handleLookAtCommand(String[] args) {
        if(this.commandTick == 0) {
            this.faceOverride = this.resolveLookTarget(args);
        }
        // The face logic in tick() eases Bip toward faceOverride; just hold for a beat, then finish.
        if(this.faceOverride == null || this.commandTick >= LOOK_AT_TICKS) {
            this.completeCommand();
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

        if(this.currentCommand.type().equalsIgnoreCase("look_at")) {
            this.handleLookAtCommand(this.currentCommand.args());
            return;
        }

        if(this.currentCommand.type().equalsIgnoreCase("give_clocks")) {
            // Sequenced right after the hand-off "say", so the clocks appear in the player's hand
            // exactly when Bip finishes saying "take these" — not on a guessed timer.
            this.agent.getBeetrapStateManager().giveTimeTravelItemsToAllPlayers();
            this.completeCommand();
            return;
        }

        if(this.currentCommand.type().equalsIgnoreCase("done")) {
            // A pure signal, not a physical action — always sequenced after the say command it
            // belongs to, so it lands exactly when that reply has actually finished.
            this.agent.signalConversationDone();
            this.completeCommand();
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
            if(beetrap.btfmc.BipFeatures.IDLE_WANDER) {
                this.tickIdle();
            }
        } else {
            this.idleWanderTarget = null;
            this.idleAnchor = null;
            synchronized(this.currentCommandLock) {
                if(this.currentCommand == null) {
                    this.currentCommand = this.agent.getNextCommand();
                    this.agent.markCommandStarted(this.currentCommand);
                }

                ++this.commandTick;
                this.handleCurrentCommand();
            }
        }

        // Face a target whenever MoveControl isn't actively steering and Bip is essentially still —
        // this keeps eye contact during idle/talking without fighting MoveControl's own heading
        // control (which would otherwise cause rapid left-right head twitching). Checking
        // MoveControl.isMoving() directly (not just "is there a fly_to command") matters: idle
        // wander also steers via MoveControl.moveTo() without going through a command at all, so a
        // command-type check alone missed that case and let the two fight during idle hovering.
        // An explicit look_at override (e.g. glancing at a dead flower before reacting) always wins;
        // otherwise face the player when that feature is on.
        if(!this.beeEntity.getMoveControl().isMoving()
                && this.beeEntity.getVelocity().horizontalLengthSquared() < FACE_VELOCITY_EPSILON) {
            if(this.faceOverride != null) {
                this.faceToward(this.faceOverride);
            } else if(beetrap.btfmc.BipFeatures.LOOK_AT_PLAYER
                    && !this.world.getPlayers().isEmpty()) {
                this.facePlayer(this.world.getPlayers().getFirst());
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
        if(this.world.getPlayers().isEmpty()) {
            return;
        }
        Vec3d playerPos = this.world.getPlayers().getFirst().getPos();

        // Hover close to the player instead of wandering off to random spots. Only pick a new
        // hover position when we don't have one, or the player has walked away (so Bip follows
        // rather than drifting somewhere useless). MoveControl is called ONLY on retarget —
        // calling it every tick forces a heading recalculation and causes visible twitching.
        if(this.idleWanderTarget == null || this.idleAnchor == null
                || playerPos.distanceTo(this.idleAnchor) > IDLE_FOLLOW_DISTANCE) {
            this.idleAnchor = playerPos;
            double angle = IDLE_RANDOM.nextDouble() * 2 * Math.PI;
            this.idleWanderTarget = new Vec3d(
                    playerPos.x + Math.cos(angle) * IDLE_HOVER_RADIUS,
                    playerPos.y + 1.6,
                    playerPos.z + Math.sin(angle) * IDLE_HOVER_RADIUS);
            this.beeEntity.getMoveControl().moveTo(
                    this.idleWanderTarget.x, this.idleWanderTarget.y, this.idleWanderTarget.z, 0.5);
        }
    }

    /** Smoothly turn Bip to face the player (yaw, body, head and pitch). */
    private void facePlayer(ServerPlayerEntity player) {
        this.faceToward(player.getEyePos());
    }

    /** Ease Bip's yaw/pitch toward an arbitrary world point (a glance, not a snap). */
    private void faceToward(Vec3d target) {
        Vec3d bee = this.beeEntity.getPos();
        double dx = target.x - bee.x;
        double dy = target.y - (bee.y + this.beeEntity.getStandingEyeHeight());
        double dz = target.z - bee.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float)(MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float targetPitch = (float)(-(MathHelper.atan2(dy, horizontal) * (180.0 / Math.PI)));

        float yaw = this.approachAngle(this.beeEntity.getYaw(), targetYaw, 0.4f);
        float pitch = this.approachAngle(this.beeEntity.getPitch(), targetPitch, 0.4f);

        this.beeEntity.setYaw(yaw);
        this.beeEntity.setBodyYaw(yaw);
        this.beeEntity.setHeadYaw(yaw);
        this.beeEntity.setPitch(pitch);
    }

    private float approachAngle(float from, float to, float fraction) {
        return from + MathHelper.wrapDegrees(to - from) * fraction;
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

        // Lets the active activity state hand the LLM ground truth it otherwise has no visibility
        // into (e.g. the real value behind a data-panel reading), so a chat reply can't be blindly
        // affirmed when it's factually wrong.
        String taskContext = this.agent.getBeetrapStateManager().getState().describeCurrentTaskForAgent();
        if(taskContext != null && !taskContext.isBlank()) {
            contextInstructionBuilder.append(taskContext).append(System.lineSeparator());
        }
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
