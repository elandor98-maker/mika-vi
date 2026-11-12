package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.net.ProxyFailure

sealed class ModelError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Auth : ModelError("API Key không hợp lệ hoặc không có quyền")
    class QuotaExceeded : ModelError("Hạn mức đã dùng hết")
    /** 本任务的已报告用量达到预算；不再发起任何收尾或重试请求。 */
    class TaskBudgetExceeded(val limit: Long, val used: Long) : ModelError("Ngân sách Token của tác vụ này đã dùng hết")
    /** 这家服务商今天在本地已报告的用量达到预算；[providerName] 只用于给用户说明是哪一家。 */
    class DailyBudgetExceeded(val providerName: String, val limit: Long, val used: Long) : ModelError("Ngân sách Token hôm nay của nhà cung cấp đã dùng hết")
    class RateLimited(val retryAfterMs: Long?) : ModelError("Yêu cầu quá thường xuyên, hãy thử lại sau")
    /**
     * Phòng AI v3.5: MỌI mắt xích trong chuỗi dự phòng đều bị từ chối vì xác thực (key chết, bị thu hồi,
     * hoặc đăng nhập hết hạn) — không còn key nào chạy được. Thử lại vô ích; phải thay key / đăng nhập lại.
     * Chuỗi dự phòng ném lỗi này thay cho lỗi Auth của mắt xích cuối để màn hình nói rõ là TẤT CẢ key đều chết.
     */
    class AllKeysDead : ModelError("Tất cả các key đều bị từ chối (lỗi xác thực)")
    /**
     * Phòng AI v3.5: mọi key còn dùng được trong chuỗi dự phòng đều đã hết hạn mức NGÀY của nhà cung cấp
     * (các key chết vì xác thực không tính). Chỉ còn cách chờ qua mốc reset lượt ngày (~15:00) hoặc thêm key khác.
     */
    class AllDailyQuotaExhausted : ModelError("Tất cả các key đều đã hết lượt hôm nay")
    class ContextTooLong : ModelError("Nội dung hội thoại vượt quá độ dài ngữ cảnh của mô hình")
    /**
     * 服务商那里没有这个模型(或地址不对,请求的接口不存在);[model] 是请求的模型名,不知道时为空。
     * [detail] 是服务端 JSON 报错里的原话(截短),没有为空:网关、中转服务还会用 404 说别的情况(比如它的上游模型这会儿都不可用),
     * 看原话才分得出是哪一种。
     */
    class ModelNotFound(val model: String = "", val detail: String = "") :
        ModelError(if (model.isEmpty()) "Không tìm thấy mô hình này" else "Không tìm thấy mô hình 「$model」")
    /**
     * 服务商有这个模型,这个账号却用不了(没开通、没权限、没买);[detail] 是服务端的原话(截短),没有为空。
     * 换一个模型或到服务商后台开通才行,重试没用。见 [ModelAccessErrors]。
     */
    class ModelNotAvailable(val model: String = "", val detail: String = "") :
        ModelError(if (model.isEmpty()) "Tài khoản này không dùng được mô hình này (chưa được mở quyền hoặc không có quyền)" else "Tài khoản này không dùng được mô hình 「$model」 (chưa được mở quyền hoặc không có quyền)")
    /** 服务端明说这个模型不支持调用工具(带工具的请求被拒)。Mika 干活靠工具,它当不了主模型。 */
    class ToolsUnsupported(val model: String = "") :
        ModelError(if (model.isEmpty()) "Mô hình này không hỗ trợ gọi công cụ" else "Mô hình 「$model」 không hỗ trợ gọi công cụ")
    class Network(cause: Throwable? = null) : ModelError("Kết nối mạng thất bại", cause)
    /**
     * 走网络代理时代理那一段出的错(连不上代理,或代理拒了账号密码),不是服务商的问题。见 [com.wangzan101.mika.core.net.NetProxy]。
     */
    class Proxy(val failure: ProxyFailure, cause: Throwable? = null) : ModelError(
        when (failure) {
            ProxyFailure.Unreachable -> "Không kết nối được tới proxy mạng"
            ProxyFailure.AuthFailed -> "Proxy mạng đã từ chối tài khoản hoặc mật khẩu"
        },
        cause,
    )
    /**
     * 流式回复还没收尾(没有结束标记,也没有结束原因)连接就关了:多半是途中的网关、代理空闲超时或网络切换把连接掐断。
     * 回复不完整、不能用,重发同一个请求通常就好;和接口配置无关。
     */
    class StreamCut : ModelError("Câu trả lời chưa truyền xong thì kết nối đã bị ngắt")
    class Server(val status: Int) : ModelError("Dịch vụ mô hình gặp lỗi (HTTP $status)")
    class ContentRefused : ModelError("Mô hình từ chối xử lý nội dung này")
    /** 服务端明确拒绝思考强度参数:保留服务端原话,引导改档位,不能误报成地址或接口错误。 */
    class ReasoningEffortRejected(val detail: String) : ModelError("Mô hình không chấp nhận mức độ suy nghĩ đã chọn")
    /** ChatGPT 套餐给其他 App 用的额度到了上限:不重试,等额度恢复或换模型。 */
    class ChatGptLimitReached : ModelError("Hạn mức ChatGPT đã dùng hết")
    /** 这个 ChatGPT 账号不能在其他 App 里用套餐额度(套餐不符、地区或账号策略不允许):不重试。 */
    class ChatGptNotEligible(val detail: String = "") :
        ModelError(if (detail.isEmpty()) "Tài khoản này không thể dùng hạn mức ChatGPT trong ứng dụng khác" else "Tài khoản này không thể dùng hạn mức ChatGPT trong ứng dụng khác: $detail")
    /** 登录已失效(换新令牌被拒,或用户在账号里断开了这个 App),要重新登录。[account] 是哪一家的登录(界面上的名字)。 */
    class SignInExpired(val account: String = "ChatGPT") : ModelError("Đăng nhập đã hết hiệu lực, hãy đăng nhập lại")
    /**
     * 用账号登录的(如 xAI)接口回 HTTP 403:登录是成功的,这个订阅档位却不能调这个接口。不重试;
     * 改用这家的 API Key 才行。[account] 是哪一家,[detail] 是服务端的原话(截短),没有为空。
     */
    class AccountPlanNotAllowed(val account: String, val detail: String = "") :
        ModelError("Gói đăng ký ${account} của bạn không dùng được giao diện này")
    class BadResponse(detail: String = "", cause: Throwable? = null) :
        ModelError(if (detail.isEmpty()) "Mô hình trả về nội dung không phân tích được" else "Mô hình trả về nội dung không phân tích được: $detail", cause)
}
