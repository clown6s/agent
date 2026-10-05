package com.zzp.agent.graph;

import java.util.Map;

/**
 * 工具调用接口——多步 function-calling 里的"纯函数工具"。
 *
 * <p>一次工具只做一件事并返回事实（{@link ToolResult}），不做最终作答；
 * 由 {@link AgentGraphService} 的 agent 节点在 tools→agent 回边里综合所有工具结果生成最终回答。
 *
 * <p>graph 通过 {@link ToolRegistry} 按 {@link #name()} 派发：agent 节点把工具的
 * {@link #name()} / {@link #description()} / {@link #inputType()} 声明的 schema 交给 LLM，
 * LLM 返回 {@code tool_calls} 后，tools 节点按名查到这里并调用 {@link #execute(java.util.Map)}。
 *
 * <p>新增工具：实现此接口并注册为 Spring bean 即可，无需改图、改枚举。
 */
public interface AgentTool {

    /** 工具名（对 LLM 暴露，唯一）。 */
    String name();

    /** 工具的用途说明（给 LLM 决定何时调用）。 */
    String description();

    /**
     * 入参类型：一个 JDK record，描述该工具的 JSON 入参结构（字段即参数）。
     * spring-ai 据此给模型生成 function schema，模型据此填 {@code arguments}。
     */
    Class<?> inputType();

    /** 按参数执行工具，返回原始事实结果 + 来源。entry 由 {@code ToolCall.arguments()} 解析而来。 */
    ToolResult execute(Map<String, Object> args);
}