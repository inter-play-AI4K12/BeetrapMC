package beetrap.btfmc.handler;

import beetrap.btfmc.BeetrapGame;
import beetrap.btfmc.networking.MultipleChoiceSelectionResultC2SPayload;
import beetrap.btfmc.networking.PlayerPollinateC2SPayload;
import beetrap.btfmc.networking.PlayerTargetNewEntityC2SPayload;
import beetrap.btfmc.networking.PlayerTimeTravelRequestC2SPayload;
import beetrap.btfmc.networking.PollinationCircleRadiusIncreaseRequestC2SPayload;
import beetrap.btfmc.networking.TextInputResultC2SPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.Context;
import net.minecraft.network.message.MessageType.Parameters;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.joml.Vector3i;

public final class BeetrapGameHandler {

    private static BeetrapGame game;

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

    public static void registerEvents() {
        ServerTickEvents.START_WORLD_TICK.register(BeetrapGameHandler::onWorldTick);
        ServerMessageEvents.CHAT_MESSAGE.register(BeetrapGameHandler::onChatMessageReceived);
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
