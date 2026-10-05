package com.zzp.agent.graph;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 默认意图判断实现：让 LLM 判断提问属于 CHAT（知识问答）还是 TOOLS（需要调工具）。
 * 主流做法是交给模型自己判断，不靠硬编码关键词。
 */
@Service
public class LlmAgentIntentResolver implements AgentIntentResolver {

    private static final String SYSTEM_PROMPT = """
            你是一个意图分类器。根据用户的提问判断它属于哪一类，只输出下面两个词之一，不要输出其他任何内容：
              CHAT   —— 普通知识问答/闲聊/基于资料的事实问答（如"什么是Agent？"、"这篇文章讲了什么"）。
              TOOLS  —— 需要调用工具去查询数据才能回答的问题（如"上季度销售额top5的客户"、"帮我查某份文档里的内容"、"排名/聚合/统计类计算"）。
            规则：如果回答必须先查库/检索数据/做计算才有依据，判为 TOOLS；否则判为 CHAT。
            """;

    private final ChatClient chatClient;

    public LlmAgentIntentResolver(ChatClient.Builder builder) {
        // 手动装 ChatClient：autoconfigure 只提供 ChatClient.Builder bean，
        // 不提供现成 ChatClient bean（源码 ChatClientAutoConfiguration 仅声明 chatClientBuilder @Bean）。
        this.chatClient = builder.build();
    }

    @Override
    public Intent resolve(String query) {
        return resolve(query, null);
    }

    /**
     * 多轮意图判断：把上一轮上下文（历史回答 + 来源）随用户问题一起交给 LLM，
     * 让 follow-up 追问（如"你说的那top5，下季度呢"）能基于上文正确判为 TOOLS，
     * 而不是因为措辞不含难词就降级成 CHAT。
     */
    @Override
    public Intent resolve(String query, String history) {
        String userPrompt = (history == null || history.isBlank())
                ? "用户提问：" + query
                : "上一轮上下文：\n" + history + "\n\n用户新一轮提问：" + query;
        String raw = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();
        String up = raw == null ? "" : raw.trim().toUpperCase();
        // 含 TOOLS / N2SQL / 查 等信号判为工具调用；其余归为普通问答
        if (up.contains("TOOLS") || up.contains("N2SQL")) {
            return Intent.TOOLS;
        }
        return Intent.CHAT;
    }
}