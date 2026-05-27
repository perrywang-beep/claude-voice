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

    private var audioPlayer: AudioPlayer? = null
    private val arkClient   = VolcengineArkClient()
    private val history     = ConversationHistory()
    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val triggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Config.ACTION_TRIGGER_VOICE,
                ACTION_MANUAL_TRIGGER       -> onTrigger()
                Config.ACTION_CLEAR_CONTEXT -> onClearContext()
            }
        }
    }

    // ─── 生命周期 ─────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        // 第一步：先把通知起来，这样即使后面崩溃也不会 ANR
        try { createNotificationChannel() } catch (e: Exception) { Log.e(TAG, "channel: $e") }
        try { startForeground(Config.NOTIFICATION_ID, buildNotification(State.IDLE)) }
        catch (e: Exception) { Log.e(TAG, "startForeground: $e") }

        // 第二步：其他初始化
        try { audioPlayer = AudioPlayer(this) } catch (e: Exception) { Log.e(TAG, "AudioPlayer: $e") }
        try { acquireWakeLock() }             catch (e: Exception) { Log.e(TAG, "WakeLock: $e") }
        try { registerTriggerReceiver() }     catch (e: Exception) { Log.e(TAG, "Receiver: $e") }
        try { initSpeechRecognizer() }        catch (e: Exception) { Log.e(TAG, "STT: $e") }

        // 启动确认震动
        try { vibrate(VIB_START) } catch (e: Exception) { Log.e(TAG, "vibrate: $e") }

        Log.d(TAG, "onCreate 完成")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.post { speechRecognizer?.destroy() }
        try { audioPlayer?.release() } catch (_: Exception) {}
        try { wakeLock?.release() }    catch (_: Exception) {}
        try { unregisterReceiver(triggerReceiver) } catch (_: Exception) {}
        Log.d(TAG, "onDestroy")
    }

    // ─── 触发 ─────────────────────────────────────────────────────

    fun onTrigger() {
        Log.d(TAG, "onTrigger, state=$state")
        if (state != State.IDLE) return
        startListening()
    }

    private fun onClearContext() {
        history.clear()
        try { vibrate(VIB_CLEAR) } catch (_: Exception) {}
    }

    // ─── 状态机 ───────────────────────────────────────────────────

    private fun startListening() {
        setState(State.LISTENING)
        try { vibrate(VIB_START) } catch (_: Exception) {}
        updateNotification(State.LISTENING)

        // 先播提示音，再开始录音
        audioPlayer?.speak("需要什么帮助吗", object : AudioPlayer.Listener {
            override fun onPlaybackFinished() { mainHandler.post { startSpeechRecognizer() } }
            override fun onError(msg: String)  { mainHandler.post { startSpeechRecognizer() } }
        }) ?: run {
            // audioPlayer 为 null 时直接开录
            mainHandler.post { startSpeechRecognizer() }
        }
    }

    private fun startSpeechRecognizer() {
        if (speechRecognizer == null) {
            try { initSpeechRecognizerSync() } catch (e: Exception) {
                Log.e(TAG, "STT init: $e")
                resetToIdle()
                return
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "startListening OK")
        } catch (e: Exception) {
            Log.e(TAG, "startListening: $e")
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
        audioPlayer?.speak(text, object : AudioPlayer.Listener {
            override fun onPlaybackFinished() = resetToIdle()
            override fun onError(msg: String)  = resetToIdle()
        }) ?: resetToIdle()
    }

    private fun resetToIdle() {
        setState(State.IDLE)
        updateNotification(State.IDLE)
        try { vibrate(VIB_END) } catch (_: Exception) {}
    }

    // ─── SpeechRecognizer ─────────────────────────────────────────

    private fun initSpeechRecognizer() {
        mainHandler.post { initSpeechRecognizerSync() }
    }

    private fun initSpeechRecognizerSync() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "语音识别不可用")
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(recognitionListener)
        }
        Log.d(TAG, "SpeechRecognizer 初始化成功")
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            Log.e(TAG, "ASR error=$error")
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                mainHandler.post { initSpeechRecognizerSync() }
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
}
