package com.zzp.agent.graph;

/**
 * 意图判断接口。策略可替换：默认实现 {@link LlmAgentIntentResolver} 用 LLM 判断意图，
 * 测试/无模型时可注入假实现验证图的路由。
 */
public interface AgentIntentResolver {
    Intent resolve(String query);
}