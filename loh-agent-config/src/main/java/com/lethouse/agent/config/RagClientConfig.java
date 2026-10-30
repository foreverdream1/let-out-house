package com.lethouse.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RagClientConfig {
    @Bean
    RestClient ragRestClient(@Value("${loh.rag.base-url}") String base){
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(300_000);
        return RestClient.builder().baseUrl(base).requestFactory(f).build();
    }
}
