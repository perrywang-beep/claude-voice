package com.example.claudevoice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * 音频播放器。
 *
 * 使用 Android 系统 TTS 将 Claude 的文字回复转为语音播放。
 * - 自动申请音频焦点（使蓝牙耳机播放）
 * - 播放完成后回调 [onFinished]
 */
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
    private var pendingText: String? = null
    private var pendingListener: Listener? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // 优先使用中文，不可用则退回系统默认
                val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "中文 TTS 不可用，使用系统默认语言")
                    tts?.language = Locale.getDefault()
                }
                tts?.setSpeechRate(1.05f)   // 语速略快，更自然
                tts?.setPitch(1.0f)

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS 播放完成")
                        abandonAudioFocus()
                        pendingListener?.onPlaybackFinished()
                        pendingListener = null
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS 播放错误")
                        abandonAudioFocus()
                        pendingListener?.onError("TTS 播放失败")
                        pendingListener = null
                    }
                })

                isInitialized = true
                Log.d(TAG, "TTS 初始化成功")

                // 处理初始化前的待播放请求
                pendingText?.let { text ->
                    pendingListener?.let { listener ->
                        speak(text, listener)
                    }
                }
            } else {
                Log.e(TAG, "TTS 初始化失败，status=$status")
                pendingListener?.onError("TTS 初始化失败")
            }
        }
    }

    /**
     * 播放文字。如果 TTS 尚未初始化，将在初始化完成后自动播放。
     */
    fun speak(text: String, listener: Listener) {
        if (!isInitialized) {
            pendingText     = text
            pendingListener = listener
            return
        }

        pendingListener = listener
        requestAudioFocus()

        val utteranceId = UUID.randomUUID().toString()
        val params = android.os.Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        Log.d(TAG, "开始 TTS 播放，文本长度: ${text.length}")
    }

    /** 立即停止当前播放 */
    fun stop() {
        tts?.stop()
        abandonAudioFocus()
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        abandonAudioFocus()
    }

    // ─── 音频焦点 ─────────────────────────────────────────────────

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
