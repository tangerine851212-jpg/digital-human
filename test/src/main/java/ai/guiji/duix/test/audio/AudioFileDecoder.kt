package ai.guiji.duix.test.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

object AudioFileDecoder {

    private const val TARGET_SAMPLE_RATE = 16_000

    data class DecodedAudio(
        val pcm16Mono16k: ByteArray,
        val durationMs: Long,
    )

    fun decodeToPcm16Mono16k(context: Context, uri: Uri): DecodedAudio {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val rawPcmOutput = ByteArrayOutputStream()
        var selectedTrack = -1
        var sampleRate = TARGET_SAMPLE_RATE
        var channelCount = 1

        try {
            val scheme = uri.scheme?.lowercase(Locale.US)
            if (scheme.isNullOrEmpty() || scheme == "file") {
                val path = uri.path ?: throw IllegalArgumentException("无法读取所选音频路径")
                extractor.setDataSource(path)
            } else {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                } ?: throw IllegalArgumentException("无法打开所选音频")
            }

            selectedTrack = findFirstAudioTrack(extractor)
            if (selectedTrack < 0) {
                throw IllegalArgumentException("没有找到可读取的音频轨道")
            }

            extractor.selectTrack(selectedTrack)
            val inputFormat = extractor.getTrackFormat(selectedTrack)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("无法识别音频格式")
            sampleRate = inputFormat.optInt(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
            channelCount = inputFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT, 1)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("解码器输入缓冲区不可用")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime.coerceAtLeast(0L),
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        sampleRate = outputFormat.optInt(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channelCount = outputFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                        val pcmEncoding = outputFormat.optInt(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                        if (pcmEncoding != AudioFormat.ENCODING_PCM_16BIT) {
                            throw IllegalArgumentException("暂不支持该音频 PCM 编码")
                        }
                    }
                    else -> {
                        if (outputIndex >= 0) {
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                                ?: throw IllegalStateException("解码器输出缓冲区不可用")
                            if (bufferInfo.size > 0) {
                                val chunk = ByteArray(bufferInfo.size)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                outputBuffer.get(chunk)
                                rawPcmOutput.write(chunk)
                            }
                            val endOfStream = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            decoder.releaseOutputBuffer(outputIndex, false)
                            outputDone = endOfStream
                        }
                    }
                }
            }

            val converted = convertPcm16ToMono16k(rawPcmOutput.toByteArray(), sampleRate, channelCount)
            if (converted.isEmpty()) {
                throw IllegalArgumentException("音频解码后没有可用数据")
            }
            val durationMs = ((converted.size / 2L) * 1000L / TARGET_SAMPLE_RATE).coerceAtLeast(0L)
            return DecodedAudio(
                pcm16Mono16k = converted,
                durationMs = durationMs,
            )
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { if (selectedTrack >= 0) extractor.unselectTrack(selectedTrack) }
            runCatching { extractor.release() }
            runCatching { rawPcmOutput.close() }
        }
    }

    private fun findFirstAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                return index
            }
        }
        return -1
    }

    private fun convertPcm16ToMono16k(input: ByteArray, inputSampleRate: Int, inputChannels: Int): ByteArray {
        if (input.isEmpty()) {
            return ByteArray(0)
        }
        if (inputChannels <= 0) {
            throw IllegalArgumentException("无效的声道数")
        }

        val frameCount = input.size / (2 * inputChannels)
        if (frameCount <= 0) {
            return ByteArray(0)
        }

        val monoSamples = FloatArray(frameCount)
        var byteOffset = 0
        for (frameIndex in 0 until frameCount) {
            var sum = 0
            for (channelIndex in 0 until inputChannels) {
                sum += readShortLE(input, byteOffset).toInt()
                byteOffset += 2
            }
            monoSamples[frameIndex] = sum.toFloat() / inputChannels.toFloat()
        }

        if (inputSampleRate == TARGET_SAMPLE_RATE) {
            val output = ByteArray(frameCount * 2)
            for (index in monoSamples.indices) {
                writeShortLE(output, index * 2, monoSamples[index].roundToInt().toShort())
            }
            return output
        }

        val outputFrameCount = ceil(frameCount.toDouble() * TARGET_SAMPLE_RATE / inputSampleRate).toInt()
        val output = ByteArray(outputFrameCount * 2)
        val positionScale = inputSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()

        for (outputIndex in 0 until outputFrameCount) {
            val srcPosition = outputIndex * positionScale
            val srcIndex0 = floor(srcPosition).toInt().coerceIn(0, monoSamples.lastIndex)
            val srcIndex1 = (srcIndex0 + 1).coerceAtMost(monoSamples.lastIndex)
            val fraction = srcPosition - srcIndex0
            val interpolated = monoSamples[srcIndex0] +
                ((monoSamples[srcIndex1] - monoSamples[srcIndex0]) * fraction.toFloat())
            val clamped = interpolated.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            writeShortLE(output, outputIndex * 2, clamped.toShort())
        }
        return output
    }

    private fun readShortLE(data: ByteArray, offset: Int): Short {
        val low = data[offset].toInt() and 0xFF
        val high = data[offset + 1].toInt() shl 8
        return (low or high).toShort()
    }

    private fun writeShortLE(data: ByteArray, offset: Int, value: Short) {
        data[offset] = (value.toInt() and 0xFF).toByte()
        data[offset + 1] = ((value.toInt() ushr 8) and 0xFF).toByte()
    }

    private fun MediaFormat.optInt(key: String, fallback: Int): Int {
        return runCatching {
            if (containsKey(key)) getInteger(key) else fallback
        }.getOrDefault(fallback)
    }

    private const val TIMEOUT_US = 10_000L
}