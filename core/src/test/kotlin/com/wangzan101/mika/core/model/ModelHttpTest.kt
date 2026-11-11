package com.wangzan101.mika.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ánh xạ lỗi HTTP của ModelHttp, đặc biệt là 413 (v3.3.1: trước đây 413 rơi xuống BadResponse làm FailoverChatModel không chịu chuyển hãng, cả tác vụ chết tại chỗ). */
class ModelHttpTest {

    /** Nguyên văn lỗi Groq free khi gói yêu cầu vượt trần token/phút: phải hiểu là bị giới hạn tần suất (RateLimited) để chuỗi dự phòng tự xoay key/hãng. */
    @Test fun openAi413WithTpmBodyIsRateLimited() {
        val body = """{"error":{"message":"Request too large for model `openai/gpt-oss-20b` in organization `org_01m73` service tier `on_demand` on tokens per minute (TPM): Limit 8000, Requested 8779"}}"""
        val e = ModelHttp.openAiError(413, "30", body, "openai/gpt-oss-20b")
        assertTrue(e is ModelError.RateLimited)
        assertEquals(30_000L, (e as ModelError.RateLimited).retryAfterMs)
    }

    /** 413 không nhắc gì tới giới hạn tần suất: là bản thân gói yêu cầu quá lớn, cùng loại với ngữ cảnh quá dài (ContextTooLong) — cũng là lỗi được chuyển hãng. */
    @Test fun openAi413WithoutRateWordsIsContextTooLong() {
        val e = ModelHttp.openAiError(413, null, """{"error":{"message":"Request Entity Too Large"}}""", "m")
        assertTrue(e is ModelError.ContextTooLong)
        val html = ModelHttp.openAiError(413, null, "<html><body>413 Request Entity Too Large</body></html>", "m")
        assertTrue(html is ModelError.ContextTooLong)
    }

    /** Các ánh xạ cũ giữ nguyên: 429 là giới hạn tần suất, 401 là key sai, 503 lỗi máy chủ, 418 lạ thì BadResponse. */
    @Test fun openAiOtherMappingsUnchanged() {
        assertTrue(ModelHttp.openAiError(429, null, "{}", "m") is ModelError.RateLimited)
        assertTrue(ModelHttp.openAiError(401, null, "{}", "m") is ModelError.Auth)
        assertTrue(ModelHttp.openAiError(402, null, "{}", "m") is ModelError.QuotaExceeded)
        assertTrue(ModelHttp.openAiError(503, null, "{}", "m") is ModelError.Server)
        assertTrue(ModelHttp.openAiError(418, null, "teapot", "m") is ModelError.BadResponse)
    }

    @Test fun says413RateLimitMatchesTpmWording() {
        assertTrue(ModelHttp.says413RateLimit("Limit is 8000 tokens per minute, slow down"))
        assertTrue(ModelHttp.says413RateLimit("TPM exceeded"))
        assertTrue(ModelHttp.says413RateLimit("rate limit reached for this key"))
        assertFalse(ModelHttp.says413RateLimit("Request Entity Too Large"))
        assertFalse(ModelHttp.says413RateLimit(""))
    }

    /** Phía Gemini: 413 vốn đã là ContextTooLong từ trước, giữ nguyên. */
    @Test fun gemini413StaysContextTooLong() {
        val e = ModelHttp.geminiError(413, null, """{"error":{"code":413,"message":"Request payload size exceeds the limit"}}""", "gemini-x")
        assertTrue(e is ModelError.ContextTooLong)
    }
}
