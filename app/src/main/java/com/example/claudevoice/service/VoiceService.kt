package com.example.claudevoice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.claudevoice.Config
import com.example.claudevoice.MainActivity
import com.example.claudevoice.api.VolcengineArkClient
import com.example.claudevoice.audio.AudioPlayer
import com.example.claudevoice.model.ConversationHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 常驻前台服务（豆包版）。
 *
 * 状态机：
 *   IDLE ──触发──▶ LISTENING（SpeechRecognizer）
 *        ──识别完成──▶ PROCESSING（豆包 Ark API）
 *        ──回复完成──▶ PLAYING（TTS）
 *        ──播放完成──▶ IDLE
 *
 * 所有反馈通过震动和音频完成，全程不点亮屏幕。
 */
class VoiceService : Service() {

    companion object {
        private const val TAG = "VoiceService"

        private val VIB_START = longArrayOf(0, 100)                     // 1次：开始录音
        private val VIB_END   = longArrayOf(0, 100, 100, 100)           // 2次：对话结束
        private val VIB_CLEAR = longArrayOf(0, 100, 100, 100, 100, 100) // 3次：清除上下文
    }

    // ─── 状态 ─────────────────────────────────────────────────────

    enum class State { IDLE, LISTENING, PROCESSING, PLAYING }
    @Volatile private var state = State.IDLE

    // ─── 组件 ─────────────────────────────────────────────────────

    private lateinit var audioPlayer: AudioPlayer
    private val arkClient   = VolcengineArkClient()
    private val history     = ConversationHistory()
    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var vibrator: Vibrator
    private var wakeLock: PowerManager.WakeLock? = null
    private var speechRecognizer: SpeechRecognizer? = null

    // ─── 广播接收器 ───────────────────────────────────────────────

