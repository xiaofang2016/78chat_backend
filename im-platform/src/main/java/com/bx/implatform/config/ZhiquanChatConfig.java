package com.bx.implatform.config;

import com.bx.implatform.config.props.ZhiquanChatProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.lambda.LambdaClient;

@Configuration
public class ZhiquanChatConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "zhiquan.chat", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DynamoDbClient zhiquanDynamoDbClient(ZhiquanChatProperties properties) {
        return DynamoDbClient.builder()
            .region(Region.of(properties.getRegion()))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "zhiquan.chat", name = "enabled", havingValue = "true", matchIfMissing = true)
    public LambdaClient zhiquanLambdaClient(ZhiquanChatProperties properties) {
        return LambdaClient.builder()
            .region(Region.of(properties.getRegion()))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
    }
}
