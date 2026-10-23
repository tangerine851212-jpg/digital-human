package ai.guiji.duix.test.video

import ai.guiji.duix.sdk.client.bean.ImageFrame
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class DigitalHumanMp4Exporter(
    private val outputFile: File,
) {

    data class ExportResult(
        val file: File,
        val durationMs: Long,
    )

    private data class QueuedAudio(val data: ByteArray)

    private data class FramePacket(
        val raw: ByteArray,
        val mask: ByteArray,
        val width: Int,
        val height: Int,
        val timestampNs: Long,
    )

    private data class EncodedSample(
        val data: ByteArray,
        val info: MediaCodec.BufferInfo,
    )

    private val executor = Executors.newSingleThreadExecutor()

    private var startNs = -1L
    private var initialized = false
    private var closed = false

    private var videoWidth = 0
    private var videoHeight = 0
    private var videoColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

    private var muxer: MediaMuxer? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false

    private val pendingAudioChunks = ArrayList<QueuedAudio>()
    private val pendingVideoSamples = ArrayList<EncodedSample>()
    private val pendingAudioSamples = ArrayList<EncodedSample>()

    private var lastFramePacket: FramePacket? = null
    private var lastVideoPtsUs = -1L
    private var audioBytesQueued = 0L

    fun recordAudioChunk(pcm24k: ByteArray, timestampNs: Long = System.nanoTime()) {
        if (pcm24k.isEmpty() || closed) {
            return
        }
        val copy = pcm24k.clone()
        executor.execute {
            if (closed) {
                return@execute
            }
            ensureStarted(timestampNs)
            if (!initialized) {
                pendingAudioChunks.add(QueuedAudio(copy))
            } else {
                queueAudioInternal(copy)
            }
        }
    }

    fun recordFrame(imageFrame: ImageFrame, timestampNs: Long = System.nanoTime()) {
        if (closed) {
            return
        }
        val rawSize = imageFrame.width * imageFrame.height * RGB_CHANNELS
        val rawCopy = copyBufferBytes(imageFrame.rawBuffer, rawSize)
        val maskCopy = copyBufferBytes(imageFrame.maskBuffer, rawSize)
        val packet = FramePacket(rawCopy, maskCopy, imageFrame.width, imageFrame.height, timestampNs)
        executor.execute {
            if (closed) {
                return@execute
            }
            ensureStarted(timestampNs)
            if (!initialized) {
                initializeEncoders(packet.width, packet.height)
            }
            queueVideoInternal(packet)
            lastFramePacket = packet
            if (pendingAudioChunks.isNotEmpty()) {
                flushPendingAudioChunks()
            }
        }
    }

    fun finish(): ExportResult {
        val latch = CountDownLatch(1)
        var result: ExportResult? = null
        var failure: Throwable? = null

        executor.execute {
            try {
                closed = true
                if (!initialized) {
                    throw IllegalStateException("未收到数字人渲染帧，无法导出视频")
                }
                if (pendingAudioChunks.isNotEmpty()) {
                    flushPendingAudioChunks()
                }
                if (audioBytesQueued <= 0L) {
                    throw IllegalStateException("未收到模型音频响应")
                }

                val audioDurationUs = calculateAudioDurationUs()
                val lastFrame = lastFramePacket
                if (lastFrame != null && audioDurationUs > lastVideoPtsUs + FRAME_PADDING_US) {
                    queueVideoInternal(lastFrame.copy(timestampNs = startNs + (audioDurationUs * 1000L)))
                }

                signalAudioEndOfStream(audioDurationUs)
                drainAudioEncoder(endOfStream = true)

                val finalVideoPtsUs = max(lastVideoPtsUs, audioDurationUs)
                signalVideoEndOfStream(finalVideoPtsUs)
                drainVideoEncoder(endOfStream = true)

                releaseInternal(deleteOutput = false)
                result = ExportResult(
                    file = outputFile,
                    durationMs = (audioDurationUs / 1000L).coerceAtLeast(0L),
                )
            } catch (throwable: Throwable) {
                failure = throwable
                releaseInternal(deleteOutput = true)
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            executor.shutdownNow()
            throw IllegalStateException("视频导出超时")
        }
        executor.shutdown()
        failure?.let { throw it }
        return result ?: throw IllegalStateException("视频导出失败")
    }

    fun cancel() {
        val latch = CountDownLatch(1)
        executor.execute {
            try {
                closed = true
                releaseInternal(deleteOutput = true)
            } finally {
                latch.countDown()
            }
        }
        latch.await(CANCEL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        executor.shutdownNow()
    }

    private fun ensureStarted(timestampNs: Long) {
        if (startNs < 0L) {
            startNs = timestampNs
        }
    }

    private fun initializeEncoders(width: Int, height: Int) {
        if (initialized) {
            return
        }
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
            throw IllegalArgumentException("渲染帧尺寸无效: ${width}x${height}")
        }

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) {
            outputFile.delete()
        }

        videoWidth = width
        videoHeight = height
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE).apply {
            videoColorFormat = selectVideoColorFormat(codecInfo)
            val format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, videoColorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, max(width * height * VIDEO_BITRATE_FACTOR, MIN_VIDEO_BITRATE))
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL)
            }
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE).apply {
            val format = MediaFormat.createAudioFormat(AUDIO_MIME_TYPE, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_MAX_INPUT_SIZE)
            }
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        initialized = true
    }

    private fun flushPendingAudioChunks() {
        if (pendingAudioChunks.isEmpty()) {
            return
        }
        val iterator = pendingAudioChunks.iterator()
        while (iterator.hasNext()) {
            queueAudioInternal(iterator.next().data)
            iterator.remove()
        }
    }

    private fun queueAudioInternal(pcm24k: ByteArray) {
        var offset = 0
        val encoder = audioEncoder ?: throw IllegalStateException("音频编码器尚未初始化")
        while (offset < pcm24k.size) {
            val inputIndex = dequeueInputBuffer(encoder)
            val inputBuffer = encoder.getInputBuffer(inputIndex)
                ?: throw IllegalStateException("音频输入缓冲区不可用")
            inputBuffer.clear()
            val chunkSize = min(inputBuffer.remaining(), pcm24k.size - offset)
            inputBuffer.put(pcm24k, offset, chunkSize)
            val ptsUs = audioBytesQueued * MICROS_PER_SECOND / AUDIO_BYTES_PER_SECOND
            encoder.queueInputBuffer(inputIndex, 0, chunkSize, ptsUs, 0)
            audioBytesQueued += chunkSize.toLong()
            offset += chunkSize
            drainAudioEncoder(endOfStream = false)
        }
    }

    private fun queueVideoInternal(packet: FramePacket) {
        if (packet.width != videoWidth || packet.height != videoHeight) {
            throw IllegalStateException("渲染帧尺寸发生变化，无法继续编码")
        }
        val encoder = videoEncoder ?: throw IllegalStateException("视频编码器尚未初始化")
        val inputIndex = dequeueInputBuffer(encoder)
        val inputBuffer = encoder.getInputBuffer(inputIndex)
            ?: throw IllegalStateException("视频输入缓冲区不可用")
        inputBuffer.clear()

        val yuv = convertFrameToYuv(packet.raw, packet.mask, packet.width, packet.height, videoColorFormat)
        if (inputBuffer.remaining() < yuv.size) {
            throw IllegalStateException("视频输入缓冲区容量不足")
        }

        inputBuffer.put(yuv)
        val ptsUs = computeVideoPresentationTimeUs(packet.timestampNs)
        encoder.queueInputBuffer(inputIndex, 0, yuv.size, ptsUs, 0)
        lastVideoPtsUs = ptsUs
        drainVideoEncoder(endOfStream = false)
    }

    private fun signalAudioEndOfStream(finalPtsUs: Long) {
        val encoder = audioEncoder ?: return
        val inputIndex = dequeueInputBuffer(encoder)
        encoder.queueInputBuffer(inputIndex, 0, 0, finalPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    private fun signalVideoEndOfStream(finalPtsUs: Long) {
        val encoder = videoEncoder ?: return
        val inputIndex = dequeueInputBuffer(encoder)
        encoder.queueInputBuffer(inputIndex, 0, 0, finalPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    private fun drainAudioEncoder(endOfStream: Boolean) {
        drainEncoder(
            encoder = audioEncoder,
            pendingSamples = pendingAudioSamples,
            getTrackIndex = { audioTrackIndex },
            setTrackIndex = { audioTrackIndex = it },
            endOfStream = endOfStream,
        )
    }

    private fun drainVideoEncoder(endOfStream: Boolean) {
        drainEncoder(
            encoder = videoEncoder,
            pendingSamples = pendingVideoSamples,
            getTrackIndex = { videoTrackIndex },
            setTrackIndex = { videoTrackIndex = it },
            endOfStream = endOfStream,
        )
    }

    private fun drainEncoder(
        encoder: MediaCodec?,
        pendingSamples: MutableList<EncodedSample>,
        getTrackIndex: () -> Int,
        setTrackIndex: (Int) -> Unit,
        endOfStream: Boolean,
    ) {
        if (encoder == null) {
            return
        }
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) {
                        return
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val muxerInstance = muxer ?: throw IllegalStateException("复用器未初始化")
                    val trackIndex = muxerInstance.addTrack(encoder.outputFormat)
                    setTrackIndex(trackIndex)
                    startMuxerIfReady()
                }
                outputIndex >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)
                        ?: throw IllegalStateException("编码器输出缓冲区不可用")

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0) {
                        val trackIndex = getTrackIndex()
                        if (trackIndex < 0) {
                            throw IllegalStateException("编码轨道尚未创建")
                        }
                        val sample = copyEncodedSample(outputBuffer, bufferInfo)
                        if (muxerStarted) {
                            writeEncodedSample(trackIndex, sample)
                        } else {
                            pendingSamples.add(sample)
                        }
                    }

                    val endReached = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (endReached) {
                        return
                    }
                }
            }
        }
    }

    private fun startMuxerIfReady() {
        if (muxerStarted || videoTrackIndex < 0 || audioTrackIndex < 0) {
            return
        }
        val muxerInstance = muxer ?: throw IllegalStateException("复用器未初始化")
        muxerInstance.start()
        muxerStarted = true

        flushPendingSamples(videoTrackIndex, pendingVideoSamples)
        flushPendingSamples(audioTrackIndex, pendingAudioSamples)
    }

    private fun flushPendingSamples(trackIndex: Int, pendingSamples: MutableList<EncodedSample>) {
        if (pendingSamples.isEmpty()) {
            return
        }
        pendingSamples.sortBy { it.info.presentationTimeUs }
        val iterator = pendingSamples.iterator()
        while (iterator.hasNext()) {
            writeEncodedSample(trackIndex, iterator.next())
            iterator.remove()
        }
    }

    private fun writeEncodedSample(trackIndex: Int, sample: EncodedSample) {
        val muxerInstance = muxer ?: throw IllegalStateException("复用器未初始化")
        muxerInstance.writeSampleData(trackIndex, ByteBuffer.wrap(sample.data), sample.info)
    }

    private fun copyEncodedSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo): EncodedSample {
        val data = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.get(data)
        val copiedInfo = MediaCodec.BufferInfo().apply {
            offset = 0
            size = data.size
            presentationTimeUs = info.presentationTimeUs
            flags = info.flags
        }
        return EncodedSample(data, copiedInfo)
    }

    private fun computeVideoPresentationTimeUs(timestampNs: Long): Long {
        val relativeUs = ((timestampNs - startNs) / 1_000L).coerceAtLeast(0L)
        return if (lastVideoPtsUs < 0L) {
            relativeUs
        } else {
            max(relativeUs, lastVideoPtsUs + 1L)
        }
    }

    private fun calculateAudioDurationUs(): Long {
        return audioBytesQueued * MICROS_PER_SECOND / AUDIO_BYTES_PER_SECOND
    }

    private fun dequeueInputBuffer(encoder: MediaCodec): Int {
        while (true) {
            val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                return inputIndex
            }
            if (inputIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                throw IllegalStateException("获取编码器输入缓冲区失败")
            }
            if (closed) {
                throw IllegalStateException("编码任务已结束")
            }
        }
    }

    private fun releaseInternal(deleteOutput: Boolean) {
        runCatching {
            if (muxerStarted) {
                muxer?.stop()
            }
        }
        runCatching { muxer?.release() }
        runCatching { videoEncoder?.stop() }
        runCatching { videoEncoder?.release() }
        runCatching { audioEncoder?.stop() }
        runCatching { audioEncoder?.release() }

        muxer = null
        videoEncoder = null
        audioEncoder = null
        muxerStarted = false
        initialized = false
        pendingAudioChunks.clear()
        pendingAudioSamples.clear()
        pendingVideoSamples.clear()

        if (deleteOutput && outputFile.exists()) {
            outputFile.delete()
        }
    }

    private fun selectVideoColorFormat(codecInfo: MediaCodecInfo): Int {
        val capabilities = codecInfo.getCapabilitiesForType(VIDEO_MIME_TYPE)
        val candidates = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        )
        for (candidate in candidates) {
            if (capabilities.colorFormats.contains(candidate)) {
                return candidate
            }
        }
        throw IllegalStateException("当前设备不支持可用的 YUV420 视频编码格式")
    }

    private fun convertFrameToYuv(
        raw: ByteArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        colorFormat: Int,
    ): ByteArray {
        val frameSize = width * height
        val output = ByteArray(frameSize + frameSize / 2)
        val isSemiPlanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        var yIndex = 0
        var uIndex = frameSize
        var vIndex = if (isSemiPlanar) frameSize + 1 else frameSize + frameSize / 4

        for (row in 0 until height) {
            for (col in 0 until width) {
                val pixelIndex = row * width + col
                val rgbOffset = pixelIndex * RGB_CHANNELS

                val alpha = ((mask[rgbOffset].toInt() and 0xFF) +
                    (mask[rgbOffset + 1].toInt() and 0xFF) +
                    (mask[rgbOffset + 2].toInt() and 0xFF)) / RGB_CHANNELS

                val blue = ((raw[rgbOffset].toInt() and 0xFF) * alpha) / 255
                val green = ((raw[rgbOffset + 1].toInt() and 0xFF) * alpha) / 255
                val red = ((raw[rgbOffset + 2].toInt() and 0xFF) * alpha) / 255

                val y = clampToByte(((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16)
                val u = clampToByte(((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128)
                val v = clampToByte(((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128)

                output[yIndex++] = y.toByte()

                if (row % 2 == 0 && col % 2 == 0) {
                    if (isSemiPlanar) {
                        output[uIndex] = u.toByte()
                        output[vIndex] = v.toByte()
                        uIndex += 2
                        vIndex += 2
                    } else {
                        output[uIndex++] = u.toByte()
                        output[vIndex++] = v.toByte()
                    }
                }
            }
        }
        return output
    }

    private fun clampToByte(value: Int): Int {
        return value.coerceIn(0, 255)
    }

    private fun copyBufferBytes(buffer: ByteBuffer, size: Int): ByteArray {
        if (buffer.hasArray()) {
            return buffer.array().copyOf(size)
        }
        val duplicate = buffer.duplicate()
        duplicate.position(0)
        val output = ByteArray(size)
        duplicate.get(output, 0, min(size, duplicate.remaining()))
        return output
    }

    companion object {
        private const val VIDEO_MIME_TYPE = "video/avc"
        private const val AUDIO_MIME_TYPE = "audio/mp4a-latm"

        private const val AUDIO_SAMPLE_RATE = 24_000
        private const val AUDIO_CHANNEL_COUNT = 1
        private const val AUDIO_BYTES_PER_SECOND = AUDIO_SAMPLE_RATE * AUDIO_CHANNEL_COUNT * 2L
        private const val AUDIO_BITRATE = 64_000
        private const val AUDIO_MAX_INPUT_SIZE = 48_000

        private const val VIDEO_FRAME_RATE = 24
        private const val VIDEO_I_FRAME_INTERVAL = 1
        private const val VIDEO_BITRATE_FACTOR = 6
        private const val MIN_VIDEO_BITRATE = 1_200_000

        private const val RGB_CHANNELS = 3
        private const val FRAME_PADDING_US = 80_000L
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val TIMEOUT_US = 10_000L
        private const val FINISH_TIMEOUT_SECONDS = 60L
        private const val CANCEL_TIMEOUT_SECONDS = 5L
    }
}