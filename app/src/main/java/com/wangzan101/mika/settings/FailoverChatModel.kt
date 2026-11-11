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
    /**
     * Danh tính từng mắt xích (hãng + số thứ tự key, xem [ServingLink]) để báo trạng thái key (Phòng AI v3.4);
     * không truyền thì không báo gì, hành vi y như cũ. Các [onAttempt]/[onServed]/[onFailed]/[onSwitch]
     * chỉ là chỗ BÁO sự kiện ra ngoài (ProviderStore ghi lại, hiện lên màn hình); logic chuyển nhà không đổi.
     */
    private val linkIds: List<ServingLink> = emptyList(),
    /** Một mắt xích bắt đầu được gọi dùng. */
    private val onAttempt: (ServingLink) -> Unit = {},
    /** Một mắt xích vừa chạy ngon một lần gọi. */
    private val onServed: (ServingLink) -> Unit = {},
    /** Một mắt xích vừa sập vì lỗi đáng chuyển nhà; kèm thời điểm nó hết bị bỏ qua (cooldown). */
    private val onFailed: (ServingLink, ModelError, Long) -> Unit = { _, _, _ -> },
    /** Vừa chuyển từ một mắt xích sang mắt xích KHÁC vì lỗi hết lượt / key chết; lần chọn đầu tiên của một lượt không tính. */
    private val onSwitch: (ServingLink, ServingLink, ModelError) -> Unit = { _, _, _ -> },
) : StreamingChatModel {
    init {
        require(links.isNotEmpty()) { "Chuỗi dự phòng cần ít nhất một mô hình" }
    }

    override val capabilities: Capabilities get() = links.first().capabilities
    override val name: String get() = links.first().name

    private val deadUntilMs = LongArray(links.size)

    /** Mắt xích vừa sập vì lỗi đáng thông báo (hết lượt / key chết) và lỗi đó; chờ mắt xích khác chạy ngon thì báo [onSwitch]. */
    private var pendingSwitchFrom = -1
    private var pendingSwitchError: ModelError? = null

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        var last: ModelError? = null
        for ((i, link) in candidates()) {
            linkIds.getOrNull(i)?.let(onAttempt)
            try {
                val reply = link.complete(system, messages, tools)
                onServedLink(i)
                return reply
            } catch (e: ModelError) {
                if (!e.isFailoverWorthy()) throw e
                last = e
                onFailedLink(i, e)
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
            linkIds.getOrNull(i)?.let(onAttempt)
            try {
                val reply = if (link is StreamingChatModel) {
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
                onServedLink(i)
                return reply
            } catch (e: ModelError) {
                if (emitted || !e.isFailoverWorthy()) throw e
                last = e
                onFailedLink(i, e)
            }
        }
        throw last ?: ModelError.BadResponse("Tất cả các nhà cung cấp đều đang không dùng được")
    }

    /** Mắt xích [i] vừa chạy ngon: báo [onServed], và nếu trước đó có mắt xích KHÁC sập vì hết lượt / key chết thì báo [onSwitch]. */
    private fun onServedLink(i: Int) {
        linkIds.getOrNull(i)?.let(onServed)
        val from = pendingSwitchFrom
        val error = pendingSwitchError
        pendingSwitchFrom = -1
        pendingSwitchError = null
        if (from < 0 || from == i || error == null) return
        val f = linkIds.getOrNull(from) ?: return
        val t = linkIds.getOrNull(i) ?: return
        onSwitch(f, t, error)
    }

    /** Mắt xích [i] vừa sập: đánh dấu bỏ qua một lúc, báo [onFailed]; lỗi thuộc nhóm hết lượt / key chết thì nhớ lại chờ báo chuyển nhà. */
    private fun onFailedLink(i: Int, e: ModelError) {
        val until = markDead(i, e)
        linkIds.getOrNull(i)?.let { onFailed(it, e, until) }
        when (keyErrorKind(e)) {
            "RateLimited", "QuotaExceeded", "DailyBudgetExceeded", "ChatGptLimitReached", "Auth", "SignInExpired" -> {
                pendingSwitchFrom = i
                pendingSwitchError = e
            }
        }
    }

    /** Các mắt xích chưa bị đánh dấu sập; nếu tất cả đều đang bị bỏ qua thì thử lại tất cả (thà thử nhà "sập" còn hơn bó tay). */
    private fun candidates(): List<IndexedValue<ChatModel>> {
        val now = nowMs()
        val alive = links.withIndex().filter { now >= deadUntilMs[it.index] }
        return alive.ifEmpty { links.withIndex().toList() }
    }

    /** Đánh dấu mắt xích [index] là "sập" một lúc theo loại lỗi; trả về thời điểm nó được thử lại. */
    private fun markDead(index: Int, e: ModelError): Long {
        val ms = when (e) {
            is ModelError.RateLimited -> e.retryAfterMs ?: 60_000L
            is ModelError.QuotaExceeded, is ModelError.DailyBudgetExceeded, is ModelError.ChatGptLimitReached,
            is ModelError.Auth, is ModelError.SignInExpired, is ModelError.ModelNotFound, is ModelError.ModelNotAvailable,
            is ModelError.ToolsUnsupported, is ModelError.ReasoningEffortRejected, is ModelError.ContextTooLong -> 600_000L
            else -> 30_000L // Server, Network, StreamCut và các lỗi hạ tầng khác: thử lại sau nửa phút
        }
        val until = nowMs() + ms
        deadUntilMs[index] = until
        return until
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
