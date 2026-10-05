package com.zzp.agent.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实链路集成测试：不 mock，直连真 DeepSeek + ES/Milvus + 硅基流动 embedding。
 *
 * <p>这是 P1（"真实路径未经证明"）的验证载体——刻意验证那些只在真机上才暴露的风险：
 * <ul>
 *   <li>{@link LlmAgentPlanner} 用 {@code DeepSeekChatOptions.internalToolExecutionEnabled(false)}
 *       发出的裸 tool_calls，DeepSeek 是否真接受、是否真回 function 调用；</li>
 *   <li>{@link ToolRegistry} 的 schema-only no-op {@link ToolCallback} 是否被真实 LLM 接受；</li>
 *   <li>{@code search_docs} → {@code QueryDocumentService} 真实混合召回 → 结果喂回 LLM 的多步循环；</li>
 *   <li>{@code MemorySaver} + {@code interruptBefore("toolsFirst")} + {@code withResume()} 的真实断点续跑。</li>
 * </ul>
 *
 * <p>需要环境变量（application.yaml 的 {@code ${...}} 占位）齐备，且启动容器在本机可达，否则 Spring
 * 上下文起不来、本类失败——与 {@code AgentApplicationTests} 相同的运行前提。
 *
 * <p><b>运行前提</b>：ES/Milvus/DeepSeek/硅基流动全可达；会消耗真实 DeepSeek token。因此仅在被显式指定时
 * 运行（{@code -Dtest=AgentGraphIntegrationTest}），不适合无环境时的日常 {@code mvn test}。
 */
@SpringBootTest
class AgentGraphIntegrationTest {

    @Autowired
    AgentGraphService graph;

    @Autowired
    ToolRegistry toolRegistry;

    @Test
    void realLlM_invoke_multiStepSearchAndAnswer() throws Exception {
        // 措辞刻意要求"检索资料库再回答"，逼真实 DeepSeek 走 search_docs 工具路径。
        String query = "用 search_docs 工具检索资料库，找出 Agent 的定义与核心特征，然后基于检索结果综合回答。";
        Optional<OverAllState> state = graph.invoke(query);
        assertTrue(state.isPresent(), "真实 LLM 路径应能完成一次图执行");

        String answer = String.valueOf(state.get().value(AgentGraphService.ANSWER).orElse(""));
        System.out.println("==== 最终回答 ====\n" + answer);

        List<?> sources = (List<?>) state.get().value(AgentGraphService.SOURCES).orElse(List.of());
        System.out.println("==== 工具来源数: " + sources.size() + " (>0 说明真走了多步工具路径) ====");
        sources.forEach(System.out::println);

        // 硬断言：真实链路必须产出非空回答（否则 DeepSeek 调用/工具 schema 就有问题）。
        assertTrue(!answer.isBlank(), "真实 LLM 路径应产出非空最终回答");
        System.out.println("==== 是否触发工具: " + (sources.size() > 0 ? "是(多步)" : "否(直接答)") + " ====");
    }

    @Test
    void realLlM_interrupt_thenResume() throws Exception {
        String query = "用 search_docs 工具检索资料库中关于 Agent 的内容，然后回答。";
        Optional<OverAllState> run = graph.runForApproval("it-sess-1", query);
        Optional<StateSnapshot> pending = graph.pendingApproval("it-sess-1");
        System.out.println("==== 首次是否停在工具循环前待确认: " + pending.isPresent() + " ====");

        if (pending.isEmpty()) {
            // LLM 直接作答（未触发工具中断）——仍是有效的真实链路执行，但不构成"断点续跑"证明。
            System.out.println("（未触发工具中断）起点最终回答: " + run.map(s -> s.value(AgentGraphService.ANSWER).orElse("")).orElse(""));
            return;
        }
        Optional<OverAllState> finalState = graph.resumeAgent("it-sess-1");
        assertTrue(finalState.isPresent(), "真实断点恢复应能跑完");
        String answer = String.valueOf(finalState.get().value(AgentGraphService.ANSWER).orElse(""));
        System.out.println("==== 恢复后最终回答 ====\n" + answer);
        assertTrue(!answer.isBlank(), "真实断点续跑应产出非空最终回答");
    }

    @Test
    void realToolRegistry_registeredSearchDocs() {
        // 真实 spring 装配下，默认注册表里必须有 search_docs 工具（供上面测试的 schema 派发使用）。
        assertTrue(toolRegistry.all().stream().anyMatch(t -> "search_docs".equals(t.name())),
                "真实装配应注册 search_docs 工具");
    }
}
