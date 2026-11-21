package com.bx.implatform.config;

import com.bx.implatform.config.props.AiCustomerServiceProperties;
import com.bx.implatform.config.props.AiKnowledgeBaseProperties;
import com.bx.implatform.config.props.OpenAIProperties;
import com.bx.implatform.openai.OpenAiAuthMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class AiCsConfigStartupLogger implements ApplicationRunner {

    private final AiCustomerServiceProperties aiCsProps;
    private final AiKnowledgeBaseProperties kbProps;
    private final OpenAIProperties openAIProperties;

    @Override
    public void run(ApplicationArguments args) {
        OpenAiAuthMode authMode = OpenAiAuthMode.fromConfig(openAIProperties.getAuthMode());
        String codexModel = openAIProperties.getCodexModel();
        if (codexModel == null || codexModel.isBlank()) {
            codexModel = openAIProperties.getModel();
        }
        log.info(
            "[ai-cs] startup enabled={} kb.enabled={} kb.bots={} openai.auth-mode={} openai.codex-model={}",
            aiCsProps.isEnabled(),
            kbProps.isEnabled(),
            kbProps.getBotUserIds(),
            authMode,
            codexModel);
    }
}
