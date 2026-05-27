package com.example.claudevoice.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import com.example.claudevoice.Config
import com.example.claudevoice.service.VoiceService

/**
 * 蓝牙耳机媒体按键接收器。
 *
 * 监听 ACTION_MEDIA_BUTTON，拦截耳机单击事件触发语音对话。
 *
 * 工作原理：
 *   蓝牙耳机按键 → 系统发出 ACTION_MEDIA_BUTTON Intent →
 *   本接收器以最高优先级拦截 → 向 VoiceService 发送触发广播
 *
 * 注意：
 *   - 需要应用持有 AudioFocus 或注册 MediaSession 才能在某些设备上接收到此广播。
 *   - 此接收器注册在 AndroidManifest 中，支持息屏状态。
 *   - Android 8+ 要求显式指定组件，本接收器已 exported=true。
 */
class HeadsetButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HeadsetButtonReceiver"
        /** 单击阈值（毫秒）：超过此值判定为长按，不触发（避免与其他操作冲突） */
        private const val CLICK_MAX_MS = 600L

        private var lastKeyDownTime = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return

        val keyEvent: KeyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) ?: return
        }

        Log.d(TAG, "媒体按键: code=${keyEvent.keyCode}, action=${keyEvent.action}")

        // 只响应耳机中心键（HEADSETHOOK）或播放/暂停键
        val isTargetKey = keyEvent.keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

        if (!isTargetKey) return

        when (keyEvent.action) {
            KeyEvent.ACTION_DOWN -> {
                lastKeyDownTime = System.currentTimeMillis()
                abortBroadcast()  // 阻止广播继续传递给媒体播放器
            }
            KeyEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - lastKeyDownTime
                if (duration in 1..CLICK_MAX_MS) {
                    Log.d(TAG, "耳机单击（${duration}ms），触发语音")
                    triggerVoice(context)
                }
                abortBroadcast()
            }
        }
    }

    private fun triggerVoice(context: Context) {
        val intent = Intent(Config.ACTION_TRIGGER_VOICE).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }
}
