package com.yamaha.sxstyleplayer.sequencer

import android.util.Log
import com.yamaha.sxstyleplayer.parser.SectionType
import com.yamaha.sxstyleplayer.parser.StyleMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "Sequencer"

data class SequencerState(
    val isPlaying: Boolean = false,
    val currentSection: SectionType? = null,
    val queuedSection: SectionType? = null,
    val currentBpm: Int = 120,
    val currentBeat: Int = 0,       // 0-based beat index
    val currentBar: Int = 0,
    val currentTick: Long = 0L,
    val playbackState: PlaybackState = PlaybackState.STOPPED
)

enum class PlaybackState {
    STOPPED,
    PLAYING,
    PLAY_ONCE,          // Used for Intro/Ending
    AUTO_TRANSITION,    // About to jump to next section
    BREAK_FILL          // One-shot break fill
}

/**
 * TempoClock: High-resolution PPQN-based clock
 * Pulse interval = 60,000,000 / (BPM × PPQN) microseconds
 */
class TempoClock(
    private var bpm: Int = 120,
    private val ppqn: Int = 480
) {
    // Interval in nanoseconds for Thread.sleep precision
    var pulseIntervalNanos: Long = calcInterval(bpm)
        private set

    fun setBpm(newBpm: Int) {
        bpm = newBpm.coerceIn(20, 300)
        pulseIntervalNanos = calcInterval(bpm)
    }

    fun getBpm() = bpm

    private fun calcInterval(bpm: Int): Long {
        // 60,000,000 µs per minute / (bpm × ppqn) = µs per pulse
        // Convert to nanoseconds: × 1000
        val microsPerPulse = 60_000_000L / (bpm * ppqn)
        return microsPerPulse * 1_000L
    }
}

/**
 * Quantization Queue: Buffers section changes to bar boundaries
 * Section switches at: currentTick % (PPQN * timeSigNumerator) == 0
 */
class QuantizationQueue(private val ppqn: Int, private val timeSigNumerator: Int) {
    private val barLengthTicks = ppqn.toLong() * timeSigNumerator

    fun isBarBoundary(tick: Long): Boolean {
        return tick % barLengthTicks == 0L
    }

    fun ticksToNextBar(currentTick: Long): Long {
        val ticksInBar = currentTick % barLengthTicks
        return if (ticksInBar == 0L) 0L else barLengthTicks - ticksInBar
    }
}

/**
 * Main Sequencer Engine
 * Drives MIDI playback based on TempoClock, handles section state machine
 */
