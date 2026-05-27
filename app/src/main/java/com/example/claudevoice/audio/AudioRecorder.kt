package com.example.claudevoice.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.claudevoice.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.sqrt

/**
 * 麦克风录音器。
 *
 * 功能：
 * - 16kHz 单声道 PCM 录音
 * - 实时 RMS 计算用于静音检测
 * - 静音超过 [Config.SILENCE_DURATION_MS] 后自动回调
 * - 将 PCM 数据打包为标准 WAV 格式供 API 使用
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val CHANNEL_CONFIG  = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT    = AudioFormat.ENCODING_PCM_16BIT
    }

    interface Listener {
        /** 录音自然结束（静音超时），返回完整 WAV 字节数组 */
        fun onRecordingFinished(wavBytes: ByteArray)
        /** 录音过程中发生错误 */
        fun onError(msg: String)
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false

    /** 开始录音。如已在录音则忽略。 */
    fun start(listener: Listener) {
        if (isRecording) return

        val minBufSize = AudioRecord.getMinBufferSize(
            Config.SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        if (minBufSize == AudioRecord.ERROR_BAD_VALUE) {
            listener.onError("不支持此音频配置")
            return
        }

        val bufSize = maxOf(minBufSize, Config.AUDIO_FRAMES_PER_READ * 2 * 4)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            Config.SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            listener.onError("AudioRecord 初始化失败，可能是麦克风权限未授予")
            record.release()
            return
        }

        audioRecord = record
        isRecording = true
        record.startRecording()

        recordingThread = Thread({
            recordLoop(record, listener)
        }, "AudioRecorder-Thread").also { it.start() }
    }

    /** 手动停止录音（通常由静音检测自动停止，此方法用于强制中断） */
    fun stop() {
        isRecording = false
        recordingThread?.interrupt()
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    val isActive: Boolean get() = isRecording

    // ─── 内部录音循环 ─────────────────────────────────────────────

    private fun recordLoop(record: AudioRecord, listener: Listener) {
        val frameBuffer    = ShortArray(Config.AUDIO_FRAMES_PER_READ)
        val pcmOutput      = ByteArrayOutputStream()
        var silenceStartMs = 0L
        var hasSpeech      = false   // 至少录到一段有效语音才触发回调

        Log.d(TAG, "录音线程启动")

        try {
            while (isRecording) {
                val read = record.read(frameBuffer, 0, frameBuffer.size)
                if (read <= 0) continue

                val rms = computeRms(frameBuffer, read)

                // 写入 PCM（小端 16-bit）
                for (i in 0 until read) {
                    val sample = frameBuffer[i]
                    pcmOutput.write(sample.toInt() and 0xFF)
                    pcmOutput.write((sample.toInt() shr 8) and 0xFF)
                }

                val now = System.currentTimeMillis()

                if (rms >= Config.SILENCE_RMS_THRESHOLD) {
                    // 有声音
                    hasSpeech      = true
                    silenceStartMs = 0L
                } else {
                    // 静音
                    if (silenceStartMs == 0L) silenceStartMs = now
                    val silenceDuration = now - silenceStartMs
                    if (hasSpeech && silenceDuration >= Config.SILENCE_DURATION_MS) {
                        Log.d(TAG, "检测到静音 ${silenceDuration}ms，结束录音")
                        break
                    }
                }
            }
        } catch (e: InterruptedException) {
            Log.d(TAG, "录音线程被中断")
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "录音异常: ${e.message}")
            listener.onError("录音异常: ${e.message}")
            return
        } finally {
            isRecording = false
            record.stop()
            record.release()
            audioRecord = null
        }

        val pcmData = pcmOutput.toByteArray()
        if (pcmData.isEmpty() || !hasSpeech) {
            Log.d(TAG, "无有效语音数据")
            return
        }

        Log.d(TAG, "录音完成，PCM 大小: ${pcmData.size} bytes")
        listener.onRecordingFinished(buildWav(pcmData))
    }

    // ─── RMS 计算 ─────────────────────────────────────────────────

    private fun computeRms(buffer: ShortArray, len: Int): Double {
        var sum = 0.0
        for (i in 0 until len) sum += buffer[i].toDouble() * buffer[i].toDouble()
        return sqrt(sum / len)
    }

    // ─── 生成 WAV 文件头 ──────────────────────────────────────────

    /**
     * 在 PCM 数据前添加标准 44 字节 WAV 文件头。
     * 格式：16kHz、单声道、16-bit PCM。
     */
    private fun buildWav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)

        val channels      = 1
        val bitsPerSample = 16
        val byteRate      = Config.SAMPLE_RATE * channels * bitsPerSample / 8
        val blockAlign    = channels * bitsPerSample / 8
        val dataSize      = pcm.size

        fun writeInt(v: Int)   { dos.write(v and 0xFF); dos.write((v shr 8) and 0xFF); dos.write((v shr 16) and 0xFF); dos.write((v shr 24) and 0xFF) }
        fun writeShort(v: Int) { dos.write(v and 0xFF); dos.write((v shr 8) and 0xFF) }

        // RIFF 块
        dos.writeBytes("RIFF")
        writeInt(36 + dataSize)   // 文件总长度 - 8
        dos.writeBytes("WAVE")

        // fmt 子块
        dos.writeBytes("fmt ")
        writeInt(16)              // 子块大小（PCM = 16）
        writeShort(1)             // 音频格式（1 = PCM）
        writeShort(channels)
        writeInt(Config.SAMPLE_RATE)
        writeInt(byteRate)
        writeShort(blockAlign)
        writeShort(bitsPerSample)

        // data 子块
        dos.writeBytes("data")
        writeInt(dataSize)
        dos.write(pcm)

        dos.flush()
        return out.toByteArray()
    }
}
