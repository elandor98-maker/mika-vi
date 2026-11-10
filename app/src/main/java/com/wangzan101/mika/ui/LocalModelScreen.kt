package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.local.LocalModelStore
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Trang quản lý mô hình AI nội bộ: tải mô hình GGUF từ danh mục (Qwen2.5
 * chính chủ trên Hugging Face), chọn mô hình đang dùng làm mô hình chính,
 * xóa mô hình không dùng. Mọi thao tác đều thật — không nút giả.
 */
@Composable
fun LocalModelScreen(store: ProviderStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val roles by store.roles.collectAsState()
    val configs by store.configs.collectAsState()

    var installed by remember { mutableStateOf(LocalModelStore.installed(context)) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var progressFraction by remember { mutableStateOf(0f) }
    var progressLabel by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val mainConfigId = (roles[ModelRole.Main] as? RoleChoice.Model)?.configId
    val activeFile = configs.firstOrNull { it.id == mainConfigId && it.kind == ProviderConfig.Kind.Local }?.model

    fun refresh() {
        installed = LocalModelStore.installed(context)
    }

    fun startDownload(preset: LocalModelStore.Preset) {
        if (downloadingId != null) return
        error = null
        downloadingId = preset.id
        progressFraction = 0f
        progressLabel = ""
        scope.launch {
            try {
                LocalModelStore.download(context, preset) { downloaded, total ->
                    progressFraction = if (total > 0) downloaded.toFloat() / total else 0f
                    progressLabel = "${formatSize(downloaded)} / ${formatSize(total)}"
                }
                refresh()
                // Tai xong la chon lam mo hinh chinh ngay — dung muc dich nguoi dung vua tai
                store.useLocalModel(preset.fileName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Tải mô hình thất bại. Hãy kiểm tra mạng rồi thử lại."
            } finally {
                downloadingId = null
            }
        }
    }

    Page("AI nội bộ (trên máy)", onBack) {
        Text(
            "Mô hình AI chạy ngay trên điện thoại bằng llama.cpp: không cần API key, " +
                "không cần Internet khi sử dụng, hội thoại không rời khỏi máy. " +
                "Chỉ cần mạng lúc tải mô hình lần đầu.",
            style = MikaType.Body, color = MikaTheme.colors.ink3,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        GroupLabel("Mô hình đã tải")
        GroupCard {
            if (installed.isEmpty()) {
                ListRow(
                    icon = MikaIcons.Info, first = true,
                    title = "Chưa tải mô hình nào",
                    subtitle = "Chọn một mô hình ở mục Tải mô hình bên dưới (tải bằng Wi-Fi cho nhanh).",
                )
            }
            installed.forEachIndexed { i, item ->
                val inUse = item.fileName == activeFile
                RadioRow(
                    title = item.fileName.removeSuffix(".gguf"),
                    subtitle = formatSize(item.sizeBytes) + if (inUse) " • Đang dùng làm mô hình chính" else "",
                    selected = inUse,
                    first = i == 0,
                    onClick = { store.useLocalModel(item.fileName) },
                    trailing = {
                        if (!inUse) {
                            TextButton(onClick = {
                                LocalModelStore.delete(context, item.fileName)
                                refresh()
                            }) { Text("Xóa") }
                        }
                    },
                )
            }
        }

        GroupLabel("Tải mô hình")
        GroupCard {
            LocalModelStore.PRESETS.forEachIndexed { i, preset ->
                val isInstalled = LocalModelStore.isInstalled(context, preset)
                val partial = if (!isInstalled) LocalModelStore.partialBytes(context, preset) else 0L
                val downloading = downloadingId == preset.id
                Column(Modifier.fillMaxWidth()) {
                    ListRow(
                        icon = MikaIcons.Spark,
                        first = i == 0,
                        title = preset.displayName,
                        subtitle = preset.note,
                        detail = when {
                            downloading -> progressLabel
                            partial > 0L -> "Đã tải dở ${formatSize(partial)} / ${formatSize(preset.sizeBytes)} — bấm Tải tiếp"
                            else -> "Dung lượng ${formatSize(preset.sizeBytes)} • Nguồn: Qwen trên Hugging Face"
                        },
                        trailing = {
                            when {
                                isInstalled -> RowValue("Đã tải", ValueTone.Ok)
                                downloading -> RowValue("${(progressFraction * 100).toInt()}%", ValueTone.Link)
                                else -> TextButton(
                                    onClick = { startDownload(preset) },
                                    enabled = downloadingId == null,
                                ) { Text(if (partial > 0L) "Tải tiếp" else "Tải về") }
                            }
                        },
                    )
                    if (downloading) {
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                        )
                    }
                }
            }
        }

        if (error != null) {
            Text(
                error!!,
                style = MikaType.Body, color = MikaTheme.colors.bad,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        FooterNote(
            "File mô hình được kiểm tra mã SHA-256 sau khi tải để chắc chắn không hỏng hay sai nguồn. " +
                "Bạn cũng có thể chép file .gguf khác vào thư mục dữ liệu của app (models) — app sẽ tự nhận diện.",
        )
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1_073_741_824L) String.format("%.2f GB", bytes / 1_073_741_824.0)
    else String.format("%.0f MB", bytes / 1_048_576.0)
