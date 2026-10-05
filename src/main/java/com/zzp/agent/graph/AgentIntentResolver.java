package com.zzp.agent.graph;

/**
 * 意图判断接口。策略可替换：默认实现 {@link LlmAgentIntentResolver} 用 LLM 判断意图，
 * 测试/无模型时可注入假实现验证图的路由。
 */
public interface AgentIntentResolver {
    /** 单轮意图判断：只看当前 query。 */
    Intent resolve(String query);

    /**
     * 多轮意图判断：额外带上上一轮上下文（历史回答 + 来源），
     * 让 follow-up 追问（如"那下季度呢"）能基于上文正确延续 TOOLS。
     * 默认实现忽略历史退化为 {@link #resolve(String)}，便于测试用假实现。
     */
    default Intent resolve(String query, String history) {
        return resolve(query);
    }
}