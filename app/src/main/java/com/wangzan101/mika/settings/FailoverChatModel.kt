package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Chuỗi dự phòng nhiều nhà cung cấp (bản Phòng AI): gọi lần lượt từng mô hình trong [links];
 * khi một nhà sập vì hết hạn mức / quá tải / lỗi máy chủ / mất mạng / key chết / model không còn
 * (xem [isFailoverWorthy]) thì tự chuyển sang nhà kế tiếp ngay trong cùng một lần gọi, thay vì để
 * cả tác vụ chết theo. Nhà nào vừa sập sẽ bị bỏ qua một lúc (bảng [deadUntilMs]) để các bước sau
 * của tác vụ không tốn thời gian gõ lại cánh cửa đã khóa. Lỗi thuộc về nội dung/yêu cầu
 * (mô hình từ chối nội dung...) thì ném ra ngay — nhà khác cũng không cứu được.
 * Với đường truyền dần (streaming): chỉ được chuyển nhà khi CHƯA có chữ nào tới người dùng;
 * đã hiện chữ rồi mà đứt thì ném lỗi ra, tránh in trùng hai câu trả lời.
 *
 * Phòng AI v3.5 thêm ba cơ chế (xem KeyStatus.kt về các bảng dùng chung):
 * - [linkGates]: cổng theo trạng thái key ĐÃ LƯU — mắt xích biết là key chết ([LinkGate.Dead]) bị bỏ qua hẳn,
 *   mắt xích đang cooldown ([LinkGate.Cooling]) chỉ thử sau khi mọi mắt xích sẵn sàng đã trượt;
 *   nhờ vậy lần thử ĐẦU TIÊN của một tác vụ rơi vào key đang sống nếu có key sống.
 * - Đồng hồ key ì ([callTimeoutMs]): một lần gọi quá lâu không có hồi âm (streaming: không có thêm chữ)
 *   thì cắt, tính là lỗi hạ tầng (cooldown ngắn) rồi chuyển mắt xích khác.
 * - [neverStop] (chỉ chuỗi mô hình chính): mọi mắt xích đều trượt vì lỗi TẠM THỜI (giới hạn theo phút,
 *   máy chủ, mạng, đứt truyền) thì KHÔNG ném lỗi dừng tác vụ — chờ mắt xích sớm nhất mở lại cooldown
 *   rồi vòng lại TỪ MẮT XÍCH ĐẦU. Chỉ dừng khi mọi key đều chết vì xác thực ([ModelError.AllKeysDead])
 *   hoặc mọi key còn lại đều hết lượt ngày ([ModelError.AllDailyQuotaExhausted]).
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
    /** Cổng từng mắt xích theo trạng thái key đã lưu (v3.5, xem [LinkGate]); thiếu thì mọi mắt xích đều [LinkGate.Ready]. */
    private val linkGates: List<LinkGate> = emptyList(),
    /** Chuỗi mô hình chính: lỗi tạm thời ở mọi mắt xích thì chờ cooldown rồi vòng lại, không dừng (v3.5). */
    private val neverStop: Boolean = false,
    /** Sắp phải chờ cooldown lâu trước vòng thử tiếp theo (v3.5): báo ra ngoài để giao diện nói rõ là đang chờ, không phải treo. */
    private val onWait: (Long) -> Unit = {},
    /** Một lần gọi tối đa bao lâu không có hồi âm thì coi là key ì và cắt (v3.5). */
    private val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    /** Chỗ chờ cooldown khi vòng lại (v3.5); tách ra để test đi theo đồng hồ giả. */
    private val delayer: suspend (Long) -> Unit = { delay(it) },
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

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        execute { i ->
            linkIds.getOrNull(i)?.let(onAttempt)
            try {
                AttemptResult.Ok(withIdleTimeout { links[i].complete(system, messages, tools) })
            } catch (e: IdleTimeoutException) {
                // Key ì: quá lâu không có hồi âm → tính là lỗi hạ tầng (cooldown ngắn), chuyển mắt xích khác.
                AttemptResult.Fail(ModelError.Network(), fatal = false)
            } catch (e: ModelError) {
                AttemptResult.Fail(e, fatal = !e.isFailoverWorthy())
            }
        }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply = execute { i ->
        val link = links[i]
        var emitted = false
        linkIds.getOrNull(i)?.let(onAttempt)
        try {
            val reply = withIdleTimeout { bump ->
                if (link is StreamingChatModel) {
                    link.completeStreaming(system, messages, tools) { text ->
                        emitted = true
                        bump()
                        onText(text)
                    }
                } else {
                    val r = link.complete(system, messages, tools)
                    r.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
                        .takeIf { it.isNotEmpty() }?.let { text ->
                            emitted = true
                            bump()
                            onText(text)
                        }
                    r
                }
            }
            AttemptResult.Ok(reply)
        } catch (e: IdleTimeoutException) {
            // Đang truyền mà đứng im quá lâu = đứt truyền; chưa ra chữ nào = key ì, chuyển mắt xích khác được.
            val err = if (emitted) ModelError.StreamCut() else ModelError.Network()
            AttemptResult.Fail(err, fatal = emitted)
        } catch (e: ModelError) {
            AttemptResult.Fail(e, fatal = emitted || !e.isFailoverWorthy())
        }
    }

    /** Kết quả một lần thử một mắt xích: [Ok] mang hồi âm về; [Fail.fatal] = ném ra ngay (lỗi không đáng chuyển nhà, hoặc streaming đã phát chữ). */
    private sealed interface AttemptResult {
        data class Ok(val reply: ModelReply) : AttemptResult
        data class Fail(val error: ModelError, val fatal: Boolean) : AttemptResult
    }

    /** Kết luận khi mọi mắt xích đều đã trượt trong lần gọi này (chế độ [neverStop]). */
    private enum class Aggregate { Temporary, AllAuth, AllDaily, Other }

    /**
     * Vòng chạy của chuỗi: từng vòng thử các mắt xích chưa chết hẳn (sẵn sàng trước, đang cooldown sau).
     * Chế độ cũ ([neverStop] = false): đúng một vòng rồi ném lỗi cuối như trước nay.
     * Chế độ [neverStop]: vòng lại sau khi chờ cooldown nếu vẫn còn mắt xích chỉ bị chặn tạm thời;
     * chỉ ném lỗi khi mọi key đều chết / hết lượt ngày / lỗi vĩnh viễn khác.
     */
    private suspend fun execute(attempt: suspend (Int) -> AttemptResult): ModelReply {
        val lastErrors = arrayOfNulls<ModelError>(links.size)
        var last: ModelError? = null
        while (true) {
            val order = passOrder()
            // Mọi mắt xích đều đã biết là key chết (cổng Dead từ trạng thái đã lưu): khỏi thử, dừng ngay với tín hiệu rõ ràng.
            if (order.isEmpty()) throw ModelError.AllKeysDead()
            val now = nowMs()
            val due = order.filter { now >= deadUntilMs[it] }
            if (due.isEmpty() && neverStop) {
                // Mắt xích nào cũng đang trong cooldown của chính lần gọi này: chờ mắt xích sớm nhất mở lại rồi vòng tiếp.
                awaitCooldown(order.minOf { deadUntilMs[it] } - now)
                continue
            }
            val pass = if (neverStop) due else due.ifEmpty { order }
            for (i in pass) {
                when (val r = attempt(i)) {
                    is AttemptResult.Ok -> {
                        onServedLink(i)
                        return r.reply
                    }
                    is AttemptResult.Fail -> {
                        if (r.fatal) throw r.error
                        last = r.error
                        lastErrors[i] = r.error
                        onFailedLink(i, r.error)
                    }
                }
            }
            if (!neverStop) throw last ?: ModelError.BadResponse("Tất cả các nhà cung cấp đều đang không dùng được")
            when (aggregate(lastErrors)) {
                Aggregate.Temporary -> {
                    val earliest = links.indices
                        .filter { lastErrors[it]?.let { e -> failureClassOf(e) } == FailureClass.Temporary }
                        .minOf { deadUntilMs[it] }
                    awaitCooldown(earliest - nowMs())
                }
                Aggregate.AllAuth -> throw ModelError.AllKeysDead()
                Aggregate.AllDaily -> throw ModelError.AllDailyQuotaExhausted()
                Aggregate.Other -> throw last ?: ModelError.BadResponse("Tất cả các nhà cung cấp đều đang không dùng được")
            }
        }
    }

    /**
     * Phân loại tổng hợp các lỗi đã gặp trong lần gọi này (mắt xích cổng [LinkGate.Dead] tính là chết vì xác thực):
     * còn mắt xích nào chỉ bị chặn tạm thời → [Aggregate.Temporary]; mọi mắt xích đều chết vì xác thực → [Aggregate.AllAuth];
     * mắt xích còn lại đều hết lượt ngày THẬT của nhà cung cấp → [Aggregate.AllDaily] (chỉ hết ngân sách Token
     * nội bộ theo ngày — DailyBudgetExceeded — thì giữ lỗi gốc như cũ, xem ProviderStore.checkDailyBudget).
     */
    private fun aggregate(lastErrors: Array<ModelError?>): Aggregate {
        var temporary = false
        var auth = false
        var daily = false
        var realDaily = false
        var other = false
        for (i in links.indices) {
            val cls = when {
                gateOf(i) == LinkGate.Dead -> FailureClass.AuthDead
                lastErrors[i] != null -> failureClassOf(lastErrors[i]!!)
                else -> FailureClass.OtherPermanent // chưa từng thử trong lần gọi này: không suy ra được gì, chặn tín hiệu dừng «tất cả»
            }
            when (cls) {
                FailureClass.Temporary -> temporary = true
                FailureClass.AuthDead -> auth = true
                FailureClass.DailyUsed -> {
                    daily = true
                    val e = lastErrors[i]
                    if (e is ModelError.QuotaExceeded || e is ModelError.ChatGptLimitReached) realDaily = true
                }
                FailureClass.OtherPermanent -> other = true
            }
        }
        return when {
            temporary -> Aggregate.Temporary
            other -> Aggregate.Other
            daily && realDaily -> Aggregate.AllDaily
            daily -> Aggregate.Other
            else -> Aggregate.AllAuth
        }
    }

    /** Chờ trước vòng thử tiếp theo: đúng hạn cooldown thật nhưng kẹp trong [MIN_WAIT_MS]..[MAX_WAIT_MS] để không quay vòng rỗng cũng không treo vô hạn vì một Retry-After lạ. */
    private suspend fun awaitCooldown(rawWaitMs: Long) {
        val wait = rawWaitMs.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
        if (wait >= WAIT_NOTE_MIN_MS) onWait(wait)
        delayer(wait)
    }

    private fun gateOf(i: Int): LinkGate = linkGates.getOrNull(i) ?: LinkGate.Ready

    /**
     * Thứ tự thử trong một vòng: mắt xích sẵn sàng (không cổng [LinkGate.Cooling], không cooldown nội bộ) trước,
     * đang cooldown sau; mắt xích [LinkGate.Dead] bị loại hẳn. Thứ tự gốc được giữ trong từng nhóm.
     */
    private fun passOrder(): List<Int> {
        val now = nowMs()
        val ready = mutableListOf<Int>()
        val cooling = mutableListOf<Int>()
        for (i in links.indices) {
            if (gateOf(i) == LinkGate.Dead) continue
            if (gateOf(i) == LinkGate.Ready && now >= deadUntilMs[i]) ready += i else cooling += i
        }
        return ready + cooling
    }

    /** Tín hiệu «quá lâu không có hồi âm» của chó canh; phân biệt với hủy từ bên ngoài bằng cờ riêng, không lộ ra ngoài lớp này. */
    private class IdleTimeoutException : Exception()

    /**
     * Chạy [block] với một «chó canh»: quá [callTimeoutMs] kể từ hoạt động cuối cùng mà block chưa xong
     * thì hủy block và ném [IdleTimeoutException]. Hoạt động = block bắt đầu, và với streaming là mỗi lần
     * có chữ mới (block gọi [bump]). Thời gian ì đo bằng chính đồng hồ [nowMs] của chuỗi (cùng đồng hồ
     * với cooldown) nên ở máy thật nó là thời gian thật, còn test thời gian ảo lái được bằng đồng hồ giả.
     * Hủy từ bên ngoài (người dùng dừng tác vụ) vẫn ném CancellationException như thường.
     */
    private suspend fun <T> withIdleTimeout(block: suspend (bump: () -> Unit) -> T): T {
        val lastActivityMs = AtomicLong(nowMs())
        val timedOut = AtomicBoolean(false)
        return coroutineScope {
            val work = async { block { lastActivityMs.set(nowMs()) } }
            val dog = async {
                while (work.isActive) {
                    delay(WATCH_POLL_MS)
                    if (!work.isActive) break
                    if (nowMs() - lastActivityMs.get() >= callTimeoutMs) {
                        timedOut.set(true)
                        work.cancel()
                        break
                    }
                }
            }
            try {
                work.await()
            } catch (e: CancellationException) {
                if (timedOut.get()) throw IdleTimeoutException()
                throw e
            } finally {
                dog.cancel()
            }
        }
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

    /** Đánh dấu mắt xích [index] là "sập" một lúc theo loại lỗi (bảng [keyCooldownMs]); trả về thời điểm nó được thử lại. */
    private fun markDead(index: Int, e: ModelError): Long {
        val until = nowMs() + keyCooldownMs(e)
        deadUntilMs[index] = until
        return until
    }

    companion object {
        /** Một lần gọi tối đa bao lâu không có hồi âm thì coi là key ì; không đặt thấp hơn — model xịn suy luận sâu có khi trả lời lâu thật. */
        const val DEFAULT_CALL_TIMEOUT_MS = 60_000L
        /** Mỗi lần chờ cooldown khi vòng lại: tối thiểu để không quay vòng rỗng, tối đa để một Retry-After lạ không treo tác vụ cả giờ. */
        const val MIN_WAIT_MS = 1_000L
        const val MAX_WAIT_MS = 600_000L
        /** Chờ từ mức này mới báo ra giao diện (khớp ngưỡng ModelCaller.RETRY_WAIT_NOTE_MIN_MS ở :core). */
        const val WAIT_NOTE_MIN_MS = 10_000L
        /** Nhịp chó canh key ì kiểm tra hoạt động. */
        const val WATCH_POLL_MS = 250L
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
