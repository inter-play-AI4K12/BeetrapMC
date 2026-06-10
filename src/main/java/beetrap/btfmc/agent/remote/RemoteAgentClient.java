package beetrap.btfmc.agent.remote;

import beetrap.btfmc.agent.AgentCommand;
import beetrap.btfmc.agent.AgentCommandDeserializer;
import beetrap.btfmc.agent.GptResponse;
import beetrap.btfmc.agent.GptResponseDeserializer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for BeeCuriousService session and event endpoints.
 */
public class RemoteAgentClient {
    private static final ObjectMapper OM;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    static {
        OM = new ObjectMapper();
        SimpleModule sm = new SimpleModule();
        sm.addDeserializer(AgentCommand.class, new AgentCommandDeserializer());
        sm.addDeserializer(GptResponse.class, new GptResponseDeserializer());
        OM.registerModule(sm);
    }

    private final HttpClient httpClient;
    private final URI serviceBaseUri;

    /**
     * Creates a client for the given BeeCuriousService base URL.
     */
    public RemoteAgentClient(String serviceBaseUrl) {
        this.serviceBaseUri = URI.create(serviceBaseUrl.endsWith("/")
                ? serviceBaseUrl.substring(0, serviceBaseUrl.length() - 1)
                : serviceBaseUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * Creates an agent session without blocking the Minecraft server thread.
     */
    public CompletableFuture<RemoteAgentSession> createSession(String gameSessionId,
            boolean loggingConsent, String participantId) {
        String body;
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("game_session_id", gameSessionId);
            payload.put("logging_consent", loggingConsent);
            if(participantId != null && !participantId.isBlank()) {
                payload.put("participant_id", participantId);
            }
            body = OM.writeValueAsString(payload);
        } catch(JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }

        HttpRequest request = HttpRequest.newBuilder(this.serviceBaseUri.resolve("/v1/sessions"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        ensureSuccess(response);
                        Map<String, Object> payload = OM.readValue(response.body(), MAP_TYPE);
                        Object sessionId = payload.get("agent_session_id");
                        if(!(sessionId instanceof String s) || s.isBlank()) {
                            throw new IOException(
                                    "BeeCuriousService did not return an agent_session_id");
                        }
                        return new RemoteAgentSession(
                                s,
                                requiredString(payload, "agent"),
                                requiredString(payload, "version"),
                                requiredString(payload, "agent_name")
                        );
                    } catch(IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    /**
     * Sends an event and returns commands that Fabric can execute.
     */
    public CompletableFuture<AgentCommand[]> sendEvent(String agentSessionId, String eventJson,
            String context) {
        String body;
        try {
            Map<String, Object> event = OM.readValue(eventJson, MAP_TYPE);
            Map<String, Object> payload = new HashMap<>();
            payload.put("event", event);
            payload.put("context", context);
            body = OM.writeValueAsString(payload);
        } catch(JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }

        HttpRequest request = HttpRequest.newBuilder(
                        this.serviceBaseUri.resolve(
                                "/v1/sessions/" + agentSessionId + "/events"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        ensureSuccess(response);
                        return OM.readValue(response.body(), GptResponse.class)
                                .getAgentCommands();
                    } catch(IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    /**
     * Requests deletion of an agent session.
     */
    public void closeSession(String agentSessionId) {
        HttpRequest request = HttpRequest.newBuilder(
                        this.serviceBaseUri.resolve("/v1/sessions/" + agentSessionId))
                .timeout(Duration.ofSeconds(2))
                .DELETE()
                .build();
        this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
    }

    private static void ensureSuccess(HttpResponse<String> response) throws IOException {
        if(response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RemoteAgentHttpException(response.statusCode(), response.body());
        }
    }

    private static String requiredString(Map<String, Object> payload, String key)
            throws IOException {
        Object value = payload.get(key);
        if(value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        throw new IOException("BeeCuriousService did not return " + key);
    }

    /**
     * Resolved Python-side agent profile for a remote session.
     */
    public record RemoteAgentSession(String agentSessionId, String agentId, String version,
                                     String agentName) {
        public String profileId() {
            return this.agentId + "@" + this.version;
        }
    }

    /**
     * Represents a non-success response from BeeCuriousService.
     */
    public static class RemoteAgentHttpException extends IOException {
        private final int statusCode;

        public RemoteAgentHttpException(int statusCode, String responseBody) {
            super("BeeCuriousService returned " + statusCode + ": " + responseBody);
            this.statusCode = statusCode;
        }

        /**
         * Returns the HTTP status code from BeeCuriousService.
         */
        public int getStatusCode() {
            return this.statusCode;
        }
    }
}
