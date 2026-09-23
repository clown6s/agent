package com.zzp.agent;

import com.zzp.agent.document.parser.DocumentParseService;
import com.zzp.agent.document.parser.ParseResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.skyscreamer.jsonassert.JSONAssert.assertEquals;

@SpringBootTest
class AgentApplicationTests {

    @Autowired
    DocumentParseService documentParseService;

    @Test
    void parseDocument() throws IOException {
//        String path = "D:\\datum\\AI-Agents-in-Depth-zh-CN.pdf";
//        String path = "D:\\datum\\中华人工共和国刑法.pdf";
//        String path = "D:\\datum\\导游面试问答.docx";
        String path = "D:\\datum\\中华人民共和国治安管理处罚法_20250627.docx";
//        String path = "D:\\datum\\古代诗歌常用意象.txt";
//        String path = "D:\\datum\\家庭教育促进法.pdf";
        ParseResult r = documentParseService.parse(path);
        System.out.println("==== 文件: " + r.fileName());
        System.out.println("==== 类型: " + r.type());
        System.out.println("==== 字符数: " + r.length());
        System.out.println("==== 内容 start ====");
        System.out.println(r.text());
        System.out.println("==== 内容 end   ====");
        assertTrue(r.length() > 0);
    }

}
