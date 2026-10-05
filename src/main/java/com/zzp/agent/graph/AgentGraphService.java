package com.zzp.agent.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
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
import com.zzp.agent.common.document.DocHitResponse;
import reactor.core.publisher.Flux;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 用 Spring AI Alibaba Graph 的 {@link StateGraph} 搭的 Agent 工作流。
 *
 * <p>图结构（可扩展）：
 * <pre>
 *   START → agent ──条件路由──→ TOOLS → END
 *              │
 *              └────────────→ END   (CHAT)
 * </pre>
 * <ul>
 *   <li><b>agent 节点</b>：用 LLM 判断提问意图（{@link AgentIntentResolver}），写入 state 的 {@code intent}；</li>
 *   <li><b>tools 节点</b>：意图为 TOOLS 时进入，调 {@link AgentTool}（默认 {@link SearchDocsAgentTool}），
 *       把回答写进 state 的 {@code answer}、来源写进 {@code sources}；</li>
 *   <li><b>条件路由</b>：{@code intent==CHAT → END}，{@code intent==TOOLS → tools}。</li>
 * </ul>
 *
 * <p>state 键统一走常量 {@link #QUERY} / {@link #INTENT} / {@link #ANSWER} / {@link #SOURCES}，避免魔法字符串。
 */
@Service
public class AgentGraphService {

    /** state 键：用户提问原文本。 */
    public static final String QUERY = "query";
    /** state 键：LLM 判断出的意图名（{@link Intent} 的 name）。 */
    public static final String INTENT = "intent";
    /** state 键：最终回答文本（TOOLS 分支由工具生成）。 */
    public static final String ANSWER = "answer";
    /** state 键：回答引用的来源（{@code List<DocHitResponse>}）。 */
    public static final String SOURCES = "sources";
    /** state 键：多轮时由 {@link #invoke(String, Optional)} 注入的上一轮上下文（格式化字符串）。 */
    public static final String HISTORY = "history";

    private final AgentIntentResolver intentResolver;
    private final AgentTool agentTool;
    /** 普通图：流式/单轮/多轮对话用（无 checkpoint）。 */
    private final CompiledGraph compiledGraph;
    /** 打断/恢复用：带 MemorySaver checkpoint，TOOLS 意图会在 tools 前 `interruptBefore` 暂停等人确认。 */
    private final CompiledGraph interruptGraph;
    /** 内存 checkpoint 存储器：同 JVM 进程内按 threadId 存取中断点，保证"同一会话可恢复"。 */
    private final MemorySaver checkpointer;

    public AgentGraphService(AgentIntentResolver intentResolver, AgentTool agentTool) throws GraphStateException {
        this.intentResolver = intentResolver;
        this.agentTool = agentTool;
        this.checkpointer = new MemorySaver();
        this.compiledGraph = buildGraph(null);
        this.interruptGraph = buildGraph(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(checkpointer).build())
                .interruptBefore("tools")
                .build());
    }

    private CompiledGraph buildGraph(CompileConfig config) throws GraphStateException {
        // agent 节点：读 query → LLM 判断意图 → 写回 state
        AsyncNodeAction agentNode = state -> {
            String query = String.valueOf(state.value(QUERY).orElse(""));
            // 多轮时带上上一轮上下文，让意图判断能正确延续 follow-up 追问
            String history = state.value(HISTORY).map(String::valueOf).orElse(null);
            Intent intent = intentResolver.resolve(query, history);
            return CompletableFuture.completedFuture(Map.of(INTENT, intent.name()));
        };

        // tools 节点：调真实工具（默认 search_docs），把回答和来源写回 state
        AsyncNodeAction toolsNode = state -> {
            String query = String.valueOf(state.value(QUERY).orElse(""));
            // 多轮时带上上一轮上下文（若无则为 null，工具退化为单轮）
            String history = state.value(HISTORY).map(String::valueOf).orElse(null);
            AgentToolResult result = agentTool.execute(query, history);
            Map<String, Object> out = new HashMap<>();
            out.put(ANSWER, result.answer());
            out.put(SOURCES, result.sources());
            return CompletableFuture.completedFuture(out);
        };

        // 条件路由：根据 intent 值决定下一步（返回值是映射表的 key）
        AsyncEdgeAction route = state -> CompletableFuture.completedFuture(
                String.valueOf(state.value(INTENT).orElse(Intent.CHAT.name())));

        StateGraph graph = new StateGraph()
                .addNode("agent", agentNode)
                .addNode("tools", toolsNode)
                .addEdge(StateGraph.START, "agent")
                .addConditionalEdges("agent", route, Map.of(
                        Intent.CHAT.name(), StateGraph.END,
                        Intent.TOOLS.name(), "tools"))
                .addEdge("tools", StateGraph.END);
        return config == null ? graph.compile() : graph.compile(config);
    }

    /**
     * 执行一次对话（单轮）：入参只放 query，走完整图。返回最终 state。
     *
     * @param query 用户提问
     * @return 图执行完的最终状态（可在此读 intent/answer/sources）
     */
    public Optional<OverAllState> invoke(String query) {
        return invoke(query, Optional.empty());
    }

    /**
     * 执行一次对话（支持多轮）：把上一轮 {@code previous} 的 answer/sources 格式化成历史上下文，
     * 注入 state 的 {@link #HISTORY}，让 tools 节点能基于"刚才说过什么"继续回答追问。
     *
     * @param query    用户本轮提问
     * @param previous 上一轮返回的 state；首轮传 {@link Optional#empty()}
     * @return 本轮执行完的最终 state
     */
    public Optional<OverAllState> invoke(String query, Optional<OverAllState> previous) {
        Map<String, Object> input = new HashMap<>();
        input.put(QUERY, query);
        previous.ifPresent(prev -> {
            String hist = formatHistory(prev);
            if (hist != null) {
                input.put(HISTORY, hist);
            }
        });
        return compiledGraph.invoke(input);
    }

    /**
     * 把上一轮 state 里的 answer/sources 拼成给 LLM 的历史上下文。
     *
     * @param prev 上一轮 state
     * @return 格式化后的历史字符串；上一轮没有回答时返回 {@code null}
     */
    private String formatHistory(OverAllState prev) {
        Optional<String> answer = prev.value(ANSWER);
        if (answer.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("上一轮回答：").append(answer.get());
        Object sources = prev.value(SOURCES).orElse(null);
        if (sources instanceof List<?> list && !list.isEmpty()) {
            sb.append("\n来源：");
            for (Object o : list) {
                if (o instanceof DocHitResponse h) {
                    sb.append(" [").append(h.documentId()).append(']');
                }
            }
        }
        return sb.toString();
    }

    /** 暴露编译后的图，供流式/打断/恢复拿到底层 {@link CompiledGraph}。 */
    public CompiledGraph compiledGraph() {
        return compiledGraph;
    }

    /**
     * 流式执行一次对话：返回节点级输出流 {@link Flux}<{@link NodeOutput}>。
     * 订阅端（SSE）逐条消费：普通节点是中间状态，最末 {@code isEND()} 节点的 {@link OverAllState}
     * 含最终 answer / sources。
     *
     * @param query 用户提问
     * @return 节点输出流（Reactor）
     */
    public Flux<NodeOutput> streamQuery(String query) {
        return compiledGraph.stream(Map.of(QUERY, query));
    }

    /**
     * 可打断/可恢复——中断式执行（人工确认式对话）。
     * <p>用给定 {@code threadId} 标识一个会话线程；TOOLS 意图会在 tools 节点前停下并保存 checkpoint，
     * CHAT 意图不中断、直接跑完。返回该会话的 {@link RunnableConfig}（含 threadId）。
     *
     * @param threadId 会话线程 id（调用方持久化，恢复时复用）
     * @param query    用户提问
     * @return 该会话的 RunnableConfig
     */
    public RunnableConfig runForApproval(String threadId, String query) {
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        interruptGraph.invoke(Map.of(QUERY, query), config);
        return config;
    }

    /**
     * 读取某会话 threadId 的中断快照（待人工确认的意图/状态）。
     *
     * @return CHAT 意图已跑完则 empty；TOOLS 意图中断则返回快照（可读 intent、并定位下一步）
     */
    public Optional<StateSnapshot> pendingApproval(String threadId) {
        return interruptGraph.stateOf(RunnableConfig.builder().threadId(threadId).build());
    }

    /**
     * 人工确认后恢复：用同 threadId + {@code withResume()} 标记恢复，续跑 tools → END。
     *
     * @param threadId 会话线程 id（与 {@link #runForApproval} 相同）
     * @return 恢复执行完的最终 state（含 answer / sources）
     */
    public Optional<OverAllState> resumeAgent(String threadId) {
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build().withResume();
        return interruptGraph.invoke(Map.of(), config);
    }
}