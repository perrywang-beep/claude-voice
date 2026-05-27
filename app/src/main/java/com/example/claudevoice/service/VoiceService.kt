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
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
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
import android.view.KeyEvent
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
 * 常驻前台服务。
 *
 * 触发方式：
 *   1. 音量下键长按（AccessibilityService）
 *   2. 蓝牙耳机按键（MediaSession 回调）
 *   3. MainActivity 手动测试按钮
 *
 * 状态机：IDLE → LISTENING → PROCESSING → PLAYING → IDLE
 */
class VoiceService : Service() {

    companion object {
        private const val TAG = "VoiceService"

        const val ACTION_MANUAL_TRIGGER = "com.example.claudevoice.MANUAL_TRIGGER"

        private val VIB_START = longArrayOf(0, 100)
        private val VIB_END   = longArrayOf(0, 100, 100, 100)
        private val VIB_CLEAR = longArrayOf(0, 100, 100, 100, 100, 100)
    }

    enum class State { IDLE, LISTENING, PROCESSING, PLAYING }
    @Volatile private var state = State.IDLE

    private lateinit var audioPlayer: AudioPlayer
    private val arkClient   = VolcengineArkClient()
    private val history     = ConversationHistory()
    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var vibrator: Vibrator
    private var wakeLock: PowerManager.WakeLock? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaSession: MediaSession? = null

    // ─── 广播接收器（音量键 / 手动触发） ─────────────────────────

    private val triggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Config.ACTION_TRIGGER_VOICE,
                ACTION_MANUAL_TRIGGER     -> onTrigger()
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
        setupMediaSession()      // ← 蓝牙耳机支持

        // 启动时震动一次，确认服务已启动
        vibrate(VIB_START)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        mediaSession?.release()
        mediaSession = null
        mainHandler.post { speechRecognizer?.destroy() }
        audioPlayer.release()
        wakeLock?.release()
        try { unregisterReceiver(triggerReceiver) } catch (_: Exception) {}
        Log.d(TAG, "服务销毁")
    }

    // ─── MediaSession（蓝牙耳机按键） ────────────────────────────

    /**
     * 注册 MediaSession 使 App 成为"活跃媒体应用"。
     * 现代 TWS 耳机（AirPods、OPPO Enco 等）通过 MediaSession 路由按键事件，
     * 而不发送旧版 ACTION_MEDIA_BUTTON 广播。
     */
    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "ClaudeVoice").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }

                    Log.d(TAG, "MediaSession 按键: ${event?.keyCode} action=${event?.action}")

                    if (event?.action == KeyEvent.ACTION_DOWN) {
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_HEADSETHOOK,
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                onTrigger()
                                return true
                            }
                        }
                    }
                    return false
                }
            })

            // 设置 PlaybackState，让系统认为这是活跃的媒体 App
            val state = PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PAUSED, 0, 1.0f)
                .build()
            setPlaybackState(state)

            isActive = true
        }

        // 向系统注册为媒体按键监听者
        val audioManager = getSystemService(AudioManager::class.java)
        audioManager.registerMediaButtonEventReceiver(
            android.content.ComponentName(this, com.example.claudevoice.receiver.HeadsetButtonReceiver::class.java)
        )

        Log.d(TAG, "MediaSession 已激活")
    }

    // ─── 触发入口 ─────────────────────────────────────────────────

    fun onTrigger() {
        if (state != State.IDLE) {
            Log.d(TAG, "当前状态 $state，忽略")
            return
        }
        startListening()
    }

    private fun onClearContext() {
        history.clear()
        vibrate(VIB_CLEAR)
        Log.d(TAG, "上下文已清除")
    }

    // ─── 状态机 ───────────────────────────────────────────────────

    private fun startListening() {
        setState(State.LISTENING)
        vibrate(VIB_START)
        updateNotification(State.LISTENING)

        // 先播报提示语，播完再开始录音
        audioPlayer.speak("需要什么帮助吗", object : AudioPlayer.Listener {
            override fun onPlaybackFinished() {
                mainHandler.post { startSpeechRecognizer() }
            }
            override fun onError(msg: String) {
                // TTS 失败也继续录音
                mainHandler.post { startSpeechRecognizer() }
            }
        })
    }

    private fun startSpeechRecognizer() {
        if (speechRecognizer == null) initSpeechRecognizerSync()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "开始监听")
        } catch (e: Exception) {
            Log.e(TAG, "startListening 失败: ${e.message}")
            resetToIdle()
        }
    }

    private fun sendToArk(userText: String) {
        setState(State.PROCESSING)
        updateNotification(State.PROCESSING)

        scope.launch {
            arkClient.chat(userText, history, object : VolcengineArkClient.StreamListener {
                override fun onToken(token: String) {}
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
            override fun onError(msg: String) { resetToIdle() }
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
            Log.w(TAG, "设备不支持语音识别")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(recognitionListener)
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            Log.e(TAG, "ASR 错误码: $error")
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
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: ""
            Log.d(TAG, "识别结果: $text")
            if (text.isBlank()) resetToIdle() else sendToArk(text)
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ─── 通知 ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                Config.NOTIFICATION_CHANNEL_ID, "豆包语音助手",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(s: State): Notification {
        val title = when (s) {
            State.IDLE       -> "豆包待命中"
            State.LISTENING  -> "🎙 正在听…"
            State.PROCESSING -> "🤔 思考中…"
            State.PLAYING    -> "🔊 回复中…"
        }

        // 点击通知主体 → 打开 MainActivity
        val openAppPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        // 通知栏「开始」按钮 → 直接触发语音
        val triggerIntent = Intent(ACTION_MANUAL_TRIGGER).apply { setPackage(packageName) }
        val triggerPi = PendingIntent.getBroadcast(
            this, 1, triggerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, Config.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (s == State.IDLE) "点「开始」或长按音量下键" else "")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openAppPi)
            .addAction(android.R.drawable.ic_btn_speak_now, "🎙 开始", triggerPi)
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
            addAction(ACTION_MANUAL_TRIGGER)
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
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
}
