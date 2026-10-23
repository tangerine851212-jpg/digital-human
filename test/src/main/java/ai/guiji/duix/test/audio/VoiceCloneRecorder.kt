package ai.guiji.duix.test.audio

import ai.guiji.duix.test.service.DashScopeConfig
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File

class VoiceCloneRecorder {

    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var startAtMs: Long = 0L

    fun start(outputFile: File) {
        release()
        outputFile.parentFile?.mkdirs()
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(DashScopeConfig.AUDIO_SAMPLE_RATE)
            setAudioEncodingBitRate(96000)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
        recordingFile = outputFile
        startAtMs = SystemClock.elapsedRealtime()
    }

    fun stop(): VoiceRecording {
        val file = recordingFile ?: throw IllegalStateException("还没有开始录音")
        val recorder = mediaRecorder ?: throw IllegalStateException("录音器不可用")
        val duration = (SystemClock.elapsedRealtime() - startAtMs).coerceAtLeast(0L)
        try {
            recorder.stop()
        } catch (e: RuntimeException) {
            file.delete()
            throw IllegalStateException("录音太短，请再试一次", e)
        } finally {
            release()
        }
        return VoiceRecording(file, duration)
    }

    fun release() {
        mediaRecorder?.runCatching {
            reset()
        }
        mediaRecorder?.runCatching {
            release()
        }
        mediaRecorder = null
        recordingFile = null
        startAtMs = 0L
    }

    data class VoiceRecording(
        val file: File,
        val durationMs: Long
    )
}