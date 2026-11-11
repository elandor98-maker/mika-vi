package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.cap.phone.PHONE
import com.wangzan101.mika.core.cap.root.ROOT
import com.wangzan101.mika.core.cap.sandbox.SANDBOX

/** Việc người dùng vừa giao thuộc loại nào — quyết định đi đường nào (xem [QuickChat] và AgentLoop). */
enum class TaskKind {
    /** Việc phải đụng vào điện thoại/ứng dụng: mở, bật, tắt, gửi, gọi, chạm… — vòng lặp đầy đủ, đủ công cụ. */
    PhoneAction,

    /** Câu hỏi cần dữ kiện tươi (giá cả, thời tiết, tin tức…): vòng lặp đầy đủ nhưng KHÔNG phát công cụ điện thoại/sandbox. */
    LiveInfo,

    /** Câu hỏi thường, trả lời thẳng được: đường chat nhanh — đúng một lần gọi model, không công cụ nào. */
    Plain,
}

/**
 * Phân loại việc bằng dấu hiệu trong chính câu nói (tiếng Việt + tiếng Anh), KHÔNG gọi model —
 * gọi model để phân loại còn tốn thêm một lượt, mất ý nghĩa tăng tốc. Danh sách dấu hiệu để hết
 * ở đây, một chỗ duy nhất, để dễ rà soát và bổ sung.
 *
 * Nguyên tắc bảo thủ: thà chạy vòng lặp đầy đủ (chậm mà đúng) còn hơn đi đường nhanh mà thiếu tay chân.
 * - Câu «làm sao / cách …» là hỏi CÁCH làm → coi là câu hỏi thường (trả lời bằng chữ), trừ khi có ý nhờ làm hộ rõ ràng.
 * - Có bất kỳ dấu hiệu hành động nào lên điện thoại/ứng dụng → [TaskKind.PhoneAction].
 * - Cần dữ kiện tươi (giá, thời tiết, tin…) → [TaskKind.LiveInfo]: đường nhanh không có công cụ tra cứu,
 *   trả lời bừa sẽ sai; vòng lặp đầy đủ với bộ công cụ rút gọn vẫn nhanh hơn hẳn vì không vác schema điện thoại.
 * - Còn lại → [TaskKind.Plain]. Câu giờ giấc («mấy giờ rồi») cũng là Plain: đường nhanh được cấp giờ hiện tại.
 */
object TaskClassifier {
    /** Dấu hiệu «hỏi cách làm» — kiểm tra trước dấu hiệu hành động. */
    private val EXPLAIN_CUES = listOf(
        "làm sao để", "làm thế nào để", "cách ", "hướng dẫn", "chỉ tôi cách", "chỉ a cách", "chỉ em cách",
        "how to", "what is", "là gì", "tại sao", "vì sao", "có nghĩa là", "giải thích",
    )

    /** Dấu hiệu nhờ làm hộ rõ ràng — có nó thì câu «làm sao» vẫn là việc hành động. */
    private val DO_IT_CUES = listOf(
        "giúp a", "giúp anh", "giúp em", "giúp tôi", "hộ a", "hộ anh", "hộ em", "làm hộ", "do it for me",
    )

    /** Động từ hành động lên điện thoại/ứng dụng (viết thường, đã đệm khoảng trắng hai đầu khi so). */
    private val PHONE_ACTION_CUES = listOf(
        "mở ", "bật ", "tắt ", "gửi ", "gọi ", "chụp ", "bấm ", "nhấn ", "vuốt ", "lướt ",
        "đặt lịch", "đặt bàn", "đặt vé", "đặt hàng", "mua hộ", "thanh toán hộ",
        "cài đặt ứng dụng", "gỡ ứng dụng", "xoá ứng dụng", "xóa ứng dụng", "tải ứng dụng", "cài app", "mở app", "vào app",
        "tăng âm lượng", "giảm âm lượng", "báo thức", "hẹn giờ", "phát nhạc", "dừng nhạc",
        "chụp màn hình", "quay màn hình", "lưu số", "tìm đường", "chỉ đường", "dẫn đường",
        "open ", "turn on", "turn off", "send ", "call ", "tap ", "swipe ", "install ", "uninstall ",
        "take a photo", "screenshot", "set an alarm", "set a timer", "play music", "pause music",
    )

