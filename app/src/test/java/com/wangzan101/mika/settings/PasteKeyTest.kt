package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ô dán key v3.3: nhận diện hãng từ tiền tố key. */
class PasteKeyTest {
    @Test fun detectsProvidersByPrefix() {
        assertEquals("Groq", PasteKey.presetFor("gsk_abc123")?.label)
        assertEquals("OpenRouter", PasteKey.presetFor("sk-or-v1-abc")?.label)
        assertEquals("Gemini", PasteKey.presetFor("AIzaSyAbc")?.label)
        assertEquals("Cerebras", PasteKey.presetFor("csk-abc123")?.label)
        assertEquals("OpenAI", PasteKey.presetFor("sk-proj-abc")?.label)
    }

    @Test fun longerPrefixesWinOverGenericSk() {
        // sk-or- phải thắng sk- chung; csk- không được rơi vào sk-
        assertEquals(ProviderConfig.Kind.OpenAiCompat, PasteKey.presetFor("sk-or-v1-x")?.kind)
        assertEquals("https://openrouter.ai/api/v1", PasteKey.presetFor("sk-or-v1-x")?.baseUrl)
        assertEquals("https://api.cerebras.ai/v1", PasteKey.presetFor("csk-x")?.baseUrl)
    }

    @Test fun unknownPrefixesAreNotGuessed() {
        assertNull(PasteKey.presetFor("sk-ant-api03-abc"))
        assertNull(PasteKey.presetFor("xai-abc"))
        assertNull(PasteKey.presetFor(""))
        assertNull(PasteKey.presetFor("   "))
        assertNull(PasteKey.presetFor("hello"))
    }

    @Test fun detectedPresetsUseTheRightKinds() {
        assertEquals(ProviderConfig.Kind.Gemini, PasteKey.presetFor("AIzaX")?.kind)
        assertEquals(ProviderConfig.Kind.OpenAiResponses, PasteKey.presetFor("sk-x")?.kind)
        assertEquals(ProviderConfig.Kind.OpenAiCompat, PasteKey.presetFor("gsk_x")?.kind)
    }
}
