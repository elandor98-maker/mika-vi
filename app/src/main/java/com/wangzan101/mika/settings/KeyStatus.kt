package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.ModelError

/**
 * Phòng AI v3.4 — cho người dùng NHÌN THẤY key: đang chạy hãng nào, key số mấy của hãng đó,
 * key nào hết lượt / chết. Mọi thứ ở đây đều là logic thuần (không Android, không IO) để test được;
 * phần ghi nhận lúc chạy nằm ở [ProviderStore] (lưu xuống đĩa) và [FailoverChatModel] (báo sự kiện).
 *
 * Key chỉ được nhận diện bằng SỐ THỨ TỰ trong hãng (1-based, đúng thứ tự đã lưu = thứ tự xoay vòng
 * của [ProviderStore.modelFor]); không nơi nào trong tính năng này ghi hay in ra giá trị key.
 */

/** Một mắt xích trong chuỗi dự phòng, nhìn từ phía người dùng: hãng nào, key số mấy, model nào. */
data class ServingLink(
    val providerId: String,
    /** Tên hiển thị của hãng, ví dụ "Gemini", "Groq". */
    val providerName: String,
    /** Số thứ tự của key trong hãng (1-based); 0 = mắt xích này không dùng key (hãng không cần key). */
    val keyIndex: Int,
    /** Hãng có tổng cộng bao nhiêu key đã lưu; 0 = không dùng key. */
    val keyCount: Int,
    val model: String,
) {
    /** "Gemini · key 3/10" — dùng trong thông báo chuyển key (vế "đã chuyển sang"). */
    fun shortText(): String =
        if (keyCount > 0 && keyIndex > 0) "$providerName · key $keyIndex/$keyCount" else providerName

    /** "Gemini · key 3/10 · gemini-3.8-flash" — dòng trạng thái trên màn hình trò chuyện. */
    fun statusText(): String =
        if (keyCount > 0 && keyIndex > 0) "$providerName · key $keyIndex/$keyCount · $model" else "$providerName · $model"

    /** "Gemini key 2" — vế "key vừa sập" trong thông báo chuyển key (không kèm /tổng số, theo đúng mẫu người dùng chốt). */
    fun fromText(): String =
        if (keyCount > 0 && keyIndex > 0) "$providerName key $keyIndex" else providerName
}

/** Trạng thái ghi nhận của MỘT key (theo số thứ tự trong hãng); lưu xuống đĩa cạnh file key. */
data class KeyLinkStatus(
    /** Lần gần nhất key này được gọi dùng (epoch ms); 0 = chưa từng. */
    val lastUsedAtMs: Long = 0L,
    /** Loại lỗi gần nhất (xem [keyErrorKind]); null = lần gần nhất chạy ngon / chưa lỗi. */
    val lastErrorKind: String? = null,
    /** Thời điểm lỗi gần nhất (epoch ms). */
    val lastErrorAtMs: Long = 0L,
    /** Bị bỏ qua (cooldown) đến thời điểm này (epoch ms); 0 = không bị bỏ qua. */
    val cooldownUntilMs: Long = 0L,
)

/** Tên ngắn của một lỗi mô hình để lưu vào [KeyLinkStatus.lastErrorKind]; lỗi lạ gom vào "Other". */
fun keyErrorKind(e: ModelError): String = when (e) {
    is ModelError.RateLimited -> "RateLimited"
    is ModelError.QuotaExceeded -> "QuotaExceeded"
    is ModelError.DailyBudgetExceeded -> "DailyBudgetExceeded"
    is ModelError.ChatGptLimitReached -> "ChatGptLimitReached"
    is ModelError.Auth -> "Auth"
    is ModelError.SignInExpired -> "SignInExpired"
    is ModelError.ModelNotFound -> "ModelNotFound"
    is ModelError.ModelNotAvailable -> "ModelNotAvailable"
    is ModelError.ContextTooLong -> "ContextTooLong"
    is ModelError.Server -> "Server"
    is ModelError.Network -> "Network"
    is ModelError.StreamCut -> "StreamCut"
    else -> "Other"
}

