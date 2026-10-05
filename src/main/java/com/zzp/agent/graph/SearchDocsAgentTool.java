package com.zzp.agent.graph;

import com.zzp.agent.common.document.DocHitResponse;
import com.zzp.agent.document.search.QueryDocumentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 默认工具 {@code search_docs}：在资料库做混合召回，返回命中的文档块作为事实源。
 *
 * <p>这已从"召回+LLM 作答"削薄为"纯召回"：多步循环里工具只负责给 agent 提供事实片段，
 * 最终回答由 agent 的 LLM 综合所有工具结果生成。命中片段按 {@code [来源: documentId]} 标注，
 * 便于 agent 在最终回答里引用出处。
 *
 * <p>入参 schema：{@code { "query": 用户查询 } }，由 {@link #Args} record 推导。
 */
@Service
public class SearchDocsAgentTool implements AgentTool {

    /** 工具入参：单一 {@code query} 字段，spring-ai 据此生成 function schema。 */
    public record Args(String query) {
    }

    private final QueryDocumentService queryDocumentService;
    /** 召回命中 topK，可由配置 {@code search.topK} 覆盖，默认 5。 */
    private final int topK;

    public SearchDocsAgentTool(QueryDocumentService queryDocumentService,
                               @Value("${search.topK:5}") int topK) {
        this.queryDocumentService = queryDocumentService;
        this.topK = topK;
    }

    @Override
    public String name() {
        return "search_docs";
    }

    @Override
    public String description() {
        return "在资料库中检索与用户查询相关的文档片段，输入用户查询得到命中文档";
    }

    @Override
    public Class<?> inputType() {
        return Args.class;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        Object raw = args == null ? null : args.get("query");
        String query = raw == null ? "" : String.valueOf(raw);
        List<DocHitResponse> hits = queryDocumentService.getDocHitResponses(query, topK, null);
        if (hits.isEmpty()) {
            return new ToolResult(name(), "（无相关文档命中）", List.of());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            DocHitResponse h = hits.get(i);
            sb.append('[').append(i + 1).append("] 文档 ").append(h.documentId())
                    .append("（score=").append(h.score()).append("）\n")
                    .append(h.text()).append("\n\n");
        }
        return new ToolResult(name(), sb.toString(), hits);
    }
}