package com.wangzan101.mika.local

import android.content.Context
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.PromptCaching
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import java.io.File
import java.util.UUID
import org.json.JSONObject

/**
 * Adapter chay mo hinh GGUF ngay trong may bang llama.cpp — khong API key,
 * khong Internet. [modelFileName] la ten file .gguf trong thu muc models cua
 * app (xem [LocalModelStore]).
 *
 * Prompt dung dinh dang ChatML + function calling chinh thuc cua Qwen2.5:
 * khoi <tools> trong system, <tool_call> khi goi cong cu, <tool_response>
 * trong luot user khi tra ket qua cong cu.
 */
class LocalLlamaModel(
    context: Context,
    private val modelFileName: String,
) : StreamingChatModel {

    private val appContext: Context = context.applicationContext

    override val capabilities: Capabilities = DEFAULT_CAPABILITIES

    override val name: String get() = modelFileName.removeSuffix(".gguf")

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
    ): ModelReply = completeStreaming(system, messages, tools) {}

    override suspend fun completeStreaming(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        onText: (String) -> Unit,
    ): ModelReply {
        val file = File(LocalModelStore.modelsDir(appContext), modelFileName)
        if (!file.isFile) {
            throw ModelError.BadResponse(
                "Chưa có mô hình AI nội bộ «$modelFileName» trên máy. " +
                    "Hãy mở mục Mô hình trong ứng dụng và tải mô hình về trước.",
            )
        }
        val prompt = buildPrompt(system, messages, tools)
        val raw = StringBuilder()
        val result = LlamaEngine.generate(
            context = appContext,
            modelFile = file,
            prompt = prompt,
            maxTokens = MAX_TOKENS,
            temp = TEMP,
            topP = TOP_P,
            topK = TOP_K,
            repeatPenalty = REPEAT_PENALTY,
        ) { piece ->
            raw.append(piece)
            onText(visibleText(raw))
        }
        when {
            result.finishReason == -1 -> throw ModelError.ContextTooLong()
            result.finishReason == -4 -> throw ModelError.BadResponse(
                "Mô hình AI nội bộ xử lý quá lâu (quá 5 phút) nên lượt này đã dừng lại. " +
                    "Phần nội dung đã đọc được giữ lại trong bộ nhớ đệm — hãy gửi lại để nó làm tiếp.",
            )
            result.finishReason < 0 -> throw ModelError.BadResponse(
                "Mô hình AI nội bộ gặp lỗi khi xử lý (mã ${result.finishReason}). " +
                    "Hãy thử gửi lại; nếu vẫn lỗi, thử mô hình nhẹ hơn trong mục Mô hình.",
            )
        }
        return parseReply(raw.toString(), tools, result)
    }

    // ---- Dung prompt ChatML ----

    private fun buildPrompt(system: String, messages: List<Message>, tools: List<ToolSpec>): String {
        val systemText = buildString {
            append(system)
            for (m in messages) {
                if (m.role == Role.System) {
                    val t = m.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
                    if (t.isNotBlank()) append("\n\n").append(t)
                }
            }
            if (tools.isNotEmpty()) append(toolsBlock(tools))
            append(VI_ADDENDUM)
        }
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(systemText).append("<|im_end|>\n")
        for (m in messages) {
            when (m.role) {
                Role.System -> Unit // da gop vao system o tren
                Role.User -> sb.append("<|im_start|>user\n").append(userContent(m)).append("<|im_end|>\n")
                Role.Assistant -> sb.append("<|im_start|>assistant\n").append(assistantContent(m)).append("<|im_end|>\n")
                Role.Tool -> sb.append("<|im_start|>user\n").append(toolResultsContent(m)).append("<|im_end|>\n")
            }
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun toolsBlock(tools: List<ToolSpec>): String = buildString {
        append("\n\n# Tools\n\nYou may call one or more functions to assist with the user query.\n\n")
        append("You are provided with function signatures within <tools></tools> XML tags:\n<tools>")
        for (t in tools) {
            append('\n').append(toolJson(t))
        }
        append("\n</tools>\n\nFor each function call, return a json object with function name and arguments ")
        append("within <tool_call></tool_call> XML tags:\n<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call>")
    }

    private fun toolJson(t: ToolSpec): String {
        val fn = JSONObject()
        fn.put("name", t.name)
        fn.put("description", t.description)
        fn.put("parameters", runCatching { JSONObject(t.parametersJsonSchema) }.getOrElse { JSONObject() })
        val wrapper = JSONObject()
        wrapper.put("type", "function")
        wrapper.put("function", fn)
        return wrapper.toString()
    }

    private fun userContent(m: Message): String = buildString {
        for (p in m.parts) {
            when (p) {
                is Part.Text -> append(p.text)
                is Part.Image -> append("[hình ảnh]")
                is Part.ToolResult -> append(toolResponse(p))
                else -> Unit
            }
        }
    }

    private fun toolResultsContent(m: Message): String = buildString {
        for (p in m.parts) {
            if (p is Part.ToolResult) append(toolResponse(p))
        }
    }

    private fun toolResponse(p: Part.ToolResult): String =
        "<tool_response>\n${p.content}\n</tool_response>"

    private fun assistantContent(m: Message): String = buildString {
        for (p in m.parts) {
            when (p) {
                is Part.Text -> append(p.text)
                is Part.ToolCall -> {
                    val call = JSONObject()
                    call.put("name", p.name)
                    call.put(
                        "arguments",
                        runCatching { JSONObject(p.argsJson) }.getOrElse { JSONObject() },
                    )
                    append("<tool_call>\n").append(call.toString()).append("\n</tool_call>")
                }
                else -> Unit // Thinking khong dua lai vao prompt ChatML
            }
        }
    }

    // ---- Phan tich phan hoi ----

    /** Phan chu nguoi dung duoc thay khi dang stream: cat tu <tool_call> tro di. */
    private fun visibleText(raw: StringBuilder): String {
        val s = raw.toString()
        val idx = s.indexOf(TOOL_CALL_OPEN)
        var visible = if (idx >= 0) s.substring(0, idx) else s
        // An not doan the dang go do (vi du "<to" o cuoi) khoi nhap nhay thanh ky tu la
        for (len in TOOL_CALL_OPEN.length - 1 downTo 1) {
            if (visible.endsWith(TOOL_CALL_OPEN.substring(0, len))) {
                visible = visible.dropLast(len)
                break
            }
        }
        return visible
    }

    private fun parseReply(
        output: String,
        tools: List<ToolSpec>,
        result: LlamaEngine.Generation,
    ): ModelReply {
        val calls = mutableListOf<Part.ToolCall>()
        var text = output
        for (match in TOOL_CALL_REGEX.findAll(output)) {
            parseToolCallJson(match.groupValues[1])?.let { calls.add(it) }
        }
        if (calls.isNotEmpty()) {
            text = TOOL_CALL_REGEX.replace(output, "")
            // Cat not khoi <tool_call> dang do (het token giua chung) — khoi hong thi bo,
            // vong lap agent se yeu cau lai thay vi nhan mot loi goi vo nghia.
            val dangling = text.indexOf(TOOL_CALL_OPEN)
            if (dangling >= 0) text = text.substring(0, dangling)
        } else {
            // Du phong: model tra JSON tran {"name":...} khong boc the
            val trimmed = output.trim()
            if (tools.isNotEmpty() && trimmed.startsWith("{")) {
                val call = parseToolCallJson(trimmed)
                if (call != null && tools.any { it.name == call.name }) {
                    calls.add(call)
                    text = ""
                }
            }
        }
        val parts = mutableListOf<Part>()
        val cleanText = text.trim()
        if (cleanText.isNotEmpty()) parts.add(Part.Text(cleanText))
        parts.addAll(calls)
        val stop = when {
            calls.isNotEmpty() -> StopReason.ToolUse
            result.finishReason == 1 || result.finishReason == 2 -> StopReason.MaxTokens
            else -> StopReason.EndTurn
        }
        return ModelReply(
            parts = parts,
            stopReason = stop,
            usage = Usage(inputTokens = result.promptTokens, outputTokens = result.generatedTokens),
        )
    }

    /** Parse mot khoi {"name": ..., "arguments": ...}; null neu khong hop le. */
    private fun parseToolCallJson(json: String): Part.ToolCall? {
        val obj = runCatching { JSONObject(json.trim()) }.getOrNull() ?: return null
        val name = obj.optString("name", "")
        if (name.isBlank()) return null
        val argsJson = when (val args = obj.opt("arguments")) {
            is JSONObject -> args.toString()
            is String -> args
            null -> "{}"
            else -> args.toString()
        }
        return Part.ToolCall(id = UUID.randomUUID().toString(), name = name, argsJson = argsJson)
    }

    companion object {
        /** Khả năng chuẩn của mô hình nội bộ (ProviderStore dùng khi gán vai Main). */
        val DEFAULT_CAPABILITIES: Capabilities = Capabilities(
            toolCalls = true,
            vision = false,
            contextTokens = LlamaEngine.N_CTX,
            reasoning = false,
            promptCaching = PromptCaching.None,
            compactionTriggerTokens = 6144,
        )

        private const val TEMP = 0.4f
        private const val TOP_P = 0.9f
        private const val TOP_K = 40
        private const val REPEAT_PENALTY = 1.05f
        private const val MAX_TOKENS = 2048
        private const val TOOL_CALL_OPEN = "<tool_call>"
        private val TOOL_CALL_REGEX =
            Regex("<tool_call>\\s*(.*?)\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)

        /** Chi dan hanh vi noi vao cuoi system prompt khi chay AI noi bo. */
        private const val VI_ADDENDUM =
            "\n\n# Cách làm việc (chế độ AI nội bộ)\n" +
                "- Luôn trả lời bằng tiếng Việt, trừ khi người dùng yêu cầu ngôn ngữ khác.\n" +
                "- Chỉ nói những gì bạn biết chắc. Nếu không biết hoặc không chắc chắn, hãy nói rõ " +
                "là không biết/không chắc — tuyệt đối không bịa số liệu, tên riêng, sự kiện hay kết quả.\n" +
                "- Thông tin có thể thay đổi theo thời gian (giá cả, tin tức, thời tiết, lịch trình...) " +
                "hoặc cần kiểm chứng: hãy dùng công cụ tìm kiếm trên web trước khi trả lời.\n" +
                "- Khi cần thao tác trên điện thoại, gọi đúng công cụ với đủ tham số; thiếu thông tin " +
                "thì hỏi lại người dùng thay vì đoán.\n" +
                "- Bạn không xem được hình ảnh. Khi hội thoại có [hình ảnh], hãy dựa vào phần mô tả " +
                "bằng chữ đi kèm."
    }
}
