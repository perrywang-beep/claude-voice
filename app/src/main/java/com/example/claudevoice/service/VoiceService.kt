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
import android.media.session.MediaSession
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.claudevoice.Config
import com.example.claudevoice.MainActivity
import com.example.claudevoice.TransparentVoiceActivity
import com.example.claudevoice.api.VolcengineArkClient
import com.example.claudevoice.audio.TtsPlayer
import com.example.claudevoice.model.ConversationHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    private lateinit var ttsPlayer: TtsPlayer
    private val arkClient   = VolcengineArkClient()
    private var mediaSession: MediaSession? = null
    private val history     = ConversationHistory()
    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    // 提示音超时兜底
    private val greetingTimeoutRunnable = Runnable {
        if (state == State.LISTENING) {
            Log.w(TAG, "提示音超时，直接启动 SR")
            startTransparentSr()
        }
    }

    private val triggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Config.ACTION_TRIGGER_VOICE,
                ACTION_MANUAL_TRIGGER       -> onTrigger()
                Config.ACTION_CLEAR_CONTEXT -> onClearContext()

                TransparentVoiceActivity.ACTION_VOICE_RESULT -> {
                    val text = intent.getStringExtra(TransparentVoiceActivity.EXTRA_RESULT) ?: ""
                    Log.d(TAG, "SR 结果: \"$text\"")
                    if (text.isBlank()) {
                        // 没听到说话 → 结束本轮持续对话
                        resetToIdle()
                    } else {
                        sendToArk(text)
                    }
                }
                "com.example.claudevoice.SR_READY" -> {
                    try { vibrate(longArrayOf(0, 500)) } catch (_: Exception) {}
                }
            }
        }
    }

    // ─── 生命周期 ─────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        try { createNotificationChannel() } catch (e: Exception) { Log.e(TAG, "channel: $e") }
        try { startForeground(Config.NOTIFICATION_ID, buildNotification(State.IDLE)) }
        catch (e: Exception) { Log.e(TAG, "startForeground: $e") }

        try { ttsPlayer = TtsPlayer(this) } catch (e: Exception) { Log.e(TAG, "TtsPlayer: $e") }
        try { acquireWakeLock() }         catch (e: Exception) { Log.e(TAG, "WakeLock: $e") }
        try { registerTriggerReceiver() } catch (e: Exception) { Log.e(TAG, "Receiver: $e") }
        try { setupMediaSession() }       catch (e: Exception) { Log.e(TAG, "MediaSession: $e") }

        try { vibrate(VIB_START) } catch (e: Exception) { Log.e(TAG, "vibrate: $e") }
        Log.d(TAG, "onCreate 完成")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        try { ttsPlayer.release() }               catch (_: Exception) {}
        try { wakeLock?.release() }               catch (_: Exception) {}
        try { unregisterReceiver(triggerReceiver) } catch (_: Exception) {}
        try { mediaSession?.release() }           catch (_: Exception) {}
        Log.d(TAG, "onDestroy")
    }

    // ─── 触发 ─────────────────────────────────────────────────────

    fun onTrigger() {
        Log.d(TAG, "onTrigger, state=$state")
        when (state) {
            State.IDLE -> startListening()
            State.PLAYING -> {
                // 打断：立刻停 TTS，重新开始（带提示音）
                Log.d(TAG, "打断 TTS，重新开始")
                ttsPlayer.stop()
                setState(State.IDLE)
                startListening()
            }
            State.LISTENING, State.PROCESSING -> {
                // 已在进行中，忽略
                Log.d(TAG, "忽略触发，当前 state=$state")
            }
        }
    }

    private fun onClearContext() {
        history.clear()
        try { vibrate(VIB_CLEAR) } catch (_: Exception) {}
        Log.d(TAG, "上下文已清除")
    }

    // ─── 状态机 ───────────────────────────────────────────────────

    /** 首次触发：播提示音再监听 */
    private fun startListening() {
        setState(State.LISTENING)
        try { vibrate(VIB_START) } catch (_: Exception) {}
        updateNotification(State.LISTENING)

        mainHandler.postDelayed(greetingTimeoutRunnable, 3000)

        ttsPlayer.speak("需要什么帮助吗", object : TtsPlayer.Listener {
            override fun onPlaybackFinished() {
                mainHandler.removeCallbacks(greetingTimeoutRunnable)
                if (state == State.LISTENING) mainHandler.post { startTransparentSr() }
            }
            override fun onError(msg: String) {
                Log.w(TAG, "提示音失败: $msg")
                mainHandler.removeCallbacks(greetingTimeoutRunnable)
                if (state == State.LISTENING) mainHandler.post { startTransparentSr() }
            }
        })
    }

    /** 持续对话：不播提示音，直接监听 */
    private fun continueListening() {
        setState(State.LISTENING)
        try { vibrate(VIB_START) } catch (_: Exception) {}
        updateNotification(State.LISTENING)
        mainHandler.post { startTransparentSr() }
    }

    private fun startTransparentSr() {
        try {
            startActivity(Intent(this, TransparentVoiceActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            Log.d(TAG, "已启动 SR Activity")
        } catch (e: Exception) {
            Log.e(TAG, "启动 SR 失败: $e")
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
                    Log.e(TAG, "ARK: $message")
                    resetToIdle()
                }
            })
        }
    }

    private fun playResponse(text: String) {
        setState(State.PLAYING)
        updateNotification(State.PLAYING)
        ttsPlayer.speak(text, object : TtsPlayer.Listener {
            override fun onPlaybackFinished() {
                if (state != State.PLAYING) return   // 已被打断，不处理
                // 播完自动继续监听（持续对话）
                mainHandler.postDelayed({ continueListening() }, 300)
            }
            override fun onError(msg: String) {
                if (state != State.PLAYING) return
                Log.w(TAG, "回复 TTS 失败: $msg")
                mainHandler.postDelayed({ continueListening() }, 300)
            }
        })
    }

    private fun resetToIdle() {
        setState(State.IDLE)
        updateNotification(State.IDLE)
        try { vibrate(VIB_END) } catch (_: Exception) {}
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
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val triggerPi = PendingIntent.getBroadcast(
            this, 1,
            Intent(ACTION_MANUAL_TRIGGER).apply { setPackage(packageName) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Config.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (s == State.IDLE) "点「开始」触发对话" else "")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_btn_speak_now, "🎙 开始", triggerPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(s: State) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(Config.NOTIFICATION_ID, buildNotification(s))
        } catch (_: Exception) {}
    }

    // ─── 工具 ─────────────────────────────────────────────────────

    private fun setState(s: State) { state = s; Log.d(TAG, "→ $s") }

    private fun vibrate(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)
                .defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java).vibrate(pattern, -1)
        }
    }

    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClaudeVoice:WakeLock")
            .also { it.acquire(12 * 60 * 60 * 1000L) }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "VoiceService").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        ?: return false
                    val code = event.keyCode
                    Log.d(TAG, "MediaSession 按键: keyCode=$code action=${event.action}")
                    if (event.action == KeyEvent.ACTION_UP &&
                        (code == KeyEvent.KEYCODE_HEADSETHOOK ||
                         code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                         code == KeyEvent.KEYCODE_MEDIA_NEXT ||
                         code == KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
                        Log.d(TAG, "耳机键触发")
                        mainHandler.post { onTrigger() }
                        return true
                    }
                    return false
                }
            })
            @Suppress("DEPRECATION")
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            isActive = true
        }
        Log.d(TAG, "MediaSession 已激活")
    }

    private fun registerTriggerReceiver() {
        val filter = IntentFilter().apply {
            addAction(Config.ACTION_TRIGGER_VOICE)
            addAction(Config.ACTION_CLEAR_CONTEXT)
            addAction(ACTION_MANUAL_TRIGGER)
            addAction(TransparentVoiceActivity.ACTION_VOICE_RESULT)
            addAction("com.example.claudevoice.SR_READY")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(triggerReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(triggerReceiver, filter)
        }
    }
}
