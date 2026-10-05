package com.zzp.agent.graph;

/**
 * 工具调用接口。意图判为 TOOLS 时由 {@link AgentGraphService} 的 tools 节点调用。
 *
 * <p>策略可替换：默认实现 {@link SearchDocsAgentTool} 复用 {@link com.zzp.agent.document.search.QueryDocumentService}
 * 做混合召回 + LLM 基于资料生成带出处的回答；测试时可注入假实现验证图的路由与状态写入。
 */
public interface AgentTool {
    /** 单轮调用：只依据当轮 query 执行工具。 */
    AgentToolResult execute(String query);

    /**
     * 多轮调用：额外带上一轮已格式化的历史上下文（上轮回答 + 来源），
     * 随当轮 query 一起给 LLM。默认实现忽略历史退化为 {@link #execute(String)}，
     * 便于测试用假工具时不必关心历史。
     */
    default AgentToolResult execute(String query, String history) {
        return execute(query);
    }
}