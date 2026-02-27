package beetrap.btfmc;

import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_INITIALIZE;

import beetrap.btfmc.handler.EntityHandler;
import beetrap.btfmc.handler.LokiHandler;
import beetrap.btfmc.networking.BeetrapLogS2CPayload;
import beetrap.btfmc.networking.BeginSubActivityS2CPayload;
import beetrap.btfmc.networking.EntityPositionUpdateS2CPayload;
import beetrap.btfmc.networking.ShowMultipleChoiceScreenS2CPayload;
import beetrap.btfmc.networking.ShowTextInputScreenS2CPayload;
import beetrap.btfmc.networking.ShowTextScreenS2CPayload;
import beetrap.btfmc.render.entity.FlowerEntityRenderer;
import beetrap.btfmc.render.entity.model.BeetrapEntityModelLayers;
import beetrap.btfmc.util.TextUtil;
import java.io.File;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.Context;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

public class BeetrapfabricmcClient implements ClientModInitializer {

    public static final String MOD_ID = "beetrap-fabricmc";
    public static final String MOD_DATE_TIME_PATTERN = "uuuu-MM-dd-HH-mm-ss-nnnnnnnnn";
    public static final DateTimeFormatter MOD_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(
            MOD_DATE_TIME_PATTERN);
    public static final File MOD_PATH = new File("beetrap");
    public static final File MOD_PATH_LOG = new File(MOD_PATH, "logs");
    private static Logger LOG;
    private BeetrapGameClient bg;

    public static void beetrapLog(String id, String log) {
        if(Beetrapfabricmc.PLAYER_DATA_CONSENT && Beetrapfabricmc.USERNAME != null) {
             LOG.info("{}{{}}{}", Beetrapfabricmc.SESSION_ID, id, log);
             LokiHandler.pushLokiLog(log);
        }
    }

    private void onEntityPositionUpdate(EntityPositionUpdateS2CPayload payload, Context context) {
        this.bg.onEntityPositionUpdate(payload.entityId(), payload.posX(), payload.posY(),
                payload.posZ());
    }

    private void onShowMultipleChoiceScreenReceived(
            ShowMultipleChoiceScreenS2CPayload showMultipleChoiceScreenS2CPayload,
            Context context) {
        this.bg.showMultipleChoiceScreen(showMultipleChoiceScreenS2CPayload.questionId(),
                showMultipleChoiceScreenS2CPayload.question(),
                showMultipleChoiceScreenS2CPayload.choices());
    }

    private void initializeLogger() {
        LoggerContext ctx = (LoggerContext)LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();

        String fileName = new File(MOD_PATH_LOG, MOD_ID + "-" + ZonedDateTime.now(
                ZoneId.systemDefault()).format(MOD_DATE_TIME_FORMATTER) + ".log").getAbsolutePath();
        PatternLayout layout = PatternLayout.newBuilder()
                .withPattern("[%d{ABSOLUTE}] [%t/%level]: %msg%n")
                .build();

        FileAppender appender = FileAppender.newBuilder()
                .setName(MOD_ID + "-file-appender")
                .withFileName(fileName)
                .setLayout(layout)
                .setIgnoreExceptions(false)
                .build();
        appender.start();
        config.addAppender(appender);

        AppenderRef ref = AppenderRef.createAppenderRef(MOD_ID + "-file-appender", null, null);
        LoggerConfig loggerConfig = LoggerConfig.createLogger(
                false,
                org.apache.logging.log4j.Level.ALL,
                MOD_ID,
                "true",
                new AppenderRef[]{ref},
                null,
                config,
                null
        );
        loggerConfig.addAppender(appender, null, null);
        config.addLogger(MOD_ID, loggerConfig);
        ctx.updateLoggers();

        LOG = LogManager.getLogger(MOD_ID);

        beetrapLog(BEETRAP_LOG_ID_INITIALIZE, "Imagine!");
    }

    private void beetrapLog(BeetrapLogS2CPayload beetrapLogS2CPayload, Context context) {
        beetrapLog(beetrapLogS2CPayload.id(), beetrapLogS2CPayload.log());
    }

    @Override
    public void onInitializeClient() {
        this.initializeLogger();
        this.bg = new BeetrapGameClient();
        // This entrypoint is suitable for setting up client-specific logic, such as rendering.
        ClientTickEvents.START_WORLD_TICK.register(bg::onStartWorldTick);
        UseItemCallback.EVENT.register(bg::onPlayerUseItem);
        ClientPlayNetworking.registerGlobalReceiver(EntityPositionUpdateS2CPayload.ID,
                this::onEntityPositionUpdate);
        ClientPlayNetworking.registerGlobalReceiver(ShowTextScreenS2CPayload.ID,
                this::onShowTextScreenReceived);
        ClientPlayNetworking.registerGlobalReceiver(ShowMultipleChoiceScreenS2CPayload.ID,
                this::onShowMultipleChoiceScreenReceived);
        ClientPlayNetworking.registerGlobalReceiver(ShowTextInputScreenS2CPayload.ID,
                this::onShowTextInputScreenReceived);
        ClientPlayNetworking.registerGlobalReceiver(BeginSubActivityS2CPayload.ID,
                this::beginSubActivity);
        ClientPlayNetworking.registerGlobalReceiver(BeetrapLogS2CPayload.ID, this::beetrapLog);

        EntityRendererRegistry.register(EntityHandler.FLOWER, FlowerEntityRenderer::new);
        BeetrapEntityModelLayers.registerModelLayers();

        // Register HUD rendering for the guide text
        HudRenderCallback.EVENT.register(this::renderGuideText);
    }


    private void renderGuideText(net.minecraft.client.gui.DrawContext drawContext, net.minecraft.client.render.RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.world != null) {
            String guideText = "use RIGHT click to use an item. scroll (or use numbers) to change item from inventory";
            List<String> wrappedLines = TextUtil.wrapText(guideText, 32);

            int x = 10; // Top left corner with some padding
            int y = 25;
            int color = 0xFFFFFF; // White color
            int lineHeight = 10; // Height between lines

            // Scale the text to make it smaller
            drawContext.getMatrices().push();
            drawContext.getMatrices().scale(0.85f, 0.85f, 1.0f);

            // Adjust coordinates for the scaled text
            int scaledX = (int)(x / 0.85f);
            int scaledY = (int)(y / 0.85f);
            int scaledLineHeight = (int)(lineHeight / 0.85f);

            // Render each line
            for (int i = 0; i < wrappedLines.size(); i++) {
                drawContext.drawText(client.textRenderer, wrappedLines.get(i), scaledX, scaledY + (i * scaledLineHeight), color, true);
            }

            drawContext.getMatrices().pop();
        }
    }

    private void beginSubActivity(BeginSubActivityS2CPayload beginSubActivityS2CPayload,
            Context context) {
        this.bg.beginSubActivity(beginSubActivityS2CPayload.subActivityId());
    }

    private void onShowTextScreenReceived(ShowTextScreenS2CPayload showTextScreenS2CPacket,
            Context context) {
        this.bg.showTextScreen(showTextScreenS2CPacket.text());
    }

    private void onShowTextInputScreenReceived(ShowTextInputScreenS2CPayload payload,
            Context context) {
        this.bg.showTextInputScreen(payload.screenId(), payload.prompt());
    }
}
