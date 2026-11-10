package com.wangzan101.mika.local

import android.content.Context
import com.wangzan101.mika.core.model.ModelError
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Giu trang thai nap model cua llama.cpp: mot model duy nhat trong bo nho tai
 * mot thoi diem (file 1-2GB, khong the nap hai cai). Moi lan sinh van ban deu
 * qua [mutex] nay nen cac tac vu chay tuan tu — dung ban chat cua suy luan
 * tren dien thoai.
 */
object LlamaEngine {
    /** Context token cua ban local (khop Capabilities cua LocalLlamaModel). */
    const val N_CTX = 32768

    /** So luong khi sinh tung token: 6 luong tren con chip 8 nhan cua may. */
    private const val N_THREADS = 6

    /** So luong khi doc prompt (prefill): dung het 8 nhan cho nhanh. */
    private const val N_THREADS_BATCH = 8

    /**
     * Gioi han cung cho mot lan goi mo hinh. Qua muc nay native dung lai va
     * bao loi ro rang thay vi treo im lang; phan tien to da xu ly van nam
     * trong bo nho dem nen lan goi sau di tiep duoc.
     */
    private const val TIME_LIMIT_SEC = 300

    private val mutex = Mutex()
    private var initialized = false
    private var loadedPath: String? = null

    /** Ket qua mot lan sinh van ban. */
    class Generation(val promptTokens: Int, val generatedTokens: Int, val finishReason: Int)

    suspend fun generate(
        context: Context,
        modelFile: File,
        prompt: String,
        maxTokens: Int,
        temp: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        onPiece: (String) -> Unit,
    ): Generation = mutex.withLock {
        ensureLoadedLocked(context, modelFile)
        val result = withContext(Dispatchers.Default) {
            LlamaNative.nativeGenerate(
                prompt, maxTokens, temp, topP, topK, repeatPenalty, TIME_LIMIT_SEC,
                LlamaTokenCallback { piece -> onPiece(piece) },
            )
        }
        Generation(
            promptTokens = result.getOrElse(0) { 0 },
            generatedTokens = result.getOrElse(1) { 0 },
            finishReason = result.getOrElse(2) { -2 },
        )
    }

    private fun ensureLoadedLocked(context: Context, modelFile: File) {
        if (loadedPath == modelFile.absolutePath) return
        if (!initialized) {
            LlamaNative.nativeInit(context.applicationInfo.nativeLibraryDir)
            initialized = true
        }
        val rc = LlamaNative.nativeLoadModel(modelFile.absolutePath, N_CTX, N_THREADS, N_THREADS_BATCH)
        if (rc != 0) {
            loadedPath = null
            throw ModelError.BadResponse(
                "Không nạp được mô hình AI nội bộ «${modelFile.name}» " +
                    "(mã lỗi $rc). File mô hình có thể bị hỏng — hãy xóa và tải lại trong mục Mô hình.",
            )
        }
        loadedPath = modelFile.absolutePath
    }

    /** Model dang nap la file nao (null = chua nap); chi de hien thi trang thai. */
    fun loadedModelName(): String? = loadedPath?.let { File(it).name }
}
