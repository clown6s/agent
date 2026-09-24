package com.zzp.agent.client;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.transport.TransportUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.net.URI;
import java.util.List;

@Configuration
public class EsClient {

    @Value("${elaasticsearch.url}")
    private String url;

    @Value("${elaasticsearch.userName}")
    private String username;

    @Value("${elaasticsearch.password}")
    private String password;

    @Value("${elaasticsearch.caCert}")
    private String caCert;

    @Bean
    public ElasticsearchAsyncClient elasticsearchClient() throws Exception {

        List<URI> hosts = List.of(URI.create(url));

        File certFile = new File(caCert);

        SSLContext ssl = TransportUtils
                .sslContextFromHttpCaCrt(certFile);

        return ElasticsearchAsyncClient.of(b -> b
                .hosts(hosts)
                .sslContext(ssl)
                .usernameAndPassword(username, password));
    }
}