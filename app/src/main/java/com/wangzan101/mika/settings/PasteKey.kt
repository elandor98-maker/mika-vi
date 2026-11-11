package com.wangzan101.mika.settings

/**
 * Ô «dán key» ở trang Mô hình (bản Phòng AI v3.3): người dùng dán một key bất kỳ, app tự nhận
 * ra hãng từ định dạng key, tự nối nhà đó, tự chọn model mặc định và gán vai còn trống.
 * Người dùng có thể dán NHIỀU key của CÙNG một hãng (nhiều tài khoản của chính họ): các key
 * được lưu thành một danh sách theo nhà (xem [ProviderStore.appendProviderKey]) và khi chạy,
 * hết hạn mức ở key này thì tự xoay sang key kế tiếp của cùng hãng trước khi sang hãng khác
 * (xem [ProviderStore.modelFor] / [FailoverChatModel]).
 *
 * Nhận diện chỉ dựa vào tiền tố mà các hãng công khai dùng cho key của họ; không có tiền tố nào
 * khớp thì trả về null — lúc đó giao diện sẽ THỬ gọi thật từng hãng để nhận diện (xem
 * ModelSetupViewModel.submitPasteKey), vẫn không ra mới báo «không nhận ra hãng», không đoán bừa.
 * Thứ tự kiểm tra quan trọng: tiền tố dài/đặc thù phải xét trước tiền tố chung (`sk-or-` trước `sk-`).
 * Key Gemini có HAI đời: đời cũ bắt đầu `AIza…`; từ 2026-05-28 AI Studio chỉ phát đời mới
 * «key uỷ quyền» bắt đầu `AQ.` — cả hai đều là Gemini. Key đời mới vẫn gửi bằng header
 * `x-goog-api-key` như đời cũ (đã kiểm chứng chạy thật trên máy người dùng 2026-11-11 qua
 * luồng nối cổ điển: liệt kê model + trò chuyện đều được) — KHÔNG đổi sang Bearer.
 */
object PasteKey {
    /** Key đã cắt khoảng trắng thuộc hãng nào (mẫu có sẵn trong [ProviderPresets.all]); nhận không ra thì null. */
    fun presetFor(key: String): ProviderPreset? {
        val k = key.trim()
        if (k.isEmpty()) return null
        return when {
            k.startsWith("gsk_") -> byLabel("Groq")
            k.startsWith("sk-or-") -> byLabel("OpenRouter")
            k.startsWith("AIza") -> byLabel("Gemini")
            k.startsWith("AQ.") -> byLabel("Gemini")
            k.startsWith("csk-") -> byLabel("Cerebras")
            // `sk-` chung: gán cho mẫu OpenAI (mẫu có sẵn trong danh sách). Ngoại lệ: `sk-ant-`
            // là Key Anthropic — KHÔNG được nhận nhầm thành OpenAI (Anthropic không có trong
            // mẫu dán key; trả về null để giao diện thử gọi từng hãng rồi mới báo không nhận ra).
            k.startsWith("sk-") && !k.startsWith("sk-ant-") -> byLabel("OpenAI")
            else -> null
        }
    }

    private fun byLabel(label: String): ProviderPreset? = ProviderPresets.all.firstOrNull { it.label == label }

    /** Nhà đã nối rồi ứng với mẫu [preset] (cùng kiểu giao diện + cùng địa chỉ + cùng tên); chưa nối thì null. */
    fun existingProvider(store: ProviderStore, preset: ProviderPreset): Provider? =
        store.providers.value.firstOrNull {
            it.kind == preset.kind &&
                ProviderConnection.normalizeUrl(it.baseUrl) == ProviderConnection.normalizeUrl(preset.baseUrl) &&
                it.name == preset.label && it.signIn == null
        }
}
