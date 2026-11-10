package com.wangzan101.mika.local

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Quan ly cac file mo hinh GGUF trong bo nho app: danh muc tai san (Qwen2.5
 * chinh chu tren Hugging Face), tai co tien do + tiep tuc khi dut mang, kiem
 * tra SHA-256 sau khi tai, liet ke/xoa mo hinh da tai.
 */
object LocalModelStore {

    /** Mot mo hinh trong danh muc tai san. [sha256]/[sizeBytes] lay tu API chinh thuc cua Hugging Face. */
    data class Preset(
        val id: String,
        val displayName: String,
        val fileName: String,
        val url: String,
        val sizeBytes: Long,
        val sha256: String,
        val note: String,
    )

    data class Installed(val fileName: String, val sizeBytes: Long)

    val PRESETS: List<Preset> = listOf(
        Preset(
            id = "qwen2.5-3b-q4km",
            displayName = "Qwen2.5 3B (bản chính)",
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeBytes = 2_104_932_768L,
            sha256 = "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
            note = "Khuyên dùng: thông minh nhất trong các bản nhẹ, tiếng Việt tốt, gọi công cụ ổn.",
        ),
        Preset(
            id = "qwen2.5-1.5b-q4km",
            displayName = "Qwen2.5 1.5B (bản nhẹ)",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 1_117_320_736L,
            sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            note = "Nhẹ và nhanh hơn, hợp máy ít bộ nhớ; độ chính xác thấp hơn bản 3B.",
        ),
    )

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun installed(context: Context): List<Installed> =
        modelsDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gguf") }
            ?.map { Installed(it.name, it.length()) }
            ?.sortedBy { it.fileName }
            ?: emptyList()

    fun isInstalled(context: Context, preset: Preset): Boolean =
        File(modelsDir(context), preset.fileName).isFile

    fun delete(context: Context, fileName: String) {
        File(modelsDir(context), fileName).delete()
        File(modelsDir(context), "$fileName.part").delete()
    }

    /** So byte da tai do cua mot preset (file .part), 0 neu chua tai. */
    fun partialBytes(context: Context, preset: Preset): Long =
        File(modelsDir(context), "${preset.fileName}.part").let { if (it.isFile) it.length() else 0L }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Tai [preset] ve thu muc models. Bao tien do qua [onProgress] (da tai/tong).
     * Dut mang thi goi lai se tai tiep tu cho cu (Range). Tai xong kiem tra
     * SHA-256: sai thi xoa file va nem IOException — khong bao gio tra ve mot
     * file mo hinh hong.
     */
    suspend fun download(
        context: Context,
        preset: Preset,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val dir = modelsDir(context)
        val target = File(dir, preset.fileName)
        if (target.isFile && target.length() == preset.sizeBytes) {
            onProgress(preset.sizeBytes, preset.sizeBytes)
            return@withContext
        }
        val part = File(dir, "${preset.fileName}.part")
        var existing = if (part.isFile) part.length() else 0L
        if (existing > preset.sizeBytes) {
            part.delete()
            existing = 0L
        }
        val request = Request.Builder()
            .url(preset.url)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()
        client.newCall(request).execute().use { response ->
            // 206 = tai tiep duoc; 200 = server khong ho tro Range, tai lai tu dau
            val append = response.code == 206 && existing > 0
            if (!append && !response.isSuccessful) {
                throw IOException("Tải mô hình thất bại (HTTP ${response.code}). Kiểm tra mạng rồi thử lại.")
            }
            val body = response.body ?: throw IOException("Tải mô hình thất bại: không có dữ liệu.")
            var downloaded = if (append) existing else 0L
            val total = preset.sizeBytes
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastReport >= 4 * 1024 * 1024 || downloaded == total) {
                            lastReport = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
        }
        if (part.length() != preset.sizeBytes) {
            throw IOException(
                "Tải chưa đủ dữ liệu (${part.length()}/${preset.sizeBytes} byte). " +
                    "Bấm tải lại để tải tiếp từ chỗ đang dở.",
            )
        }
        val digest = sha256(part)
        if (!digest.equals(preset.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("File tải về không khớp mã kiểm tra SHA-256 nên đã bị xóa. Hãy tải lại.")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            throw IOException("Không lưu được file mô hình vào bộ nhớ app.")
        }
        onProgress(preset.sizeBytes, preset.sizeBytes)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
