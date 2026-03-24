package beetrap.btfmc.networking;

import beetrap.btfmc.Beetrapfabricmc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ShowTextInputScreenS2CPayload(String screenId, String prompt) implements
        CustomPayload {

    public static final Identifier SHOW_TEXT_INPUT_SCREEN_ID = Identifier.of(
            Beetrapfabricmc.MOD_ID, "show_text_input_screen");
    public static final Id<ShowTextInputScreenS2CPayload> ID = new Id<>(
            SHOW_TEXT_INPUT_SCREEN_ID);
    public static final PacketCodec<RegistryByteBuf, ShowTextInputScreenS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, ShowTextInputScreenS2CPayload::screenId,
            PacketCodecs.STRING, ShowTextInputScreenS2CPayload::prompt,
            ShowTextInputScreenS2CPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
