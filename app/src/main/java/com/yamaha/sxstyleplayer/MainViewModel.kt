package com.yamaha.sxstyleplayer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yamaha.sxstyleplayer.engine.MidiEngine
import com.yamaha.sxstyleplayer.parser.SectionType
import com.yamaha.sxstyleplayer.parser.StyleMetadata
import com.yamaha.sxstyleplayer.parser.StyleParser
import com.yamaha.sxstyleplayer.sequencer.Sequencer
import com.yamaha.sxstyleplayer.sequencer.SequencerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class AppUiState(
    val isPlaying: Boolean = false,
    val currentSection: SectionType? = null,
    val queuedSection: SectionType? = null,
    val currentBpm: Int = 120,
    val currentBeat: Int = 0,
    val timeSigNumerator: Int = 4,
    val timeSigDenominator: Int = 4,
    val loadedStyleName: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val availableSections: Set<SectionType> = emptySet()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val styleParser = StyleParser(application)
    private val midiEngine = MidiEngine(application)
    private val sequencer = Sequencer(midiEngine)

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private var styleMetadata: StyleMetadata? = null

    init {
        // Observe sequencer state
        sequencer.state.onEach { seqState ->
            _uiState.value = _uiState.value.copy(
                isPlaying = seqState.isPlaying,
                currentSection = seqState.currentSection,
                queuedSection = seqState.queuedSection,
                currentBpm = seqState.currentBpm,
                currentBeat = seqState.currentBeat
            )
        }.launchIn(viewModelScope)

        // Load default demo style if available
        loadDefaultStyle()
    }

    private fun loadDefaultStyle() {
        viewModelScope.launch {
            try {
                val assets = getApplication<Application>().assets
                val styleFiles = assets.list("styles") ?: emptyArray()
                if (styleFiles.isNotEmpty()) {
                    loadStyleFromAssets(styleFiles[0])
                }
            } catch (e: Exception) {
                // No default style — that's OK
            }
        }
    }

    fun loadStyleFromAssets(filename: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val metadata = styleParser.parseFromAssets(filename)
                if (metadata != null) {
                    onStyleLoaded(metadata, filename)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Failed to parse style file"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "Unknown error"
                )
            }
        }
    }

    fun loadStyleFromUri(uri: Uri, displayName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            sequencer.stop()
            try {
                val metadata = styleParser.parseFromUri(uri)
                if (metadata != null) {
                    onStyleLoaded(metadata, displayName)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Failed to parse: $displayName"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "Parse error"
                )
            }
        }
    }

    private fun onStyleLoaded(metadata: StyleMetadata, name: String) {
        styleMetadata = metadata
        sequencer.loadStyle(metadata)
        sequencer.setBpm(metadata.initialBpm)

        _uiState.value = _uiState.value.copy(
            isLoading = false,
            loadedStyleName = name,
            currentBpm = metadata.initialBpm,
            timeSigNumerator = metadata.timeSigNumerator,
            timeSigDenominator = metadata.timeSigDenominator,
            availableSections = metadata.sections.keys,
            errorMessage = null
        )
    }

    fun onSectionClicked(section: SectionType) {
        if (_uiState.value.isPlaying) {
            sequencer.queueSection(section)
        } else {
            sequencer.start(section)
        }
    }

    fun onStartStop() {
        if (_uiState.value.isPlaying) {
            sequencer.stop()
        } else {
            val section = _uiState.value.currentSection ?: SectionType.MAIN_A
            sequencer.start(section)
        }
    }

    fun onBpmChanged(bpm: Int) {
        sequencer.setBpm(bpm)
        _uiState.value = _uiState.value.copy(currentBpm = bpm)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        sequencer.stop()
        midiEngine.release()
    }
}
