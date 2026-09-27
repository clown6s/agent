package com.zzp.agent;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.zzp.agent.common.document.DocHitResponse;
import com.zzp.agent.common.document.DocSource;
import com.zzp.agent.document.intake.DocumentIntakeService;
import com.zzp.agent.document.parser.DocumentParseService;
import com.zzp.agent.document.parser.ParseResult;
import com.zzp.agent.document.split.ChunkingService;
import com.zzp.agent.document.split.TextChunk;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class AgentApplicationTests {

    @Autowired
    DocumentParseService documentParseService;

    @Autowired
    ChunkingService chunkingService;

    @Autowired
    ElasticsearchAsyncClient elasticsearchAsyncClient;

    @Autowired
    DocumentIntakeService documentIntakeService;

    @Test
    void parseDocument() throws IOException {
        String path = "D:\\datum\\AI-Agents-in-Depth-zh-CN.pdf";
//        String path = "D:\\datum\\中华人工共和国刑法.pdf";
//        String path = "D:\\datum\\家庭教育促进法.pdf";
//        String path = "D:\\datum\\导游面试问答.docx";
//        String path = "D:\\datum\\中华人民共和国治安管理处罚法_20250627.docx";
//        String path = "D:\\datum\\古代诗歌常用意象.txt";

        InputStream in = Files.newInputStream(Path.of(path));
        ParseResult r = documentParseService.parse(path,in);
        System.out.println("==== 文件: " + r.fileName());
        System.out.println("==== 文件id: " + r.documentId());
        System.out.println("==== 类型: " + r.type());
        System.out.println("==== 字符数: " + r.length());
        System.out.println("==== 内容 start ====");
        System.out.println(r.text());
        System.out.println("==== 内容 end   ====");
        assertTrue(r.length() > 0);
    }

    @Test
    void parseText() throws IOException {
        String path = "D:\\datum\\AI-Agents-in-Depth-zh-CN.pdf";
//        String path = "D:\\datum\\中华人工共和国刑法.pdf";
//        String path = "D:\\datum\\家庭教育促进法.pdf";
//        String path = "D:\\datum\\导游面试问答.docx";
//        String path = "D:\\datum\\中华人民共和国治安管理处罚法_20250627.docx";
//        String path = "D:\\datum\\古代诗歌常用意象.txt";
        InputStream in = Files.newInputStream(Path.of(path));
        ParseResult r = documentParseService.parse(path,in);
        List<TextChunk> chunk = chunkingService.chunk(r.documentId(), r.text());
        for (TextChunk textChunk : chunk) {
            System.out.println("==================================");
            System.out.println(textChunk);
        }
    }

    @Test
    void setSearch() throws Exception {
        // 1) search() 必须显式传 DocSource.class 作为反序列化目标
        CompletableFuture<SearchResponse<DocSource>> future =
                elasticsearchAsyncClient.search(s ->
                                s.index("docs")
                                        .query(q -> q
                                                .match(m -> m
                                                        .field("text")
                                                        .query("少女"))),
                        DocSource.class);

        // 2) 异步 future 用 join() 取结果（阻塞等待 ES 返回）
        SearchResponse<DocSource> resp = future.join();

        // 3) 遍历每个命中的 Hit<DocSource>
        for (Hit<DocSource> hit : resp.hits().hits()) {
            // 4) 组装 4 字段：chunkId/documentId/text/score
            DocHitResponse d = DocHitResponse.from(hit);
            System.out.println(d);
        }
    }

    @Test
    void intake() throws Exception {
//        String path = "D:\\datum\\AI-Agents-in-Depth-zh-CN.pdf";
//        String path = "D:\\datum\\中华人工共和国刑法.pdf";
//        String path = "D:\\datum\\家庭教育促进法.pdf";
//        String path = "D:\\datum\\导游面试问答.docx";
//        String path = "D:\\datum\\中华人民共和国治安管理处罚法_20250627.docx";
        String path = "D:\\datum\\古代诗歌常用意象.txt";
        InputStream in = Files.newInputStream(Path.of(path));
        DocumentIntakeService.IntakeResult intake = documentIntakeService.intake(path, in);

        System.out.println("intake = " + intake);
    }

}
