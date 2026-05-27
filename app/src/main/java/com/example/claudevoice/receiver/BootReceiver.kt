package com.example.claudevoice.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.claudevoice.service.VoiceService

/**
 * 开机自启广播接收器。
 *
 * 接收系统 BOOT_COMPLETED 广播后启动 VoiceService。
 *
 * 小米/澎湃OS 注意事项：
 *   1. 还需在「设置 → 应用 → ClaudeVoice → 自启动」中手动开启自启权限。
 *   2. 「省电策略」设为「无限制」，否则服务会被杀死。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "收到广播: $action")

        val bootActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )

        if (action in bootActions) {
            Log.d(TAG, "开机广播，启动 VoiceService")
            startVoiceService(context)
        }
    }

    private fun startVoiceService(context: Context) {
        val serviceIntent = Intent(context, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
