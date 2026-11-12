package com.wangzan101.mika.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Đường tăng tốc v3.3: phân loại việc, nhận diện «phải hành động», và trần ngân sách lịch sử. */
class QuickChatTest {
    @Test fun phoneActionsArePhoneAction() {
        assertEquals(TaskKind.PhoneAction, TaskClassifier.classify("bật đèn pin giúp a"))
        assertEquals(TaskKind.PhoneAction, TaskClassifier.classify("Mở ứng dụng Telegram"))
        assertEquals(TaskKind.PhoneAction, TaskClassifier.classify("gửi tin nhắn cho mẹ"))
        assertEquals(TaskKind.PhoneAction, TaskClassifier.classify("turn on the flashlight"))
        assertEquals(TaskKind.PhoneAction, TaskClassifier.classify("đặt lịch hẹn lúc 9 giờ mai"))
    }

    @Test fun howToQuestionsStayPlain() {
        assertEquals(TaskKind.Plain, TaskClassifier.classify("làm sao để bật đèn pin trên điện thoại Xiaomi"))
        assertEquals(TaskKind.Plain, TaskClassifier.classify("cách tắt quảng cáo trên Chrome"))
        // …nhưng nhờ làm hộ rõ ràng thì vẫn là việc hành động
        assertEquals(TaskKind.PhoneAction, TaskClassifier.classify("bật đèn pin giúp a với"))
    }

    @Test fun liveDataQuestionsAreLiveInfo() {
        assertEquals(TaskKind.LiveInfo, TaskClassifier.classify("giá BTC hôm nay bao nhiêu"))
        assertEquals(TaskKind.LiveInfo, TaskClassifier.classify("thời tiết Hà Nội mai thế nào"))
        assertEquals(TaskKind.LiveInfo, TaskClassifier.classify("tin tức mới nhất về chứng khoán"))
    }

    @Test fun plainQuestionsArePlain() {
        assertEquals(TaskKind.Plain, TaskClassifier.classify("mấy giờ rồi e"))
        assertEquals(TaskKind.Plain, TaskClassifier.classify("thủ đô của Pháp là gì"))
        assertEquals(TaskKind.Plain, TaskClassifier.classify("dịch câu này sang tiếng Anh: con mèo"))
    }

    @Test fun needsPhoneActionDetectsEscalationPhrases() {
        assertTrue(TaskClassifier.needsPhoneAction("Em cần mở ứng dụng Cài đặt mới bật được đèn pin."))
        assertTrue(TaskClassifier.needsPhoneAction("Em không có dữ liệu thời gian thực để trả lời giá hôm nay."))
        assertTrue(TaskClassifier.needsPhoneAction("I need to search the web for that."))
        assertFalse(TaskClassifier.needsPhoneAction("Thủ đô của Pháp là Paris."))
    }

    @Test fun cappedTurnsDropsOldestFirstAndKeepsNewest() {
        val turns = (1..6).map { Turn("câu $it", "đáp ".repeat(200) + it, "succeeded") }
        val kept = Conversation.cappedTurns(turns, maxTokens = 400)
        assertTrue(kept.size < turns.size)
        assertEquals("câu 6", kept.last().userText)
        assertFalse(kept.any { it.userText == "câu 1" })
        // Dưới ngân sách: giữ nguyên
        assertEquals(turns, Conversation.cappedTurns(turns))
        // Chỉ một lượt dù dài: vẫn giữ (context() sẽ tự cắt khi dựng)
        val one = listOf(Turn("câu", "đáp ".repeat(5_000), "succeeded"))
        assertEquals(one, Conversation.cappedTurns(one, maxTokens = 100))
    }

    @Test fun quickSystemPromptCarriesTimeAndInstructions() {
        val s = QuickChat.systemPrompt("IDENTITY", "PRINCIPLES", "2026-11-11 09:30")
        assertTrue("IDENTITY" in s && "PRINCIPLES" in s && "2026-11-11 09:30" in s)
    }

    @Test fun quickSystemPromptCarriesDeviceInfoWhenGiven() {
        // v3.5-A: có thông tin máy thật thì đường nhanh cũng nói đúng tên máy
        val s = QuickChat.systemPrompt("IDENTITY", "PRINCIPLES", "2026-11-11 09:30", "DEVICE LINE")
        assertTrue("DEVICE LINE" in s)
        // Không có: bộ lệnh giữ nguyên như cũ
        assertFalse("DEVICE LINE" in QuickChat.systemPrompt("IDENTITY", "PRINCIPLES", "2026-11-11 09:30"))
    }

    @Test fun lookupBudgetOnlyCapsLiveInfoLookups() {
        // v3.5-B: trong ngân sách thì chạy tiếp, bước đầu tiên vượt ngân sách mới bị chặn
        assertFalse(QuickChat.lookupBudgetExceeded(TaskKind.LiveInfo, QuickChat.LIVE_INFO_STEP_CAP, toolsEscalated = false))
        assertTrue(QuickChat.lookupBudgetExceeded(TaskKind.LiveInfo, QuickChat.LIVE_INFO_STEP_CAP + 1, toolsEscalated = false))
        // Đã leo thang thành hành động thật lên điện thoại thì thả cap
        assertFalse(QuickChat.lookupBudgetExceeded(TaskKind.LiveInfo, 99, toolsEscalated = true))
        // Việc hành động và câu hỏi thường không bao giờ bị cap kiểu tra cứu
        assertFalse(QuickChat.lookupBudgetExceeded(TaskKind.PhoneAction, 99, toolsEscalated = false))
        assertFalse(QuickChat.lookupBudgetExceeded(TaskKind.Plain, 99, toolsEscalated = false))
    }
}
