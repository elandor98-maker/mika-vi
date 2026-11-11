package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.wangzan101.mika.core.shell.AccessibilityList
import com.wangzan101.mika.core.shell.BuiltinShellControl
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.PermissionCommands
import com.wangzan101.mika.core.shell.ShellCommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Kết quả một bước trong chuỗi tắt/bật quyền. */
enum class BankStep { Done, NotNeeded, Failed }

data class BankStepResult(val step: BankStep, val detail: String? = null)

/**
 * Kết quả tắt quyền đi ngân hàng: [developerOptions] = null nghĩa là chưa chạy tới bước đó
 * vì bước Trợ năng đã hỏng (dừng sớm cho an toàn, xem [BankMode.turnOff]).
 */
data class BankOffResult(val accessibility: BankStepResult, val developerOptions: BankStepResult?) {
    /** Có ít nhất một thứ THẬT SỰ vừa bị tắt bởi lần này — chỉ khi đó mới hẹn "mở app thì bật lại". */
    val changedSomething: Boolean get() =
        accessibility.step == BankStep.Done || developerOptions?.step == BankStep.Done
}

/** Kết quả một lần thử bật lại quyền khi mở app. */
sealed interface BankRestoreResult {
    /** Không có gì đang chờ bật lại. */
    data object AlreadyOn : BankRestoreResult
    /** Đã bật lại đủ (Trợ năng + Tùy chọn nhà phát triển). */
    data object Restored : BankRestoreResult
    /** App không tự bật lại hết được; [detail] nói rõ phần nào còn thiếu để người dùng bật tay (thẻ trên màn hình trò chuyện). */
    data class NeedsManual(val detail: String) : BankRestoreResult
    /** Có kênh ghi mà ghi không thành. */
    data class Failed(val detail: String) : BankRestoreResult
}

/**
 * «Đi ngân hàng» (Phòng AI v3.4, theo yêu cầu của người dùng 2026-11-11 23:56): app ngân hàng ở Việt Nam
 * chặn khi (a) Tùy chọn nhà phát triển đang bật và (b) dịch vụ Trợ năng của Phòng AI đang bật, nên người dùng
 * phải tắt tay cả hai mỗi lần vào ngân hàng rồi bật lại. Tính năng này tự làm thay:
 *
 * - TẮT (nút bấm trong Cài đặt / nút khiên trên màn hình trò chuyện / vuốt app khỏi danh sách gần đây):
 *   (i) gỡ CHÍNH thành phần trợ năng của app này khỏi `enabled_accessibility_services` (đọc–sửa–ghi,
 *   giữ nguyên mọi dịch vụ khác), rồi mới (ii) ghi `development_settings_enabled 0` SAU CÙNG
 *   (ghi xong là kênh shell không khởi động thêm được nữa). Mỗi bước ghi xong đều ĐỌC LẠI kiểm chứng;
 *   bước (i) hỏng thì dừng, không đụng bước (ii).
 * - BẬT LẠI (khi mở app / vào màn hình trò chuyện, nếu lần tắt trước có tắt thật thứ gì): làm ngược lại.
 *
 * Hai đường ghi, theo thứ tự: (1) app tự ghi nếu đang giữ quyền WRITE_SECURE_SETTINGS (trợ lý nền từng cấp);
 * (2) qua trợ lý shell thường trú. Về trợ lý: nó được khởi động bằng `setsid app_process` (xem HelperHandoff)
 * nên tách hẳn khỏi phiên adb — tắt gỡ lỗi không dây / tắt Tùy chọn nhà phát triển KHÔNG giết nó, nó vẫn
 * nhận app khi app mở lại và giao lại Binder (giống Shizuku). Nhờ vậy sau khi tắt hết, mở lại app vẫn còn
 * đường ghi để tự bật lại Trợ năng. Chỉ khi trợ lý đã chết hẳn (khởi động lại máy, bị hệ thống giết) và app
 * cũng không có quyền tự ghi thì mới phải nhờ người dùng bật tay Tùy chọn nhà phát triển (thẻ trên màn hình chat).
 *
 * Ranh giới an toàn: chỉ chạm (a) thành phần trợ năng của CHÍNH app này trong danh sách, và (b) hai cài đặt
 * toàn cục `development_settings_enabled` / `adb_wifi_enabled`. Không đụng danh sách chặn ngân hàng hay app khác.
 */
