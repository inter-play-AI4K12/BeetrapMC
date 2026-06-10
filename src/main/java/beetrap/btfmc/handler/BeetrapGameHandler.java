package beetrap.btfmc.handler;

import beetrap.btfmc.BeetrapGame;
import beetrap.btfmc.Beetrapfabricmc;
import beetrap.btfmc.networking.MultipleChoiceSelectionResultC2SPayload;
import beetrap.btfmc.networking.PlayerPollinateC2SPayload;
import beetrap.btfmc.networking.PlayerTargetNewEntityC2SPayload;
import beetrap.btfmc.networking.PlayerTimeTravelRequestC2SPayload;
import beetrap.btfmc.networking.PollinationCircleRadiusIncreaseRequestC2SPayload;
import beetrap.btfmc.networking.TextInputResultC2SPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.Context;
import net.minecraft.network.message.MessageType.Parameters;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameMode;
import org.joml.Vector3i;

public final class BeetrapGameHandler {

    private static final int DEFAULT_AI_LEVEL = 3;
    private static final int AUTO_START_DELAY_TICKS = 2;
    private static BeetrapGame game;
    private static MinecraftServer pendingAutoStartServer;
    private static int pendingAutoStartTicks;

    private BeetrapGameHandler() {
        throw new AssertionError();
    }

    public static boolean hasGame() {
        return game != null;
    }

    public static void createGame(MinecraftServer server, int aiLevel) {
        if(hasGame()) {
            return;
        }

        Beetrapfabricmc.beginGameSession();
        game = new BeetrapGame(server, new Vector3i(-10, 0, -10), new Vector3i(10, 0, 10), aiLevel);
    }

    public static void destroyGame() {
        if(!hasGame()) {
            return;
        }

        game.dispose();
        game = null;
    }

    public static void onPlayerTargetNewEntity(
            PlayerTargetNewEntityC2SPayload payload, Context context) {
        if(!hasGame()) {
            return;
        }

        game.onPlayerTargetNewEntity(context.player(), payload.exists(), payload.entityId());
    }

    public static void onPlayerPollinate(PlayerPollinateC2SPayload payload,
            Context context) {
        if(!hasGame()) {
            return;
        }

        game.onPlayerPollinate(context.player(), payload.exists(), payload.entityId());
    }

    public static void onPlayerRequestTimeTravel(PlayerTimeTravelRequestC2SPayload payload,
            Context context) {
        if(!hasGame()) {
            return;
        }

        game.onPlayerRequestTimeTravel(context.player(), payload.n(), payload.operation());
    }

    public static void onPollinationCircleRadiusIncreaseRequested(
            PollinationCircleRadiusIncreaseRequestC2SPayload payload, Context context) {
        if(!hasGame()) {
            return;
        }

        game.onPollinationCircleRadiusIncreaseRequested(payload.a());
    }

    public static void onWorldTick(ServerWorld world) {
        if(pendingAutoStartServer != null && world == pendingAutoStartServer.getOverworld()) {
            if(--pendingAutoStartTicks <= 0) {
                if(!hasGame()) {
                    createGame(pendingAutoStartServer, DEFAULT_AI_LEVEL);
                }
                pendingAutoStartServer = null;
                pendingAutoStartTicks = 0;
            }
        }

        if(!hasGame()) {
            return;
        }

        game.onWorldTick();
    }

    public static void onChatMessageReceived(SignedMessage signedMessage,
            ServerPlayerEntity serverPlayerEntity, Parameters parameters) {
        if(!hasGame()) {
            return;
        }

        game.onChatMessageMessage(signedMessage, serverPlayerEntity, parameters);
    }

    public static void onPlayerJoin(ServerPlayNetworkHandler serverPlayNetworkHandler,
            PacketSender packetSender, MinecraftServer minecraftServer) {
        ServerPlayerEntity player = serverPlayNetworkHandler.player;

        if(hasGame() && game.getServer() != minecraftServer) {
            destroyGame();
        }

        player.changeGameMode(GameMode.ADVENTURE);
        player.getAbilities().allowFlying = true;
        player.sendAbilitiesUpdate();

        if(!hasGame()) {
            pendingAutoStartServer = minecraftServer;
            pendingAutoStartTicks = AUTO_START_DELAY_TICKS;
        }
    }

    public static void onPlayerDisconnect(ServerPlayNetworkHandler serverPlayNetworkHandler,
            MinecraftServer minecraftServer) {
        destroyGame();
        pendingAutoStartServer = null;
        pendingAutoStartTicks = 0;
        Beetrapfabricmc.CONSENT_ANSWERED = false;
        Beetrapfabricmc.PLAYER_DATA_CONSENT = false;
        Beetrapfabricmc.PARTICIPANT_ID = null;
    }

    public static void registerEvents() {
        ServerTickEvents.START_WORLD_TICK.register(BeetrapGameHandler::onWorldTick);
        ServerMessageEvents.CHAT_MESSAGE.register(BeetrapGameHandler::onChatMessageReceived);
        ServerPlayConnectionEvents.JOIN.register(BeetrapGameHandler::onPlayerJoin);
        ServerPlayConnectionEvents.DISCONNECT.register(BeetrapGameHandler::onPlayerDisconnect);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            destroyGame();
            pendingAutoStartServer = null;
            pendingAutoStartTicks = 0;
        });
    }

    public static void onMultipleChoiceSelectionResultReceived(
            MultipleChoiceSelectionResultC2SPayload multipleChoiceSelectionResultC2SPayload,
            Context context) {
        if(!hasGame()) {
            return;
        }

        game.onMultipleChoiceSelectionResultReceived(
                multipleChoiceSelectionResultC2SPayload.questionId(),
                multipleChoiceSelectionResultC2SPayload.option());
    }

    public static void onTextInputResultReceived(
            TextInputResultC2SPayload textInputResultC2SPayload,
            Context context) {
        if(!hasGame()) {
            return;
        }

        game.onTextInputResultReceived(
                textInputResultC2SPayload.screenId(),
                textInputResultC2SPayload.textInput());
    }

    public static BeetrapGame getGame() {
        return game;
    }
}
