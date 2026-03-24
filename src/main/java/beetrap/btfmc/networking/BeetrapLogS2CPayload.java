package beetrap.btfmc.networking;

import beetrap.btfmc.Beetrapfabricmc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record BeetrapLogS2CPayload(String id, String log) implements CustomPayload {

    public static final Identifier BEETRAP_LOG_ID = Identifier.of(Beetrapfabricmc.MOD_ID,
            "beetrap_log");
    public static final Id<BeetrapLogS2CPayload> ID = new Id<>(BEETRAP_LOG_ID);
    public static final PacketCodec<RegistryByteBuf, BeetrapLogS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, BeetrapLogS2CPayload::id, PacketCodecs.STRING,
            BeetrapLogS2CPayload::log, BeetrapLogS2CPayload::new);

    public static final String BEETRAP_LOG_ID_INITIALIZE = "INITIALIZE";
    public static final String BEETRAP_LOG_ID_ACTIVITY_BEGIN_0 = "ACTIVITY_BEGIN_0";
    public static final String BEETRAP_LOG_ID_ACTIVITY_BEGIN_1 = "ACTIVITY_BEGIN_1";
    public static final String BEETRAP_LOG_ID_ACTIVITY_BEGIN_2 = "ACTIVITY_BEGIN_2";
    public static final String BEETRAP_LOG_ID_ACTIVITY_BEGIN_3 = "ACTIVITY_BEGIN_3";
    public static final String BEETRAP_LOG_ID_ACTIVITY_BEGIN_4 = "ACTIVITY_BEGIN_4";
    public static final String BEETRAP_LOG_ID_POLLINATION_INITIATED = "POLLINATION_INITIATED";
    public static final String BEETRAP_LOG_ID_DIVERSITY_SCORE = "DIVERSITY_SCORE";
    public static final String BEETRAP_LOG_ID_TIME_MACHINE_BACKWARD = "TIME_MACHINE_BACKWARD";
    public static final String BEETRAP_LOG_ID_TIME_MACHINE_FORWARD = "TIME_MACHINE_FORWARD";
    public static final String BEETRAP_LOG_ID_POLLINATION_CIRCLE_RADIUS_INCREASED = "POLLINATION_CIRCLE_RADIUS_INCREASED";
    public static final String BEETRAP_LOG_ID_RANKING_METHOD_LEVER_FLICKED = "RANKING_METHOD_LEVER_FLICKED";
    public static final String BEETRAP_LOG_ID_TEXT_SCREEN_SHOWN = "TEXT_SCREEN_SHOWN";
    public static final String BEETRAP_LOG_ID_TEXT_SCREEN_CONFIRMATION_BUTTON_PRESSED = "TEXT_SCREEN_CONFIRMATION_BUTTON_PRESSED";
    public static final String BEETRAP_LOG_ID_MULTIPLE_CHOICE_SCREEN_SHOWN = "MULTIPLE_CHOICE_SCREEN_SHOWN";
    public static final String BEETRAP_LOG_ID_MULTIPLE_CHOICE_SCREEN_ANSWER_SELECTED = "MULTIPLE_CHOICE_SCREEN_ANSWER_SELECTED";
    public static final String BEETRAP_LOG_ID_TEXT_INPUT_SCREEN_SHOWN = "TEXT_INPUT_SCREEN_SHOWN";
    public static final String BEETRAP_LOG_ID_TEXT_INPUT_SCREEN_SUBMITTED = "TEXT_INPUT_SCREEN_SUBMITTED";


    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
