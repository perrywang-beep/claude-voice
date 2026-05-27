package com.example.claudevoice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.TimeUnit

/**
 * 使用火山引擎 Ark TTS API 合成语音，用 MediaPlayer 播放。
 * 彻底绕过 MIUI 对 Android TextToSpeech 引擎的限制。
 */
class TtsPlayer(private val context: Context) {

    companion object {
        private const val TAG = "TtsPlayer"
        private const val TTS_URL = "https://ark.cn-beijing.volces.com/api/v3/audio/speech"
        private const val TTS_MODEL = "doubao-tts-hd"
        private const val TTS_VOICE = "zh_female_tianmei_saas"
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
            put("model", TTS_MODEL)
            put("input", text)
            put("voice", TTS_VOICE)
        }.toString()

        val req = Request.Builder()
            .url(TTS_URL)
            .header("Authorization", "Bearer ${Config.ARK_API_KEY}")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            return if (resp.isSuccessful) {
                resp.body?.bytes()
            } else {
                Log.e(TAG, "TTS HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
                null
            }
        }
    }

    private fun playBytes(bytes: ByteArray, listener: Listener) {
        val tmp = File(context.cacheDir, "tts_audio.mp3")
        tmp.writeBytes(bytes)

        mainHandler.post {
            try {
                // 确保媒体音量不为 0
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
                    Log.d(TAG, "MediaPlayer 开始")
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
