package com.bx.implatform.config;

import com.bx.implatform.config.props.OpenAIProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
@RequiredArgsConstructor
public class OpenAIConfig {

    private final OpenAIProperties props;

    @Bean
    public RestTemplate openAiRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getTimeoutMs());
        factory.setReadTimeout(props.getTimeoutMs());
        return new RestTemplate(factory);
    }
}

