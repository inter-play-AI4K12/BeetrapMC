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
    public CompletableFuture<String> createSession() {
        HttpRequest request = HttpRequest.newBuilder(this.serviceBaseUri.resolve("/v1/sessions"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        return this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        ensureSuccess(response);
                        Map<String, Object> payload = OM.readValue(response.body(), MAP_TYPE);
                        Object sessionId = payload.get("session_id");
                        if(!(sessionId instanceof String s) || s.isBlank()) {
                            throw new IOException(
                                    "BeeCuriousService did not return a session_id");
                        }
                        return s;
                    } catch(IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    /**
     * Sends an event and returns commands that Fabric can execute.
     */
    public CompletableFuture<AgentCommand[]> sendEvent(String sessionId, String eventJson,
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
                        this.serviceBaseUri.resolve("/v1/sessions/" + sessionId + "/events"))
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
    public void closeSession(String sessionId) {
        HttpRequest request = HttpRequest.newBuilder(
                        this.serviceBaseUri.resolve("/v1/sessions/" + sessionId))
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
