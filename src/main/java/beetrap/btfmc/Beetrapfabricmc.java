package beetrap.btfmc;

import beetrap.btfmc.handler.BeetrapGameHandler;
import beetrap.btfmc.handler.CommandHandler;
import beetrap.btfmc.handler.EntityHandler;
import beetrap.btfmc.handler.LokiHandler;
import beetrap.btfmc.handler.NetworkHandler;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Beetrapfabricmc implements ModInitializer {

    public static boolean PLAYER_DATA_CONSENT = false;
    public static boolean CONSENT_ANSWERED = false;
    public static String USERNAME = null;
    public static String SESSION_CODE = UUID.randomUUID().toString().substring(0, 8);
    public static String TIMESTAMP = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    public static String SESSION_ID = SESSION_CODE + "_" + TIMESTAMP;
    public static final Logger LOG = LogManager.getLogger(Beetrapfabricmc.class);
    public static final String MOD_ID = "beetrap-fabricmc";
    public static final String MOD_REQUIRED_OPENAI_API_KEY = "OPENAI_API_KEY";
    public static final String MOD_REQUIRED_TYPECAST_API_KEY = "TYPECAST_API_KEY";
    public static final String BEECURIOUS_SERVICE_URL = "BEECURIOUS_SERVICE_URL";

    public static String id(String name) {
        return MOD_ID + ":" + name;
    }

    private void loadEnv() {
        Map<String, String> env = System.getenv();
        Map<Object, Object> properties = System.getProperties();

        File f = new File(".env");
        if(f.isFile()) {
            Properties p = new Properties();
            try {
                p.load(new FileReader(f));
                properties.putAll(p);
            } catch(IOException e) {
                LOG.error("Could not load environment file {}", f.getAbsolutePath(), e);
            }
        }

        properties.putAll(env);
        LOG.info("BeeCuriousService URL: {}",
                properties.getOrDefault(BEECURIOUS_SERVICE_URL, "http://127.0.0.1:8765"));
    }



    @Override
    public void onInitialize() {
        this.loadEnv();
        // LokiHandler.pushLokiLog("beetrap server initialized");
        BeetrapGameHandler.registerEvents();
        CommandHandler.registerCommands();
        NetworkHandler.registerCustomPayloads();
        EntityHandler.registerEntities();
    }
}
