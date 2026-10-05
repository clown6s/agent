package com.zzp.agent.graph;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 LLM 意图判断的独立测试。
 *
 * <p>不启动 Spring 上下文、不起 ES/Milvus：直接用 {@link DeepSeekApi} + {@link DeepSeekChatModel}
 * 手搓一个 {@link ChatClient},再构造 {@link LlmAgentIntentResolver}。
 *
 * <p>依赖环境变量(与 application.yaml 一致,复用 .env.example 已留的变量):
 * <pre>
 *   OPENAI_BASE_URL  = https://api.deepseek.com/v1
 *   OPENAI_API_KEY   = &lt;你的 key&gt;
 *   OPENAI_MODEL     = deepseek-chat
 * </pre>
 * 未配置 OPENAI_API_KEY 时用例自动跳过,不报错。
 */
class LlmAgentIntentResolverTest {

    private LlmAgentIntentResolver buildResolver() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        String baseUrl = System.getenv("OPENAI_BASE_URL");
        String model = System.getenv("OPENAI_MODEL");
        // 没 key 就跳过,不联网、不报错
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "未配置 OPENAI_API_KEY,跳过真实 LLM 测试");

        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(baseUrl == null ? "https://api.deepseek.com/v1" : baseUrl)
                .apiKey(apiKey)
                .build();
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(model == null ? "deepseek-chat" : model)
                .build();
        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .defaultOptions(options)
                .build();
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        return new LlmAgentIntentResolver(builder);
    }

    @Test
    void toolsQuery_resolvesToTools() {
        LlmAgentIntentResolver resolver = buildResolver();
        String q = "上季度销售额top5的客户是哪些";
        Intent intent = resolver.resolve(q);
        System.out.println(">> query: " + q + "  →  intent=" + intent);
        assertEquals(Intent.TOOLS, intent, "需要查数据的问题应判为 TOOLS");
    }

    @Test
    void chatQuery_resolvesToChat() {
        LlmAgentIntentResolver resolver = buildResolver();
        String q = "什么是Agent?";
        Intent intent = resolver.resolve(q);
        System.out.println(">> query: " + q + "  →  intent=" + intent);
        assertEquals(Intent.CHAT, intent, "普通知识问答应判为 CHAT");
    }
}
