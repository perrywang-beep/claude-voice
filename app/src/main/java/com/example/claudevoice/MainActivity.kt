package com.example.claudevoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.claudevoice.databinding.ActivityMainBinding
import com.example.claudevoice.service.VoiceService

/**
 * 主界面。
 *
 * 职责：
 *   1. 申请运行时权限（RECORD_AUDIO）
 *   2. 启动/停止 VoiceService
 *   3. 引导用户开启辅助功能（音量键触发需要）
 *   4. 引导用户设置省电策略
 *
 * App 正常使用时不需要打开此界面，所有交互通过震动+音频完成。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val isServiceRunning: Boolean
        get() {
            val manager = getSystemService(android.app.ActivityManager::class.java)
            @Suppress("DEPRECATION")
            return manager.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == com.example.claudevoice.service.VoiceService::class.java.name }
        }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (audioGranted) {
            startVoiceService()
        } else {
            Toast.makeText(this, "麦克风权限被拒绝，无法录音", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        updateServiceStatus()
    }

    private fun setupButtons() {
        // 启动/停止服务
        binding.btnToggleService.setOnClickListener {
            if (isServiceRunning) {
                stopVoiceService()
            } else {
                checkPermissionsAndStart()
            }
        }

        // 手动测试触发（不依赖音量键和蓝牙）
        binding.btnTest.setOnClickListener {
            if (!isServiceRunning) {
                Toast.makeText(this, "请先启动服务", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(com.example.claudevoice.service.VoiceService.ACTION_MANUAL_TRIGGER).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
            Toast.makeText(this, "已触发，注意震动和语音", Toast.LENGTH_SHORT).show()
        }

        // 开启辅助功能
        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "请在列表中找到「ClaudeVoice 音量键监听」并开启",
                Toast.LENGTH_LONG
            ).show()
        }

        // 省电策略（跳转到应用详情）
        binding.btnBattery.setOnClickListener {
            try {
                // Android 原生：请求忽略电池优化
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                // 部分厂商定制系统不支持，跳转应用详情
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Toast.makeText(
                    this,
                    "请手动将省电策略设为「无限制」",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            startVoiceService()
        } else {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startVoiceService() {
        val intent = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // 延迟 500ms 再刷新状态，等服务启动
        binding.root.postDelayed({ updateServiceStatus() }, 500)
        Toast.makeText(this, "启动中…", Toast.LENGTH_SHORT).show()
    }

    private fun stopVoiceService() {
        stopService(Intent(this, VoiceService::class.java))
        binding.root.postDelayed({ updateServiceStatus() }, 300)
    }

    private fun updateServiceStatus() {
        if (isServiceRunning) {
            binding.tvServiceStatus.text = getString(R.string.status_service_running)
            binding.btnToggleService.text = getString(R.string.btn_stop_service)
        } else {
            binding.tvServiceStatus.text = getString(R.string.status_service_stopped)
            binding.btnToggleService.text = getString(R.string.btn_start_service)
        }
    }

    private fun updateAccessibilityStatus() {
        val enabled = isAccessibilityServiceEnabled()
        if (enabled) {
            binding.tvAccessibilityStatus.text = getString(R.string.status_accessibility_on)
            binding.tvAccessibilityStatus.setTextColor(getColor(android.R.color.holo_green_light))
        } else {
            binding.tvAccessibilityStatus.text = getString(R.string.status_accessibility_off)
            binding.tvAccessibilityStatus.setTextColor(getColor(android.R.color.holo_orange_light))
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/${packageName}.accessibility.VolumeKeyService"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return TextUtils.SimpleStringSplitter(':').apply {
            setString(enabledServices)
        }.any { it.equals(serviceName, ignoreCase = true) }
    }
}
