package com.example.claudevoice.audio

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

class AudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "AudioPlayer"
        // TTS 引擎优先级列表（null = 系统默认）
        private val TTS_ENGINES = listOf(
            "com.google.android.tts",       // Google TTS
            "com.xiaomi.mibrain.speech",    // 小米语音
            null                            // 系统默认兜底
        )
    }

    interface Listener {
        fun onPlaybackFinished()
        fun onError(msg: String)
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var ttsInitFailed = false
    private var pendingText: String? = null
    private var pendingListener: Listener? = null
    private var engineIndex = 0

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        tryNextEngine()
    }

    private fun tryNextEngine() {
        if (engineIndex >= TTS_ENGINES.size) {
            Log.e(TAG, "所有 TTS 引擎均失败")
            ttsInitFailed = true
            pendingListener?.onError("TTS 不可用")
            pendingListener = null
            pendingText = null
            return
        }

        val engine = TTS_ENGINES[engineIndex++]
        Log.d(TAG, "尝试 TTS 引擎: ${engine ?: "系统默认"}")

        val initListener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.getDefault()
                }
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS 播完")
                        pendingListener?.onPlaybackFinished()
                        pendingListener = null
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS 播放错误")
                        pendingListener?.onError("TTS 播放失败")
                        pendingListener = null
                    }
                })
                isInitialized = true
                Log.d(TAG, "TTS 引擎初始化成功: ${engine ?: "系统默认"}")
                // 补上在初始化前排队的 speak 请求
                val t = pendingText; val l = pendingListener
                if (t != null && l != null) {
                    pendingText = null; pendingListener = null
                    speak(t, l)
                }
            } else {
                Log.w(TAG, "TTS 引擎失败 status=$status: ${engine ?: "系统默认"}，换下一个")
                tts?.shutdown(); tts = null
                tryNextEngine()
            }
        }

        tts = if (engine != null) {
            TextToSpeech(context, initListener, engine)
        } else {
            TextToSpeech(context, initListener)
        }
    }

    fun speak(text: String, listener: Listener) {
        if (ttsInitFailed) {
            Log.w(TAG, "TTS 不可用，跳过播报")
            listener.onError("TTS 不可用")
            return
        }
        if (!isInitialized) {
            pendingText = text
            pendingListener = listener
            return
        }

        pendingListener = listener

        val stream = AudioManager.STREAM_MUSIC
        val maxVol = audioManager.getStreamMaxVolume(stream)
        val curVol = audioManager.getStreamVolume(stream)
        if (curVol == 0) audioManager.setStreamVolume(stream, maxVol / 2, 0)

        val params = android.os.Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val id = UUID.randomUUID().toString()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        Log.d(TAG, "TTS speak: $text")
    }

    fun stop() { tts?.stop() }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
