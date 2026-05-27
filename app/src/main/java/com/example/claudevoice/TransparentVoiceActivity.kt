package com.example.claudevoice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 透明语音识别 Activity。
 *
 * MIUI 不允许从后台 Service 直接调用 SpeechRecognizer，
 * 通过启动一个透明 Activity 来绕过这个限制。
 * 识别完成后立即发广播给 VoiceService 并关闭自身。
 */
class TransparentVoiceActivity : Activity() {

    companion object {
        private const val TAG = "TransparentVoiceActivity"
        const val ACTION_VOICE_RESULT = "com.example.claudevoice.VOICE_RESULT"
        const val EXTRA_RESULT = "result"
    }

    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 无界面，直接开始识别
        Log.d(TAG, "启动透明 Activity，开始识别")
        startRecognition()
    }

    private fun startRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "语音识别不可用")
            sendResult("")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "SR 就绪")
                // 通知 Service 发出"可以说话"的震动
                sendBroadcast(Intent("com.example.claudevoice.SR_READY").apply {
                    setPackage(packageName)
                })
            }
            override fun onBeginningOfSpeech() { Log.d(TAG, "检测到语音") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Log.d(TAG, "语音结束") }
            override fun onError(error: Int) {
                Log.e(TAG, "SR 错误 $error")
                sendResult("")
            }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                Log.d(TAG, "识别结果: $text")
                sendResult(text)
            }
            override fun onPartialResults(partial: Bundle?) {}
            override fun onEvent(type: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun sendResult(text: String) {
        sendBroadcast(Intent(ACTION_VOICE_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_RESULT, text)
        })
        finish()
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }
}
