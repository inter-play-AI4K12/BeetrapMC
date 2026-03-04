package beetrap.btfmc.openai;

import static beetrap.btfmc.Beetrapfabricmc.MOD_REQUIRED_OPENAI_API_KEY;
import static beetrap.btfmc.Beetrapfabricmc.MOD_REQUIRED_OPENAI_BASE_URL;
import static beetrap.btfmc.Beetrapfabricmc.MOD_REQUIRED_OPENAI_ORG_ID;
import static beetrap.btfmc.Beetrapfabricmc.MOD_REQUIRED_OPENAI_PROJECT_ID;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import java.util.concurrent.CompletableFuture;

public final class OpenAiUtil {

    private static final OpenAIClient client;
    private static Response previousResponse;

    static {
        client = OpenAIOkHttpClient.builder()
                .apiKey(System.getProperty(MOD_REQUIRED_OPENAI_API_KEY))
                .baseUrl(System.getProperty(MOD_REQUIRED_OPENAI_BASE_URL))
                .organization(System.getProperty(MOD_REQUIRED_OPENAI_ORG_ID))
                .project(System.getProperty(MOD_REQUIRED_OPENAI_PROJECT_ID))
                .build();
    }

    private OpenAiUtil() {
        throw new AssertionError();
    }

    public static void load() {

    }

    public static void clearHistory() {
        previousResponse = null;
    }

    public static CompletableFuture<Response> getGpt4oLatestResponseAsync(String input) {
        ResponseCreateParams params;

        if(previousResponse != null) {
            params = ResponseCreateParams.builder()
                    .input(input)
                    .model("gpt-5.2")
                    .previousResponseId(previousResponse.id())
                    .build();
        } else {
            params = ResponseCreateParams.builder()
                    .input(input)
                    .model("gpt-5.2")
                    .build();
        }

        CompletableFuture<Response> response = client.async().responses().create(params);
        return response.whenComplete(
                (response1, throwable) -> OpenAiUtil.previousResponse = response1);
    }

    public static OpenAIClient getClient() {
        return client;
    }
}
