package com.zzp.agent.graph;

import com.zzp.agent.common.document.DocHitResponse;
import com.zzp.agent.document.search.QueryDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 默认工具实现 {@code search_docs}：混合召回 + LLM 基于资料生成带出处的回答。
 *
 * <p>流程：
 * <ol>
 *   <li>调 {@link QueryDocumentService#getDocHitResponses(String, int, String)} 取 topK 个命中块；</li>
 *   <li>把命中的 {@code text} 拼成【资料】上下文；</li>
 *   <li>让 LLM 只依据这些资料回答，用 {@code [来源: documentId]} 标注出处，资料不足如实说明、不编造。</li>
 * </ol>
 *
 * <p>多轮：{@link #execute(String, String)} 会在【资料】前带上【历史上下文】（上轮回答 + 来源），
 * 让 LLM 能基于"刚才说过什么"继续回答追问。
 *
 * <p>LLM 调用走 autoconfigure 提供的 {@code ChatClient.Builder}（源码 ChatClientAutoConfiguration
 * 只声明 chatClientBuilder @Bean），此处手动 {@code builder.build()}。
 */
@Service
public class SearchDocsAgentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SearchDocsAgentTool.class);

    /** 检索不到任何命中时给用户的兜底回答。 */
    private static final String NO_HIT_ANSWER = "抱歉，资料中没有相关内容。请换一种问法或上传相关文档后再试。";

    private static final String SYSTEM_PROMPT = """
            你是一个基于资料的问答助手。下面【资料】是检索到的文档片段。
            规则：
            1. 只依据【资料】回答用户问题，不要使用资料以外的知识来推测；
            2. 资料不足以回答时，如实说"资料中没有相关内容"，不要编造；
            3. 每条论断用 [来源: documentId] 标注对应的文档来源。
            """;

    private final QueryDocumentService queryDocumentService;
    private final ChatClient chatClient;
    /** 工具召回命中的 topK，可由配置 {@code search.topK} 覆盖，默认 5。 */
    private final int topK;

    public SearchDocsAgentTool(QueryDocumentService queryDocumentService, ChatClient.Builder builder,
                               @Value("${search.topK:5}") int topK) {
        this.queryDocumentService = queryDocumentService;
        this.chatClient = builder.build();
        this.topK = topK;
    }

    @Override
    public AgentToolResult execute(String query) {
        return execute(query, null);
    }

    @Override
    public AgentToolResult execute(String query, String history) {
        List<DocHitResponse> hits = queryDocumentService.getDocHitResponses(query, topK, null);
        if (hits.isEmpty()) {
            log.info("search_docs 无命中，返回兜底回答 query={}", query);
            return new AgentToolResult(NO_HIT_ANSWER, List.of());
        }

        StringBuilder context = new StringBuilder();
        if (history != null && !history.isBlank()) {
            context.append("【历史上下文】\n").append(history).append("\n\n");
        }
        for (int i = 0; i < hits.size(); i++) {
            DocHitResponse h = hits.get(i);
            context.append('[').append(i + 1).append("] 文档 ").append(h.documentId())
                    .append("（score=").append(h.score()).append("）\n")
                    .append(h.text()).append("\n\n");
        }

        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("用户问题：" + query + "\n\n【资料】\n" + context)
                .call()
                .content();

        return new AgentToolResult(answer, hits);
    }
}