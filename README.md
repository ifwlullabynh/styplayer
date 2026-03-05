# SX Style Player — Yamaha SFF2 Android App

A high-fidelity Android arranger workstation app for playing Yamaha SX-series `.sty` style files.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     Jetpack Compose UI                      │
│  MainScreen → SectionGrid → BeatShower → TransportRow       │
└────────────────────────┬────────────────────────────────────┘
                         │ StateFlow<AppUiState>
┌────────────────────────▼────────────────────────────────────┐
│                    MainViewModel                             │
│        (orchestrates parser, sequencer, engine)             │
└───────┬──────────────────────────────────┬──────────────────┘
        │                                  │
┌───────▼──────────┐              ┌────────▼─────────────────┐
│   StyleParser    │              │       Sequencer           │
│  (SFF2/Binary)   │              │  TempoClock + QueueLogic  │
│                  │              │  StateMachine             │
│ • MTrk headers   │              │  PPQN-based clock         │
│ • CASM chunk     │              │  Bar-boundary quantizer   │
│ • Meta 0x06      │              └────────┬─────────────────┘
│   (Markers)      │                       │ MidiEventListener
│ • Meta 0x58      │              ┌────────▼─────────────────┐
│   (TimeSig)      │              │       MidiEngine          │
│ • Meta 0x51      │              │  Channel 10 Filter        │
│   (Tempo)        │              │  XG Drum Map              │
└──────────────────┘              │  CC 123 (stopAllNotes)    │
                                  └────────┬─────────────────┘
                                           │ JNI
                                  ┌────────▼─────────────────┐
                                  │   FluidSynth (C++)        │
                                  │   SF2 SoundFont loader    │
                                  │   Oboe/OpenSLES output    │
                                  └──────────────────────────┘
```

---

## Project Structure

```
app/src/main/
├── java/com/yamaha/sxstyleplayer/
│   ├── MainActivity.kt              ← Entry point + SAF file picker
│   ├── MainViewModel.kt             ← State orchestration
│   ├── parser/
│   │   └── StyleParser.kt          ← SFF2 binary parser
│   ├── sequencer/
│   │   └── Sequencer.kt            ← TempoClock + QuantizationQueue
│   ├── engine/
│   │   └── MidiEngine.kt           ← MIDI Channel 10 filter + FluidSynth
│   └── ui/
│       ├── MainScreen.kt           ← Full Compose UI
│       └── theme/Theme.kt
├── cpp/
│   ├── CMakeLists.txt
│   └── fluidsynth_bridge.cpp       ← JNI bridge
└── assets/
    └── styles/                     ← Drop .sty files here
```

---

## 1. SFF2 Parser

The `StyleParser` reads Yamaha SFF2 binary files:

- **MThd** header → extracts PPQN (typically 480 for Yamaha)
- **CASM** chunk → confirms SFF2 format, reads section assignment table
- **MTrk** chunks → parses all MIDI tracks with running-status support
- **Meta 0x06** (Marker) → maps `"Intro A"`, `"Main B"`, etc. to tick offsets
- **Meta 0x58** (Time Signature) → initializes beat indicator
- **Meta 0x51** (Set Tempo) → calculates initial BPM

### Section Marker Names (SFF2 convention)
The parser normalizes marker strings:
```
"Intro A" / "IntroA" / "INTRO_A" → SectionType.INTRO_A
"Main B" / "MainB"               → SectionType.MAIN_B
"Break" / "Fill In"              → SectionType.BREAK
```

---

## 2. MIDI Engine & Channel Filtering

```kotlin
// HARD-CODED filter — only Channel 10 (index 9) passes through
if (event.channel != PERCUSSION_CHANNEL && event.type != 0xFF) {
    return  // Drop Bass, Chord1/2, Pad, Strings etc.
}
```

### stopAllNotes()
Called automatically on section switch and stop:
```kotlin
// Sends CC 123 + CC 120 to all 16 channels
// Prevents open hi-hat/cymbal bleed
for (ch in 0..15) {
    sendControlChange(ch, CC_ALL_NOTES_OFF, 0)  // CC 123
    sendControlChange(ch, 120, 0)               // All Sound Off
}
```

---

## 3. Sequencer Clock

### Pulse Interval Formula
```
interval (µs) = 60,000,000 / (BPM × PPQN)
interval (ns)  = interval (µs) × 1,000
```

At 120 BPM, PPQN=480:
```
60,000,000 / (120 × 480) = 1,041.67 µs per pulse
```

### Bar Boundary Quantization
```kotlin
// Section changes are held in a queue and applied only at bar boundaries:
fun isBarBoundary(tick: Long): Boolean {
    return tick % (ppqn * timeSigNumerator) == 0L
}
```

---

## 4. Playback State Machine

```
             ┌──────────┐
     START   │          │   STOP
  ──────────►│ PLAYING  │◄──────────
             │  (Main)  │
             └────┬─────┘
                  │ Queue Intro
                  ▼
        ┌──────────────────┐
        │    PLAY_ONCE     │
        │  (Intro A/B/C)   │
        └────────┬─────────┘
                 │ End of section
                 ▼
        ┌──────────────────┐
        │ AUTO_TRANSITION  │──► PLAYING (Main A)
        └──────────────────┘

    Break Fill:  PLAYING → BREAK_FILL (1 bar) → PLAYING (previous Main)
    Ending:      PLAYING → PLAY_ONCE (Ending) → STOPPED
```

---

## 5. UI Components

### Beat Shower
- **Beat 1 (downbeat)**: Red circle with glow pulse
- **Beats 2–4**: Green circles
- Inactive beats: Dark gray
- All beats animate with spring physics on activation

### Section Grid (12 buttons)
| Row | Buttons | Color |
|-----|---------|-------|
| INTRO | A, B, C | Blue |
| MAIN | A, B, C, D | Yellow |
| BREAK | — | Orange |
| ENDING | A, B, C | Red |

Active buttons have a **glowing pulsing border** using `infiniteRepeatable` animation.
Queued buttons show a "QUEUED" label below the name.

---

## Setup Instructions

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- NDK 25+
- CMake 3.22+

### FluidSynth Integration (Optional but Recommended)
1. Download prebuilt FluidSynth for Android:
   ```
   https://github.com/FluidSynth/fluidsynth/releases
   ```
2. Place `.so` files:
   ```
   app/src/main/jniLibs/arm64-v8a/libfluidsynth.so
   app/src/main/jniLibs/armeabi-v7a/libfluidsynth.so
   ```
3. Place headers:
   ```
   app/src/main/jniLibs/include/fluidsynth.h
   ```

### SoundFont (SF2)
Place a Yamaha XG-compatible SoundFont:
```
app/src/main/assets/yamaha_xg.sf2
```
Free options: GeneralUser GS, SGM-V2.01, or OmegaGMGS2.

### Style Files
Place `.sty` files in:
```
app/src/main/assets/styles/MyStyle.sty
```
Or use the in-app file picker to load from internal storage/SD card.

### Build
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Notes on SFF2 Compatibility

The Yamaha SFF2 format used in PSR-SX/PSR-A/Genos series has these key properties:
- PPQN: Always 480
- Channel mapping: 9=Rhythm, 10=Bass, 11=Chord1, 12=Chord2, 13=Pad, 14=Phrase1, 15=Phrase2
- Section ordering in CASM: Controlled by `CSEG` sub-chunks
- Some newer models use encrypted OTS chunks (not parsed here)

> **Note**: This app reads the **rhythm channel only** (Ch 10 / index 9) per the spec requirements. To hear the full arrangement, remove the channel filter in `MidiEngine.kt`.
