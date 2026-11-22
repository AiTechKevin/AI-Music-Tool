package com.aimusicstudio.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

data class DecodedAudio(
    val left: FloatArray,
    val right: FloatArray,
    val sampleRate: Int
)

object WavCodec {

    class StreamingWavWriter(
        file: File,
        val sampleRate: Int
    ) : Closeable {
        private val raf = RandomAccessFile(file, "rw")
        private var totalFrames: Long = 0L
        private var byteBuf = ByteArray(8192 * 4)
        private var closed = false

        init {
            raf.setLength(0L)
            raf.write(ByteArray(44))
        }

        fun writeChunk(left: FloatArray, right: FloatArray, frameCount: Int) {
            if (frameCount <= 0 || closed) return
            val neededBytes = frameCount * 4
            if (byteBuf.size < neededBytes) {
                byteBuf = ByteArray(neededBytes)
            }
            var p = 0
            for (i in 0 until frameCount) {
                val l = (left[i].coerceIn(-1f, 1f) * 32767f).toInt()
                val r = (right[i].coerceIn(-1f, 1f) * 32767f).toInt()
                byteBuf[p++] = (l and 0xFF).toByte()
                byteBuf[p++] = ((l shr 8) and 0xFF).toByte()
                byteBuf[p++] = (r and 0xFF).toByte()
                byteBuf[p++] = ((r shr 8) and 0xFF).toByte()
            }
            raf.write(byteBuf, 0, neededBytes)
            totalFrames += frameCount
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                val dataSize = (totalFrames * 4L).coerceAtMost(Int.MAX_VALUE.toLong() - 36L).toInt()
                val numChannels = 2
                val bitsPerSample = 16
                val byteRate = sampleRate * numChannels * bitsPerSample / 8
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray())
                    putInt(36 + dataSize)
                    put("WAVE".toByteArray())
                    put("fmt ".toByteArray())
                    putInt(16)
                    putShort(1)
                    putShort(numChannels.toShort())
                    putInt(sampleRate)
                    putInt(byteRate)
                    putShort((numChannels * bitsPerSample / 8).toShort())
                    putShort(bitsPerSample.toShort())
                    put("data".toByteArray())
                    putInt(dataSize)
                }
                raf.seek(0L)
                raf.write(header.array())
            } finally {
                raf.close()
            }
        }
    }

    class StreamingWavReader(file: File) : Closeable {
        private val raf = RandomAccessFile(file, "r")
        var sampleRate: Int = 44100
            private set
        var channels: Int = 2
            private set
        var dataSize: Long = 0L
            private set
        var isValidPcm16: Boolean = false
            private set
        private var bytesRemaining: Long = 0L
        private var rawBuf = ByteArray(8192 * 4)

        init {
            parseHeader()
        }

        private fun parseHeader() {
            try {
                val fileLen = raf.length()
                if (fileLen < 44L) return
                val riff = ByteArray(12)
                raf.readFully(riff)
                if (String(riff, 0, 4) != "RIFF" || String(riff, 8, 4) != "WAVE") return

                var pos = 12L
                var audioFormat = 1
                var bitsPerSample = 16
                val chunkHeader = ByteArray(8)

                while (pos + 8L <= fileLen) {
                    raf.seek(pos)
                    raf.readFully(chunkHeader)
                    val chunkId = String(chunkHeader, 0, 4)
                    val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

                    if (chunkId == "fmt ") {
                        val fmtLen = min(chunkSize, 16L).toInt()
                        val fmtBytes = ByteArray(fmtLen)
                        raf.readFully(fmtBytes)
                        val fmtBuf = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                        if (fmtLen >= 16) {
                            audioFormat = fmtBuf.short.toInt()
                            channels = fmtBuf.short.toInt().coerceAtLeast(1)
                            sampleRate = fmtBuf.int
                            fmtBuf.int
                            fmtBuf.short
                            bitsPerSample = fmtBuf.short.toInt()
                        }
                    } else if (chunkId == "data") {
                        dataSize = min(chunkSize, fileLen - (pos + 8L))
                        bytesRemaining = dataSize
                        raf.seek(pos + 8L)
                        isValidPcm16 = (audioFormat == 1 && bitsPerSample == 16)
                        return
                    }
                    pos += 8L + chunkSize + (chunkSize and 1L)
                }
            } catch (_: Exception) {
                isValidPcm16 = false
            }
        }

        fun readChunk(left: FloatArray, right: FloatArray, maxFrames: Int): Int {
            if (!isValidPcm16 || bytesRemaining <= 0L) return 0
            val bytesPerFrame = channels * 2
            val toReadBytes = min(bytesRemaining, (maxFrames * bytesPerFrame).toLong()).toInt()
            if (toReadBytes < bytesPerFrame) return 0
            if (rawBuf.size < toReadBytes) {
                rawBuf = ByteArray(toReadBytes)
            }
            val actualRead = raf.read(rawBuf, 0, toReadBytes)
            if (actualRead <= 0) {
                bytesRemaining = 0L
                return 0
            }
            bytesRemaining -= actualRead
            val frames = actualRead / bytesPerFrame
            var p = 0
            for (i in 0 until frames) {
                val lLo = rawBuf[p].toInt() and 0xFF
                val lHi = rawBuf[p + 1].toInt()
                val l = ((lHi shl 8) or lLo).toShort() / 32768f
                val r = if (channels > 1) {
                    val rLo = rawBuf[p + 2].toInt() and 0xFF
                    val rHi = rawBuf[p + 3].toInt()
                    ((rHi shl 8) or rLo).toShort() / 32768f
                } else {
                    l
                }
                left[i] = l
                right[i] = r
                p += bytesPerFrame
            }
            return frames
        }

        override fun close() {
            raf.close()
        }
    }

    /**
     * Memory-safe chunked streaming decoder for WAV, MP3, AAC, M4A, OGG, FLAC, and MP4/MKV audio.
     * Uses less than 1 MB of heap memory regardless of song length.
     */
    fun streamDecodeStereoChunks(
        file: File,
        chunkFrames: Int = 8192,
        maxSeconds: Int = 600,
        onHeader: (sampleRate: Int) -> Unit,
        onChunk: (left: FloatArray, right: FloatArray, frameCount: Int, progress: Float) -> Unit
    ) {
        if (file.extension.equals("wav", ignoreCase = true)) {
            if (streamWavFile(file, chunkFrames, maxSeconds, onHeader, onChunk)) {
                return
            }
        }
        streamViaMediaCodec(file, chunkFrames, maxSeconds, onHeader, onChunk)
    }

    private fun streamWavFile(
        file: File,
        chunkFrames: Int,
        maxSeconds: Int,
        onHeader: (sampleRate: Int) -> Unit,
        onChunk: (left: FloatArray, right: FloatArray, frameCount: Int, progress: Float) -> Unit
    ): Boolean {
        val reader = StreamingWavReader(file)
        reader.use {
            if (!it.isValidPcm16) return false
            val sr = it.sampleRate
            val totalExpectedFrames = (it.dataSize / (it.channels * 2L)).coerceAtLeast(1L)
            val maxFrames = maxSeconds.toLong() * sr.toLong()
            onHeader(sr)

            val left = FloatArray(chunkFrames)
            val right = FloatArray(chunkFrames)
            var framesReadTotal = 0L

            while (framesReadTotal < maxFrames) {
                val want = min(chunkFrames.toLong(), maxFrames - framesReadTotal).toInt()
                val read = it.readChunk(left, right, want)
                if (read <= 0) break
                framesReadTotal += read
                val prog = (framesReadTotal.toFloat() / totalExpectedFrames.toFloat()).coerceIn(0f, 1f)
                onChunk(left, right, read, prog)
            }
            return true
        }
    }

    private fun streamViaMediaCodec(
        file: File,
        chunkFrames: Int,
        maxSeconds: Int,
        onHeader: (sampleRate: Int) -> Unit,
        onChunk: (left: FloatArray, right: FloatArray, frameCount: Int, progress: Float) -> Unit
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var audioTrackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                format = f
                break
            }
        }
        if (audioTrackIndex < 0 || format == null) {
            extractor.release()
            throw IllegalArgumentException("No audio track found in ${file.name}")
        }

        extractor.selectTrack(audioTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else 44100
        var channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 2
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
        } else 1L

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val outL = FloatArray(chunkFrames)
        val outR = FloatArray(chunkFrames)
        var outPos = 0
        var headerSent = false
        var isInputDone = false
        var isOutputDone = false
        var totalFramesEmitted = 0L
        var rawBuf = ByteArray(16384)
        var lastProgress = 0f

        fun ensureHeader() {
            if (!headerSent) {
                headerSent = true
                onHeader(sampleRate)
            }
        }

        try {
            while (!isOutputDone) {
                val maxFrames = maxSeconds.toLong() * sampleRate.toLong()
                if (totalFramesEmitted >= maxFrames) break

                if (!isInputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            isInputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            val size = bufferInfo.size
                            if (rawBuf.size < size) {
                                rawBuf = ByteArray(size)
                            }
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + size)
                            outBuf.get(rawBuf, 0, size)

                            val bytesPerFrame = (channelCount * 2).coerceAtLeast(2)
                            val frames = size / bytesPerFrame
                            lastProgress = if (durationUs > 1L) {
                                (bufferInfo.presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                            } else {
                                (totalFramesEmitted.toFloat() / (sampleRate * 180f)).coerceIn(0f, 0.99f)
                            }

                            var p = 0
                            for (i in 0 until frames) {
                                val lLo = rawBuf[p].toInt() and 0xFF
                                val lHi = rawBuf[p + 1].toInt()
                                val l = ((lHi shl 8) or lLo).toShort() / 32768f
                                val r = if (channelCount > 1) {
                                    val rLo = rawBuf[p + 2].toInt() and 0xFF
                                    val rHi = rawBuf[p + 3].toInt()
                                    ((rHi shl 8) or rLo).toShort() / 32768f
                                } else {
                                    l
                                }
                                p += bytesPerFrame

                                outL[outPos] = l
                                outR[outPos] = r
                                outPos++
                                totalFramesEmitted++

                                if (outPos == chunkFrames) {
                                    ensureHeader()
                                    onChunk(outL, outR, outPos, lastProgress)
                                    outPos = 0
                                }
                                if (totalFramesEmitted >= maxFrames) break
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isOutputDone = true
                        }
                    }

                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFmt = codec.outputFormat
                        if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }
            }

            if (outPos > 0) {
                ensureHeader()
                onChunk(outL, outR, outPos, 1f)
            } else if (!headerSent) {
                ensureHeader()
            }
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
            extractor.release()
        }
    }

    fun decodeAudioToStereoFloat(file: File, maxSeconds: Int = 120): DecodedAudio {
        var sr = 44100
        val chunksL = ArrayList<FloatArray>()
        val chunksR = ArrayList<FloatArray>()
        var total = 0

        streamDecodeStereoChunks(
            file = file,
            chunkFrames = 8192,
            maxSeconds = maxSeconds,
            onHeader = { sr = it },
            onChunk = { l, r, count, _ ->
                chunksL.add(l.copyOf(count))
                chunksR.add(r.copyOf(count))
                total += count
            }
        )

        val left = FloatArray(total)
        val right = FloatArray(total)
        var offset = 0
        for (i in chunksL.indices) {
            val cl = chunksL[i]
            val cr = chunksR[i]
            System.arraycopy(cl, 0, left, offset, cl.size)
            System.arraycopy(cr, 0, right, offset, cr.size)
            offset += cl.size
        }
        return DecodedAudio(left, right, sr)
    }

    fun readWavFile(file: File): DecodedAudio? {
        return try {
            decodeAudioToStereoFloat(file, maxSeconds = 120)
        } catch (_: Exception) {
            null
        }
    }

    fun writeStereoWav(file: File, left: FloatArray, right: FloatArray, sampleRate: Int) {
        val frames = min(left.size, right.size)
        StreamingWavWriter(file, sampleRate).use { writer ->
            val chunk = 8192
            val lBuf = FloatArray(chunk)
            val rBuf = FloatArray(chunk)
            var idx = 0
            while (idx < frames) {
                val count = min(chunk, frames - idx)
                System.arraycopy(left, idx, lBuf, 0, count)
                System.arraycopy(right, idx, rBuf, 0, count)
                writer.writeChunk(lBuf, rBuf, count)
                idx += count
            }
        }
    }

    /**
     * Memory-safe waveform peak computation via chunked streaming (< 100 KB RAM).
     */
    fun computeWaveformPeaks(file: File, barCount: Int = 64): List<Float> {
        return try {
            val chunkPeaks = ArrayList<Float>(256)
            streamDecodeStereoChunks(
                file = file,
                chunkFrames = 4096,
                maxSeconds = 360,
                onHeader = {},
                onChunk = { l, r, count, _ ->
                    var localMax = 0f
                    var i = 0
                    while (i < count) {
                        val amp = (abs(l[i]) + abs(r[i])) * 0.5f
                        if (amp > localMax) localMax = amp
                        i += 8
                    }
                    chunkPeaks.add(localMax)
                }
            )
            if (chunkPeaks.isEmpty()) return List(barCount) { 0.3f }

            val bars = FloatArray(barCount)
            var globalMax = 0.001f
            for (b in 0 until barCount) {
                val start = (b * chunkPeaks.size) / barCount
                val end = (((b + 1) * chunkPeaks.size) / barCount).coerceAtLeast(start + 1).coerceAtMost(chunkPeaks.size)
                var m = 0f
                for (k in start until end) {
                    if (chunkPeaks[k] > m) m = chunkPeaks[k]
                }
                bars[b] = m
                if (m > globalMax) globalMax = m
            }
            bars.map { (it / globalMax).coerceIn(0.08f, 1f) }
        } catch (_: Exception) {
            List(barCount) { 0.35f }
        }
    }

    fun computeWaveformPeaks(left: FloatArray, right: FloatArray, barCount: Int = 64): List<Float> {
        if (left.isEmpty()) return List(barCount) { 0.2f }
        val step = (left.size / barCount).coerceAtLeast(1)
        val peaks = ArrayList<Float>(barCount)
        var maxPeak = 0.001f
        for (b in 0 until barCount) {
            val start = b * step
            val end = min(start + step, left.size)
            var localMax = 0f
            var i = start
            while (i < end) {
                val amp = (abs(left[i]) + abs(right[i])) * 0.5f
                if (amp > localMax) localMax = amp
                i += 4
            }
            if (localMax > maxPeak) maxPeak = localMax
            peaks.add(localMax)
        }
        return peaks.map { (it / maxPeak).coerceIn(0.08f, 1f) }
    }
}
