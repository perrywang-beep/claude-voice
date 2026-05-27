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
    }

    interface Listener {
        fun onPlaybackFinished()
        fun onError(msg: String)
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var ttsInitFailed = false   // TTS 引擎初始化彻底失败（MIUI 常见）
    private var pendingText: String? = null
    private var pendingListener: Listener? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
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
                        Log.e(TAG, "TTS 错误")
                        pendingListener?.onError("TTS 失败")
                        pendingListener = null
                    }
                })

                isInitialized = true
                Log.d(TAG, "TTS 初始化成功")

                // 如果 speak() 在初始化前被调用，现在补上
                val t = pendingText
                val l = pendingListener
                if (t != null && l != null) {
                    pendingText = null
                    pendingListener = null
                    speak(t, l)
                }
            } else {
                Log.e(TAG, "TTS 初始化失败 status=$status")
                ttsInitFailed = true
                // 把排队的 listener 立刻通知失败，让调用方继续走流程
                pendingListener?.onError("TTS 初始化失败")
                pendingListener = null
                pendingText = null
            }
        }
    }

    fun speak(text: String, listener: Listener) {
        // TTS 已经确认初始化失败，直接回调 onError
        if (ttsInitFailed) {
            Log.w(TAG, "TTS 已失败，跳过播报")
            listener.onError("TTS 不可用")
            return
        }

        if (!isInitialized) {
            // 还在初始化中，排队等待
            pendingText     = text
            pendingListener = listener
            return
        }

        pendingListener = listener

        // 确保媒体音量不为 0
        val stream = AudioManager.STREAM_MUSIC
        val maxVol = audioManager.getStreamMaxVolume(stream)
        val curVol = audioManager.getStreamVolume(stream)
        if (curVol == 0) {
            audioManager.setStreamVolume(stream, maxVol / 2, 0)
        }

        val params = android.os.Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val id = UUID.randomUUID().toString()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        Log.d(TAG, "TTS speak: $text")
    }

    fun stop() {
        tts?.stop()
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
