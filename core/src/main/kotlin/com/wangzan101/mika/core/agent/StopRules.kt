package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.tool.Outcome
import kotlinx.serialization.json.JsonPrimitive

/**
 * 停止规则:任务什么时候、以什么结局结束(放弃、守卫叫停、模型出错、没给出回答、步数用完)。办完不在这里:
 * 不带工具调用的回复就是回答,由循环核对后交出。纯函数;结局里给用户看的说明不发给模型。
 */
internal object StopRules {
    /** 一个动作执行完之后:放弃了就按它结束;否则守卫([stop],屏幕守卫或不动屏幕的工具的守卫)叫停了就停。都没有为 null,接着做。 */
    fun afterAction(outcome: Outcome?, stop: GuardVerdict?): TaskState? = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stop?.let(::guard)
    }

    /** 步数用完后的最后一轮调了工具(只给了 end_not_done):放弃按它结束,否则算步数用完。 */
    fun afterFinalTurn(outcome: Outcome?, maxSteps: Int): TaskState = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stepLimit(maxSteps)
    }

    fun stepLimit(maxSteps: Int) = TaskState.Failed("Đã đạt giới hạn $maxSteps bước, tác vụ chưa hoàn thành.", FailKind.StepLimit)

    /** 收尾请求本身失败或仍没交出结果时，按触发收尾的原因落结局，不谎报步数用完。 */
    fun closeoutFailed(reason: Closeout, maxSteps: Int): TaskState.Failed = when (reason) {
        Closeout.StepLimit -> stepLimit(maxSteps)
        Closeout.Completed -> TaskState.Failed("Tiến độ tác vụ đã hoàn thành, nhưng mô hình không đưa ra kết quả.", FailKind.Model)
        Closeout.NoNewData -> TaskState.Failed("Đã ${NewDataTracker.LIMIT} lần liên tiếp không đọc được dữ liệu mới, tác vụ đã dừng.", FailKind.Guard)
    }

    /** 提醒过一次,模型仍然回了空话(或又被输出上限截断)。 */
    fun noAnswer() = TaskState.Failed("Mô hình đã dừng thao tác mà không đưa ra câu trả lời.", FailKind.Model)

    /** 退回过一次,模型的工具调用又以文字形式回来了(见 [LeakedToolCall]):不把原样的标记当回答交出。 */
    fun toolCallAsText() = TaskState.Failed("Lời gọi công cụ của mô hình không được máy chủ nhận diện, bị trả về nguyên dạng dưới dạng văn bản nên không thực thi được.", FailKind.Model)

    /** 模型调用失败(重试过仍不行,或重试也没用)。 */
    fun modelFailed(e: Throwable): TaskState.Failed = when (e) {
        is ModelError.TaskBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.DailyBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError -> TaskState.Failed(userReason(e), FailKind.Model)
        else -> TaskState.Failed("Lỗi bất thường khi gọi mô hình:${e.message ?: e::class.simpleName}", FailKind.Model)
    }

    fun guard(v: GuardVerdict): TaskState.Failed {
        val treeBlind = v.evidence["treeBlind"] == JsonPrimitive(true)
        val why = when (v.code) {
            "repeating_action" -> "Cùng một thao tác lặp lại nhiều lần vẫn không có tác dụng"
            "no_progress" -> if (treeBlind) "Cây điều khiển không đọc được vị trí thao tác, sau nhiều lần thao tác liên tiếp màn hình vẫn không có thay đổi rõ rệt, không xác nhận được thao tác có hiệu quả hay không, đã dừng thử lại"
                else "Sau nhiều lần thao tác, màn hình vẫn không thay đổi"
            "read_loop" -> "Trang không thay đổi, đọc đi đọc lại vẫn không tìm được nội dung cần tìm"
            "too_many_failures" -> "Thao tác thất bại liên tiếp"
            "sandbox_loop" -> "Cùng một lệnh lặp lại nguyên văn vẫn thất bại"
            else -> "Đã kích hoạt cơ chế bảo vệ thao tác"
        }
        return TaskState.Failed("Đã dừng an toàn (${v.code}): $why.", FailKind.Guard)
    }
}

