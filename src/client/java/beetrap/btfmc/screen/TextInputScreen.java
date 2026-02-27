package beetrap.btfmc.screen;

import static beetrap.btfmc.BeetrapfabricmcClient.beetrapLog;
import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_TEXT_INPUT_SCREEN_SUBMITTED;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;

import beetrap.btfmc.networking.TextInputResultC2SPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;

public class TextInputScreen extends Screen {

    private static final int TEXT_FIELD_WIDTH = 300;
    private static final int TEXT_FIELD_HEIGHT = 20;

    private final ScreenQueue sq;
    private final Screen parent;
    private final String screenId;
    private final String prompt;
    private TextWidget promptWidget;
    private TextFieldWidget textField;
    private ButtonWidget submitButton;

    public TextInputScreen(ScreenQueue sq, String screenId, String prompt) {
        super(Text.literal("Text Input: " + prompt));
        this.sq = sq;
        if (this.client == null) {
            this.client = MinecraftClient.getInstance();
        }

        this.parent = this.client.currentScreen;
        this.screenId = screenId;
        this.prompt = prompt;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW_KEY_ESCAPE) {
            return false;
        }

        // Handle Enter key to submit
        if (keyCode == GLFW_KEY_ENTER || keyCode == GLFW_KEY_KP_ENTER) {
            this.submitButton.onPress();
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void init() {
        this.sq.setActive(true);

        // Create the prompt text widget
        this.promptWidget = new TextWidget(Text.literal(this.prompt), this.textRenderer);
        this.promptWidget.setPosition(
                (this.width - this.promptWidget.getWidth()) / 2,
                (int) ((double) (this.height - this.promptWidget.getHeight()) / 2 * 0.35)
        );
        this.addDrawableChild(this.promptWidget);

        // Create the text field
        this.textField = new TextFieldWidget(
                this.textRenderer,
                (this.width - TEXT_FIELD_WIDTH) / 2,
                (int) ((double) this.height / 2 * 0.7),
                TEXT_FIELD_WIDTH,
                TEXT_FIELD_HEIGHT,
                Text.literal("Enter text")
        );
        this.textField.setMaxLength(16);
        this.textField.setEditable(true);
        this.textField.setFocused(true);
        this.textField.active = true;
        this.addDrawableChild(this.textField);
        this.setInitialFocus(this.textField);

        // Create the submit button
        this.submitButton = ButtonWidget.builder(Text.of("Submit"),
                button -> {
                    String enteredText = this.textField.getText();
                    ClientPlayNetworking.send(
                            new TextInputResultC2SPayload(this.screenId, enteredText));
                    beetrapLog(BEETRAP_LOG_ID_TEXT_INPUT_SCREEN_SUBMITTED,
                            "Screen id: " + this.screenId + ", input: " + enteredText);
                    this.close();
                }).build();
        this.submitButton.setWidth(200);
        this.submitButton.setPosition(
                (this.width - this.submitButton.getWidth()) / 2,
                (int) ((double) this.height / 2 * 0.95)
        );
        this.addDrawableChild(this.submitButton);
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
        this.sq.setActive(false);
    }

    @Override
    public String toString() {
        return "TextInputScreen{" +
                "screenId='" + screenId + '\'' +
                ", prompt='" + prompt + '\'' +
                '}';
    }
}
