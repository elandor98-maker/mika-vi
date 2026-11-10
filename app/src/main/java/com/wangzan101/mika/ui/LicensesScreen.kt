package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.SubPage
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 许可证页上第三方组件的数据;与仓库 licenses/THIRD_PARTY_NOTICES.md 的条目一一对应(LicensesTest 核对)。 */
object Licenses {
    data class Entry(val name: String, val license: String, val note: String)

    const val GPL_SOURCE_OFFER =
        "PRoot (GPL-2.0-or-later) và talloc (LGPL-3.0-or-later) được phát hành cùng gói cài đặt dưới dạng chương trình độc lập; ứng dụng không liên kết trực tiếp với chúng." +
            "Mã nguồn đầy đủ của chúng, cùng bản vá và tập lệnh biên dịch, có trong thư mục tools/sandbox/ của kho mã nguồn đi kèm ứng dụng này, có thể tự biên dịch lại từ đó."

    const val RUNTIME_NOTE =
        "Hệ thống dùng để chạy chương trình Python, Node... được phát hành cùng gói cài đặt, lấy từ Alpine Linux 3.22; các gói phần mềm trong đó và giấy phép của từng gói được liệt kê ngay bên dưới." +
            "Mã nguồn của các gói thuộc GPL, LGPL, MPL (công thức đóng gói của Alpine và mã nguồn gốc) có thể lấy từ kho mã nguồn của Alpine Linux."

