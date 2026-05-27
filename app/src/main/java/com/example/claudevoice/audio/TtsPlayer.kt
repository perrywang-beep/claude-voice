package com.example.claudevoice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.example.claudevoice.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 使用火山引擎语音合成 API 合成语音，MediaPlayer 播放。
 * 彻底绕过 MIUI 对 Android TextToSpeech 的限制。
 */
class TtsPlayer(private val context: Context) {

    companion object {
        private const val TAG = "TtsPlayer"
    }

    interface Listener {
        fun onPlaybackFinished()
        fun onError(msg: String)
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null

    fun speak(text: String, listener: Listener) {
        scope.launch {
            try {
                Log.d(TAG, "请求 TTS: $text")
                val bytes = fetchAudio(text)
                if (bytes == null || bytes.isEmpty()) {
                    listener.onError("TTS API 无数据")
                    return@launch
                }
                Log.d(TAG, "收到音频 ${bytes.size} 字节")
                playBytes(bytes, listener)
            } catch (e: Exception) {
                Log.e(TAG, "TTS 异常: $e")
                listener.onError(e.message ?: "TTS 失败")
            }
        }
    }

    private fun fetchAudio(text: String): ByteArray? {
        val body = JSONObject().apply {
            put("app", JSONObject().apply {
                put("appid",   Config.TTS_APP_ID)
                put("token",   Config.TTS_TOKEN)
                put("cluster", Config.TTS_CLUSTER)
            })
            put("user", JSONObject().apply {
                put("uid", "voice_app")
            })
            put("audio", JSONObject().apply {
                put("voice_type",   Config.TTS_VOICE)
                put("encoding",     "mp3")
                put("speed_ratio",  1.0)
                put("volume_ratio", 1.0)
                put("pitch_ratio",  1.0)
            })
            put("request", JSONObject().apply {
                put("reqid",     UUID.randomUUID().toString())
                put("text",      text)
                put("text_type", "plain")
                put("operation", "query")
            })
        }.toString()

        val req = Request.Builder()
            .url(Config.TTS_URL)
            .header("Authorization", "Bearer;${Config.TTS_TOKEN}")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: run {
                Log.e(TAG, "TTS 响应体为空")
                return null
            }
            return if (resp.isSuccessful) {
                val json = JSONObject(bodyStr)
                val code = json.optInt("code", -1)
                if (code == 3000) {
                    val data = json.optString("data", "")
                    if (data.isEmpty()) { Log.e(TAG, "TTS data 为空"); null }
                    else Base64.decode(data, Base64.DEFAULT)
                } else {
                    Log.e(TAG, "TTS API code=$code: ${bodyStr.take(300)}")
                    null
                }
            } else {
                Log.e(TAG, "TTS HTTP ${resp.code}: ${bodyStr.take(300)}")
                null
            }
        }
    }

    private fun playBytes(bytes: ByteArray, listener: Listener) {
        val tmp = File(context.cacheDir, "tts_audio.mp3")
        tmp.writeBytes(bytes)

        mainHandler.post {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, 0)

                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(tmp.absolutePath)
                    setOnCompletionListener {
                        Log.d(TAG, "播放完成")
                        tmp.delete()
                        listener.onPlaybackFinished()
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer 错误 what=$what extra=$extra")
                        tmp.delete()
                        listener.onError("播放失败 $what")
                        true
                    }
                    prepare()
                    start()
                    Log.d(TAG, "MediaPlayer 开始播放")
                }
            } catch (e: Exception) {
                Log.e(TAG, "播放异常: $e")
                listener.onError(e.message ?: "播放失败")
            }
        }
    }

    fun stop() {
        mainHandler.post {
            try { mediaPlayer?.stop() } catch (_: Exception) {}
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    fun release() = stop()
}
