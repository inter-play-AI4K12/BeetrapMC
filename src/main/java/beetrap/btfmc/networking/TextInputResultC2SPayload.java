package beetrap.btfmc.networking;

import beetrap.btfmc.Beetrapfabricmc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record TextInputResultC2SPayload(String screenId, String textInput) implements
        CustomPayload {

    public static final Identifier TEXT_INPUT_RESULT_ID = Identifier.of(
            Beetrapfabricmc.MOD_ID, "text_input_result");
    public static final Id<TextInputResultC2SPayload> ID = new Id<>(
            TEXT_INPUT_RESULT_ID);
    public static final PacketCodec<RegistryByteBuf, TextInputResultC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, TextInputResultC2SPayload::screenId,
            PacketCodecs.STRING, TextInputResultC2SPayload::textInput,
            TextInputResultC2SPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