class Sequencer(
    private val midiEngine: MidiEventListener
) {
    private var styleMetadata: StyleMetadata? = null
    private var tempoClock = TempoClock()
    private var quantizer = QuantizationQueue(480, 4)

    private val _state = MutableStateFlow(SequencerState())
    val state: StateFlow<SequencerState> = _state.asStateFlow()

    private val isRunning = AtomicBoolean(false)
    private val currentTickAtomic = AtomicLong(0L)
    private val currentBarAtomic = AtomicInteger(0)
    private val queuedSection = MutableStateFlow<SectionType?>(null)
    private val currentSection = MutableStateFlow<SectionType?>(null)

    private var sequencerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Section end ticks for PLAY_ONCE detection
    private val sectionEndTick = AtomicLong(Long.MAX_VALUE)
    private var postSectionTarget: SectionType? = null
    private var currentPlaybackState = PlaybackState.STOPPED
    private var previousMainSection: SectionType? = SectionType.MAIN_A

    fun loadStyle(metadata: StyleMetadata) {
        stop()
        styleMetadata = metadata
        tempoClock = TempoClock(metadata.initialBpm, metadata.ppqn)
        quantizer = QuantizationQueue(metadata.ppqn, metadata.timeSigNumerator)
        updateState()
        Log.d(TAG, "Loaded style: BPM=${metadata.initialBpm}, PPQN=${metadata.ppqn}")
    }

    fun start(section: SectionType = SectionType.MAIN_A) {
        if (isRunning.get()) return
        isRunning.set(true)
        currentSection.value = section
        currentTickAtomic.set(getSectionStartTick(section))
        sectionEndTick.set(getSectionEndTick(section))
        postSectionTarget = null

        currentPlaybackState = when (section) {
            SectionType.INTRO_A, SectionType.INTRO_B, SectionType.INTRO_C -> {
                postSectionTarget = SectionType.MAIN_A
                currentPlaybackState = PlaybackState.PLAY_ONCE
                PlaybackState.PLAY_ONCE
            }
            SectionType.BREAK -> {
                postSectionTarget = previousMainSection
                PlaybackState.BREAK_FILL
            }
            SectionType.ENDING_A, SectionType.ENDING_B, SectionType.ENDING_C -> {
                postSectionTarget = null
                PlaybackState.PLAY_ONCE
            }
            else -> {
                previousMainSection = section
                PlaybackState.PLAYING
            }
        }

        updateState()
        startClock()
    }

    fun stop() {
        isRunning.set(false)
        sequencerJob?.cancel()
        sequencerJob = null
        currentPlaybackState = PlaybackState.STOPPED
        currentTickAtomic.set(0L)
        currentBarAtomic.set(0)
        queuedSection.value = null
        midiEngine.stopAllNotes()
        updateState()
    }

    fun queueSection(section: SectionType) {
        val meta = styleMetadata ?: return
        if (!isRunning.get()) {
            start(section)
            return
        }

        when (section) {
            SectionType.INTRO_A, SectionType.INTRO_B, SectionType.INTRO_C -> {
                // Intros: queue for bar boundary, then auto-transition to Main A
                queuedSection.value = section
                postSectionTarget = SectionType.MAIN_A
            }
            SectionType.BREAK -> {
                // Break: play one measure then return to current main
                queuedSection.value = section
                postSectionTarget = previousMainSection
            }
            SectionType.ENDING_A, SectionType.ENDING_B, SectionType.ENDING_C -> {
                // Endings: play once then stop
                queuedSection.value = section
                postSectionTarget = null
            }
            else -> {
                // Main sections: queue for bar boundary
                previousMainSection = section
                queuedSection.value = section
            }
        }
        updateState()
        Log.d(TAG, "Queued section: ${section.name}")
    }

    fun setBpm(bpm: Int) {
        tempoClock.setBpm(bpm)
        updateState()
    }

    fun getBpm() = tempoClock.getBpm()

    private fun startClock() {
        sequencerJob = scope.launch(Dispatchers.Default) {
            val meta = styleMetadata
            val ppqn = meta?.ppqn ?: 480
            val timeSigNum = meta?.timeSigNumerator ?: 4

            // High-priority thread via coroutine
            while (isRunning.get() && isActive) {
                val intervalNanos = tempoClock.pulseIntervalNanos
                val startNanos = System.nanoTime()

                val tick = currentTickAtomic.incrementAndGet()
                val beat = ((tick / ppqn) % timeSigNum).toInt()
                val bar = (tick / (ppqn * timeSigNum)).toInt()

                if (bar != currentBarAtomic.get()) {
                    currentBarAtomic.set(bar)
                    // Bar boundary — check quantization queue
                    val queued = queuedSection.value
                    if (queued != null && quantizer.isBarBoundary(tick)) {
                        transitionToSection(queued)
                        queuedSection.value = null
                    }
                }

                // Check if PLAY_ONCE section ended
                if (tick >= sectionEndTick.get()) {
                    handleSectionEnd()
                }

                // Dispatch MIDI events for this tick
                dispatchMidiEvents(tick)

                // Emit beat for UI
                _state.value = _state.value.copy(
                    currentBeat = beat,
                    currentBar = bar,
                    currentTick = tick
                )

                // Precise sleep to next pulse
                val elapsed = System.nanoTime() - startNanos
                val sleepNanos = intervalNanos - elapsed
                if (sleepNanos > 1_000L) {
                    delay(sleepNanos / 1_000_000L)
                }
            }
        }
    }

    private fun transitionToSection(section: SectionType) {
        val startTick = getSectionStartTick(section)
        currentTickAtomic.set(startTick)
        sectionEndTick.set(getSectionEndTick(section))
        currentSection.value = section
        currentPlaybackState = when (section) {
            SectionType.BREAK -> PlaybackState.BREAK_FILL
            SectionType.INTRO_A, SectionType.INTRO_B, SectionType.INTRO_C,
            SectionType.ENDING_A, SectionType.ENDING_B, SectionType.ENDING_C -> PlaybackState.PLAY_ONCE
            else -> PlaybackState.PLAYING
        }
        midiEngine.stopAllNotes()
        Log.d(TAG, "Transitioned to: ${section.name}")
        updateState()
    }

    private fun handleSectionEnd() {
        val next = postSectionTarget
        if (next != null) {
            transitionToSection(next)
            postSectionTarget = null
        } else {
            stop() // Ending or no target → stop
        }
    }

    private fun dispatchMidiEvents(tick: Long) {
        val meta = styleMetadata ?: return
        val sectionStart = getSectionStartTick(currentSection.value ?: return)
        val relativeTick = tick - sectionStart

        for (track in meta.tracks) {
            for (event in track.events) {
                if (event.tick == relativeTick && event.channel == 9) { // Channel 10 = index 9
                    midiEngine.onMidiEvent(event)
                }
            }
        }
    }

    private fun getSectionStartTick(section: SectionType): Long {
        return styleMetadata?.sections?.get(section)?.startOffset ?: 0L
    }

    private fun getSectionEndTick(section: SectionType): Long {
        val meta = styleMetadata ?: return Long.MAX_VALUE
        return meta.sections[section]?.endOffset ?: Long.MAX_VALUE
    }

    private fun updateState() {
        _state.value = SequencerState(
            isPlaying = isRunning.get(),
            currentSection = currentSection.value,
            queuedSection = queuedSection.value,
            currentBpm = tempoClock.getBpm(),
            currentBeat = _state.value.currentBeat,
            currentBar = _state.value.currentBar,
            currentTick = currentTickAtomic.get(),
            playbackState = currentPlaybackState
        )
    }
}

interface MidiEventListener {
    fun onMidiEvent(event: com.yamaha.sxstyleplayer.parser.MidiEvent)
    fun stopAllNotes()
}