internal fun userReason(e: ModelError): String = when (e) {
    is ModelError.Auth -> "Dịch vụ mô hình đã từ chối yêu cầu: API Key không hợp lệ hoặc không có quyền. Hãy kiểm tra trong Cài đặt xem API Key đã điền đúng chưa và có quyền dùng mô hình này không."
    is ModelError.AllKeysDead -> "Tất cả các key đã lưu đều bị nhà cung cấp từ chối (lỗi xác thực — key đã chết hoặc bị thu hồi). Hãy dán key mới trong Cài đặt › Mô hình rồi thử lại."
    is ModelError.QuotaExceeded -> "Hạn mức của tài khoản mô hình đã dùng hết, hãy kiểm tra số dư hoặc gói dịch vụ của tài khoản nhà cung cấp."
    is ModelError.AllDailyQuotaExhausted -> "Tất cả các key đều đã hết lượt miễn phí của hôm nay. Lượt sẽ mở lại vào khoảng 15:00 (giờ reset của nhà cung cấp); hãy thử lại sau giờ đó, hoặc dán thêm key của tài khoản khác trong Cài đặt › Mô hình."
    is ModelError.TaskBudgetExceeded -> "Đã đạt giới hạn ngân sách ${e.limit} Token của tác vụ này, tác vụ chưa hoàn thành."
    is ModelError.DailyBudgetExceeded ->
        "Nhà cung cấp 「${e.providerName}」 đã đạt giới hạn ngân sách ${e.limit} Token của hôm nay, tác vụ chưa hoàn thành. Sẽ tự động khôi phục sau khi qua ngày mới theo giờ trên máy."
    is ModelError.ModelNotFound -> {
        // 404 不一定是模型名不对(地址不对,或网关、中转服务说的别的情况):有原话时照原话说,不断定模型不存在
        val check = if (e.model.isEmpty()) "Hãy vào Cài đặt › Mô hình để kiểm tra tên mô hình và Base URL đã đúng chưa."
            else "Vào Cài đặt › Mô hình, kiểm tra tên mô hình (phải điền đầy đủ mã ID của mô hình, ví dụ tên mô hình của Gemini có dạng gemini-flash-latest) hoặc đổi sang preset tương ứng."
        when {
            e.detail.isNotEmpty() ->
                (if (e.model.isEmpty()) "" else "Khi gọi mô hình 「${e.model}」") +
                    "Máy chủ trả lời: ${quoted(e.detail)}. Có thể do tên mô hình chưa đúng, hoặc phía máy chủ tạm thời không dùng được. $check"
            e.model.isEmpty() -> "Không tìm thấy mô hình này, $check"
            else -> "Mô hình 「${e.model}」 không có ở nhà cung cấp này. $check"
        }
    }
    is ModelError.ModelNotAvailable ->
        "Mô hình 「${e.model.ifEmpty { "mô hình hiện tại" }}」 tài khoản này chưa được mở quyền dùng hoặc không có quyền dùng. Vào Cài đặt › Mô hình để đổi mô hình khác, hoặc mở quyền tại trang quản lý của nhà cung cấp."
    is ModelError.ToolsUnsupported ->
        "Mô hình 「${e.model.ifEmpty { "mô hình hiện tại" }}」 không hỗ trợ gọi công cụ, mà Phòng AI cần công cụ để làm việc. Vào Cài đặt › Mô hình để đổi sang mô hình có hỗ trợ gọi công cụ."
    is ModelError.ContextTooLong -> "Nội dung hội thoại đã vượt quá độ dài ngữ cảnh của mô hình, hãy đổi sang mô hình có ngữ cảnh dài hơn, hoặc chia nhỏ tác vụ."
    is ModelError.ContentRefused -> "Mô hình từ chối xử lý nội dung này, hãy điều chỉnh mô tả tác vụ hoặc đổi mô hình khác."
    is ModelError.ReasoningEffortRejected ->
        "Mô hình không chấp nhận mức độ suy nghĩ đã chọn. Máy chủ trả lời: ${quoted(e.detail)}. Hãy vào Cài đặt › Mô hình, đổi mức độ suy nghĩ về mặc định hoặc mức mà máy chủ hỗ trợ."
    is ModelError.RateLimited -> "Dịch vụ mô hình đang giới hạn tần suất, thử lại vẫn báo yêu cầu quá thường xuyên. Hãy thử lại sau, hoặc kiểm tra giới hạn tần suất gọi của tài khoản."
    is ModelError.Network -> "Kết nối tới dịch vụ mô hình thất bại, thử lại vẫn không thông. Hãy kiểm tra mạng và Base URL."
    is ModelError.Proxy -> when (e.failure) {
        ProxyFailure.Unreachable ->
            "Không kết nối được tới proxy mạng, hoặc proxy không chuyển tiếp yêu cầu này, thử lại vẫn không thông. Hãy kiểm tra proxy có đang bật không, địa chỉ và cổng trong Cài đặt › Proxy mạng đã đúng chưa; nếu không cần proxy thì hãy xóa trống mục đó."
        ProxyFailure.AuthFailed -> "Proxy mạng đã từ chối tài khoản hoặc mật khẩu (HTTP 407). Hãy kiểm tra tên người dùng và mật khẩu trong địa chỉ proxy tại Cài đặt › Proxy mạng."
    }
    is ModelError.Server -> "Dịch vụ mô hình gặp lỗi (HTTP ${e.status}), hãy thử lại sau."
    is ModelError.StreamCut ->
        "Câu trả lời của mô hình chưa truyền xong thì kết nối đã bị ngắt ở phía nguồn, thử lại vẫn vậy. Nguyên nhân thường gặp là mạng không ổn định, hoặc thời gian chờ nhàn rỗi của dịch vụ trung gian, cổng kết nối quá ngắn (dễ gặp khi mô hình suy nghĩ lâu). Hãy thử lại sau."
    is ModelError.BadResponse -> "${e.message}. Hãy kiểm tra Base URL và loại giao diện có khớp nhau không."
    is ModelError.ChatGptLimitReached ->
        "Hạn mức ChatGPT đã dùng hết, hãy thử lại sau. Có thể xem mức sử dụng tại Cài đặt ChatGPT › Mức sử dụng (chatgpt.com/settings/usage); hạn mức mỗi 5 giờ của gói Plus được dùng chung với các ứng dụng khác đăng nhập bằng ChatGPT. Cũng có thể tạm đổi sang mô hình khác."
    is ModelError.ChatGptNotEligible ->
        "Tài khoản này không thể dùng hạn mức ChatGPT trong ứng dụng khác (cần gói Plus hoặc Pro, và khu vực cùng cài đặt tài khoản phải cho phép).${if (e.detail.isEmpty()) "" else "Giải thích của máy chủ:${quoted(e.detail)}."}Có thể đổi sang mô hình khác."
    is ModelError.SignInExpired -> "Đăng nhập ${e.account} đã hết hiệu lực, hãy đăng nhập lại bằng ${e.account} trong Cài đặt › Mô hình."
    is ModelError.AccountPlanNotAllowed ->
        "Gói đăng ký ${e.account} của bạn không dùng được giao diện này (HTTP 403).${if (e.detail.isEmpty()) "" else "Giải thích của máy chủ:${quoted(e.detail)}."}Có thể đổi sang dùng API Key của ${e.account}, hoặc đổi mô hình khác trong Cài đặt › Mô hình."
}

/** 引在句中的服务端原话:去掉末尾一个句号(原话自带的),由引它的句子补上自己的「。」;省略号(截短的「…」、「...」)不动。 */
private fun quoted(detail: String): String =
    when {
        detail.endsWith("..") -> detail
        detail.endsWith(".") || detail.endsWith("。") -> detail.dropLast(1)
        else -> detail
    }
