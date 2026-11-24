package com.bx.implatform.openai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiSecretValueTest {

    @Test
    void readsOpenAiKeyField() {
        assertEquals("sk-test", OpenAiSecretValue.parse("{\"openAI-KEY\":\"sk-test\"}"));
    }

    @Test
    void readsRawKey() {
        assertEquals("sk-raw", OpenAiSecretValue.parse("  sk-raw  "));
    }

    @Test
    void rejectsJsonWithoutKey() {
        assertThrows(IllegalArgumentException.class,
            () -> OpenAiSecretValue.parse("{\"other\":\"sk-test\"}"));
    }

    @Test
    void rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> OpenAiSecretValue.parse("  "));
    }
}