class BankMode(
    context: Context,
    private val shellManager: BuiltinShellManager,
    private val control: BuiltinShellControl,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _pendingRestore = MutableStateFlow(prefs.getBoolean(KEY_OFF, false))
    /** Lần tắt trước có tắt thật thứ gì và chưa bật lại xong: mở app phải thử bật lại, không được thì hiện thẻ nhắc. */
    val pendingRestore: StateFlow<Boolean> = _pendingRestore.asStateFlow()

    private val _lastRestore = MutableStateFlow<BankRestoreResult?>(null)
    /** Kết quả lần thử bật lại gần nhất (cho thẻ trên màn hình trò chuyện và mục trong Cài đặt). */
    val lastRestore: StateFlow<BankRestoreResult?> = _lastRestore.asStateFlow()

    @Volatile private var lastRestoreAttemptMs = 0L

    private fun canWriteSecureDirectly(): Boolean =
        app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Kênh shell: trợ lý đang chạy, hoặc đã ghép nối (commandRunner tự mở kết nối adb khi trợ lý chưa lên). Như [BuiltinShellRecovery.permissions]. */
    private fun shell(): ShellCommandRunner? =
        shellManager.commandRunner.takeIf { control.status.value == BuiltinShellStatus.Running || control.paired }

    private suspend fun runShellCommands(commands: List<String>): Boolean {
        val shell = shell() ?: return false
        for (cmd in commands) {
            val out = runCatching { shell.exec(PermissionCommands.checked(cmd)) }.getOrNull() ?: return false
            if (PermissionCommands.exitCode(out) != 0) return false
        }
        return true
    }

    /** Ghi lại toàn bộ danh sách trợ năng (đã đọc–sửa ở ngoài): app tự ghi nếu có quyền, không thì qua shell. */
    private suspend fun writeAccessibilityList(list: String): Boolean {
        if (canWriteSecureDirectly()) {
            val cr = app.contentResolver
            val okList = runCatching {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.ifEmpty { null })
            }.getOrDefault(false)
            val okFlag = runCatching {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (list.isEmpty()) 0 else 1)
            }.getOrDefault(false)
            if (okList && okFlag) return true
        }
        return runShellCommands(PermissionCommands.writeAccessibility(list))
    }

    private fun ownAccessibilityEnabled(): Boolean {
        val current = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return AccessibilityList.contains(current, PermissionCommands.accessibilityService(app.packageName))
    }

    /** Bước (i) của chiều tắt: gỡ thành phần trợ năng của app này khỏi danh sách, giữ nguyên các dịch vụ khác. */
    private suspend fun removeOwnAccessibility(): BankStepResult {
        val cr = app.contentResolver
        val me = PermissionCommands.accessibilityService(app.packageName)
        val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (!AccessibilityList.contains(current, me)) return BankStepResult(BankStep.NotNeeded)
        if (!writeAccessibilityList(AccessibilityList.without(current, me))) {
            return BankStepResult(BankStep.Failed, "không ghi được danh sách trợ năng")
        }
        return if (!ownAccessibilityEnabled()) BankStepResult(BankStep.Done)
        else BankStepResult(BankStep.Failed, "đã ghi nhưng hệ thống chưa gỡ Trợ năng của Phòng AI")
    }

    /** Bước (ii) của chiều tắt — luôn chạy SAU CÙNG vì ghi xong là kênh shell không khởi động thêm được nữa. */
    private suspend fun turnOffDeveloperOptions(): BankStepResult {
        val cr = app.contentResolver
        if (!DeveloperSettings.developerOptions(cr)) return BankStepResult(BankStep.NotNeeded)
        var wrote = canWriteSecureDirectly() && runCatching {
            Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
        }.getOrDefault(false)
        if (!wrote) wrote = runShellCommands(listOf("settings put global development_settings_enabled 0"))
        if (!wrote) return BankStepResult(BankStep.Failed, "không ghi được cài đặt nhà phát triển")
        return if (!DeveloperSettings.developerOptions(cr)) BankStepResult(BankStep.Done)
        else BankStepResult(BankStep.Failed, "đã ghi nhưng Tùy chọn nhà phát triển vẫn đang bật")
    }

    /**
     * Tắt hết quyền để đi ngân hàng, theo đúng thứ tự: Trợ năng trước, Tùy chọn nhà phát triển sau cùng.
     * Chỉ khi có thứ thật sự bị tắt thì mới hẹn bật lại ở lần mở app sau (tránh bật thừa những thứ người dùng tự tắt).
     */
    suspend fun turnOff(): BankOffResult = mutex.withLock {
        val a11y = removeOwnAccessibility()
        if (a11y.step == BankStep.Failed) return@withLock BankOffResult(a11y, null)
        val dev = turnOffDeveloperOptions()
        val result = BankOffResult(a11y, dev)
        if (result.changedSomething) setPending(true)
        result
    }

    /** Vuốt app khỏi danh sách gần đây cũng tắt quyền: chạy nền, không chờ, lỗi thì thôi (đường chính vẫn là nút bấm). */
    fun turnOffInBackground() {
        scope.launch(NonCancellable) { runCatching { turnOff() } }
    }

    /** Thử bật lại khi app mở / quay lại màn hình trò chuyện; gọi dồn dập thì cách nhau tối thiểu vài giây. */
    suspend fun restoreOnLaunch() {
        if (!pendingRestore.value) return
        val now = System.currentTimeMillis()
        if (now - lastRestoreAttemptMs < RESTORE_THROTTLE_MS) return
        lastRestoreAttemptMs = now
        restore()
    }

    /** Bật lại ngay (nút bấm trong Cài đặt / trên thẻ nhắc): Trợ năng + Tùy chọn nhà phát triển + gỡ lỗi không dây (best-effort). */
    suspend fun restore(): BankRestoreResult = mutex.withLock {
        if (!pendingRestore.value) return@withLock BankRestoreResult.AlreadyOn
        val cr = app.contentResolver
        if (!canWriteSecureDirectly() && shell() == null) {
            return@withLock BankRestoreResult.NeedsManual(
                "Trợ lý nền đã dừng và app chưa có quyền tự ghi cài đặt, nên chưa tự bật lại được. " +
                    "Anh bật Tùy chọn nhà phát triển bằng tay, app sẽ tự lo phần còn lại.",
            ).also { _lastRestore.value = it }
        }
        val a11y = ensureOwnAccessibility()
        val dev = ensureDeveloperOptions()
        if (dev) ensureWirelessDebugging()
        val result = when {
            a11y && dev -> BankRestoreResult.Restored.also { setPending(false) }
            !a11y && !dev -> BankRestoreResult.Failed("Chưa bật lại được cả Trợ năng lẫn Tùy chọn nhà phát triển — anh thử lại, hoặc bật tay theo hai nút dưới đây.")
            !a11y -> BankRestoreResult.NeedsManual("Đã bật lại Tùy chọn nhà phát triển, nhưng chưa ghi được Trợ năng của Phòng AI — anh bật tay trong trang Trợ năng giúp em.")
            else -> BankRestoreResult.NeedsManual("Đã bật lại Trợ năng, nhưng Tùy chọn nhà phát triển vẫn đang tắt — anh bật tay để app điều khiển điện thoại tiếp được.")
        }
        _lastRestore.value = result
        result
    }

    /** Thêm lại thành phần trợ năng của app này vào danh sách (đọc–sửa–ghi, giữ các dịch vụ khác), đọc lại kiểm chứng. */
    private suspend fun ensureOwnAccessibility(): Boolean {
        if (ownAccessibilityEnabled()) return true
        val cr = app.contentResolver
        val me = PermissionCommands.accessibilityService(app.packageName)
        val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (!writeAccessibilityList(AccessibilityList.with(current, me))) return false
        return ownAccessibilityEnabled()
    }

    private suspend fun ensureDeveloperOptions(): Boolean {
        val cr = app.contentResolver
        if (DeveloperSettings.developerOptions(cr)) return true
        var wrote = canWriteSecureDirectly() && runCatching {
            Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
        }.getOrDefault(false)
        if (!wrote) wrote = runShellCommands(listOf("settings put global development_settings_enabled 1"))
        return wrote && DeveloperSettings.developerOptions(cr)
    }

    /** Gỡ lỗi không dây: có thì thôi, chưa có thì thử bật (hệ thống có thể tự tắt lại trên Wi-Fi lạ — chấp nhận, trợ lý còn sống là đủ chạy). */
    private suspend fun ensureWirelessDebugging() {
        val cr = app.contentResolver
        if (DeveloperSettings.wirelessDebugging(cr)) return
        if (canWriteSecureDirectly() && DeveloperSettings.turnOnWirelessDebugging(cr)) return
        runShellCommands(listOf("settings put global ${DeveloperSettings.ADB_WIFI_ENABLED} 1"))
    }

    private fun setPending(on: Boolean) {
        prefs.edit().putBoolean(KEY_OFF, on).apply()
        _pendingRestore.value = on
        if (!on) _lastRestore.value = null
    }

    private companion object {
        const val PREFS = "bank_mode"
        const val KEY_OFF = "permissions_off"
        /** Hai lần thử bật lại tự động cách nhau tối thiểu chừng này, tránh dội liên tục mỗi lần quay lại app. */
        const val RESTORE_THROTTLE_MS = 5_000L
    }
}
