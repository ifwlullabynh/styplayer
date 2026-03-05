package com.yamaha.sxstyleplayer.engine

import android.content.Context
import android.media.midi.*
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.yamaha.sxstyleplayer.parser.MidiEvent
import com.yamaha.sxstyleplayer.sequencer.MidiEventListener
import java.io.File

private const val TAG = "MidiEngine"

// MIDI Channel 10 = index 9 (0-based) — percussion channel
private const val PERCUSSION_CHANNEL = 9
private const val CC_ALL_NOTES_OFF = 123

/**
 * XG Drum Map — maps General MIDI note numbers to Yamaha XG percussion names
 * Used for logging/debugging and SoundFont lookup
 */
val XG_DRUM_MAP = mapOf(
    35 to "Acoustic Bass Drum",
    36 to "Bass Drum 1",
    37 to "Side Stick",
    38 to "Acoustic Snare",
    39 to "Hand Clap",
    40 to "Electric Snare",
    41 to "Low Floor Tom",
    42 to "Closed Hi-Hat",
    43 to "High Floor Tom",
    44 to "Pedal Hi-Hat",
    45 to "Low Tom",
    46 to "Open Hi-Hat",
    47 to "Low-Mid Tom",
    48 to "Hi-Mid Tom",
    49 to "Crash Cymbal 1",
    50 to "High Tom",
    51 to "Ride Cymbal 1",
    52 to "Chinese Cymbal",
    53 to "Ride Bell",
    54 to "Tambourine",
    55 to "Splash Cymbal",
    56 to "Cowbell",
    57 to "Crash Cymbal 2",
    58 to "Vibraslap",
    59 to "Ride Cymbal 2",
    60 to "Hi Bongo",
    61 to "Low Bongo",
    62 to "Mute Hi Conga",
    63 to "Open Hi Conga",
    64 to "Low Conga",
    65 to "High Timbale",
    66 to "Low Timbale",
    81 to "Open Triangle"
)

/**
 * MidiEngine: Bridges sequencer events to Android MIDI API + FluidSynth SF2
 *
 * Architecture:
 *  Sequencer → MidiEngine → Android MIDI API → FluidSynth JNI → SF2 Samples
 *
 * Channel filtering: HARD-CODED to pass only Channel 10 (index 9)
 * All other channels (Bass, Chord1/2, Pad, etc.) are silently dropped.
 */
class MidiEngine(private val context: Context) : MidiEventListener {

    private var midiManager: MidiManager? = null
    private var outputDevice: MidiDevice? = null
    private var inputPort: MidiInputPort? = null
    private val msgBuffer = ByteArray(3)

    // FluidSynth bridge (loaded via JNI)
    private var fluidSynthHandle: Long = 0L
    private var soundFontId: Int = -1
    private var fluidSynthAvailable = false

    private val engineLock = Any()

    init {
        initMidiManager()
        initFluidSynth()
    }

    private fun initMidiManager() {
        try {
            midiManager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
            Log.d(TAG, "MIDI Manager initialized: ${midiManager != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init MIDI manager", e)
        }
    }

    private fun initFluidSynth() {
        try {
            System.loadLibrary("fluidsynth")
            fluidSynthHandle = nativeCreateSynth()
            if (fluidSynthHandle != 0L) {
                val sf2Path = findSoundFont()
                if (sf2Path != null) {
                    soundFontId = nativeLoadSoundFont(fluidSynthHandle, sf2Path)
                    fluidSynthAvailable = soundFontId >= 0
                    Log.d(TAG, "FluidSynth ready: SF2 loaded, id=$soundFontId")
                } else {
                    Log.w(TAG, "No SF2 file found, using fallback audio")
                }
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "FluidSynth native library not found, using Android MIDI API")
        } catch (e: Exception) {
            Log.e(TAG, "FluidSynth init error", e)
        }
    }

    private fun findSoundFont(): String? {
        // Check bundled assets first
        val assetSf2 = File(context.filesDir, "yamaha_xg.sf2")
        if (assetSf2.exists()) return assetSf2.absolutePath

        // Copy from assets if available
        try {
            context.assets.open("yamaha_xg.sf2").use { input ->
                assetSf2.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return assetSf2.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "No bundled SF2 found: ${e.message}")
        }

        // Check external storage
        val externalSf2 = File(context.getExternalFilesDir(null), "yamaha_xg.sf2")
        if (externalSf2.exists()) return externalSf2.absolutePath

        return null
    }