    /** 自带运行环境里的软件包(assets/runtime/packages.json,由 tools/sandbox/build-runtime.py 生成)。 */
    fun runtimePackages(json: String): List<Entry> =
        Json.parseToJsonElement(json).jsonObject.getValue("packages").jsonArray.map { e ->
            val o = e.jsonObject
            fun f(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
            Entry(f("name"), f("license"), "Phiên bản ${f("version")}. ${f("url")}")
        }

    val ENTRIES = listOf(
        Entry("Mika", "Apache-2.0", "Mã nguồn gốc Mika — nền tảng của Phòng AI, gồm trình khởi chạy môi trường chạy, lệnh mika và các tập lệnh kỹ năng bên trong. Toàn văn giấy phép ở đầu trang này."),
        Entry("PRoot", "GPL-2.0-or-later", "Môi trường để chạy chương trình Python, Node..., phiên bản 5.4.0, có áp dụng một bản vá (khi PRoot thoát sẽ kết thúc mọi tiến trình con). $GPL_SOURCE_OFFER"),
        Entry("talloc", "LGPL-3.0-or-later", "Phiên bản 2.4.3, không chỉnh sửa, liên kết tĩnh vào chương trình PRoot. Có thể liên kết lại bằng mã nguồn và tập lệnh trong tools/sandbox/."),
        Entry("Apache Commons Compress", "Apache-2.0", "Giải nén môi trường chạy có sẵn khi khởi động lần đầu. Copyright 2002-2025 The Apache Software Foundation."),
        Entry("Apache Commons Codec", "Apache-2.0", "Thư viện phụ thuộc của Apache Commons Compress. Copyright 2002-2025 The Apache Software Foundation."),
        Entry("Apache Commons IO", "Apache-2.0", "Thư viện phụ thuộc của Apache Commons Compress. Copyright 2002-2025 The Apache Software Foundation."),
        Entry("Apache Commons Lang", "Apache-2.0", "Thư viện phụ thuộc của Apache Commons Compress. Copyright 2001-2025 The Apache Software Foundation."),
        Entry("Kotlin 标准库", "Apache-2.0", "kotlin-stdlib 2.1.20 và các gói liên quan, cùng JetBrains annotations 23.0.0. https://kotlinlang.org/"),
        Entry("kotlinx.coroutines", "Apache-2.0", "Phiên bản 1.9.0. https://github.com/Kotlin/kotlinx.coroutines"),
        Entry("kotlinx.serialization", "Apache-2.0", "Phiên bản 1.7.3. https://github.com/Kotlin/kotlinx.serialization"),
        Entry("OpenTelemetry", "Apache-2.0", "Định nghĩa thông điệp OTLP cho tính năng truy vết chi tiết (Trace). https://opentelemetry.io/"),
        Entry("Protocol Buffers", "BSD-3-Clause", "Mã hóa Protobuf cho tính năng truy vết chi tiết (Trace). https://protobuf.dev/"),
        Entry("OkHttp", "Apache-2.0", "Phiên bản 4.12.0, dùng để truy cập giao diện của các mô hình. https://square.github.io/okhttp/"),
        Entry("Okio", "Apache-2.0", "Phiên bản 3.6.0, thư viện phụ thuộc của OkHttp. https://github.com/square/okio/"),
        Entry("AndroidX(含 Jetpack Compose)", "Apache-2.0", "Compose 1.7.6, Material3 1.3.1, Activity 1.9.3, Lifecycle 2.8.3, Core 1.13.1, WebKit 1.12.1 và các thư viện giao diện, hệ thống khác. https://developer.android.com/jetpack/androidx"),
        Entry("Guava ListenableFuture(由 AndroidX 库引入)", "Apache-2.0", "listenablefuture 1.0. https://github.com/google/guava"),
        Entry("scrcpy", "Apache-2.0", "Cách lấy ngữ cảnh hệ thống với quyền shell được tham khảo từ scrcpy; đầu các tệp nguồn liên quan vẫn ghi rõ nguồn gốc."),
        Entry("Alpine Linux", "各软件包各自的许可证", RUNTIME_NOTE),
        Entry("XZ for Java", "0BSD", "Phiên bản 1.10, giải nén môi trường chạy có sẵn khi khởi động lần đầu. https://tukaani.org/xz/java.html"),
        Entry("ZXing", "Apache-2.0", "Phiên bản 3.5.3, tạo mã QR liên kết WeChat ngay trên máy. https://github.com/zxing/zxing"),
        Entry("commonmark-java", "BSD-2-Clause", "Phiên bản 0.30.1 (có mở rộng bảng và gạch bỏ), phân tích Markdown trong câu trả lời thành cấu trúc để hiển thị. Copyright (c) 2015, Atlassian Pty Ltd. https://github.com/commonmark/commonmark-java"),
        Entry("xterm.js", "MIT", "Phiên bản 5.5.0 (và @xterm/addon-fit 0.10.0), bộ giả lập terminal cho trang dòng lệnh của người dùng. https://github.com/xtermjs/xterm.js"),
        Entry("llama.cpp", "MIT", "Chạy mô hình GGUF ngay trên máy cho chế độ AI nội bộ (kèm ggml). Mã nguồn: https://github.com/ggml-org/llama.cpp"),
    )
}

/**
 * 许可证页(关于页底部「Apache-2.0 开源许可」进来):先是许可证原文(assets/licenses/Apache-2.0.txt),
 * 下面列出随 Mika 发布的第三方组件,一个组件一行(名称、许可证),说明在行下面;页脚指向完整文本。
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val licenseText = remember {
        runCatching { context.assets.open(LICENSE_ASSET).use { it.readBytes().decodeToString() } }.getOrDefault("")
    }
    val runtime = remember {
        runCatching { context.assets.open("runtime/packages.json").use { Licenses.runtimePackages(it.readBytes().decodeToString()) } }
            .getOrDefault(emptyList())
    }
    val thirdParty = Licenses.ENTRIES.filter { it.name != "Mika" } + runtime
    SubPage(AboutCopy.LICENSE_TITLE, onBack) {
        if (licenseText.isNotEmpty()) {
            MikaCard(Modifier.padding(bottom = 12.dp).testTag("license-text")) {
                Text(
                    licenseText.trimEnd(), Modifier.padding(16.dp),
                    style = MikaType.Hint.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 19.sp),
                    color = MikaTheme.colors.ink2,
                )
            }
        }
        GroupLabel(stringResource(R.string.licenses_third_party, thirdParty.size))
        GroupCard {
            thirdParty.forEachIndexed { i, e ->
                Column {
                    ListRow(null, e.name, e.license, first = i == 0)
                    Text(e.note, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = MikaType.Hint, color = MikaTheme.colors.ink2)
                }
            }
        }
        FooterNote(stringResource(R.string.licenses_footer))
    }
}

private const val LICENSE_ASSET = "licenses/Apache-2.0.txt"
