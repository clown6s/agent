package com.zzp.agent.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.agent.common.document.DocHitResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 用 Spring AI Alibaba Graph 的 {@link StateGraph} 搭的多步 function-calling Agent。
 *
 * <p>图结构（agent 与 tools 通过回边构成 ReAct 循环，直到模型不再要工具）：
 * <pre>
 *   START → agent ──条件路由──→ answer → END
 *             │   (无 tool_calls)
 *             └──(有 tool_calls)──→ toolsFirst ─┐
 *                      ▲                        ▼
 *                      └──────────── tools ─────┘
 *              （toolsFirst / tools 都回边到 agent）
 * </pre>
 * <ul>
 *   <li><b>agent 节点</b>：把累积消息 + 全部工具 schema 交给 {@link AgentPlanner}（默认 LLM），
 *       得一条 assistant 消息；若带 {@code tool_calls} 走 tools，否则走 answer；</li>
 *   <li><b>tools / toolsFirst 节点</b>：按名经 {@link ToolRegistry} 派发执行本轮所有 tool_calls，
 *       把 {@code ToolResponseMessage} 喂回消息队列、累计 {@link #SOURCES}、轮次 {@link #ITERATIONS} 加一，
 *       再回边到 agent，让模型基于新结果决定下一步——"结果相互依赖"在此天然成立；</li>
 *   <li><b>answer 节点</b>：把最后一条 assistant 消息文本写入 {@link #ANSWER} 后 END。</li>
 * </ul>
 *
 * <p><b>单次审批</b>：{@code interruptBefore("toolsFirst")}，只在第一次进入工具循环前暂停等人工确认
 * （你选的粒度）；确认后 {@code withResume()} 从 toolsFirst 续跑，后续多步不再中断。
 *
 * <p><b>迭代上限是图层硬约束</b>（默认 5，{@code agent.maxIterations}）：当已完成工具轮数达到上限时，
 * {@code route()} 无条件转 answer、不再执行任何新工具——即使模型仍反复要求 tool_calls；
 * 若模型无视"停止"指令仍发 tool_call，answer 节点会用"不暴露任何工具"的最后一轮让模型综合已有结果作答，
 * 保证循环必然终止。{@link LlmAgentPlanner} 的系统提示只是额外的"劝停"。
 */
@Service
public class AgentGraphService {

    private static final Logger log = LoggerFactory.getLogger(AgentGraphService.class);
    /** 宽容 JSON：容忍真实 LLM 常吐的单引号/未引字段名/尾逗号/注释，解析失败按空参处理而非炸图。 */
    private static final ObjectMapper PARAMS_MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true)
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true);

    /** state 键：用户提问原文本。 */
    public static final String QUERY = "query";
    /** state 键：跨轮累积的 LLM 消息队列（用户提问 + 助手 tool_calls + 工具结果）。 */
    public static final String MESSAGES = "messages";
    /** state 键：最终回答文本。 */
    public static final String ANSWER = "answer";
    /** state 键：回答引用的全部来源（{@code List<DocHitResponse>}，跨轮累计）。 */
    public static final String SOURCES = "sources";
    /** state 键：已完成了几轮工具调用（用于路由工具入口 + 迭代上限）。 */
    public static final String ITERATIONS = "iterations";

    private final ToolRegistry toolRegistry;
    private final AgentPlanner planner;
    /** 单条回答允许的工具调用轮数上限（图层里的硬约束），可由 {@code agent.maxIterations} 覆盖，默认 5。 */
    private final int maxIterations;
    /** 普通图：流式/单轮/多轮对话用（无 checkpoint，不中断）。 */
    private final CompiledGraph compiledGraph;
    /** 打断/恢复用：带 MemorySaver checkpoint，首次进工具循环前 {@code interruptBefore("toolsFirst")}。 */
    private final CompiledGraph interruptGraph;
    /** 内存 checkpoint 存储器：按 threadId 存中断点，支持"同一会话可恢复"。 */
    private final MemorySaver checkpointer;
    /** 每个 threadId 一把锁，串行化中断/恢复，防并发重复 resume 双执行工具。 */
    private final ConcurrentHashMap<String, Object> threadLocks = new ConcurrentHashMap<>();

    /** 测试用便捷构造：迭代上限取默认 5。 */
    public AgentGraphService(ToolRegistry toolRegistry, AgentPlanner planner) throws GraphStateException {
        this(toolRegistry, planner, 5);
    }

    @Autowired
    public AgentGraphService(ToolRegistry toolRegistry, AgentPlanner planner,
                             @Value("${agent.maxIterations:5}") int maxIterations) throws GraphStateException {
        this.toolRegistry = toolRegistry;
        this.planner = planner;
        this.maxIterations = maxIterations;
        this.checkpointer = new MemorySaver();
        this.compiledGraph = buildGraph(null);
        this.interruptGraph = buildGraph(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(checkpointer).build())
                .interruptBefore("toolsFirst")
                .build());
    }

    private CompiledGraph buildGraph(CompileConfig config) throws GraphStateException {
        // agent：读累积消息 + 轮次 → planner 思考 → 把模型发出的 assistant 消息追加进队列
        AsyncNodeAction agentNode = state -> {
            List<Message> history = readMessages(state);
            int iteration = stateInt(state, ITERATIONS);
            AssistantMessage out = planner.plan(history, toolRegistry.schemas(), iteration);
            List<Message> updated = new ArrayList<>(history);
            updated.add(out);
            return CompletableFuture.completedFuture(Map.of(MESSAGES, updated));
        };

        // tools / toolsFirst：执行最后一条 assistant 消息里的所有 tool_calls，结果喂回、累计 sources、轮次+1
        AsyncNodeAction toolsNode = state -> {
            List<Message> history = readMessages(state);
            if (history.isEmpty() || !(history.get(history.size() - 1) instanceof AssistantMessage am)
                    || !am.hasToolCalls()) {
                return CompletableFuture.completedFuture(Map.of());
            }
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            List<DocHitResponse> sources = new ArrayList<>(readSources(state));
            for (AssistantMessage.ToolCall call : am.getToolCalls()) {
                try {
                    AgentTool tool = toolRegistry.byName(call.name());
                    log.info("执行工具 {} 参数 {}", call.name(), call.arguments());
                    ToolResult result = tool.execute(parseArgs(call.arguments()));
                    sources.addAll(result.sources());
                    responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result.output()));
                } catch (Exception e) {
                    // 单个工具失败不炸掉整图：把错误说明作为该工具的结果喂回 LLM，让其修正重复调用或改走路径；
                    // 同一条消息里其它并行 tool_calls 照常执行，已产出的 sources 也不丢弃。
                    log.warn("工具 {} 执行失败，已作为错误结果喂回模型：{}", call.name(), e.getMessage());
                    responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                            "【工具执行出错】" + e.getMessage()));
                }
            }
            List<Message> updated = new ArrayList<>(history);
            updated.add(ToolResponseMessage.builder().responses(responses).build());

            Map<String, Object> out = new HashMap<>();
            out.put(MESSAGES, updated);
            out.put(SOURCES, sources);
            out.put(ITERATIONS, stateInt(state, ITERATIONS) + 1);
            return CompletableFuture.completedFuture(out);
        };

        // answer：把最后一条 assistant 消息文本作为最终回答；若触达迭代上限被硬切时末条仍是 tool_call，
        // 则用"不暴露任何工具"的最后一轮让模型综合已有结果作答——保证循环必然终止且产出真实回答。
        AsyncNodeAction answerNode = state -> {
            List<Message> history = readMessages(state);
            String answer = "";
            if (!history.isEmpty() && history.get(history.size() - 1) instanceof AssistantMessage am) {
                if (!am.hasToolCalls() && am.getText() != null) {
                    answer = am.getText();
                } else if (am.hasToolCalls()) {
                    // 模型无视"停止"指令、在触顶后仍要工具：不给任何 tool schema，强制其直接综合作答。
                    try {
                        AssistantMessage finalMsg =
                                planner.plan(history, List.of(), Math.max(stateInt(state, ITERATIONS), maxIterations));
                        answer = finalMsg.getText() != null ? finalMsg.getText() : "";
                    } catch (Exception e) {
                        log.warn("迭代上限收尾作答失败：{}", e.getMessage());
                        answer = "";
                    }
                }
            }
            return CompletableFuture.completedFuture(Map.of(ANSWER, answer));
        };

        // 条件路由：最后一条是带 tool_calls 的 assistant → 首轮 toolsFirst / 后续 tools；否则 answer。
        // 硬性上限：已完成工具轮数达到 maxIterations 即无条件转 answer，绝不执行超出上限的新一轮工具。
        AsyncEdgeAction route = state -> {
            List<Message> history = readMessages(state);
            boolean wantsTools = !history.isEmpty()
                    && history.get(history.size() - 1) instanceof AssistantMessage am
                    && am.hasToolCalls();
            if (!wantsTools) {
                return CompletableFuture.completedFuture("answer");
            }
            if (stateInt(state, ITERATIONS) >= maxIterations) {
                return CompletableFuture.completedFuture("answer");
            }
            boolean first = stateInt(state, ITERATIONS) == 0;
            return CompletableFuture.completedFuture(first ? "toolsFirst" : "tools");
        };

        // 关键：必须显式给每个 state key 注册 REPLACE 策略（仅 defaultStrategy 不够——resume 时
        // OverAllState.input() 用 containsKey(key) 判断是否把 checkpoint 状态合并回 initial state，
        // 没显式注册的 key 会被跳过，导致消息队列/query 丢失、工具节点读到空上文）。所有节点都写
        // "完整结构"（整条消息列表/累计 sources），故 REPLACE 是正确的合并语义。
        StateGraph graph = new StateGraph(
                KeyStrategy.builder()
                        .addStrategy(QUERY, KeyStrategy.REPLACE)
                        .addStrategy(MESSAGES, KeyStrategy.REPLACE)
                        .addStrategy(ANSWER, KeyStrategy.REPLACE)
                        .addStrategy(SOURCES, KeyStrategy.REPLACE)
                        .addStrategy(ITERATIONS, KeyStrategy.REPLACE)
                        .defaultStrategy(KeyStrategy.REPLACE)
                        .build(),
                StateGraph.DEFAULT_JACKSON_SERIALIZER)
                .addNode("agent", agentNode)
                .addNode("toolsFirst", toolsNode)
                .addNode("tools", toolsNode)
                .addNode("answer", answerNode)
                .addEdge(StateGraph.START, "agent")
                .addConditionalEdges("agent", route, Map.of(
                        "tools", "tools",
                        "toolsFirst", "toolsFirst",
                        "answer", "answer"))
                .addEdge("toolsFirst", "agent")
                .addEdge("tools", "agent")
                .addEdge("answer", StateGraph.END);
        return config == null ? graph.compile() : graph.compile(config);
    }

    /** 执行一次对话（单轮）：只入参 query，走完整（多步）图。 */
    public Optional<OverAllState> invoke(String query) {
        return invoke(query, Optional.empty());
    }

    /**
     * 执行一次对话（支持多轮）：把上一轮 {@code previous} 的消息队列继承过来 + 本条新的用户提问，
     * 让 follow-up 追问能延续上文。首轮传 {@link Optional#empty()}。
     */
    public Optional<OverAllState> invoke(String query, Optional<OverAllState> previous) {
        List<Message> messages = new ArrayList<>();
        List<DocHitResponse> sources = new ArrayList<>();
        previous.ifPresent(prev -> {
            List<?> prevMsgs = (List<?>) prev.value(MESSAGES).orElse(List.of());
            for (Object m : prevMsgs) {
                if (m instanceof Message msg) {
                    messages.add(msg);
                }
            }
            // 多轮也继承上轮累计的来源，避免"追问引用了上一轮 doc-x、UI 的 sources 面板却为空"。
            sources.addAll(readSources(prev));
        });
        messages.add(new UserMessage(query));
        Map<String, Object> inputs = new HashMap<>();
        inputs.put(QUERY, query);
        inputs.put(MESSAGES, messages);
        inputs.put(SOURCES, sources);
        return compiledGraph.invoke(inputs);
    }

    /** 流式执行：订阅图的节点级输出流；循环会依次发出 agent/tools… 等节点，最末 END 含 answer/sources。 */
    public Flux<NodeOutput> streamQuery(String query) {
        return compiledGraph.stream(Map.of(QUERY, query, MESSAGES, List.of(new UserMessage(query))));
    }

    /**
     * 中断式对话起点：首次进入工具循环前（toolsFirst）暂停等人确认；纯聊天（无工具）不中断、直接跑完。
     * 返回最终状态——若是纯聊天直接完成，调用方可从中取 answer/sources；若中断，则是待确认快照。
     *
     * @param threadId 会话线程 id（调用方持久化，恢复时复用）
     * @param query    用户提问
     * @return 图执行结果（完成态或中断快照）
     */
    public Optional<OverAllState> runForApproval(String threadId, String query) {
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        return interruptGraph.invoke(Map.of(QUERY, query, MESSAGES, List.of(new UserMessage(query))), config);
    }

    /** 读取某会话 threadId 的中断快照（待人工确认的工具调用）。已完成（已产出最终 answer）或无记录则 empty。 */
    public Optional<StateSnapshot> pendingApproval(String threadId) {
        return interruptGraph.stateOf(RunnableConfig.builder().threadId(threadId).build())
                // 关键：MemorySaver 对跑完到 END 的线程也会留下终态 checkpoint，必须用"是否已有最终回答"
                // 而非"是否有 checkpoint"来判定"待确认"，否则纯聊天/已完成会话会被误报 waitingApproval=true。
                .filter(sn -> sn.state().value(ANSWER)
                        .map(a -> String.valueOf(a).isBlank())
                        .orElse(true));
    }

    /**
     * 人工确认后恢复：同 threadId + {@code withResume()}，从 toolsFirst 续跑，后续多步不再中断。
     * 用每个 threadId 的锁串行化中断/恢复，并对"已完成"线程秒回 empty——并发/重复 resume 不会双执行工具。
     */
    public Optional<OverAllState> resumeAgent(String threadId) {
        Object lock = threadLocks.computeIfAbsent(threadId, k -> new Object());
        synchronized (lock) {
            try {
                if (pendingApproval(threadId).isEmpty()) {
                    // 幂等：第一个 resume 已跑完、或该线程本就无待确认点，后续 resume（含并发第二个）直接跳过，
                    // 避免在此基础上再触发一轮工具执行。
                    log.warn("threadId {} 无待确认中断点，跳过恢复（已完成或不存在）", threadId);
                    return Optional.empty();
                }
                RunnableConfig config = RunnableConfig.builder().threadId(threadId).build().withResume();
                return interruptGraph.invoke(Map.of(), config);
            } finally {
                // 恢复是一次性的：任何一次 resume 之后（成功跑完、或本就无可恢复）该线程都不再需要这把锁，
                // 随手清掉，避免按 threadId 无限累积锁对象造成长跑服务内存泄漏。
                threadLocks.remove(threadId);
            }
        }
    }

    /** 暴露编译后的普通图，供上层拿到底层 {@link CompiledGraph}。 */
    public CompiledGraph compiledGraph() {
        return compiledGraph;
    }

    @SuppressWarnings("unchecked")
    private static List<Message> readMessages(OverAllState state) {
        Optional<Object> opt = state.value(MESSAGES);
        if (opt.isEmpty()) {
            return List.of();
        }
        List<Message> out = new ArrayList<>();
        for (Object o : (List<?>) opt.get()) {
            if (o instanceof Message msg) {
                out.add(msg);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<DocHitResponse> readSources(OverAllState state) {
        Optional<Object> opt = state.value(SOURCES);
        return opt.map(o -> (List<DocHitResponse>) o).orElseGet(List::of);
    }

    /** 读取 int 型 state 值（state 存入的原始类型可能是 Integer，取其数值）。 */
    private static int stateInt(OverAllState state, String key) {
        Object v = state.value(key).orElse(0);
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** 把模型返回的 tool_call arguments（JSON 字符串）宽容解析为参数 Map。 */
    private static Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return PARAMS_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            // 容错：真实 LLM 常吐单引号/尾逗号/截断 JSON，解析失败按空参处理，交由工具自身的兜底承接，
            // 而不是抛异常炸掉整条会话。若工具在空参下也无法执行，tools 节点的逐调用 try/catch 会兜住并喂回 LLM。
            log.warn("工具参数解析失败，按空参处理：{}", json);
            return Map.of();
        }
    }
}