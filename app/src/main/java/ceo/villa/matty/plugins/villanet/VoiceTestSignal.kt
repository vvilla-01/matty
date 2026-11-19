package ceo.villa.matty.plugins.villanet

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin

/**
 * The debug builds' test voice: a speech-like signal in place of what the microphone hears, so
 * that an emulator can talk in a voice channel. Debug builds always build the voice audio module
 * with [onBuffer] as its buffer callback; release builds never call it.
 *
 * Off, the recording passes untouched. On, every buffer is overwritten with bursts of about
 * 300 ms of a harmonic tone whose pitch wanders, then 200 ms of silence, at the buffer's own rate
 * (48 kHz on every phone the voice module meets), 16-bit little-endian. Switched by the debug
 * receiver `ceo.villa.matty.debug.TestVoice`; the microphone still has to be allowed, and the
 * session unmuted, for anything to be recorded at all.
 */
object VoiceTestSignal {
    @Volatile var on: Boolean = false

    /** Samples written so far (per channel), across buffers: the bursts run on from one buffer to the next. */
    private var t = 0L
    private var phase = 0.0

    /** The callback WebRTC calls on its recording thread with each buffer it read. */
    fun onBuffer(buffer: ByteBuffer, format: Int, channels: Int, sampleRate: Int, bytesRead: Int, captureTimeNs: Long): Long {
        if (on && format == PCM_16BIT) fill(buffer, channels.coerceAtLeast(1), sampleRate, bytesRead)
        return captureTimeNs
    }

    /**
     * Writes [bytes] bytes of the signal into [buffer] from index 0, with absolute puts only (its
     * position and limit stay as they were). Kept apart for the tests.
     */
    @Synchronized
    internal fun fill(buffer: ByteBuffer, channels: Int, sampleRate: Int, bytes: Int) {
        val rate = sampleRate.coerceAtLeast(8_000)
        val frames = bytes / (2 * channels)
        val burst = rate * BURST_MS / 1000
        val cycle = rate * (BURST_MS + GAP_MS) / 1000
        for (i in 0 until frames) {
            val inCycle = (t % cycle).toInt()
            val s: Int = if (inCycle < burst) {
                // A voice-like pitch that wanders between about 140 and 220 Hz, with two harmonics,
                // faded in and out over 10 ms so that each burst starts and ends without a click.
                val seconds = t.toDouble() / rate
                val pitch = 180.0 + 40.0 * sin(2 * PI * 3.0 * seconds)
                phase += 2 * PI * pitch / rate
                if (phase > 2 * PI) phase -= 2 * PI
                val fade = minOf(1.0, inCycle / (rate * 0.01), (burst - inCycle) / (rate * 0.01))
                val v = 0.55 * sin(phase) + 0.25 * sin(2 * phase) + 0.12 * sin(3 * phase)
                (v * fade * AMPLITUDE).toInt()
            } else 0
            for (ch in 0 until channels) {
                val at = (i * channels + ch) * 2
                if (at + 1 >= buffer.limit()) return
                buffer.put(at, (s and 0xff).toByte())
                buffer.put(at + 1, ((s shr 8) and 0xff).toByte())
            }
            t++
        }
    }

    private const val PCM_16BIT = 2 // android.media.AudioFormat.ENCODING_PCM_16BIT
    private const val BURST_MS = 300
    private const val GAP_MS = 200
    /** About a third of full scale: loud enough to count as talking (-10 dBov or so), never clipping. */
    private const val AMPLITUDE = 12_000.0
}
