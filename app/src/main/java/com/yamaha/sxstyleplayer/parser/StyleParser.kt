package com.yamaha.sxstyleplayer.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "StyleParser"

// SFF2 Section marker names
enum class SectionType {
    INTRO_A, INTRO_B, INTRO_C,
    MAIN_A, MAIN_B, MAIN_C, MAIN_D,
    BREAK,
    ENDING_A, ENDING_B, ENDING_C
}

data class SectionMarker(
    val type: SectionType,
    val trackIndex: Int,
    val startOffset: Long,
    val endOffset: Long
)

data class StyleMetadata(
    val timeSigNumerator: Int = 4,
    val timeSigDenominator: Int = 4,
    val ppqn: Int = 480,
    val initialBpm: Int = 120,
    val sections: Map<SectionType, SectionMarker> = emptyMap(),
    val tracks: List<MidiTrack> = emptyList()
)

data class MidiTrack(
    val index: Int,
    val startOffset: Long,
    val length: Int,
    val events: List<MidiEvent>
)

data class MidiEvent(
    val tick: Long,
    val type: Int,
    val channel: Int,
    val data1: Int,
    val data2: Int,
    val metaType: Int = -1,
    val metaData: ByteArray = ByteArray(0)
)

class StyleParser(private val context: Context) {

