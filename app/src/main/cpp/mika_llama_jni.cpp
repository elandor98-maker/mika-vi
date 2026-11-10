// Cau noi JNI giua Mika (Kotlin) va llama.cpp: nap model GGUF, sinh van ban
// theo tung mẩu token (streaming), dem token. Toan bo trang thai nam trong
// singleton nay; phia Kotlin bao dam moi loi goi deu qua cung mot Mutex nen
// khong can khoa trong C++.
//
// Dinh dang prompt (ChatML cua Qwen2.5) va viec phan tich <tool_call> nam o
// phia Kotlin (LocalLlamaModel); file nay chi lam suy luan thuan.

#include <android/log.h>
#include <jni.h>

#include <string>
#include <vector>

#include "common.h"
#include "llama.h"
#include "sampling.h"

#define LOG_TAG "mika-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

llama_model * g_model = nullptr;
llama_context * g_ctx = nullptr;
int g_n_ctx = 0;
bool g_backend_ready = false;

void llama_log_to_android(ggml_log_level level, const char * text, void *) {
    const int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
                   : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
                                                   : ANDROID_LOG_INFO;
    __android_log_print(prio, LOG_TAG, "%s", text);
}

void unload_locked() {
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_n_ctx = 0;
}

// Kiem tra chuoi UTF-8 hoan chinh (token co the cat giua mot ky tu nhieu byte).
bool is_valid_utf8(const std::string & s) {
    const auto * bytes = reinterpret_cast<const unsigned char *>(s.data());
    const size_t len = s.size();
    size_t i = 0;
    while (i < len) {
        const unsigned char c = bytes[i];
        int num;
        if ((c & 0x80) == 0x00)      num = 1;
        else if ((c & 0xE0) == 0xC0) num = 2;
        else if ((c & 0xF0) == 0xE0) num = 3;
        else if ((c & 0xF8) == 0xF0) num = 4;
        else return false;
        if (i + num > len) return false;  // ky tu bi cat o cuoi chuoi
        for (int j = 1; j < num; j++) {
            if ((bytes[i + j] & 0xC0) != 0x80) return false;
        }
        i += num;
    }
    return true;
}

