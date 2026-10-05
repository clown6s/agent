package com.zzp.agent.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.zzp.agent.common.document.DocHitResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯单元测试：不启动 Spring、不依赖真实 LLM/ES/Milvus。
 * 用"脚本化返回 tool_calls 序列"的假 planner + 假工具，确定性验证图的
 * 多步 ReAct 循环、单次中断、迭代上限、sources 累计。
 */
class AgentGraphTest {

    /** 工具入参 record：与 {@link SearchDocsAgentTool.Args} 同构，仅作 schema 声明。 */
    record Args(String query) {
    }

    /** 脚本化 planner：按已完成的工具轮数(iteration)依次吐出预设的 assistant 消息，末尾固定收尾。 */
    private static final class ScriptedPlanner implements AgentPlanner {
        final List<AssistantMessage> steps;
        final List<Integer> seenIterations = new ArrayList<>();

        ScriptedPlanner(List<AssistantMessage> steps) {
            this.steps = steps;
        }

        @Override
        public AssistantMessage plan(List<Message> history, List<ToolCallback> tools, int iteration) {
            seenIterations.add(iteration);
            int idx = Math.min(iteration, steps.size() - 1);
            return steps.get(idx);
        }
    }

    /** 带 tool_calls 的 assistant 消息。 */
    private static AssistantMessage toolCallMsg(String text, String toolName, String id) {
        return AssistantMessage.builder()
                .content(text)
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", toolName,
                        "{\"query\":\"演示查询\"}")))
                .build();
    }

    /** 带自定义（可含坏 JSON）工具参数的工具调用消息，用于验证宽容解析分支。 */
    private static AssistantMessage toolCallMsgWithArgs(String text, String toolName, String id, String rawArgs) {
        return AssistantMessage.builder()
                .content(text)
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", toolName, rawArgs)))
                .build();
    }

    private static AssistantMessage finalMsg(String text) {
        return AssistantMessage.builder().content(text).build();
    }

    /** 记录调用历史的假工具。 */
    private static final class FakeTool implements AgentTool {
        final String name;
        final String outText;
        final List<Map<String, Object>> calls = new ArrayList<>();

        FakeTool(String name, String outText) {
            this.name = name;
            this.outText = outText;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "测试工具 " + name;
        }

        @Override
        public Class<?> inputType() {
            return Args.class;
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            calls.add(args);
            return new ToolResult(name, outText,
                    List.of(new DocHitResponse("chunk-" + name, "doc-" + name, outText, 0.9)));
        }
    }

    @Test
    void pureChat_answersDirectly_noToolCalled() throws Exception {
        FakeTool tool = new FakeTool("search_docs", "命中文本");
        ScriptedPlanner planner = new ScriptedPlanner(List.of(finalMsg("什么是Agent？——解释如下……")));
        AgentGraphService svc = new AgentGraphService(new ToolRegistry(List.of(tool)), planner);

        Optional<OverAllState> state = svc.invoke("什么是Agent?");
        assertTrue(state.isPresent(), "图应执行完成");
        assertEquals("什么是Agent？——解释如下……", state.get().value(AgentGraphService.ANSWER).orElse(null),
                "纯聊天应直接把 planner 的最终文本写入 answer");
        assertTrue(tool.calls.isEmpty(), "纯聊天不应触发任何工具");
        assertEquals(List.of(0), planner.seenIterations, "纯聊天应只经过一次 agent 思考（迭代从 0 起）");
    }

    @Test
    void multiStep_dependentLoop_runsAllToolsFeedsBackAndAccumulatesSources() throws Exception {
        FakeTool search = new FakeTool("search_docs", "命中：top5客户为A/B/C/D/E");
        FakeTool sql = new FakeTool("query_sql", "SELECT 客户,SUM(销售额) … LIMIT 5");
        // 三步：先检索 → 再算SQL(依赖检索) → 综合作答
        ScriptedPlanner planner = new ScriptedPlanner(List.of(
                toolCallMsg("我先检索", "search_docs", "t1"),
                toolCallMsg("我再算SQL", "query_sql", "t2"),
                finalMsg("top5客户是A/B/C/D/E，详见SQL。")));
        AgentGraphService svc = new AgentGraphService(
                new ToolRegistry(List.of(search, sql)), planner);

        Optional<OverAllState> state = svc.invoke("上季度top5客户是谁？");
        assertTrue(state.isPresent(), "图应执行完成");

        // 两把工具都按序触发
        assertEquals(1, search.calls.size(), "search_docs 应被调用一次");
        assertEquals(1, sql.calls.size(), "query_sql 应被调用一次");
        assertEquals("演示查询", search.calls.getFirst().get("query"), "工具应收到模型填的 query 参数");

        // 最终回答 = 最后一条 assistant 文本
        assertEquals("top5客户是A/B/C/D/E，详见SQL。",
                state.get().value(AgentGraphService.ANSWER).orElse(null));

        // sources 应跨轮累计两把工具的来源
        Object sources = state.get().value(AgentGraphService.SOURCES).orElse(null);
        assertTrue(sources instanceof List<?>, "sources 应为列表");
        List<String> chunkIds = ((List<?>) sources).stream()
                .map(s -> ((DocHitResponse) s).chunkId()).toList();
        assertIterableEquals(List.of("chunk-search_docs", "chunk-query_sql"), chunkIds);

        // 三轮思考，迭代计数从 0 递增
        assertEquals(List.of(0, 1, 2), planner.seenIterations, "回边应让 agent 每轮携带递增的 iteration");
    }

    @Test
    void interruptOnce_thenResume_completesLoop() throws Exception {
        FakeTool tool = new FakeTool("search_docs", "命中文本");
        ScriptedPlanner planner = new ScriptedPlanner(List.of(
                toolCallMsg("我要检索", "search_docs", "t1"),
                finalMsg("综合检索结果作答完成。")));
        AgentGraphService svc = new AgentGraphService(new ToolRegistry(List.of(tool)), planner);

        svc.runForApproval("sess-1", "查一下top5");
        Optional<StateSnapshot> pending = svc.pendingApproval("sess-1");
        assertTrue(pending.isPresent(), "首次进入工具循环前应中断等待确认");
        assertTrue(tool.calls.isEmpty(), "中断点之前不应执行工具");

        Optional<OverAllState> state = svc.resumeAgent("sess-1");
        assertTrue(state.isPresent(), "恢复后应跑完");
        assertEquals("综合检索结果作答完成。", state.get().value(AgentGraphService.ANSWER).orElse(null),
                "确认后应从 toolsFirst 续跑完整循环");
        assertEquals(1, tool.calls.size(), "恢复后才执行工具");
    }

    /** 执行时抛异常的工具：验证 tools 节点容错（单个失败不炸图、不丢并行成功结果）。 */
    private static final class ThrowingTool implements AgentTool {
        final String name;
        int calls = 0;

        ThrowingTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "会抛异常的测试工具 " + name;
        }

        @Override
        public Class<?> inputType() {
            return Args.class;
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            calls++;
            throw new IllegalStateException("工具执行爆炸：" + name);
        }
    }

    /** 一条消息并行声明多个 tool_calls（一成功一失败），验证失败工具被喂回错误、成功工具照常产出来源。 */
    private static AssistantMessage parallelToolCallMsg(String text, String okTool, String badTool) {
        return AssistantMessage.builder()
                .content(text)
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("t-bad", "function", badTool, "{\"query\":\"x\"}"),
                        new AssistantMessage.ToolCall("t-ok", "function", okTool, "{\"query\":\"x\"}")))
                .build();
    }

    @Test
    void iterationCap_hardStopsEvenIfModelKeepsRequestingTools() throws Exception {
        FakeTool tool = new FakeTool("search_docs", "命中文本");
        // 模型"失控"：永远返回 tool_calls，永不主动收尾。
        ScriptedPlanner planner = new ScriptedPlanner(List.of(toolCallMsg("失控：仍要检索", "search_docs", "t-limit")));
        AgentGraphService svc = new AgentGraphService(new ToolRegistry(List.of(tool)), planner, 2);

        Optional<OverAllState> state = svc.invoke("失控场景");
        assertTrue(state.isPresent(), "达到迭代上限应正常收尾而非死循环");
        // 硬上限：即使模型仍要工具，也只执行恰好 maxIterations 轮。
        assertEquals(2, tool.calls.size(), "达到迭代上限后绝不再执行新一轮工具");
        // 触顶收尾：answer 节点以"不暴露任何工具"再请模型综合已有结果作答，产出非空回答。
        Object answer = state.get().value(AgentGraphService.ANSWER).orElse(null);
        assertTrue(answer != null && !String.valueOf(answer).isBlank(), "触顶收尾应产出回答");
        // agent(0)→agent(1)→agent(2) 三轮思考，触顶后 answer 节点以 iteration=2 再合成一次（不执行新工具）。
        assertEquals(List.of(0, 1, 2, 2), planner.seenIterations, "应经历三轮思考后收尾，收尾为一次合成（迭代号不变）");
    }

    @Test
    void failedTool_feedsErrorBack_notKillGraph_parallelToolsStillRun() throws Exception {
        FakeTool ok = new FakeTool("query_sql", "SQL 结果");
        ThrowingTool bad = new ThrowingTool("search_docs");
        // 一条消息并行调两个工具：search_docs 抛异常、query_sql 正常。
        ScriptedPlanner planner = new ScriptedPlanner(List.of(
                parallelToolCallMsg("先检索再算SQL", "query_sql", "search_docs"),
                finalMsg("总结：SQL结果。")));
        AgentGraphService svc = new AgentGraphService(new ToolRegistry(List.of(bad, ok)), planner);

        Optional<OverAllState> state = svc.invoke("多工具");
        assertTrue(state.isPresent(), "一个工具失败不应炸掉整张图");
        assertEquals(1, bad.calls, "抛异常的工具仍会被调用一次（执行时失败）");
        assertEquals(1, ok.calls.size(), "同一条消息里正常的并行工具仍应执行");
        assertEquals("总结：SQL结果。", state.get().value(AgentGraphService.ANSWER).orElse(null),
                "失败喂回错误后，模型仍能基于正常结果综合作答");
        // 失败的 search_docs 不产生来源，正常的 query_sql 产生——并行结果不因失败而丢失。
        List<String> chunkIds = sourcesToChunkIds(state.get());
        assertIterableEquals(List.of("chunk-query_sql"), chunkIds, "仅成功工具产生来源");
    }

    @Test
    void pureChat_viaInterruptPath_completesWithoutApproval_andAnswerReturned() throws Exception {
        FakeTool tool = new FakeTool("search_docs", "命中文本");
        ScriptedPlanner planner = new ScriptedPlanner(List.of(finalMsg("直接回答，无需工具。")));
        AgentGraphService svc = new AgentGraphService(new ToolRegistry(List.of(tool)), planner);

        Optional<OverAllState> result = svc.runForApproval("sess-pc", "纯聊天");
        assertTrue(result.isPresent(), "纯聊天应直接跑完");
        assertTrue(svc.pendingApproval("sess-pc").isEmpty(),
                "纯聊天未触发工具中断，不应误报 waitingApproval=true");
        assertEquals("直接回答，无需工具。", result.get().value(AgentGraphService.ANSWER).orElse(null),
                "纯聊天可直接从起点取到最终回答");
    }

    @Test
    void resumeAgent_afterCompletion_isIdempotent_noDoubleToolExecution() throws Exception {
        FakeTool tool = new FakeTool("search_docs", "命中文本");
        ScriptedPlanner planner = new ScriptedPlanner(List.of(
                toolCallMsg("要检索", "search_docs", "t1"),
                finalMsg("综合结果作答。")));
        AgentGraphService svc = new AgentGraphService(new ToolRegistry(List.of(tool)), planner);

        svc.runForApproval("sess-idem", "查一下");
        assertTrue(svc.pendingApproval("sess-idem").isPresent(), "首次进入工具循环应等待确认");

        Optional<OverAllState> first = svc.resumeAgent("sess-idem");
        assertTrue(first.isPresent(), "第一次恢复应跑完");
        assertEquals("综合结果作答。", first.get().value(AgentGraphService.ANSWER).orElse(null));
        assertEquals(1, tool.calls.size(), "第一次恢复执行一次工具");

        Optional<OverAllState> second = svc.resumeAgent("sess-idem");
        assertTrue(second.isEmpty(), "已完成的线程重复 resume 应幂等返回空");
        assertEquals(1, tool.calls.size(), "重复 resume 绝不应二次执行工具");
    }

    @Test
    void multiTurn_inheritsSourcesAcrossRounds() throws Exception {
        FakeTool tool = new FakeTool("search_docs", "命中文本");
        AgentGraphService round1 = new AgentGraphService(
                new ToolRegistry(List.of(tool)),
                new ScriptedPlanner(List.of(toolCallMsg("先检索", "search_docs", "t1"), finalMsg("第一轮答。"))));
        Optional<OverAllState> state1 = round1.invoke("问题一");
        assertTrue(state1.isPresent());
        assertIterableEquals(List.of("chunk-search_docs"), sourcesToChunkIds(state1.get()), "第一轮检索产生来源");

        // 第二轮是"纯聊天"（不回边到工具），理应继承第一轮累计的来源，而非被清空。
        AgentGraphService round2 = new AgentGraphService(
                new ToolRegistry(List.of(tool)),
                new ScriptedPlanner(List.of(finalMsg("追问答复。"))));
        Optional<OverAllState> state2 = round2.invoke("追问一句", state1);
        assertTrue(state2.isPresent(), "多轮追问应正常返回");
        assertEquals("追问答复。", state2.get().value(AgentGraphService.ANSWER).orElse(null));
        assertIterableEquals(List.of("chunk-search_docs"), sourcesToChunkIds(state2.get()),
                "第二轮即便不调工具，也应继承第一轮来源供 UI 展示");
    }

    @SuppressWarnings("unchecked")
    private static List<String> sourcesToChunkIds(OverAllState state) {
        Object raw = state.value(AgentGraphService.SOURCES).orElse(List.of());
        return ((List<DocHitResponse>) raw).stream().map(DocHitResponse::chunkId).toList();
    }

    @Test
    void malformedToolArgs_parsedLeniently_toolStillRuns() throws Exception {
        FakeTool tool = new FakeTool("search_docs", "命中文本");
        // 真实 LLM 常吐的坏 JSON：单个引号 + 未闭合。宽容解析应降级为"空参"而非炸图。
        ScriptedPlanner planner = new ScriptedPlanner(List.of(
                toolCallMsgWithArgs("先检索", "search_docs", "t-mal", "'query': \"未闭合"),
                finalMsg("基于检索结果作答完成。")));
        AgentGraphService svc = new AgentGraphService(new ToolRegistry(List.of(tool)), planner);

        Optional<OverAllState> state = svc.invoke("坏参数场景");
        assertTrue(state.isPresent(), "坏工具参数不应炸掉整张图");
        assertEquals(1, tool.calls.size(), "宽容解析降级为空参后工具仍应执行");
        assertEquals("基于检索结果作答完成。", state.get().value(AgentGraphService.ANSWER).orElse(null),
                "坏参数路径也应正常收尾产出回答");
    }
}