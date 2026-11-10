package com.wangzan101.mika.local

/** Nhận từng mẩu chữ trong lúc mô hình đang sinh văn bản. */
fun interface LlamaTokenCallback {
    fun onToken(piece: String)
}

/**
 * Khai báo JNI sang libmika_llama_jni.so (llama.cpp). Mọi hàm đều chặn luồng
 * gọi cho tới khi xong — chỉ gọi từ [LlamaEngine] trên luồng nền.
 */
object LlamaNative {
    init {
        System.loadLibrary("mika_llama_jni")
    }

    /** Nap backend ggml tu thu muc .so cua app; goi mot lan duy nhat. */
    external fun nativeInit(nativeLibDir: String)

    /** 0 = ok, 1 = khong nap duoc model, 2 = khong tao duoc context. */
    external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int, nThreadsBatch: Int): Int

    /**
     * Sinh van ban tu mot prompt da dinh dang san (ChatML). Phan tien to cua
     * prompt giong lan goi truoc duoc tai dung tu KV cache — chi xu ly phan moi.
     * Tra ve [so token prompt, so token da sinh, ly do dung] — ly do dung xem
     * mika_llama_jni.cpp: 0 EOG, 1 het maxTokens, 2 day context, am = loi
     * (-4 = qua [timeLimitSec]).
     */
    external fun nativeGenerate(
        prompt: String,
        maxTokens: Int,
        temp: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        timeLimitSec: Int,
        callback: LlamaTokenCallback,
    ): IntArray

    /** -1 neu chua nap model. */
    external fun nativeCountTokens(text: String): Int

    /** Thong tin model dang nap (ten, context huan luyen, dung luong); rong neu chua nap. */
    external fun nativeModelInfo(): String

    external fun nativeUnload()
}
