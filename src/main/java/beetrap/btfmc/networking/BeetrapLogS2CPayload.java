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

    public static final String BEETRAP_LOG_ID_INITIALIZE = "initialize";
    public static final String BEETRAP_LOG_ID_ACTIVITY_BEGIN_0 = "activity_begin_0";
    public static final String BEETRAP_LOG_ID_ACTIVITY_BEGIN_1 = "activity_begin_1";
    public static final String BEETRAP_LOG_ID_ACTIVITY_BEGIN_2 = "activity_begin_2";
    public static final String BEETRAP_LOG_ID_ACTIVITY_BEGIN_3 = "activity_begin_3";
    public static final String BEETRAP_LOG_ID_ACTIVITY_BEGIN_4 = "activity_begin_4";
    public static final String BEETRAP_LOG_ID_POLLINATION_INITIATED = "pollination_initiated";
    public static final String BEETRAP_LOG_ID_TIME_MACHINE_BACKWARD = "time_machine_backward";
    public static final String BEETRAP_LOG_ID_TIME_MACHINE_FORWARD = "time_machine_forward";
    public static final String BEETRAP_LOG_ID_POLLINATION_CIRCLE_RADIUS_INCREASED = "pollination_circle_radius_increased";
    public static final String BEETRAP_LOG_ID_RANKING_METHOD_LEVER_FLICKED = "ranking_method_lever_flicked";
    public static final String BEETRAP_LOG_ID_TEXT_SCREEN_SHOWN = "text_screen_shown";
    public static final String BEETRAP_LOG_ID_TEXT_SCREEN_CONFIRMATION_BUTTON_PRESSED = "text_screen_confirmation_button_pressed";
    public static final String BEETRAP_LOG_ID_MULTIPLE_CHOICE_SCREEN_SHOWN = "multiple_choice_screen_shown";
    public static final String BEETRAP_LOG_ID_MULTIPLE_CHOICE_SCREEN_ANSWER_SELECTED = "multiple_choice_screen_answer_selected";
    public static final String BEETRAP_LOG_ID_TEXT_INPUT_SCREEN_SHOWN = "text_input_screen_shown";
    public static final String BEETRAP_LOG_ID_TEXT_INPUT_SCREEN_SUBMITTED = "text_input_screen_submitted";


    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
