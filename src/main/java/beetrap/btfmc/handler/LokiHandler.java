package beetrap.btfmc.handler;

import beetrap.btfmc.Beetrapfabricmc;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class LokiHandler {

    private static final Logger LOG = LogManager.getLogger(LokiHandler.class);
    public static final String LOKI_PASSWORD_KEY = "LOKI_PASSWORD";
    public static final String LOKI_URL_KEY = "LOKI_URL";
    public static final String LOKI_USER_KEY = "LOKI_USER";
    private static final String DEFAULT_LOKI_URL = "https://loki-beetrap.interplaylab.io";
    private static final String DEFAULT_LOKI_USER = "beetrap";
    private static final int MAX_QUEUE_SIZE = 1000;
    private static final BlockingQueue<LokiRecord> QUEUE =
            new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
    private static final AtomicBoolean WARNED_MISSING_PASSWORD = new AtomicBoolean();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    static {
        Thread worker = new Thread(LokiHandler::processQueue, "beetrap-loki");
        worker.setDaemon(true);
        worker.start();
    }

    private LokiHandler() {
        throw new AssertionError();
    }

    public static void pushLokiLog(String eventType, String message) {
        if(getSetting(LOKI_PASSWORD_KEY, null) == null) {
            if(WARNED_MISSING_PASSWORD.compareAndSet(false, true)) {
                LOG.warn("{} is not set; skipping Loki pushes", LOKI_PASSWORD_KEY);
            }
            return;
        }

        LokiRecord record = new LokiRecord(
                eventType,
                message,
                Beetrapfabricmc.SESSION_ID,
                Beetrapfabricmc.PARTICIPANT_ID
        );
        if(!QUEUE.offer(record)) {
            LOG.warn("Loki queue is full; dropping event {}", eventType);
        }
    }

    private static void processQueue() {
        while(!Thread.currentThread().isInterrupted()) {
            try {
                sendWithRetry(QUEUE.take());
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void sendWithRetry(LokiRecord record) {
        for(int attempt = 0; attempt < 3; attempt++) {
            try {
                send(record);
                return;
            } catch(IOException e) {
                if(attempt == 2) {
                    LOG.warn("Failed to push Loki event {} after retries", record.eventType(), e);
                    return;
                }
                try {
                    Thread.sleep(250L << attempt);
                } catch(InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void send(LokiRecord record) throws IOException {
        String password = getSetting(LOKI_PASSWORD_KEY, null);
        if(password == null) {
            return;
        }

        Instant timestamp = Instant.now();
        long timestampNs = timestamp.toEpochMilli() * 1_000_000L;
        String participantField = record.participantId() == null
                ? ""
                : ",\"participant_id\":\"" + escapeJson(record.participantId()) + "\"";
        String logRecord = """
                {"schema_version":1,"timestamp":"%s","app":"beetrap","source":"fabricmc","event_type":"%s","game_session_id":"%s"%s,"message":"%s"}
                """.formatted(
                timestamp,
                escapeJson(record.eventType()),
                escapeJson(record.gameSessionId()),
                participantField,
                escapeJson(record.message())
        ).strip();
        String jsonPayload = """
                {"streams":[{"stream":{"app":"beetrap","source":"fabricmc"},"values":[["%d","%s"]]}]}
                """.formatted(timestampNs, escapeJson(logRecord)).strip();
        String username = getSetting(LOKI_USER_KEY, DEFAULT_LOKI_USER);
        String authHeader = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getSetting(LOKI_URL_KEY, DEFAULT_LOKI_URL)
                        + "/loki/api/v1/push"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + authHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if(response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Loki returned status=" + response.statusCode()
                        + ", body=" + response.body());
            }
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while pushing a Loki event", e);
        }
    }

    private static String getSetting(String key, String defaultValue) {
        String value = System.getProperty(key);
        if(value == null || value.isBlank()) {
            value = System.getenv(key);
        }
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private record LokiRecord(
            String eventType,
            String message,
            String gameSessionId,
            String participantId
    ) {
    }
}