    // Parse from assets
    fun parseFromAssets(filename: String): StyleMetadata? {
        return try {
            context.assets.open("styles/$filename").use { stream ->
                parseStream(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse asset: $filename", e)
            null
        }
    }

    // Parse from external URI (Storage Access Framework)
    fun parseFromUri(uri: Uri): StyleMetadata? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                parseStream(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse URI: $uri", e)
            null
        }
    }

    private fun parseStream(stream: InputStream): StyleMetadata {
        val bytes = stream.readBytes()
        val buffer = ByteBuffer.wrap(bytes).apply { order(ByteOrder.BIG_ENDIAN) }

        Log.d(TAG, "Parsing style file: ${bytes.size} bytes")

        // Parse MThd header
        val ppqn = parseMidiHeader(buffer)

        // Parse all chunks
        var timeSigNumerator = 4
        var timeSigDenominator = 4
        var initialBpm = 120
        val tracks = mutableListOf<MidiTrack>()
        var casmFound = false
        val sectionMarkers = mutableMapOf<SectionType, SectionMarker>()

        buffer.position(14) // Skip MThd chunk (4 magic + 4 length + 6 data)

        while (buffer.remaining() >= 8) {
            val chunkId = ByteArray(4).also { buffer.get(it) }
            val chunkLen = buffer.int
            val chunkStart = buffer.position()

            val chunkName = String(chunkId)
            Log.d(TAG, "Chunk: $chunkName, length: $chunkLen at offset $chunkStart")

            when (chunkName) {
                "MTrk" -> {
                    val trackData = parseTrack(buffer, chunkStart, chunkLen, tracks.size)
                    tracks.add(trackData)

                    // Extract tempo and time signature from track 0 (conductor track)
                    if (tracks.size == 1) {
                        for (event in trackData.events) {
                            if (event.type == 0xFF) {
                                when (event.metaType) {
                                    0x51 -> { // Set Tempo
                                        val tempoMicros = ((event.metaData[0].toInt() and 0xFF) shl 16) or
                                                ((event.metaData[1].toInt() and 0xFF) shl 8) or
                                                (event.metaData[2].toInt() and 0xFF)
                                        initialBpm = 60_000_000 / tempoMicros
                                        Log.d(TAG, "Tempo: $initialBpm BPM")
                                    }
                                    0x58 -> { // Time Signature
                                        timeSigNumerator = event.metaData[0].toInt() and 0xFF
                                        timeSigDenominator = 1 shl (event.metaData[1].toInt() and 0xFF)
                                        Log.d(TAG, "Time Sig: $timeSigNumerator/$timeSigDenominator")
                                    }
                                    0x06 -> { // Marker
                                        val markerName = String(event.metaData).trim()
                                        Log.d(TAG, "Marker: '$markerName' at tick ${event.tick}")
                                        parseSectionMarker(markerName, event.tick, chunkStart.toLong(), sectionMarkers)
                                    }
                                }
                            }
                        }
                    }

                    buffer.position(chunkStart + chunkLen)
                }
                "CASM" -> {
                    casmFound = true
                    Log.d(TAG, "Found CASM chunk - SFF2 confirmed")
                    parseCasmChunk(buffer, chunkStart, chunkLen, sectionMarkers, tracks)
                    buffer.position(chunkStart + chunkLen)
                }
                "OTSc" -> buffer.position(chunkStart + chunkLen) // One Touch Settings
                "MH  " -> buffer.position(chunkStart + chunkLen) // Master Header
                "SInt" -> buffer.position(chunkStart + chunkLen) // Style Interpretation
                else -> {
                    Log.d(TAG, "Unknown chunk: $chunkName, skipping")
                    if (chunkStart + chunkLen <= buffer.limit()) {
                        buffer.position(chunkStart + chunkLen)
                    } else break
                }
            }
        }

        if (!casmFound) {
            Log.w(TAG, "No CASM chunk found - may not be SFF2 format")
        }

        // If no explicit markers found, build default section layout
        if (sectionMarkers.isEmpty()) {
            buildDefaultSectionMap(tracks, ppqn, timeSigNumerator, sectionMarkers)
        }

        return StyleMetadata(
            timeSigNumerator = timeSigNumerator,
            timeSigDenominator = timeSigDenominator,
            ppqn = ppqn,
            initialBpm = initialBpm,
            sections = sectionMarkers,
            tracks = tracks
        )
    }

    private fun parseMidiHeader(buffer: ByteBuffer): Int {
        val magic = ByteArray(4).also { buffer.get(it) }
        if (String(magic) != "MThd") {
            Log.w(TAG, "No MThd header found, assuming PPQN=480")
            buffer.position(0)
            return 480
        }
        val headerLen = buffer.int // Should be 6
        val format = buffer.short.toInt()
        val numTracks = buffer.short.toInt()
        val ppqn = buffer.short.toInt() and 0xFFFF
        Log.d(TAG, "MIDI: format=$format, tracks=$numTracks, PPQN=$ppqn")
        return if (ppqn > 0 && ppqn <= 960) ppqn else 480
    }

    private fun parseTrack(
        buffer: ByteBuffer,
        startOffset: Int,
        length: Int,
        trackIndex: Int
    ): MidiTrack {
        val events = mutableListOf<MidiEvent>()
        val endPos = startOffset + length
        var currentTick = 0L
        var runningStatus = 0

        while (buffer.position() < endPos && buffer.remaining() > 0) {
            // Read delta time (variable length)
            val deltaTime = readVarLen(buffer)
            currentTick += deltaTime

            if (buffer.position() >= endPos) break

            val statusByte = buffer.get().toInt() and 0xFF

            when {
                statusByte == 0xFF -> { // Meta event
                    val metaType = buffer.get().toInt() and 0xFF
                    val metaLen = readVarLen(buffer)
                    val metaData = ByteArray(metaLen.toInt()).also { buffer.get(it) }
                    events.add(MidiEvent(currentTick, 0xFF, 0, metaType, 0, metaType, metaData))
                    runningStatus = 0
                }
                statusByte == 0xF0 || statusByte == 0xF7 -> { // SysEx
                    val sysexLen = readVarLen(buffer)
                    buffer.position(buffer.position() + sysexLen.toInt())
                    runningStatus = 0
                }
                statusByte >= 0x80 -> { // Status byte
                    runningStatus = statusByte
                    val eventType = (statusByte shr 4) and 0x0F
                    val channel = statusByte and 0x0F
                    val d1 = buffer.get().toInt() and 0xFF
                    val d2 = if (eventType != 0x0C && eventType != 0x0D)
                        buffer.get().toInt() and 0xFF else 0
                    events.add(MidiEvent(currentTick, eventType, channel, d1, d2))
                }
                else -> { // Running status
                    if (runningStatus >= 0x80) {
                        val eventType = (runningStatus shr 4) and 0x0F
                        val channel = runningStatus and 0x0F
                        val d1 = statusByte
                        val d2 = if (eventType != 0x0C && eventType != 0x0D)
                            buffer.get().toInt() and 0xFF else 0
                        events.add(MidiEvent(currentTick, eventType, channel, d1, d2))
                    }
                }
            }
        }

        return MidiTrack(trackIndex, startOffset.toLong(), length, events)
    }

    private fun parseCasmChunk(
        buffer: ByteBuffer,
        startOffset: Int,
        length: Int,
        sectionMarkers: MutableMap<SectionType, SectionMarker>,
        tracks: List<MidiTrack>
    ) {
        // CASM contains CSEG sub-chunks describing section assignments
        val endPos = startOffset + length
        while (buffer.position() < endPos - 8) {
            val subId = ByteArray(4).also { buffer.get(it) }
            val subLen = buffer.int
            val subStart = buffer.position()
            val subName = String(subId)

            if (subName == "CSEG") {
                // Each CSEG describes a style section
                // Bytes: SectionCode(1) + TrackAssignment(1) + ...
                if (subLen >= 2) {
                    val sectionCode = buffer.get().toInt() and 0xFF
                    val trackAssign = buffer.get().toInt() and 0xFF
                    val section = sectionCodeToType(sectionCode)
                    if (section != null) {
                        Log.d(TAG, "CSEG: section=${section.name}, trackAssign=$trackAssign")
                    }
                }
            }

            if (subStart + subLen <= buffer.limit()) {
                buffer.position(subStart + subLen)
            } else break
        }
    }

    private fun parseSectionMarker(
        markerName: String,
        tick: Long,
        trackOffset: Long,
        map: MutableMap<SectionType, SectionMarker>
    ) {
        val normalized = markerName.lowercase().trim()
        val type = when {
            normalized.contains("intro") && normalized.contains("a") -> SectionType.INTRO_A
            normalized.contains("intro") && normalized.contains("b") -> SectionType.INTRO_B
            normalized.contains("intro") && normalized.contains("c") -> SectionType.INTRO_C
            normalized.contains("main") && normalized.contains("a") -> SectionType.MAIN_A
            normalized.contains("main") && normalized.contains("b") -> SectionType.MAIN_B
            normalized.contains("main") && normalized.contains("c") -> SectionType.MAIN_C
            normalized.contains("main") && normalized.contains("d") -> SectionType.MAIN_D
            normalized.contains("break") || normalized.contains("fill") -> SectionType.BREAK
            normalized.contains("ending") && normalized.contains("a") -> SectionType.ENDING_A
            normalized.contains("ending") && normalized.contains("b") -> SectionType.ENDING_B
            normalized.contains("ending") && normalized.contains("c") -> SectionType.ENDING_C
            else -> null
        } ?: return

        map[type] = SectionMarker(type, 0, tick, tick + 1920) // Default 1-bar length
    }

    private fun sectionCodeToType(code: Int): SectionType? = when (code) {
        0x00 -> SectionType.INTRO_A
        0x01 -> SectionType.INTRO_B
        0x02 -> SectionType.INTRO_C
        0x10 -> SectionType.MAIN_A
        0x11 -> SectionType.MAIN_B
        0x12 -> SectionType.MAIN_C
        0x13 -> SectionType.MAIN_D
        0x18 -> SectionType.BREAK
        0x20 -> SectionType.ENDING_A
        0x21 -> SectionType.ENDING_B
        0x22 -> SectionType.ENDING_C
        else -> null
    }

    private fun buildDefaultSectionMap(
        tracks: List<MidiTrack>,
        ppqn: Int,
        timeSigNum: Int,
        map: MutableMap<SectionType, SectionMarker>
    ) {
        val barTicks = (ppqn * timeSigNum).toLong()
        val sections = SectionType.values()
        sections.forEachIndexed { i, section ->
            map[section] = SectionMarker(section, 0, i * barTicks * 2, (i + 2) * barTicks * 2)
        }
    }

    private fun readVarLen(buffer: ByteBuffer): Long {
        var value = 0L
        var byte: Int
        do {
            if (buffer.remaining() == 0) break
            byte = buffer.get().toInt() and 0xFF
            value = (value shl 7) or (byte and 0x7F).toLong()
        } while (byte and 0x80 != 0)
        return value
    }
}