    /**
     * Core MIDI event dispatcher
     * HARD-CODED FILTER: Only processes Channel 10 (index 9) = percussion
     */
    override fun onMidiEvent(event: MidiEvent) {
        // CHANNEL FILTER — drop everything that isn't percussion
        if (event.channel != PERCUSSION_CHANNEL && event.type != 0xFF) {
            return
        }

        synchronized(engineLock) {
            when (event.type) {
                0x09 -> { // Note On
                    if (event.data2 > 0) {
                        val drumName = XG_DRUM_MAP[event.data1] ?: "Unknown(${event.data1})"
                        Log.v(TAG, "NoteOn: $drumName vel=${event.data2}")
                        sendNoteOn(PERCUSSION_CHANNEL, event.data1, event.data2)
                    } else {
                        sendNoteOff(PERCUSSION_CHANNEL, event.data1)
                    }
                }
                0x08 -> sendNoteOff(PERCUSSION_CHANNEL, event.data1) // Note Off
                0x0B -> sendControlChange(event.channel, event.data1, event.data2) // CC
                0x0C -> sendProgramChange(event.channel, event.data1) // Program Change
                0x0A -> sendPolyphonicPressure(event.channel, event.data1, event.data2)
            }
        }
    }

    /**
     * stopAllNotes: Sends CC 123 (All Notes Off) to prevent cymbal bleed
     * Called on section switches and Stop
     */
    override fun stopAllNotes() {
        synchronized(engineLock) {
            Log.d(TAG, "stopAllNotes: sending CC 123 to all channels")

            // Send to all 16 channels for safety
            for (ch in 0..15) {
                sendControlChange(ch, CC_ALL_NOTES_OFF, 0)
                sendControlChange(ch, 120, 0) // All Sound Off
            }

            if (fluidSynthAvailable) {
                nativeAllNotesOff(fluidSynthHandle)
            }
        }
    }

    private fun sendNoteOn(channel: Int, note: Int, velocity: Int) {
        if (fluidSynthAvailable) {
            nativeNoteOn(fluidSynthHandle, channel, note, velocity)
            return
        }
        sendRawMidi(byteArrayOf(
            (0x90 or (channel and 0x0F)).toByte(),
            (note and 0x7F).toByte(),
            (velocity and 0x7F).toByte()
        ))
    }

    private fun sendNoteOff(channel: Int, note: Int) {
        if (fluidSynthAvailable) {
            nativeNoteOff(fluidSynthHandle, channel, note)
            return
        }
        sendRawMidi(byteArrayOf(
            (0x80 or (channel and 0x0F)).toByte(),
            (note and 0x7F).toByte(),
            0x00
        ))
    }

    private fun sendControlChange(channel: Int, cc: Int, value: Int) {
        if (fluidSynthAvailable) {
            nativeCC(fluidSynthHandle, channel, cc, value)
            return
        }
        sendRawMidi(byteArrayOf(
            (0xB0 or (channel and 0x0F)).toByte(),
            (cc and 0x7F).toByte(),
            (value and 0x7F).toByte()
        ))
    }

    private fun sendProgramChange(channel: Int, program: Int) {
        sendRawMidi(byteArrayOf(
            (0xC0 or (channel and 0x0F)).toByte(),
            (program and 0x7F).toByte()
        ))
    }

    private fun sendPolyphonicPressure(channel: Int, note: Int, pressure: Int) {
        sendRawMidi(byteArrayOf(
            (0xA0 or (channel and 0x0F)).toByte(),
            (note and 0x7F).toByte(),
            (pressure and 0x7F).toByte()
        ))
    }

    private fun sendRawMidi(data: ByteArray) {
        try {
            inputPort?.send(data, 0, data.size)
        } catch (e: Exception) {
            Log.e(TAG, "MIDI send error: ${e.message}")
        }
    }

    fun release() {
        stopAllNotes()
        inputPort?.close()
        outputDevice?.close()
        if (fluidSynthAvailable) {
            nativeDestroySynth(fluidSynthHandle)
        }
    }

    // JNI declarations for FluidSynth bridge
    private external fun nativeCreateSynth(): Long
    private external fun nativeDestroySynth(handle: Long)
    private external fun nativeLoadSoundFont(handle: Long, path: String): Int
    private external fun nativeNoteOn(handle: Long, channel: Int, note: Int, velocity: Int)
    private external fun nativeNoteOff(handle: Long, channel: Int, note: Int)
    private external fun nativeCC(handle: Long, channel: Int, cc: Int, value: Int)
    private external fun nativeAllNotesOff(handle: Long)
}
