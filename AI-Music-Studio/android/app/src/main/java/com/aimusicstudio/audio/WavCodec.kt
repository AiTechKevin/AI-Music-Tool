package com.aimusicstudio.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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

    fun decodeAudioToStereoFloat(file: File, maxSeconds: Int = 300): DecodedAudio {
        if (file.extension.equals("wav", ignoreCase = true)) {
            val wav = readWavFile(file)
            if (wav != null) return wav
        }
        return decodeViaMediaCodec(file, maxSeconds)
    }

    private fun decodeViaMediaCodec(file: File, maxSeconds: Int): DecodedAudio {
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

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmOut = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var isInputDone = false
        var isOutputDone = false
        val maxBytes = maxSeconds * sampleRate * channelCount * 2

        try {
            while (!isOutputDone && pcmOut.size() < maxBytes) {
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
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        if (bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            outBuf.get(chunk)
                            pcmOut.write(chunk)
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
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        val bytes = pcmOut.toByteArray()
        val shortBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val totalShorts = shortBuffer.remaining()
        val frames = totalShorts / channelCount.coerceAtLeast(1)
        val left = FloatArray(frames)
        val right = FloatArray(frames)

        for (i in 0 until frames) {
            val l = shortBuffer.get(i * channelCount) / 32768f
            val r = if (channelCount > 1) shortBuffer.get(i * channelCount + 1) / 32768f else l
            left[i] = l
            right[i] = r
        }

        return DecodedAudio(left, right, sampleRate)
    }

    fun readWavFile(file: File): DecodedAudio? {
        return try {
            val bytes = FileInputStream(file).use { it.readBytes() }
            if (bytes.size < 44) return null
            val header = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
            if (bytes[0].toInt().toChar() != 'R' || bytes[1].toInt().toChar() != 'I') return null

            var pos = 12
            var sampleRate = 44100
            var channels = 2
            var bitsPerSample = 16
            var dataOffset = -1
            var dataSize = 0

            while (pos + 8 <= bytes.size) {
                val chunkId = String(bytes, pos, 4)
                val chunkSize = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (chunkId == "fmt ") {
                    val fmtBuf = ByteBuffer.wrap(bytes, pos + 8, chunkSize.coerceAtMost(bytes.size - pos - 8))
                        .order(ByteOrder.LITTLE_ENDIAN)
                    fmtBuf.short // audioFormat
                    channels = fmtBuf.short.toInt().coerceAtLeast(1)
                    sampleRate = fmtBuf.int
                    fmtBuf.int // byteRate
                    fmtBuf.short // blockAlign
                    bitsPerSample = fmtBuf.short.toInt()
                } else if (chunkId == "data") {
                    dataOffset = pos + 8
                    dataSize = min(chunkSize, bytes.size - dataOffset)
                    break
                }
                pos += 8 + chunkSize + (chunkSize % 2)
            }

            if (dataOffset < 0 || bitsPerSample != 16) return null
            val shortBuf = ByteBuffer.wrap(bytes, dataOffset, dataSize)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            val frames = (dataSize / 2) / channels
            val left = FloatArray(frames)
            val right = FloatArray(frames)
            for (i in 0 until frames) {
                val l = shortBuf.get(i * channels) / 32768f
                val r = if (channels > 1) shortBuf.get(i * channels + 1) / 32768f else l
                left[i] = l
                right[i] = r
            }
            DecodedAudio(left, right, sampleRate)
        } catch (_: Exception) {
            null
        }
    }

    fun writeStereoWav(file: File, left: FloatArray, right: FloatArray, sampleRate: Int) {
        val frames = min(left.size, right.size)
        val numChannels = 2
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val dataSize = frames * numChannels * 2

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1) // PCM
            putShort(numChannels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((numChannels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(dataSize)
        }

        FileOutputStream(file).use { out ->
            out.write(header.array())
            val chunkFrames = 8192
            val buf = ByteBuffer.allocate(chunkFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
            var idx = 0
            while (idx < frames) {
                val count = min(chunkFrames, frames - idx)
                buf.clear()
                for (i in 0 until count) {
                    val lSample = (left[idx + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    val rSample = (right[idx + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    buf.putShort(lSample)
                    buf.putShort(rSample)
                }
                out.write(buf.array(), 0, count * 4)
                idx += count
            }
        }
    }

    fun computeWaveformPeaks(file: File, barCount: Int = 64): List<Float> {
        return try {
            val decoded = decodeAudioToStereoFloat(file, maxSeconds = 180)
            computeWaveformPeaks(decoded.left, decoded.right, barCount)
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
