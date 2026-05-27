package com.example.claudevoice.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.example.claudevoice.Config

/**
 * 辅助功能服务：在息屏状态下捕获音量键长按事件。
 *
 * 需用户在「设置 → 辅助功能」中手动开启。
 *
 * 逻辑：
 *   - 音量下键按下超过 [Config.VOLUME_DOWN_LONG_PRESS_MS]（800ms）→ 触发语音对话
 *     短按（<800ms）放行给系统，正常调节音量
 *   - 音量上键按下超过 [Config.VOLUME_UP_LONG_PRESS_MS]（2000ms）→ 清除上下文
 *     短按放行给系统
 *
 * 注意：此服务仅监听按键事件，不读取屏幕内容。
 */
class VolumeKeyService : AccessibilityService() {

    companion object {
        private const val TAG = "VolumeKeyService"
    }

    // 记录各键的按下时间戳
    private var volumeDownPressTime = 0L
    private var volumeUpPressTime   = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "辅助功能服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* 不处理 UI 事件 */ }

    override fun onInterrupt() {
        Log.d(TAG, "服务中断")
    }

    /**
     * 拦截按键事件。
     *
     * 返回 true  → 系统不再处理此键（拦截）
     * 返回 false → 系统继续处理（放行，正常调音量）
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> handleVolumeDown(event)
            KeyEvent.KEYCODE_VOLUME_UP   -> handleVolumeUp(event)
            else -> false
        }
    }

    // ─── 音量下键处理 ─────────────────────────────────────────────

    private fun handleVolumeDown(event: KeyEvent): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (volumeDownPressTime == 0L) {
                    volumeDownPressTime = System.currentTimeMillis()
                }
                // 按住期间不拦截，让系统先不处理（等松开时判断）
                // 但 Android 会在 ACTION_DOWN 时就触发音量变化，
                // 所以这里返回 false 让系统先走默认逻辑（短按调音量），
                // 长按时由 ACTION_UP 决定是否有副作用。
                //
                // 更好的方案：返回 true 拦截所有 DOWN，松开时如果是短按则手动调音量。
                // 这里采用在 UP 时判断，短按不处理（音量已经变了），长按才触发。
                false
            }
            KeyEvent.ACTION_UP -> {
                val pressTime = volumeDownPressTime
                volumeDownPressTime = 0L

                if (pressTime == 0L) return false

                val duration = System.currentTimeMillis() - pressTime
                Log.d(TAG, "音量下键松开，按压时长: ${duration}ms")

                if (duration >= Config.VOLUME_DOWN_LONG_PRESS_MS) {
                    Log.d(TAG, "长按音量下键 → 触发语音")
                    sendTriggerBroadcast(Config.ACTION_TRIGGER_VOICE)
                    true  // 拦截此次 UP，防止再次触发音量变化
                } else {
                    false // 短按，放行
                }
            }
            else -> false
        }
    }

    // ─── 音量上键处理 ─────────────────────────────────────────────

    private fun handleVolumeUp(event: KeyEvent): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (volumeUpPressTime == 0L) {
                    volumeUpPressTime = System.currentTimeMillis()
                }
                false
            }
            KeyEvent.ACTION_UP -> {
                val pressTime = volumeUpPressTime
                volumeUpPressTime = 0L

                if (pressTime == 0L) return false

                val duration = System.currentTimeMillis() - pressTime
                Log.d(TAG, "音量上键松开，按压时长: ${duration}ms")

                if (duration >= Config.VOLUME_UP_LONG_PRESS_MS) {
                    Log.d(TAG, "长按音量上键 → 清除上下文")
                    sendTriggerBroadcast(Config.ACTION_CLEAR_CONTEXT)
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    // ─── 向 VoiceService 发送广播 ─────────────────────────────────

    private fun sendTriggerBroadcast(action: String) {
        val intent = Intent(action).apply {
            setPackage(packageName)  // 仅发给本应用（安全）
        }
        sendBroadcast(intent)
    }
}
