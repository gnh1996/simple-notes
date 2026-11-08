package cc.naihao.simplenotes.site;

import com.openai.core.ClientOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

/** Spring AI 2.0 客户端手工装配验证（docs/theme-ai.md §5）。 */
class AiClientBuildTest {

    @Test
    @DisplayName("手工构建 OpenAIClient + OpenAiChatModel 不抛缺 credential")
    void buildsClient() {
        com.openai.core.http.HttpClient httpClient = SpringAiOpenAiHttpClient.builder()
                .timeout(java.time.Duration.ofSeconds(90))
                .build();
        com.openai.core.ClientOptions clientOptions = ClientOptions.builder()
                .baseUrl("https://example.invalid/v1")
                .credential(com.openai.credential.BearerTokenCredential.create("dummy-key-for-construction-test"))
                .timeout(java.time.Duration.ofSeconds(90))
                .httpClient(httpClient)
                .maxRetries(1)
                .build();
        com.openai.client.OpenAIClientAsync asyncClient =
                new com.openai.client.OpenAIClientAsyncImpl(clientOptions);
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiClient(asyncClient.sync())
                .openAiClientAsync(asyncClient)
                .options(OpenAiChatOptions.builder()
                        .model("dummy-model")
                        .temperature(0.7)
                        .build())
                .build();
        org.assertj.core.api.Assertions.assertThat(model).isNotNull();
    }
}