std::string jstring_to_std(JNIEnv * env, jstring js) {
    if (!js) return "";
    const char * chars = env->GetStringUTFChars(js, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(js, chars);
    return out;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_wangzan101_mika_local_LlamaNative_nativeInit(JNIEnv * env, jobject, jstring native_lib_dir) {
    if (g_backend_ready) return;
    llama_log_set(llama_log_to_android, nullptr);
    const std::string dir = jstring_to_std(env, native_lib_dir);
    if (!dir.empty()) {
        // Nap cac bien the backend CPU toi uu cho tung dong chip (armv8.2/9...)
        ggml_backend_load_all_from_path(dir.c_str());
    }
    llama_backend_init();
    g_backend_ready = true;
    LOGI("backend san sang");
}

// 0 = ok, 1 = khong nap duoc model, 2 = khong tao duoc context
extern "C" JNIEXPORT jint JNICALL
Java_com_wangzan101_mika_local_LlamaNative_nativeLoadModel(
        JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    unload_locked();

    const std::string path = jstring_to_std(env, jpath);
    llama_model_params model_params = llama_model_default_params();
    // llama.cpp pinned revision configures mmap through load_mode; use the
    // memory-mapped path for GGUF weights to avoid copying the full model.
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    g_model = llama_model_load_from_file(path.c_str(), model_params);
    if (!g_model) {
        LOGE("khong nap duoc model: %s", path.c_str());
        return 1;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = (uint32_t) n_ctx;
    ctx_params.n_batch = 512;
    ctx_params.n_ubatch = 512;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("khong tao duoc context (n_ctx=%d)", n_ctx);
        llama_model_free(g_model);
        g_model = nullptr;
        return 2;
    }
    g_n_ctx = n_ctx;
    LOGI("da nap model %s, n_ctx=%d, threads=%d", path.c_str(), n_ctx, n_threads);
    return 0;
}

// Tra ve [so token prompt, so token da sinh, ly do dung]:
// ly do dung: 0 = gap token ket thuc luot (EOG), 1 = het maxTokens,
//             2 = context day, -1 = prompt dai hon context, -2 = loi decode,
//             -3 = phia Kotlin huy giua chung
extern "C" JNIEXPORT jintArray JNICALL
Java_com_wangzan101_mika_local_LlamaNative_nativeGenerate(
        JNIEnv * env, jobject, jstring jprompt, jint max_tokens,
        jfloat temp, jfloat top_p, jint top_k, jfloat repeat_penalty,
        jobject callback) {
    auto make_result = [&](int prompt_tokens, int gen_tokens, int reason) {
        jintArray arr = env->NewIntArray(3);
        const jint vals[3] = {prompt_tokens, gen_tokens, reason};
        if (arr) env->SetIntArrayRegion(arr, 0, 3, vals);
        return arr;
    };

    if (!g_ctx || !g_model) return make_result(0, 0, -2);

    const std::string prompt = jstring_to_std(env, jprompt);
    // parse_special=true de <|im_start|>/<|im_end|> duoc hieu la token dac biet;
    // add_special=false vi prompt ChatML tu chua cac token dieu khien.
    const std::vector<llama_token> prompt_tokens = common_tokenize(g_ctx, prompt, false, true);
    const int n_prompt = (int) prompt_tokens.size();
    if (n_prompt <= 0 || n_prompt >= g_n_ctx - 4) {
        LOGE("prompt %d token vuot context %d", n_prompt, g_n_ctx);
        return make_result(n_prompt, 0, -1);
    }

    jclass cb_class = env->GetObjectClass(callback);
    jmethodID cb_method = cb_class
            ? env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V")
            : nullptr;
    if (!cb_method) {
        LOGE("khong tim thay TokenCallback.onToken");
        return make_result(n_prompt, 0, -2);
    }

    llama_memory_clear(llama_get_memory(g_ctx), true);

    common_batch batch(g_ctx);
    int pos = 0;
    const int batch_size = 512;
    for (int i = 0; i < n_prompt; i += batch_size) {
        const int cur = std::min(n_prompt - i, batch_size);
        batch.clear();
        for (int j = 0; j < cur; j++) {
            batch.add(prompt_tokens[i + j], pos + j, 0, i + j == n_prompt - 1);
        }
        if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGE("decode that bai o giai doan doc prompt");
            return make_result(n_prompt, 0, -2);
        }
        pos += cur;
    }

    common_params_sampling sparams;
    sparams.temp = temp;
    sparams.top_p = top_p;
    sparams.top_k = top_k;
    sparams.penalty_repeat = repeat_penalty;
    sparams.seed = 1234;
    common_sampler * sampler = common_sampler_init(g_model, sparams);
    if (!sampler) return make_result(n_prompt, 0, -2);

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    std::string pending_utf8;
    int n_gen = 0;
    int reason = 1;  // mac dinh: het maxTokens

    while (n_gen < max_tokens) {
        if (pos >= g_n_ctx - 4) { reason = 2; break; }

        const llama_token token = common_sampler_sample(sampler, g_ctx, -1);
        common_sampler_accept(sampler, token, true);

        batch.clear();
        batch.add(token, pos, 0, true);
        if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGE("decode that bai khi sinh token");
            reason = -2;
            break;
        }
        pos++;
        n_gen++;

        if (llama_vocab_is_eog(vocab, token)) { reason = 0; break; }

        pending_utf8 += common_token_to_piece(g_ctx, token);
        if (!pending_utf8.empty() && is_valid_utf8(pending_utf8)) {
            jstring piece = env->NewStringUTF(pending_utf8.c_str());
            if (piece) {
                env->CallVoidMethod(callback, cb_method, piece);
                env->DeleteLocalRef(piece);
            }
            pending_utf8.clear();
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                reason = -3;
                break;
            }
        }
    }

    common_sampler_free(sampler);
    return make_result(n_prompt, n_gen, reason);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wangzan101_mika_local_LlamaNative_nativeCountTokens(
        JNIEnv * env, jobject, jstring jtext) {
    if (!g_ctx) return -1;
    const std::string text = jstring_to_std(env, jtext);
    return (jint) common_tokenize(g_ctx, text, false, true).size();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wangzan101_mika_local_LlamaNative_nativeModelInfo(JNIEnv * env, jobject) {
    if (!g_model) return env->NewStringUTF("");
    char desc[256];
    llama_model_desc(g_model, desc, sizeof(desc));
    char buf[384];
    snprintf(buf, sizeof(buf), "%s | ctx_train=%d | size=%.2f GiB",
             desc, llama_model_n_ctx_train(g_model),
             (double) llama_model_size(g_model) / 1024.0 / 1024.0 / 1024.0);
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wangzan101_mika_local_LlamaNative_nativeUnload(JNIEnv *, jobject) {
    unload_locked();
}
