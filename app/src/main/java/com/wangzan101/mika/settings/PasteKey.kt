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
 * khớp thì trả về null để giao diện nói rõ «không nhận ra hãng» chứ không đoán bừa.
 * Thứ tự kiểm tra quan trọng: tiền tố dài/đặc thù phải xét trước tiền tố chung (`sk-or-` trước `sk-`).
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
            k.startsWith("csk-") -> byLabel("Cerebras")
            // `sk-` chung: gán cho mẫu OpenAI (mẫu có sẵn trong danh sách); hãng khác cũng dùng
            // tiền tố này (Anthropic là sk-ant-, không khớp) — theo đúng chỉ định của bản v3.3.
            k.startsWith("sk-") -> byLabel("OpenAI")
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
