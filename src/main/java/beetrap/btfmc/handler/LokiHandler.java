package beetrap.btfmc.handler;

import beetrap.btfmc.Beetrapfabricmc;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LokiHandler {

    private static final Logger LOG = LogManager.getLogger(LokiHandler.class);
    public static final String MOD_REQUIRED_LOKI_PASSWORD = "LOKI_PASSWORD";
    public static final String LOKI_BASE_URL = "https://loki-beetrap.interplaylab.io";
    public static final String LOKI_USERNAME = "beetrap";
    private static final HttpClient LOKI_HTTP_CLIENT = HttpClient.newHttpClient();

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public static void pushLokiLog(String message) {
        String password = System.getProperty(MOD_REQUIRED_LOKI_PASSWORD);
        if(password == null || password.isBlank()) {
            LOG.warn("{} is not set; skipping Loki push", MOD_REQUIRED_LOKI_PASSWORD);
            return;
        }

        long timestampNs = Instant.now().toEpochMilli() * 1_000_000L;
        String userLabel = Beetrapfabricmc.USERNAME != null && !Beetrapfabricmc.USERNAME.isBlank() ? Beetrapfabricmc.USERNAME : "unknown";
        String sessionLabel = Beetrapfabricmc.SESSION_ID != null && !Beetrapfabricmc.SESSION_ID.isBlank() ? Beetrapfabricmc.SESSION_ID : "unknown";
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
}
