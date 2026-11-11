package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.setup.BankOffResult
import com.wangzan101.mika.cap.phone.setup.BankRestoreResult
import com.wangzan101.mika.cap.phone.setup.BankStep
import com.wangzan101.mika.cap.phone.setup.BankStepResult
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.launch

/** Dòng kết quả từng bước của một lần tắt quyền đi ngân hàng (dùng chung cho Cài đặt và màn hình trò chuyện). */
internal fun bankOffSummary(result: BankOffResult): List<String> {
    fun line(name: String, r: BankStepResult?): String = when (r?.step) {
        BankStep.Done -> "✓ Đã tắt $name"
        BankStep.NotNeeded -> "• $name vốn đang tắt"
        BankStep.Failed -> "✗ Chưa tắt được $name: ${r.detail ?: "không rõ lý do"}"
        null -> "• Chưa chạy tới bước tắt $name (đã dừng ở bước trước)"
    }
    return listOf(
        line("Trợ năng của Phòng AI", result.accessibility),
        line("Tùy chọn nhà phát triển", result.developerOptions),
    )
}

/** Câu mô tả kết quả một lần bật lại quyền; null = không có gì đáng nói. */
internal fun bankRestoreSummary(result: BankRestoreResult?): String? = when (result) {
    null, BankRestoreResult.AlreadyOn -> null
    BankRestoreResult.Restored -> "Đã bật lại đủ quyền điều khiển điện thoại."
    is BankRestoreResult.NeedsManual -> result.detail
    is BankRestoreResult.Failed -> result.detail
}

/**
 * Mục «Đi ngân hàng» trong Cài đặt › Phục hồi & sửa chữa (Phòng AI v3.4): hiện trạng hai công tắc mà
 * app ngân hàng hay vin vào để chặn (Trợ năng của Phòng AI, Tùy chọn nhà phát triển), một nút tắt hết,
 * và khi đang ở trạng thái đã tắt thì một nút bật lại ngay. Logic thật ở [com.wangzan101.mika.cap.phone.setup.BankMode].
 */
@Composable
fun BankModeSettings() {
    val context = LocalContext.current
    val c = MikaTheme.colors
    val phone = remember { PhoneServices.of(context) }
    val bank = phone.bankMode
    val pending by bank.pendingRestore.collectAsState()
    val a11y by phone.recovery.accessibility.collectAsState()
    var confirmOff by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    // Đọc thẳng mỗi lần vẽ lại: trang này mở ra là thấy đúng trạng thái hiện tại của hệ thống.
    val devOn = DeveloperSettings.developerOptions(context.contentResolver)

    GroupLabel("Đi ngân hàng")
    GroupCard {
        ListRow(MikaIcons.Shield, "Trợ năng của Phòng AI", first = true) {
            if (a11y.enabled) RowValue("Đang bật", ValueTone.Ok) else RowValue("Đang tắt")
        }
        ListRow(null, "Tùy chọn nhà phát triển") {
            if (devOn) RowValue("Đang bật", ValueTone.Ok) else RowValue("Đang tắt")
        }
    }
    FooterNote("App ngân hàng thường chặn khi hai thứ trên đang bật. Bấm nút dưới đây để tắt cả hai trước khi vào ngân hàng; mở lại Phòng AI, app sẽ tự bật lại. Vuốt Phòng AI khỏi danh sách app gần đây cũng tự tắt.")
    if (pending) {
        MikaButton(
            "Bật lại quyền ngay", {
                busy = true
                phone.pairing.scope.launch {
                    note = bankRestoreSummary(bank.restore())
                    busy = false
                }
            },
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            kind = ButtonKind.Primary, size = ButtonSize.Medium, enabled = !busy,
        )
    } else {
        MikaButton(
            "Đi ngân hàng: tắt hết quyền", { confirmOff = true },
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            kind = ButtonKind.Secondary, size = ButtonSize.Medium, enabled = !busy,
        )
    }
    note?.let { FooterNote(it, color = if (it.startsWith("✗")) c.bad else c.ink2) }

    if (confirmOff) {
        MikaDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Tắt hết quyền để đi ngân hàng?") },
            text = {
                Text(
                    "App sẽ tắt Trợ năng của Phòng AI và Tùy chọn nhà phát triển. Trong lúc tắt, Phòng AI không điều khiển điện thoại được (trò chuyện vẫn bình thường). Mở lại app, quyền sẽ tự bật lại.",
                )
            },
            confirmButton = {
                TextButton({
                    confirmOff = false
                    busy = true
                    phone.pairing.scope.launch {
                        note = bankOffSummary(bank.turnOff()).joinToString("\n")
                        busy = false
                    }
                }) { Text("Tắt hết quyền") }
            },
            dismissButton = { TextButton({ confirmOff = false }) { Text("Huỷ") } },
        )
    }
}
