package com.example.claudevoice.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.example.claudevoice.Config

/**
 * 辅助功能服务：捕获音量键长按事件。
 *
 * 策略：
 *   - ACTION_DOWN 时立即拦截（返回 true），防止系统在判断期间调节音量
 *   - ACTION_UP 时判断时长：
 *       < 800ms → 短按，手动调节音量（还原正常行为）
 *       ≥ 800ms → 长按，触发语音
 */
class VolumeKeyService : AccessibilityService() {

    companion object {
        private const val TAG = "VolumeKeyService"
    }

    private var volumeDownPressTime = 0L
    private var volumeUpPressTime   = 0L

    private val audioManager by lazy {
        getSystemService(AudioManager::class.java)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "辅助功能服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> handleVolumeDown(event)
            KeyEvent.KEYCODE_VOLUME_UP   -> handleVolumeUp(event)
            else -> false
        }
    }

    // ─── 音量下键 ─────────────────────────────────────────────────

    private fun handleVolumeDown(event: KeyEvent): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // 第一次按下时记录时间，拦截不让系统调音量
                if (event.repeatCount == 0) {
                    volumeDownPressTime = System.currentTimeMillis()
                }
                true  // 拦截，等 UP 时再决定
            }
            KeyEvent.ACTION_UP -> {
                val pressTime = volumeDownPressTime
                volumeDownPressTime = 0L
                if (pressTime == 0L) return false

                val duration = System.currentTimeMillis() - pressTime
                Log.d(TAG, "音量下键时长: ${duration}ms")

                if (duration >= Config.VOLUME_DOWN_LONG_PRESS_MS) {
                    Log.d(TAG, "长按 → 触发语音")
                    sendBroadcast(Config.ACTION_TRIGGER_VOICE)
                } else {
                    // 短按 → 手动降低音量，还原正常行为
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                true
            }
            else -> false
        }
    }

    // ─── 音量上键 ─────────────────────────────────────────────────

    private fun handleVolumeUp(event: KeyEvent): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    volumeUpPressTime = System.currentTimeMillis()
                }
                true
            }
            KeyEvent.ACTION_UP -> {
                val pressTime = volumeUpPressTime
                volumeUpPressTime = 0L
                if (pressTime == 0L) return false

                val duration = System.currentTimeMillis() - pressTime
                Log.d(TAG, "音量上键时长: ${duration}ms")

                if (duration >= Config.VOLUME_UP_LONG_PRESS_MS) {
                    Log.d(TAG, "长按 → 清除上下文")
                    sendBroadcast(Config.ACTION_CLEAR_CONTEXT)
                } else {
                    // 短按 → 手动升高音量
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                true
            }
            else -> false
        }
    }

    private fun sendBroadcast(action: String) {
        sendBroadcast(Intent(action).apply { setPackage(packageName) })
    }
}
