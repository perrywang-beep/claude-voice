package com.example.claudevoice.api

import android.util.Base64
import android.util.Log
import com.example.claudevoice.Config
import com.example.claudevoice.audio.AudioRecorder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 火山引擎语音识别（ASR）HTTP 客户端。
 * 与 TTS 共用同一个 AppID/Token，但需要在控制台单独开通「语音识别」服务。
 *
 * 发送 16kHz/Mono/PCM-16bit 音频，返回中文识别文本。
 */
class VolcengineAsrClient {

    companion object {
        private const val TAG = "VolcengineAsrClient"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * @param pcm  16kHz Mono PCM-16bit 字节流（小端序）
     * @return 识别文本；网络失败或无结果返回 null
     */
    fun recognize(pcm: ByteArray): String? {
        val audioB64 = Base64.encodeToString(pcm, Base64.NO_WRAP)

        val body = JSONObject().apply {
            put("app", JSONObject().apply {
                put("appid",   Config.TTS_APP_ID)
                put("token",   Config.TTS_TOKEN)
                put("cluster", Config.ASR_CLUSTER)
            })
            put("user", JSONObject().apply { put("uid", "voice_app") })
            put("audio", JSONObject().apply {
                put("format",      "pcm")
                put("sample_rate", AudioRecorder.SAMPLE_RATE)
                put("bits",        16)
                put("channel",     1)
                put("codec",       "raw")
                put("data",        audioB64)   // 音频 base64 在 audio.data，不是顶层
            })
            put("request", JSONObject().apply {
                put("reqid",           UUID.randomUUID().toString())
                put("sequence",        -1)
                put("nbest",           1)
                put("show_utterances", false)
            })
        }.toString()

        val req = Request.Builder()
            .url(Config.ASR_URL)
            .header("Authorization", "Bearer;${Config.TTS_TOKEN}")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: run {
                    Log.e(TAG, "ASR 空响应 HTTP=${resp.code}")
                    return null
                }
                Log.d(TAG, "ASR raw: ${raw.take(400)}")

                val json = JSONObject(raw)
                val code = json.optInt("code", -1)
                if (code != 1000) {
                    Log.e(TAG, "ASR 失败 code=$code msg=${json.optString("message")}")
                    return null
                }

                // 响应结构: { code:1000, result:[{text:"..."}] }
                json.optJSONArray("result")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ASR 请求失败: $e")
            null
        }
    }
}