    private val triggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Config.ACTION_TRIGGER_VOICE -> onTrigger()
                Config.ACTION_CLEAR_CONTEXT -> onClearContext()
            }
        }
    }

    // ─── 生命周期 ─────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "服务创建")

        audioPlayer = AudioPlayer(this)
        vibrator    = getVibrator()

        createNotificationChannel()
        startForeground(Config.NOTIFICATION_ID, buildNotification(State.IDLE))

        acquireWakeLock()
        registerTriggerReceiver()
        initSpeechRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.post { speechRecognizer?.destroy() }
        audioPlayer.release()
        wakeLock?.release()
        try { unregisterReceiver(triggerReceiver) } catch (_: Exception) {}
        Log.d(TAG, "服务销毁")
    }

    // ─── 触发入口 ─────────────────────────────────────────────────

    private fun onTrigger() {
        if (state != State.IDLE) {
            Log.d(TAG, "当前状态 $state，忽略触发")
            return
        }
        startListening()
    }

    private fun onClearContext() {
        history.clear()
        vibrate(VIB_CLEAR)
        Log.d(TAG, "上下文已清除")
    }

    // ─── 状态机各阶段 ─────────────────────────────────────────────

    private fun startListening() {
        setState(State.LISTENING)
        vibrate(VIB_START)
        updateNotification(State.LISTENING)

        mainHandler.post {
            if (speechRecognizer == null) {
                // 懒重建（某些设备 destroy 后需要重新创建）
                initSpeechRecognizerSync()
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                // 关键：不显示 UI
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }

            try {
                speechRecognizer?.startListening(intent)
                Log.d(TAG, "SpeechRecognizer 开始监听")
            } catch (e: Exception) {
                Log.e(TAG, "startListening 失败: ${e.message}")
                resetToIdle()
            }
        }
    }

    private fun onSpeechResult(text: String) {
        if (text.isBlank()) {
            Log.d(TAG, "识别结果为空，回到待命")
            resetToIdle()
            return
        }
        Log.d(TAG, "识别结果: $text")
        sendToArk(text)
    }

    private fun sendToArk(userText: String) {
        setState(State.PROCESSING)
        updateNotification(State.PROCESSING)

        scope.launch {
            arkClient.chat(userText, history, object : VolcengineArkClient.StreamListener {
                override fun onToken(token: String) { /* 可做流式 TTS 优化 */ }

                override fun onComplete(fullText: String) {
                    history.addUserMessage(userText)
                    history.addAssistantMessage(fullText)
                    playResponse(fullText)
                }

                override fun onError(message: String) {
                    Log.e(TAG, "ARK 错误: $message")
                    resetToIdle()
                }
            })
        }
    }

    private fun playResponse(text: String) {
        setState(State.PLAYING)
        updateNotification(State.PLAYING)

        audioPlayer.speak(text, object : AudioPlayer.Listener {
            override fun onPlaybackFinished() = resetToIdle()
            override fun onError(msg: String) {
                Log.e(TAG, "TTS 错误: $msg")
                resetToIdle()
            }
        })
    }

    private fun resetToIdle() {
        setState(State.IDLE)
        updateNotification(State.IDLE)
        vibrate(VIB_END)
    }

    // ─── SpeechRecognizer ─────────────────────────────────────────

    private fun initSpeechRecognizer() {
        mainHandler.post { initSpeechRecognizerSync() }
    }

    private fun initSpeechRecognizerSync() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "设备不支持语音识别，请确认已安装 Google 语音服务或系统自带 ASR")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(recognitionListener)
        Log.d(TAG, "SpeechRecognizer 初始化完成")
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "ASR: 就绪，开始说话")
        }
        override fun onBeginningOfSpeech() {
            Log.d(TAG, "ASR: 检测到语音开始")
        }
        override fun onRmsChanged(rmsdB: Float) { /* 可用于音量动画 */ }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            Log.d(TAG, "ASR: 检测到语音结束，处理中…")
        }
        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_AUDIO          -> "音频录制错误"
                SpeechRecognizer.ERROR_CLIENT         -> "客户端错误"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                SpeechRecognizer.ERROR_NETWORK        -> "网络错误"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                SpeechRecognizer.ERROR_NO_MATCH       -> "未识别到语音"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
                SpeechRecognizer.ERROR_SERVER         -> "服务器错误"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "等待语音超时"
                else -> "未知错误($error)"
            }
            Log.e(TAG, "ASR 错误: $msg")

            // 识别器忙时尝试重置
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                mainHandler.post {
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                    initSpeechRecognizerSync()
                }
            }
            resetToIdle()
        }
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text    = matches?.firstOrNull() ?: ""
            Log.d(TAG, "ASR 结果: $text")
            onSpeechResult(text)
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ─── 通知 ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                Config.NOTIFICATION_CHANNEL_ID,
                "豆包语音助手",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(s: State): Notification {
        val title = when (s) {
            State.IDLE       -> "豆包待命中"
            State.LISTENING  -> "🎙 正在听…"
            State.PROCESSING -> "🤔 豆包思考中…"
            State.PLAYING    -> "🔊 豆包回复中…"
        }
        val text = when (s) {
            State.IDLE -> "长按音量下键开始对话"
            else       -> ""
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Config.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    private fun updateNotification(s: State) {
        getSystemService(NotificationManager::class.java)
            .notify(Config.NOTIFICATION_ID, buildNotification(s))
    }

    // ─── 工具 ─────────────────────────────────────────────────────

    private fun setState(s: State) { state = s; Log.d(TAG, "→ $s") }

    private fun vibrate(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)
                .defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClaudeVoice:WakeLock")
            .also { it.acquire(12 * 60 * 60 * 1000L) }
    }

    private fun registerTriggerReceiver() {
        val filter = IntentFilter().apply {
            addAction(Config.ACTION_TRIGGER_VOICE)
            addAction(Config.ACTION_CLEAR_CONTEXT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(triggerReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(triggerReceiver, filter)
        }
    }

    private fun getVibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            getSystemService(VibratorManager::class.java).defaultVibrator
        else
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
}
