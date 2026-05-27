package com.example.claudevoice.api

import android.util.Base64
import android.util.Log
import com.example.claudevoice.Config
import com.example.claudevoice.model.ConversationHistory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Claude API 客户端。
 *
 * 调用 Anthropic Messages API（HTTP SSE 流式输出）。
 * 音频以 base64 WAV 格式传入；助手回复以文本流式返回。
 *
 * 关于音频输入格式：
 *   当前使用 Anthropic 的 multimodal 音频输入格式。
 *   如 API 端点或 content type 字段有变更，仅需修改 [buildUserAudioContent]。
 *
 * Anthropic 文档：https://docs.anthropic.com/en/api/messages
 */
class ClaudeApiClient {

    companion object {
        private const val TAG = "ClaudeApiClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    interface StreamListener {
        /** 每收到一个文本 token 触发 */
        fun onToken(token: String)
        /** 流式输出完成 */
        fun onComplete(fullText: String)
        /** 发生错误 */
        fun onError(message: String)
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 以流式方式发送音频给 Claude。
     *
     * @param wavBytes  16kHz 单声道 PCM WAV 字节数组
     * @param history   当前对话历史（不含本轮用户消息）
     * @param listener  流式回调
     */
    fun sendAudio(
        wavBytes: ByteArray,
        history: ConversationHistory,
        listener: StreamListener
    ) {
        if (Config.CLAUDE_API_KEY.isBlank()) {
            listener.onError("请在 Config.kt 中填写 CLAUDE_API_KEY")
            return
        }

        val body = buildRequestBody(wavBytes, history)
        Log.d(TAG, "发送请求，历史长度: ${history.size()}")

        val request = Request.Builder()
            .url(Config.API_BASE_URL)
            .header("x-api-key", Config.CLAUDE_API_KEY)
            .header("anthropic-version", Config.API_VERSION)
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            // 音频 beta 功能（若 API 要求）
            .header("anthropic-beta", "audio-1")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: "unknown error"
                    Log.e(TAG, "HTTP 错误 ${response.code}: $errBody")
                    listener.onError("API 错误 ${response.code}: $errBody")
                    return
                }

                parseStream(response.body?.byteStream()?.bufferedReader(), listener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "请求异常: ${e.message}")
            listener.onError("网络错误: ${e.message}")
        }
    }

    // ─── 请求体构建 ───────────────────────────────────────────────

    private fun buildRequestBody(wavBytes: ByteArray, history: ConversationHistory): JSONObject {
        val messagesArray = history.toJsonArray()

        // 追加本轮用户音频消息
        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", buildUserAudioContent(wavBytes))
        }
        messagesArray.put(userMessage)

        return JSONObject().apply {
            put("model", Config.MODEL)
            put("max_tokens", Config.MAX_TOKENS)
            put("system", Config.SYSTEM_PROMPT)
            put("stream", true)
            put("messages", messagesArray)
        }
    }

    /**
     * 构建音频类型的用户消息 content 数组。
     *
     * Anthropic 音频输入格式（参考官方文档）：
     * {
     *   "type": "document",
     *   "source": {
     *     "type": "base64",
     *     "media_type": "audio/wav",
     *     "data": "<base64>"
     *   }
     * }
     *
     * 若 API 更新了字段名，在此处修改即可。
     */
    private fun buildUserAudioContent(wavBytes: ByteArray): JSONArray {
        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        val sourceObj = JSONObject().apply {
            put("type", "base64")
            put("media_type", "audio/wav")
            put("data", base64Audio)
        }

        val audioBlock = JSONObject().apply {
            put("type", "document")
            put("source", sourceObj)
        }

        return JSONArray().apply { put(audioBlock) }
    }

    // ─── SSE 流解析 ───────────────────────────────────────────────

    /**
     * 逐行解析 Anthropic SSE 响应流。
     *
     * 事件格式示例：
     *   data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello"}}
     *   data: {"type":"message_stop"}
     */
    private fun parseStream(reader: BufferedReader?, listener: StreamListener) {
        if (reader == null) {
            listener.onError("响应体为空")
            return
        }

        val fullText = StringBuilder()

        reader.use { br ->
            var line: String?
            while (br.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (!trimmed.startsWith("data:")) continue

                val data = trimmed.removePrefix("data:").trim()
                if (data == "[DONE]") break

                try {
                    val json = JSONObject(data)
                    val type = json.optString("type")

                    when (type) {
                        "content_block_delta" -> {
                            val delta = json.optJSONObject("delta")
                            val deltaType = delta?.optString("type")
                            if (deltaType == "text_delta") {
                                val text = delta.optString("text", "")
                                if (text.isNotEmpty()) {
                                    fullText.append(text)
                                    listener.onToken(text)
                                }
                            }
                        }
                        "message_stop" -> {
                            Log.d(TAG, "流结束，总文本长度: ${fullText.length}")
                            break
                        }
                        "error" -> {
                            val errMsg = json.optJSONObject("error")?.optString("message") ?: "未知错误"
                            Log.e(TAG, "API 返回错误: $errMsg")
                            listener.onError(errMsg)
                            return
                        }
                        else -> { /* content_block_start, message_start 等，忽略 */ }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "解析 SSE 行失败: $data")
                }
            }
        }

        if (fullText.isNotEmpty()) {
            listener.onComplete(fullText.toString())
        } else {
            listener.onError("Claude 返回了空响应")
        }
    }
}
