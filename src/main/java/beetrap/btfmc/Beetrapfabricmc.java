package beetrap.btfmc;

import beetrap.btfmc.handler.BeetrapGameHandler;
import beetrap.btfmc.handler.CommandHandler;
import beetrap.btfmc.handler.EntityHandler;
import beetrap.btfmc.handler.NetworkHandler;
import beetrap.btfmc.openai.OpenAiUtil;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Properties;
import net.fabricmc.api.ModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.time.Instant;
import java.util.UUID;
import java.time.format.DateTimeFormatter;

public class Beetrapfabricmc implements ModInitializer {

    public static String sessionCode = UUID.randomUUID().toString().substring(0, 8);
    public static String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    public static String sessionId = sessionCode + "_" + timestamp;
    public static final Logger LOG = LogManager.getLogger(Beetrapfabricmc.class);
    public static final String MOD_ID = "beetrap-fabricmc";
    public static final String MOD_REQUIRED_OPENAI_API_KEY = "OPENAI_API_KEY";
    public static final String MOD_REQUIRED_OPENAI_BASE_URL = "OPENAI_BASE_URL";
    public static final String MOD_REQUIRED_OPENAI_ORG_ID = "OPENAI_ORG_ID";
    public static final String MOD_REQUIRED_OPENAI_PROJECT_ID = "OPENAI_PROJECT_ID";
    public static final String MOD_REQUIRED_TYPECAST_API_KEY = "TYPECAST_API_KEY";

    public static String id(String name) {
        return MOD_ID + ":" + name;
    }

    private void loadEnv() {
        Map<String, String> env = System.getenv();
        Map<Object, Object> properties = System.getProperties();
        properties.putAll(env);

        boolean b = true;

        b = b && properties.containsKey(MOD_REQUIRED_OPENAI_API_KEY);
        b = b && properties.containsKey(MOD_REQUIRED_OPENAI_BASE_URL);
        b = b && properties.containsKey(MOD_REQUIRED_OPENAI_ORG_ID);
        b = b && properties.containsKey(MOD_REQUIRED_OPENAI_PROJECT_ID);
        b = b && properties.containsKey(MOD_REQUIRED_TYPECAST_API_KEY);

        if(b) {
            LOG.info("All required environment variables found, good to go.");
            return;
        }

        File f = new File(".env");
        LOG.warn("Not all required environment variables found, attempting to load from {}",
                f.getAbsolutePath());
        Properties p = new Properties();
        try {
            p.load(new FileReader(f));
        } catch(IOException e) {
            throw new UncheckedIOException(e);
        }

        properties.putAll(p);
    }

    @Override
    public void onInitialize() {
        this.loadEnv();
        OpenAiUtil.load();
        BeetrapGameHandler.registerEvents();
        CommandHandler.registerCommands();
        NetworkHandler.registerCustomPayloads();
        EntityHandler.registerEntities();
    }
}
