package beetrap.btfmc.screen;

import static beetrap.btfmc.BeetrapfabricmcClient.beetrapLog;
import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_TEXT_SCREEN_CONFIRMATION_BUTTON_PRESSED;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

import beetrap.btfmc.Beetrapfabricmc;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class TextScreen extends Screen {

    private static final int TEXT_WIDGET_WIDTH = 800;
    private static final int TEXT_WIDGET_HEIGHT = 12;
    // Used when no explicit size is given (e.g. the legacy 2-arg constructor).
    private static final int DEFAULT_IMAGE_WIDTH = 300;
    private static final int DEFAULT_IMAGE_HEIGHT = 93;
    private final ScreenQueue tss;
    private final Screen parent;
    private final List<TextWidget> linesOfText;
    private ButtonWidget confirmation;
    private final String text;
    private final Identifier imageId;
    private final boolean hasImage;
    // Pixel size to draw the texture at (drawn 1:1, no scaling) — must match the actual PNG.
    private final int imageWidth;
    private final int imageHeight;
    private int imageY;

    public TextScreen(ScreenQueue tss, String s) {
        this(tss, s, "", 0, 0);
    }

    /**
     * @param imagePath texture path relative to {@code textures/} without the {@code .png}
     *                  extension (e.g. {@code "gui/observe_hover_example"}), or empty for no image.
     * @param imageWidth  pixel width to draw the texture at (0 = use the default size).
     * @param imageHeight pixel height to draw the texture at (0 = use the default size).
     */
    public TextScreen(ScreenQueue tss, String s, String imagePath, int imageWidth, int imageHeight) {
        super(Text.of(s));
        this.tss = tss;
        if(this.client == null) {
            this.client = MinecraftClient.getInstance();
        }

        this.parent = this.client.currentScreen;
        this.linesOfText = new ArrayList<>();
        this.text = s;
        this.hasImage = imagePath != null && !imagePath.isBlank();
        this.imageId = this.hasImage
                ? Identifier.of(Beetrapfabricmc.MOD_ID, "textures/" + imagePath + ".png")
                : null;
        this.imageWidth = imageWidth > 0 ? imageWidth : DEFAULT_IMAGE_WIDTH;
        this.imageHeight = imageHeight > 0 ? imageHeight : DEFAULT_IMAGE_HEIGHT;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if(keyCode == GLFW_KEY_ESCAPE) {
            return false;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private Text orderedTextToText(OrderedText ot) {
        StringBuilder sb = new StringBuilder();

        ot.accept((index, style, codePoint) -> {
            sb.append((char)codePoint);
            return true;
        });

        return Text.literal(sb.toString());
    }

    @Override
    protected void init() {
        this.tss.setActive(true);
        List<OrderedText> ot = this.textRenderer.wrapLines(
                StringVisitable.plain(this.title.getString()), TEXT_WIDGET_WIDTH);

        int buttonHeight = 20;
        int gap = 10;
        int margin = 5;
        int textBlockHeight = ot.size() * (TEXT_WIDGET_HEIGHT + 2);
        int imageBlockHeight = this.hasImage ? gap + this.imageHeight : 0;
        int totalHeight = textBlockHeight + imageBlockHeight + gap + buttonHeight;

        int y = (this.height - totalHeight) / 2;
        y = Math.max(y, margin);
        // A too-tall image (or a screen at a high GUI scale) could otherwise push the Confirm
        // button past the bottom edge, stranding the player with no way to dismiss the screen —
        // pull the whole block up so the button always stays reachable, even if that means less
        // breathing room above it.
        if(y + totalHeight > this.height - margin) {
            y = Math.max(margin, this.height - margin - totalHeight);
        }
        for(OrderedText text : ot) {
            TextWidget tw = new TextWidget(TEXT_WIDGET_WIDTH, TEXT_WIDGET_HEIGHT,
                    this.orderedTextToText(text), this.textRenderer);
            this.linesOfText.add(tw);
            tw.setPosition((this.width - tw.getWidth()) / 2, y);
            this.addDrawableChild(tw);
            y += TEXT_WIDGET_HEIGHT + 2;
        }

        if(this.hasImage) {
            y += gap;
            this.imageY = y;
            y += this.imageHeight;
        }

        y += gap;
        this.confirmation = ButtonWidget.builder(Text.of("Confirm"),
                button -> {
                    this.close();
                    beetrapLog(BEETRAP_LOG_ID_TEXT_SCREEN_CONFIRMATION_BUTTON_PRESSED, "");
                }).build();
        this.confirmation.setWidth(200);
        this.confirmation.setPosition((this.width - this.confirmation.getWidth()) / 2, y);
        this.addDrawableChild(this.confirmation);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        if(this.hasImage) {
            context.drawTexture(RenderLayer::getGuiTextured, this.imageId,
                    (this.width - this.imageWidth) / 2, this.imageY,
                    0.0f, 0.0f, this.imageWidth, this.imageHeight, this.imageWidth, this.imageHeight);
        }
    }

    @Override
    public void tick() {

    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
        this.tss.setActive(false);
    }

    @Override
    public String toString() {
        return "TextScreen{" +
                "text='" + text + '\'' +
                '}';
    }
}
