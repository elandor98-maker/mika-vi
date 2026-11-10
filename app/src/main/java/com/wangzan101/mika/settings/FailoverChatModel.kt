package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec

/**
 * Chuỗi dự phòng nhiều nhà cung cấp (bản Phòng AI): gọi lần lượt từng mô hình trong [links];
 * khi một nhà sập vì hết hạn mức / quá tải / lỗi máy chủ / mất mạng / key chết / model không còn
 * (xem [isFailoverWorthy]) thì tự chuyển sang nhà kế tiếp ngay trong cùng một lần gọi, thay vì để
 * cả tác vụ chết theo. Nhà nào vừa sập sẽ bị bỏ qua một lúc (bảng [deadUntilMs]) để các bước sau
 * của tác vụ không tốn thời gian gõ lại cánh cửa đã khóa. Lỗi thuộc về nội dung/yêu cầu
 * (mô hình từ chối nội dung...) thì ném ra ngay — nhà khác cũng không cứu được.
 * Với đường truyền dần (streaming): chỉ được chuyển nhà khi CHƯA có chữ nào tới người dùng;
 * đã hiện chữ rồi mà đứt thì ném lỗi ra, tránh in trùng hai câu trả lời.
 */
class FailoverChatModel(
    private val links: List<ChatModel>,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : StreamingChatModel {
    init {
        require(links.isNotEmpty()) { "Chuỗi dự phòng cần ít nhất một mô hình" }
    }

    override val capabilities: Capabilities get() = links.first().capabilities
    override val name: String get() = links.first().name

    private val deadUntilMs = LongArray(links.size)

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        var last: ModelError? = null
        for ((i, link) in candidates()) {
            try {
                return link.complete(system, messages, tools)
            } catch (e: ModelError) {
                if (!e.isFailoverWorthy()) throw e
                last = e
                markDead(i, e)
            }
        }
        throw last ?: ModelError.BadResponse("Tất cả các nhà cung cấp đều đang không dùng được")
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        var last: ModelError? = null
        for ((i, link) in candidates()) {
            var emitted = false
            try {
                return if (link is StreamingChatModel) {
                    link.completeStreaming(system, messages, tools) { text ->
                        emitted = true
                        onText(text)
                    }
                } else {
                    val reply = link.complete(system, messages, tools)
                    reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
                        .takeIf { it.isNotEmpty() }?.let {
                            emitted = true
                            onText(it)
                        }
                    reply
                }
            } catch (e: ModelError) {
                if (emitted || !e.isFailoverWorthy()) throw e
                last = e
                markDead(i, e)
            }
        }
        throw last ?: ModelError.BadResponse("Tất cả các nhà cung cấp đều đang không dùng được")
    }

    /** Các mắt xích chưa bị đánh dấu sập; nếu tất cả đều đang bị bỏ qua thì thử lại tất cả (thà thử nhà "sập" còn hơn bó tay). */
    private fun candidates(): List<IndexedValue<ChatModel>> {
        val now = nowMs()
        val alive = links.withIndex().filter { now >= deadUntilMs[it.index] }
        return alive.ifEmpty { links.withIndex().toList() }
    }

    private fun markDead(index: Int, e: ModelError) {
        val ms = when (e) {
            is ModelError.RateLimited -> e.retryAfterMs ?: 60_000L
            is ModelError.QuotaExceeded, is ModelError.DailyBudgetExceeded, is ModelError.ChatGptLimitReached,
            is ModelError.Auth, is ModelError.SignInExpired, is ModelError.ModelNotFound, is ModelError.ModelNotAvailable,
            is ModelError.ToolsUnsupported, is ModelError.ReasoningEffortRejected, is ModelError.ContextTooLong -> 600_000L
            else -> 30_000L // Server, Network, StreamCut và các lỗi hạ tầng khác: thử lại sau nửa phút
        }
        deadUntilMs[index] = nowMs() + ms
    }
}

/** Lỗi "nhà này đang không dùng được" — nhà kế tiếp trong chuỗi có thể cứu được. */
private fun ModelError.isFailoverWorthy(): Boolean = when (this) {
    is ModelError.QuotaExceeded, is ModelError.RateLimited, is ModelError.DailyBudgetExceeded,
    is ModelError.ChatGptLimitReached, is ModelError.Network, is ModelError.StreamCut,
    is ModelError.Auth, is ModelError.SignInExpired, is ModelError.ModelNotFound,
    is ModelError.ModelNotAvailable, is ModelError.ToolsUnsupported,
    is ModelError.ReasoningEffortRejected, is ModelError.ContextTooLong -> true
    is ModelError.Server -> status == 429 || status >= 500
    else -> false
}
