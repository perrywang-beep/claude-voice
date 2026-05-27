package com.example.claudevoice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 透明 TTS Activity。
 *
 * MIUI 不允许 Service 绑定 TTS 引擎（status=-1），
 * 在 Activity 上下文中初始化 TTS 可以绕过这个限制。
 * 播放完成后发广播通知 VoiceService，然后关闭自身。
 */
class TransparentTtsActivity : Activity() {

    companion object {
        private const val TAG = "TransparentTtsActivity"
        const val ACTION_TTS_DONE = "com.example.claudevoice.TTS_DONE"
        const val EXTRA_TEXT = "tts_text"
    }

    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        Log.d(TAG, "TTS 播放: $text")
        if (text.isBlank()) { done(); return }
        initAndSpeak(text)
    }

    private fun initAndSpeak(text: String) {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.getDefault()
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        Log.d(TAG, "TTS 播完")
                        done()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        Log.e(TAG, "TTS 播放错误")
                        done()
                    }
                })
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_id")
                Log.d(TAG, "TTS speak OK")
            } else {
                Log.e(TAG, "TTS 初始化失败 status=$status")
                done()
            }
        }
    }

    private fun done() {
        sendBroadcast(Intent(ACTION_TTS_DONE).apply { setPackage(packageName) })
        finish()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
