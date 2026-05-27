package com.example.claudevoice

import android.app.Activity
import android.content.ComponentName
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
 *
 * MIUI 还会让 isRecognitionAvailable() 返回 false，
 * 但实际上 Google App 的 SR 服务可以直接指定组件使用。
 */
class TransparentVoiceActivity : Activity() {

    companion object {
        private const val TAG = "TransparentVoiceActivity"
        const val ACTION_VOICE_RESULT = "com.example.claudevoice.VOICE_RESULT"
        const val EXTRA_RESULT = "result"

        // Google 语音识别服务组件（MIUI 上需要显式指定）
        private val GOOGLE_SR_COMPONENT = ComponentName(
            "com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
        )
    }

    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "启动透明 Activity，开始识别")
        startRecognition()
    }

    private fun startRecognition() {
        // 优先用 Google SR（绕过 MIUI 的 isRecognitionAvailable=false 问题）
        speechRecognizer = try {
            SpeechRecognizer.createSpeechRecognizer(this, GOOGLE_SR_COMPONENT).also {
                Log.d(TAG, "使用 Google SR 组件")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Google SR 创建失败，尝试系统默认: $e")
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                SpeechRecognizer.createSpeechRecognizer(this)
            } else {
                Log.e(TAG, "所有 SR 均不可用")
                sendResult("")
                return
            }
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "SR 就绪")
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
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "SR startListening OK")
        } catch (e: Exception) {
            Log.e(TAG, "SR startListening 失败: $e")
            sendResult("")
        }
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
