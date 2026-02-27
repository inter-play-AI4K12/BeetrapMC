package beetrap.btfmc.handler;

import beetrap.btfmc.networking.BeetrapLogS2CPayload;
import beetrap.btfmc.networking.BeginSubActivityS2CPayload;
import beetrap.btfmc.networking.EndSubActivityC2SPayload;
import beetrap.btfmc.networking.EntityPositionUpdateS2CPayload;
import beetrap.btfmc.networking.MultipleChoiceSelectionResultC2SPayload;
import beetrap.btfmc.networking.PlayerPollinateC2SPayload;
import beetrap.btfmc.networking.PlayerTargetNewEntityC2SPayload;
import beetrap.btfmc.networking.PlayerTimeTravelRequestC2SPayload;
import beetrap.btfmc.networking.PollinationCircleRadiusIncreaseRequestC2SPayload;
import beetrap.btfmc.networking.RestartGameC2SPayload;
import beetrap.btfmc.networking.ShowMultipleChoiceScreenS2CPayload;
import beetrap.btfmc.networking.ShowTextInputScreenS2CPayload;
import beetrap.btfmc.networking.ShowTextScreenS2CPayload;
import beetrap.btfmc.networking.TextInputResultC2SPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class NetworkHandler {

    private NetworkHandler() {
        throw new AssertionError();
    }

    public static void registerCustomPayloads() {
        PayloadTypeRegistry.playS2C()
                .register(EntityPositionUpdateS2CPayload.ID, EntityPositionUpdateS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(
                ShowTextScreenS2CPayload.ID, ShowTextScreenS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ShowMultipleChoiceScreenS2CPayload.ID,
                ShowMultipleChoiceScreenS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ShowTextInputScreenS2CPayload.ID,
                ShowTextInputScreenS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C()
                .register(BeginSubActivityS2CPayload.ID, BeginSubActivityS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(BeetrapLogS2CPayload.ID, BeetrapLogS2CPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(PlayerTargetNewEntityC2SPayload.ID,
                PlayerTargetNewEntityC2SPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PlayerTargetNewEntityC2SPayload.ID,
                BeetrapGameHandler::onPlayerTargetNewEntity);

        PayloadTypeRegistry.playC2S()
                .register(PlayerPollinateC2SPayload.ID, PlayerPollinateC2SPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PlayerPollinateC2SPayload.ID,
                BeetrapGameHandler::onPlayerPollinate);

        PayloadTypeRegistry.playC2S().register(PlayerTimeTravelRequestC2SPayload.ID,
                PlayerTimeTravelRequestC2SPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PlayerTimeTravelRequestC2SPayload.ID,
                BeetrapGameHandler::onPlayerRequestTimeTravel);

        PayloadTypeRegistry.playC2S().register(PollinationCircleRadiusIncreaseRequestC2SPayload.ID,
                PollinationCircleRadiusIncreaseRequestC2SPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(
                PollinationCircleRadiusIncreaseRequestC2SPayload.ID,
                BeetrapGameHandler::onPollinationCircleRadiusIncreaseRequested);

        PayloadTypeRegistry.playC2S().register(MultipleChoiceSelectionResultC2SPayload.ID,
                MultipleChoiceSelectionResultC2SPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MultipleChoiceSelectionResultC2SPayload.ID,
                BeetrapGameHandler::onMultipleChoiceSelectionResultReceived);

        PayloadTypeRegistry.playC2S().register(TextInputResultC2SPayload.ID,
                TextInputResultC2SPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TextInputResultC2SPayload.ID,
                BeetrapGameHandler::onTextInputResultReceived);

        PayloadTypeRegistry.playC2S()
                .register(EndSubActivityC2SPayload.ID, EndSubActivityC2SPayload.CODEC);

        PayloadTypeRegistry.playC2S()
                .register(RestartGameC2SPayload.ID, RestartGameC2SPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RestartGameC2SPayload.ID,
                (payload, context) -> {
                    BeetrapGameHandler.destroyGame();
                    BeetrapGameHandler.createGame(context.server(), 3);
                });
    }
}
