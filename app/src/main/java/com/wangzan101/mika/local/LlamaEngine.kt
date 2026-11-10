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
    const val N_CTX = 8192

    /** So luong suy luan: 4 luong la diem can bang toc do/nhiet tren Dimensity. */
    private const val N_THREADS = 4

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
                prompt, maxTokens, temp, topP, topK, repeatPenalty,
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
        val rc = LlamaNative.nativeLoadModel(modelFile.absolutePath, N_CTX, N_THREADS)
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
