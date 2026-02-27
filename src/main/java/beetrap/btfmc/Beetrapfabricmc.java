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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Beetrapfabricmc implements ModInitializer {

    public static boolean PLAYER_DATA_CONSENT = false;
    public static String USERNAME = null;
    public static String SESSION_CODE = UUID.randomUUID().toString().substring(0, 8);
    public static String TIMESTAMP = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    public static String SESSION_ID = SESSION_CODE + "_" + TIMESTAMP;
    public static final Logger LOG = LogManager.getLogger(Beetrapfabricmc.class);
    public static final String MOD_ID = "beetrap-fabricmc";
    public static final String MOD_REQUIRED_OPENAI_API_KEY = "OPENAI_API_KEY";
    public static final String MOD_REQUIRED_OPENAI_BASE_URL = "OPENAI_BASE_URL";
    public static final String MOD_REQUIRED_OPENAI_ORG_ID = "OPENAI_ORG_ID";
    public static final String MOD_REQUIRED_OPENAI_PROJECT_ID = "OPENAI_PROJECT_ID";
    public static final String MOD_REQUIRED_TYPECAST_API_KEY = "TYPECAST_API_KEY";
    public static final String MOD_REQUIRED_LOKI_PASSWORD = "LOKI_PASSWORD";
    public static final String LOKI_BASE_URL = "https://loki-beetrap.interplaylab.io";
    public static final String LOKI_USERNAME = "beetrap";
    private static final HttpClient LOKI_HTTP_CLIENT = HttpClient.newHttpClient();

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
        b = b && properties.containsKey(MOD_REQUIRED_LOKI_PASSWORD);

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

        private static String escapeJson(String value) {
                return value
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                                .replace("\t", "\\t");
        }

        private void pushLokiLog(String message) {
            String password = System.getProperty(MOD_REQUIRED_LOKI_PASSWORD);
                if(password == null || password.isBlank()) {
                        LOG.warn("{} is not set; skipping Loki push", MOD_REQUIRED_LOKI_PASSWORD);
                        return;
                }

                long timestampNs = Instant.now().toEpochMilli() * 1_000_000L;
                String userLabel = USERNAME != null && !USERNAME.isBlank() ? USERNAME : "unknown";
                String sessionLabel = SESSION_ID != null && !SESSION_ID.isBlank() ? SESSION_ID : "unknown";
                String escapedMessage = escapeJson(message);

                String jsonPayload = """
                {
                    "streams": [
                        {
                            "stream": {
                                "app": "beetrap",
                                "source": "fabricmc",
                                "user": "%s",
                                "session_id": "%s"
                            },
                            "values": [
                                [ "%d", "%s" ]
                            ]
                        }
                    ]
                }
                """.formatted(escapeJson(userLabel), escapeJson(sessionLabel), timestampNs,
                                escapedMessage);

                String authHeader = Base64.getEncoder().encodeToString(
                                (LOKI_USERNAME + ":" + password).getBytes(StandardCharsets.UTF_8));

                HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(LOKI_BASE_URL + "/loki/api/v1/push"))
                                .header("Authorization", "Basic " + authHeader)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                                .build();

                try {
                        HttpResponse<String> response = LOKI_HTTP_CLIENT.send(
                                        request, HttpResponse.BodyHandlers.ofString());
                        if(response.statusCode() < 200 || response.statusCode() >= 300) {
                                LOG.warn("Loki push failed: status={}, body={}", response.statusCode(),
                                                response.body());
                        } else {
                                LOG.info("Loki push success: status={}", response.statusCode());
                        }
                } catch(IOException | InterruptedException e) {
                        if(e instanceof InterruptedException) {
                                Thread.currentThread().interrupt();
                        }
                        LOG.error("Failed to push log to Loki", e);
                }
        }

    @Override
    public void onInitialize() {
        this.loadEnv();
        this.pushLokiLog("beetrap server initialized");
        OpenAiUtil.load();
        BeetrapGameHandler.registerEvents();
        CommandHandler.registerCommands();
        NetworkHandler.registerCustomPayloads();
        EntityHandler.registerEntities();
    }
}
