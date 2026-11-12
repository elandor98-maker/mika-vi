package com.wangzan101.mika.task

import android.os.Build

/**
 * Thông tin máy THẬT đọc lúc chạy (v3.5-A) để đưa vào ngữ cảnh của model.
 *
 * Lý do: trước đây không ai nói cho model biết máy của người dùng là máy gì, nên nó tự đoán —
 * từng trả lời sai hẳn thành "Xiaomi 14T Pro" trong khi máy thật là Xiaomi 17T Pro. Dòng này được
 * ghép vào bộ lệnh hệ thống của vòng lặp đầy đủ và truyền riêng cho đường chat nhanh (xem TaskRun).
 * Mọi nguồn đều là dữ kiện lúc chạy (android.os.Build + thuộc tính hệ thống của MIUI/HyperOS),
 * không ghi cứng tên máy nào.
 */
object DeviceInfo {

    /** Một dòng ngắn gọn, viết để model dùng trực tiếp khi nói về máy của người dùng. */
    fun line(): String {
        val os = buildString {
            append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(')')
            hyperOsVersion()?.let { append(", HyperOS ").append(it) }
        }
        val name = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
        return "Thiết bị của người dùng: $name — $os. " +
            "Khi nói về điện thoại của người dùng, chỉ dùng đúng thông tin này; không tự đoán tên máy khác."
    }

    /** HyperOS báo phiên bản qua thuộc tính hệ thống riêng (ro.mi.os.version.name, kiểu "OS3.0"); máy MIUI cũ dùng ro.miui.ui.version.name. */
    private fun hyperOsVersion(): String? =
        listOf("ro.mi.os.version.name", "ro.miui.ui.version.name")
            .firstNotNullOfOrNull { systemProperty(it) }

    /** Đọc thuộc tính hệ thống qua phản chiếu (API ẩn nhưng chỉ đọc); hỏng thì thôi, không làm rơi nhiệm vụ. */
    private fun systemProperty(name: String): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("get", String::class.java).invoke(null, name) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
