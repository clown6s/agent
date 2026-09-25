package com.zzp.agent.client;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Milvus 客户端配置。基于 milvus-sdk-java 2.5.9。
 *
 * <p>连接参数从环境变量注入。database（库名）若指定，则先以默认库连接，
 * 检查目标库是否存在，不存在则创建，再切换到目标库——避免「连一个不存在的库」导致初始化失败。
 */
@Configuration
public class MilvusClient {

    @Value("${milvus.uri}")
    private String uri;

    @Value("${milvus.token}")
    private String token;

    @Value("${milvus.dbName:}")
    private String dbName;

    @Bean
    public MilvusClientV2 milvusClientV2() throws InterruptedException {
        // 1) 以默认库连接（不带 dbName），确保能建立连接
        ConnectConfig config = ConnectConfig.builder()
                .uri(uri)
                .token(token)
                .build();
        MilvusClientV2 client = new MilvusClientV2(config);

        // 2) 若配置了目标库名：检查是否存在，不存在则创建，再切换过去
        if (dbName != null && !dbName.isBlank()) {
            List<String> names = client.listDatabases().getDatabaseNames();
            if (!names.contains(dbName)) {
                client.createDatabase(CreateDatabaseReq.builder()
                        .databaseName(dbName)
                        .build());
            }
            client.useDatabase(dbName);
        }
        return client;
    }
}