package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Part G (v3.3.1): phải chờ lâu trước lần thử lại (hết lượt) thì dòng «đang làm gì» của trạng thái
 * Running phải nói rõ là đang chờ nhà cung cấp — trước đây chỉ có sự kiện model_retry trong nhật ký
 * chạy (giao diện không hề hiện), người dùng nhìn màn hình đứng yên tưởng app treo.
 * Chờ ngắn thì không báo (chớp mắt là qua).
 */
class RetryWaitNoteTest {

    private fun loop(model: FakeModel) = AgentLoop(model, toolDispatcher(FakeScreen()), "BASE")

    @Test fun longRetryWaitShowsWaitingNote() = runTest {
        val model = FakeModel(ModelError.RateLimited(30_000), answerReply("done"))
        val loop = loop(model)
        val narrations = mutableListOf<String?>()
        backgroundScope.launch { loop.state.collect { if (it is TaskState.Running) narrations += it.narration } }
        val r = async { loop.run("mấy giờ rồi") }.await()
        assertTrue(r is TaskState.Succeeded)
        assertTrue(narrations.any { it == AgentLoop.RETRY_WAIT_NOTE })
    }

    @Test fun shortRetryWaitStaysQuiet() = runTest {
        val model = FakeModel(ModelError.RateLimited(2_000), answerReply("done"))
        val loop = loop(model)
        val narrations = mutableListOf<String?>()
        backgroundScope.launch { loop.state.collect { if (it is TaskState.Running) narrations += it.narration } }
        val r = async { loop.run("mấy giờ rồi") }.await()
        assertTrue(r is TaskState.Succeeded)
        assertFalse(narrations.any { it == AgentLoop.RETRY_WAIT_NOTE })
    }
}
