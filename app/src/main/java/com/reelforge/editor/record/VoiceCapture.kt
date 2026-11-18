package com.reelforge.editor.record

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Records the microphone into a file for a voice-over. */
interface VoiceCapture {
    /** The loudest sample since the last call (0..32767), for the level bar. */
    val maxAmplitude: Int

    /** Throws if the microphone cannot be opened. */
    fun start()

    /** Stops recording. False when nothing usable was recorded (for example, too short). */
    fun stop(): Boolean

    fun release()
}

/** The plain recorder: the microphone as it is, saved as AAC in an .m4a file. */
class MicCapture(private val context: Context, private val file: File) : VoiceCapture {
    private var recorder: MediaRecorder? = null

    override val maxAmplitude: Int get() = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    override fun start() {
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(128000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
    }

    override fun stop(): Boolean = runCatching { recorder?.stop() }.isSuccess && file.length() > 0

    override fun release() {
        runCatching { recorder?.release() }
        recorder = null
    }
}

/**
 * The clean recorder. It records through the phone's voice-call path, which on most phones removes
 * background noise and the video's own sound coming out of the speaker (echo). The phone's noise
 * suppressor, echo canceller and automatic gain are switched on where the phone has them. Saved as
 * a 16-bit mono WAV file (about 5 MB a minute).
 */
class CleanCapture(private val file: File) : VoiceCapture {
    private var record: AudioRecord? = null
    private var output: RandomAccessFile? = null
    private var thread: Thread? = null
    private val effects = mutableListOf<AudioEffect>()

    @Volatile private var running = false
    @Volatile private var peak = 0
    @Volatile private var dataBytes = 0L
    private var rate = 48_000

    override val maxAmplitude: Int get() = peak.also { peak = 0 }

    @SuppressLint("MissingPermission") // asked for before any voice-over starts
    override fun start() {
        val (r, chosenRate) = openRecord()
        rate = chosenRate
        val session = r.audioSessionId
        // Not every phone has these; those that do usually apply them already on this path.
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.let { it.enabled = true; effects += it }
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.let { it.enabled = true; effects += it }
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session)?.let { it.enabled = true; effects += it }

        val out = RandomAccessFile(file, "rw")
        out.setLength(0)
        out.write(WavFile.header(rate, 1, 0))
        output = out
        record = r
        try {
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("microphone busy")
        } catch (e: Exception) {
            release()
            throw e
        }
        running = true
        thread = Thread({ pump(r, out) }, "voice-capture").also { it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(): Pair<AudioRecord, Int> {
        for (candidate in listOf(48_000, 44_100, 16_000)) {
            val min = AudioRecord.getMinBufferSize(candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, candidate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, candidate / 5 * 2),
            )
            if (r.state == AudioRecord.STATE_INITIALIZED) return r to candidate
            r.release()
        }
        error("microphone unavailable")
    }

    private fun pump(r: AudioRecord, out: RandomAccessFile) {
        val samples = ShortArray(rate / 20) // 50 ms at a time
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        while (running) {
            val n = r.read(samples, 0, samples.size)
            if (n <= 0) {
                if (n < 0) break
                continue
            }
            var loudest = 0
            bytes.clear()
            for (i in 0 until n) {
                val s = samples[i]
                loudest = maxOf(loudest, abs(s.toInt()))
                bytes.putShort(s)
            }
            peak = maxOf(peak, loudest)
            runCatching { out.write(bytes.array(), 0, n * 2) }.onFailure { running = false }
            dataBytes += n * 2
        }
    }

    override fun stop(): Boolean {
        running = false
        runCatching { thread?.join(1_500) }
        thread = null
        runCatching { record?.stop() }
        val out = output ?: return false
        return runCatching {
            out.seek(0)
            out.write(WavFile.header(rate, 1, dataBytes))
            out.close()
            output = null
            // Less than a third of a second is treated as a mis-tap.
            dataBytes >= rate * 2L / 3
        }.getOrDefault(false)
    }

    override fun release() {
        running = false
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        runCatching { record?.release() }
        record = null
        runCatching { output?.close() }
        output = null
    }
}

/** The 44-byte header at the start of a PCM WAV file. */
object WavFile {
    fun header(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = sampleRate * channels * 2
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt((36 + dataBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)              // size of this part
        b.putShort(1)             // PCM
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(byteRate)
        b.putShort((channels * 2).toShort())
        b.putShort(16)            // bits per sample
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataBytes.coerceAtMost(Int.MAX_VALUE.toLong() - 36).toInt())
        return b.array()
    }
}
