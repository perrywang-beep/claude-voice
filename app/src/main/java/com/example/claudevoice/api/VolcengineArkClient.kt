package com.example.claudevoice.api

import android.util.Log
import com.example.claudevoice.Config
import com.example.claudevoice.model.ConversationHistory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * 火山方舟（豆包）API 客户端。
 *
 * 使用 API Key Bearer 认证 + OpenAI 兼容 SSE 流式接口。
 * 文档：https://www.volcengine.com/docs/82379/1302008
 */
class VolcengineArkClient {

    companion object {
        private const val TAG = "VolcengineArkClient"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    interface StreamListener {
        fun onToken(token: String)
        fun onComplete(fullText: String)
        fun onError(message: String)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun chat(userText: String, history: ConversationHistory, listener: StreamListener) {
        if (Config.ARK_API_KEY.isBlank()) {
            listener.onError("请在 Config.kt 中填写 ARK_API_KEY")
            return
        }
        if (Config.ARK_ENDPOINT_ID.isBlank()) {
            listener.onError("请在 Config.kt 中填写 ARK_ENDPOINT_ID（推理接入点 ID）")
            return
        }

        val bodyStr = buildRequestBody(userText, history).toString()

        val request = Request.Builder()
            .url(Config.ARK_URL)
            .header("Authorization", "Bearer ${Config.ARK_API_KEY}")
            .header("Content-Type", "application/json")
            .post(bodyStr.toRequestBody(JSON_TYPE))
            .build()

        Log.d(TAG, "发送请求：$userText")

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: "unknown"
                    Log.e(TAG, "HTTP ${response.code}: $err")
                    listener.onError("API 错误 ${response.code}：$err")
                    return
                }
                parseStream(response.body?.byteStream()?.bufferedReader(), listener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "请求异常: ${e.message}")
            listener.onError("网络错误：${e.message}")
        }
    }

    private fun buildRequestBody(userText: String, history: ConversationHistory): JSONObject {
        val messages = history.toJsonArray()
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userText)
        })
        return JSONObject().apply {
            put("model", Config.ARK_ENDPOINT_ID)
            put("max_tokens", Config.MAX_TOKENS)
            put("stream", true)
            put("messages", messages)
        }
    }

    private fun parseStream(reader: BufferedReader?, listener: StreamListener) {
        if (reader == null) { listener.onError("响应体为空"); return }

        val full = StringBuilder()
        reader.use { br ->
            var line: String?
            while (br.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (!trimmed.startsWith("data:")) continue
                val data = trimmed.removePrefix("data:").trim()
                if (data == "[DONE]") break
                try {
                    val json    = JSONObject(data)
                    val choices = json.optJSONArray("choices") ?: continue
                    val delta   = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                    val text    = delta.optString("content", "")
                    if (text.isNotEmpty()) {
                        full.append(text)
                        listener.onToken(text)
                    }
                    if (choices.getJSONObject(0).optString("finish_reason") == "stop") break
                } catch (_: Exception) {}
            }
        }

        if (full.isNotEmpty()) listener.onComplete(full.toString())
        else listener.onError("豆包返回了空响应")
    }
}
