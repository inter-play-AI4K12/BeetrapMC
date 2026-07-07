package beetrap.btfmc.networking;

import beetrap.btfmc.Beetrapfabricmc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * @param imagePath   Optional texture to show above the text, relative to {@code textures/} and
 *                    without the {@code .png} extension (e.g. {@code "gui/observe_hover_example"}).
 *                    Empty string means no image.
 * @param imageWidth  Pixel width to draw the texture at (drawn 1:1 — must match the actual PNG).
 * @param imageHeight Pixel height to draw the texture at (drawn 1:1 — must match the actual PNG).
 */
public record ShowTextScreenS2CPayload(String text, String imagePath, int imageWidth, int imageHeight)
        implements CustomPayload {

    public static final Identifier SHOW_TEXT_SCREEN_ID = Identifier.of(Beetrapfabricmc.MOD_ID,
            "show_text_screen");
    public static final Id<ShowTextScreenS2CPayload> ID = new Id<>(SHOW_TEXT_SCREEN_ID);
    public static final PacketCodec<RegistryByteBuf, ShowTextScreenS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, ShowTextScreenS2CPayload::text,
            PacketCodecs.STRING, ShowTextScreenS2CPayload::imagePath,
            PacketCodecs.VAR_INT, ShowTextScreenS2CPayload::imageWidth,
            PacketCodecs.VAR_INT, ShowTextScreenS2CPayload::imageHeight,
            ShowTextScreenS2CPayload::new);

    public ShowTextScreenS2CPayload(String text) {
        this(text, "", 0, 0);
    }

    public static String lineWrap(String s, int n) {
        StringBuilder sb = new StringBuilder();
        String[] t = s.split("\\s");
        int l = 0;

        for(int i = 0; i < t.length; ++i) {
            String u = t[i];

            if(i != 0) {
                if(l + u.length() + 1 > n) {
                    sb.append("\n");
                    l = 0;
                }

                sb.append(" ");
            }

            sb.append(u);
            l = l + u.length();
        }

        return sb.toString();
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