/** Các lỗi tính là "hết lượt theo ngày": mở lại khi hạn mức ngày reset (~15:00 giờ Việt Nam). */
private val DAILY_KINDS = setOf("QuotaExceeded", "DailyBudgetExceeded", "ChatGptLimitReached")

/**
 * Câu thông báo khi chuỗi dự phòng chuyển mắt xích GIỮA một lượt chạy vì key hết lượt / key chết.
 * Luôn có cả TÊN HÃNG lẫn SỐ THỨ TỰ KEY ở hai vế (người dùng chốt 2026-11-12 00:00), ví dụ:
 * "Gemini key 2 hết lượt → đã chuyển sang Groq · key 1/9".
 * Trả về null khi không cần thông báo: lỗi không thuộc hai nhóm trên, hoặc "chuyển" về đúng key cũ.
 */
fun failoverNoticeText(from: ServingLink, to: ServingLink, error: ModelError): String? {
    if (from.providerId == to.providerId && from.keyIndex == to.keyIndex) return null
    return when (keyErrorKind(error)) {
        "RateLimited", "QuotaExceeded", "DailyBudgetExceeded", "ChatGptLimitReached" ->
            "${from.fromText()} hết lượt → đã chuyển sang ${to.shortText()}"
        "Auth", "SignInExpired" ->
            "${from.fromText()} lỗi xác thực (key chết) → đã bỏ qua, chuyển sang ${to.shortText()}"
        else -> null
    }
}

/** Bốn trạng thái hiển thị của một key trong Cài đặt. */
enum class KeyChip { Serving, Ready, Cooldown, AuthError }

/** Chip đã dịch sẵn sang tiếng Việt: [label] là chữ trên chip, [hint] là dòng gợi ý nhỏ kèm theo (nếu có). */
data class KeyChipUi(val chip: KeyChip, val label: String, val hint: String? = null)

/**
 * Ánh xạ thuần: trạng thái đã ghi của một key + có phải key đang chạy + thời gian hiện tại → chip.
 * Key đang chạy thắng mọi thứ; key chết (lỗi xác thực) đứng trên "hết lượt" vì thay key mới là việc cần làm.
 */
fun keyChipUi(status: KeyLinkStatus?, isServing: Boolean, nowMs: Long): KeyChipUi {
    if (isServing) return KeyChipUi(KeyChip.Serving, "Đang dùng")
    val s = status ?: return KeyChipUi(KeyChip.Ready, "Sẵn sàng")
    if (s.lastErrorKind == "Auth" || s.lastErrorKind == "SignInExpired") {
        return KeyChipUi(KeyChip.AuthError, "Lỗi xác thực", "key chết — hãy thay key mới")
    }
    if (s.cooldownUntilMs > nowMs && s.lastErrorKind != null) {
        val hint = if (s.lastErrorKind in DAILY_KINDS) {
            "mở lại ~15:00"
        } else {
            val remainMs = s.cooldownUntilMs - nowMs
            if (remainMs <= 90_000L) "mở lại sau ~1 phút"
            else "mở lại sau ~${(remainMs + 59_999L) / 60_000L} phút"
        }
        return KeyChipUi(KeyChip.Cooldown, "Hết lượt", hint)
    }
    return KeyChipUi(KeyChip.Ready, "Sẵn sàng")
}

/** Đuôi key để hiển thị (chỉ 4 ký tự cuối); key ngắn hơn thì hiện nguyên nhưng vẫn có dấu "…" báo là bản che. */
fun keyTail(key: String): String = "…" + key.takeLast(4)

/** Một dòng key trong trang chi tiết nhà cung cấp: số thứ tự, đuôi đã che, chip trạng thái. */
data class KeyRowUi(val index: Int, val tail: String, val chip: KeyChipUi)
