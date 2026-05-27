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
 * 透明语音识别 Activity，带三级 fallback：
 *   1. 系统默认 SpeechRecognizer（MIUI 内置引擎）
 *   2. Google SR 组件（需 Google App）
 *   3. startActivityForResult ACTION_RECOGNIZE_SPEECH（系统弹框，万能兜底）
 */
class TransparentVoiceActivity : Activity() {

    companion object {
        private const val TAG = "TransparentVoiceActivity"
        const val ACTION_VOICE_RESULT = "com.example.claudevoice.VOICE_RESULT"
        const val EXTRA_RESULT = "result"
        private const val REQ_SPEECH = 101

        private val GOOGLE_SR = ComponentName(
            "com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
        )
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var strategyIndex = 0          // 当前正在尝试哪个方案

    // 每个方案：lambda 返回 SpeechRecognizer，null 表示跳过
    private val srFactories: List<() -> SpeechRecognizer?> = listOf(
        {
            // 方案 1：系统默认（MIUI 内置，跳过 isRecognitionAvailable 检查）
            Log.d(TAG, "SR 方案1: 系统默认")
            SpeechRecognizer.createSpeechRecognizer(this)
        },
        {
            // 方案 2：Google SR 组件
            Log.d(TAG, "SR 方案2: Google 组件")
            SpeechRecognizer.createSpeechRecognizer(this, GOOGLE_SR)
        }
    )

    private val srIntent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "启动")
        tryNextSrStrategy()
    }

    // ── 方案切换核心逻辑 ──────────────────────────────────────────

    private fun tryNextSrStrategy() {
        if (strategyIndex >= srFactories.size) {
            // 所有 SpeechRecognizer 方案都失败，用系统 Intent 弹框（方案3）
            trySystemIntentFallback()
            return
        }

        val factory = srFactories[strategyIndex++]
        speechRecognizer?.destroy()
        speechRecognizer = null

        try {
            speechRecognizer = factory() ?: run { tryNextSrStrategy(); return }
            speechRecognizer!!.setRecognitionListener(recognitionListener)
            speechRecognizer!!.startListening(srIntent)
            Log.d(TAG, "SR startListening OK (策略${strategyIndex})")
        } catch (e: Exception) {
            Log.w(TAG, "SR 策略${strategyIndex} 失败: $e，换下一个")
            tryNextSrStrategy()
        }
    }

    // 方案 3：用系统 Intent，让系统弹出语音识别框（小爱/MIUI/任何引擎都能接）
    private fun trySystemIntentFallback() {
        Log.d(TAG, "SR 方案3: 系统 Intent 弹框")
        try {
            startActivityForResult(srIntent, REQ_SPEECH)
        } catch (e: Exception) {
            Log.e(TAG, "所有 SR 方案均失败: $e")
            sendResult("")
        }
    }

    // 方案 3 的结果回调
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_SPEECH) {
            val text = if (resultCode == RESULT_OK) {
                data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull() ?: ""
            } else ""
            Log.d(TAG, "系统 Intent 结果: $text")
            sendResult(text)
        }
    }

    // ── RecognitionListener ───────────────────────────────────────

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "SR 就绪（策略${strategyIndex}）")
            sendBroadcast(Intent("com.example.claudevoice.SR_READY").apply {
                setPackage(packageName)
            })
        }
        override fun onBeginningOfSpeech() { Log.d(TAG, "检测到语音") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { Log.d(TAG, "语音结束") }

        override fun onError(error: Int) {
            Log.w(TAG, "SR 错误 $error（策略${strategyIndex}），尝试下一个")
            // 换下一个方案
            tryNextSrStrategy()
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: ""
            Log.d(TAG, "识别结果: \"$text\"")
            sendResult(text)
        }
        override fun onPartialResults(partial: Bundle?) {}
        override fun onEvent(type: Int, params: Bundle?) {}
    }

    // ── 结果发回 Service ──────────────────────────────────────────

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