    /** Dấu hiệu cần dữ kiện tươi ngoài đời (đường nhanh không có công cụ tra cứu). */
    private val LIVE_INFO_CUES = listOf(
        "giá ", "thời tiết", "tin tức", "tỷ giá", "tỉ giá", "lịch thi đấu", "kết quả trận", "kết quả bóng đá",
        "xổ số", "chứng khoán", "bitcoin", "btc", "vàng hôm nay", "hôm nay có gì", "mới nhất", "vừa xảy ra",
        "price of", "weather", "latest news", "exchange rate", "stock price",
    )

    fun classify(text: String): TaskKind {
        val t = text.lowercase()
        if (EXPLAIN_CUES.any { it in t } && DO_IT_CUES.none { it in t }) return TaskKind.Plain
        val padded = " $t "
        if (PHONE_ACTION_CUES.any { it in padded || it in t }) return TaskKind.PhoneAction
        if (LIVE_INFO_CUES.any { it in t }) return TaskKind.LiveInfo
        return TaskKind.Plain
    }

    /**
     * Câu trả lời có nói rõ là phải hành động trên điện thoại / phải tra cứu mới trả lời được không.
     * Đường nhanh và chế độ công cụ rút gọn dùng nó để rơi xuống / leo lên vòng lặp đầy đủ:
     * model nói cần tay chân thì cấp tay chân, không bao giờ để người dùng nhận một câu «em không làm được».
     */
    fun needsPhoneAction(replyText: String): Boolean {
        val t = replyText.lowercase()
        return ACTION_NEEDED_PHRASES.any { it in t }
    }

    private val ACTION_NEEDED_PHRASES = listOf(
        "cần mở", "phải mở", "cần vào ứng dụng", "cần dùng ứng dụng", "cần truy cập", "không thể tự mở",
        "không có quyền truy cập", "cần tra cứu", "cần tìm kiếm", "không có dữ liệu thời gian thực",
        "dữ liệu thời gian thực", "không thể truy cập internet", "tôi không thể duyệt web",
        "i need to open", "need to open the", "i don't have real-time", "real-time data", "need to search",
        "i can't browse", "cannot access the internet", "no internet access",
    )
}

/**
 * Đường chat nhanh (Phòng AI v3.3): câu hỏi thường được trả lời bằng đúng MỘT lần gọi model qua
 * chuỗi dự phòng sẵn có, với bộ lệnh gọn (chỉ danh tính + nguyên tắc — trong đó đã có luật chính xác
 * của bản v3.1) và KHÔNG một schema công cụ nào. Mỗi lần gọi agent thường tốn ~30K token bộ lệnh +
 * công cụ; đường này chỉ còn vài K, nên câu hỏi thường về trong vài giây.
 *
 * Mọi trục trặc ở đường nhanh (model lỗi, trả lời rỗng/bị cắt, model nói phải mở app hay tra cứu,
 * người dùng chen ngang) đều rơi xuống vòng lặp đầy đủ — đã cài ở AgentLoop, đường nhanh không
 * bao giờ là ngõ cụt.
 */
object QuickChat {
    /**
     * Các nhóm công cụ KHÔNG phát cho việc không đụng vào điện thoại (ở vòng lặp đầy đủ):
     * công cụ điện thoại, sandbox và root. Công cụ web/tìm kiếm, bộ nhớ, kỹ năng… vẫn giữ nguyên.
     */
    val REDUCED_OUT_CAPABILITIES: Set<String> = setOf(PHONE, SANDBOX, ROOT)

    /** Chỉ dẫn riêng cho đường nhanh, nối sau danh tính + nguyên tắc. */
    const val INSTRUCTIONS = """
You are answering a plain question directly, in one reply, without any tools.
Answer briefly and accurately, in the language of the user's message.
If the question actually needs live data you do not have (prices, weather, news, schedules) or an action on the phone, say plainly in one short sentence that you need to look it up or open the app to do it — never guess, never invent numbers.
Questions about the current time or date are answered from the local time given below.
"""

    /** Bộ lệnh gọn của đường nhanh: danh tính + nguyên tắc (đã gồm luật chính xác) + chỉ dẫn trên + giờ hiện tại. */
    fun systemPrompt(identity: String, principles: String, localTime: String): String =
        listOf(identity, principles, INSTRUCTIONS.trim(), "Current local date and time: $localTime")
            .joinToString("\n\n")
}
