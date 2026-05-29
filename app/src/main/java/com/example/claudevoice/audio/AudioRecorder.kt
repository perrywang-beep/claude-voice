package com.example.claudevoice.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 AudioRecord 的录音器，内置简单 VAD（振幅阈值）。
 * 不依赖 SpeechRecognizer，可在息屏/前台 Service 中正常使用。
 *
 * 录音流程：
 *   1. 开麦 → 回调 onReady（震动提示用户可说话）
 *   2. 检测到语音（RMS > threshold）→ 开始采集 PCM
 *   3. 语音结束后静音 SILENCE_TIMEOUT_MS → 停止采集，回调 onResult
 *   4. 若超过 WAIT_SPEECH_MS 没有检测到语音 → 回调 onSilence
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"

        const val SAMPLE_RATE    = 16_000              // 16 kHz
        private const val CHUNK_SAMPLES  = 320         // 20 ms per chunk
        private const val RMS_THRESHOLD  = 600.0       // 振幅阈值（0-32767）
        private const val WAIT_SPEECH_MS = 7_000L      // 最长等待语音开始
        private const val SILENCE_END_MS = 800L         // 语音后静音多久算结束
        private const val MAX_SPEECH_MS  = 8_000L      // 单次最长录音
        private const val MIN_SPEECH_MS  = 300L        // 低于此时长视为无效
    }

    interface Listener {
        fun onReady()                        // 麦克风已开，用户可以说话
        fun onResult(pcm: ByteArray)         // 采集完成，返回 PCM 字节
        fun onSilence()                      // 超时未检测到语音
        fun onError(msg: String)             // 初始化失败
    }

    @Volatile var isRecording: Boolean = false
        private set

    private val cancelled = AtomicBoolean(false)
    private var recorder:   AudioRecord? = null
    private var workerThread: Thread?   = null

    // ── 启动录音 ────────────────────────────────────────────────────

    fun start(listener: Listener) {
        cancelled.set(false)
        isRecording = true

        workerThread = Thread({ doRecord(listener) }, "AudioRecorder").also {
            it.isDaemon = true
            it.start()
        }
    }

    // ── 停止（外部调用：打断/切换状态）──────────────────────────────

    fun stop() {
        cancelled.set(true)
        try { recorder?.stop() } catch (_: Exception) {}
        // isRecording 由 doRecord 线程在退出时设 false
    }

    // ── 录音主循环（在 worker 线程运行）─────────────────────────────

    private fun doRecord(listener: Listener) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, CHUNK_SAMPLES * 2 * 8)

        val ar = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (e: Exception) {
            isRecording = false
            listener.onError("AudioRecord 创建失败: ${e.message}")
            return
        }

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            isRecording = false
            listener.onError("AudioRecord 未初始化（无麦克风权限？）")
            return
        }

        recorder = ar
        ar.startRecording()
        listener.onReady()
        Log.d(TAG, "开始录音 sampleRate=$SAMPLE_RATE")

        val out          = ByteArrayOutputStream()
        val chunk        = ShortArray(CHUNK_SAMPLES)
        var speechStart  = -1L
        var silenceStart = -1L
        val waitStart    = System.currentTimeMillis()

        loop@ while (!cancelled.get()) {
            val read = ar.read(chunk, 0, chunk.size)
            if (read <= 0) continue

            val now = System.currentTimeMillis()

            // —— PCM → 小端字节 ——
            val bytes = ByteArray(read * 2)
            for (i in 0 until read) {
                bytes[i * 2]     = (chunk[i].toInt() and 0xFF).toByte()
                bytes[i * 2 + 1] = (chunk[i].toInt() ushr 8).toByte()
            }

            // —— 计算 RMS ——
            var sum = 0.0
            for (i in 0 until read) sum += chunk[i].toDouble() * chunk[i]
            val rms = Math.sqrt(sum / read)

            if (rms > RMS_THRESHOLD) {
                // 有声音
                if (speechStart < 0) {
                    speechStart = now
                    Log.d(TAG, "检测到语音 rms=${"%.0f".format(rms)}")
                }
                silenceStart = -1
                out.write(bytes)

                if (now - speechStart >= MAX_SPEECH_MS) {
                    Log.d(TAG, "达到最大录音时长")
                    break@loop
                }
            } else {
                if (speechStart >= 0) {
                    // 语音已开始，现在静音
                    out.write(bytes)
                    if (silenceStart < 0) silenceStart = now
                    if (now - silenceStart >= SILENCE_END_MS) {
                        Log.d(TAG, "静音结束，停止录音")
                        break@loop
                    }
                } else {
                    // 还没检测到语音，等待
                    if (now - waitStart >= WAIT_SPEECH_MS) {
                        Log.d(TAG, "等待语音超时")
                        break@loop
                    }
                }
            }
        }

        try { ar.stop()    } catch (_: Exception) {}
        try { ar.release() } catch (_: Exception) {}
        recorder    = null
        isRecording = false

        if (cancelled.get()) {
            Log.d(TAG, "录音被取消")
            return
        }

        val pcm           = out.toByteArray()
        val speechMs      = if (speechStart >= 0) System.currentTimeMillis() - speechStart else 0L
        Log.d(TAG, "录音完成 pcm=${pcm.size}B speechMs=$speechMs")

        when {
            speechStart < 0 || speechMs < MIN_SPEECH_MS -> listener.onSilence()
            else -> listener.onResult(pcm)
        }
    }
}
