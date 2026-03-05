package com.yamaha.sxstyleplayer.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yamaha.sxstyleplayer.AppUiState
import com.yamaha.sxstyleplayer.parser.SectionType

// ─── Color Palette ────────────────────────────────────────────────────────────
val DarkBackground    = Color(0xFF0D0D0F)
val SurfaceCard       = Color(0xFF1A1A1F)
val SurfaceElevated   = Color(0xFF252530)
val AccentYellow      = Color(0xFFFFD700)
val AccentBlue        = Color(0xFF4488FF)
val AccentGreen       = Color(0xFF44DD88)
val AccentRed         = Color(0xFFFF4455)
val AccentOrange      = Color(0xFFFF8833)
val ActiveGlow        = Color(0xFFFFD700)
val TextPrimary       = Color(0xFFEEEEFF)
val TextSecondary     = Color(0xFF8888AA)
val BeatDownbeat      = Color(0xFFFF3344)
val BeatNormal        = Color(0xFF44CC77)
val BeatInactive      = Color(0xFF2A2A3A)

// Section button color families
val SectionColors = mapOf(
    SectionType.INTRO_A   to AccentBlue,
    SectionType.INTRO_B   to AccentBlue,
    SectionType.INTRO_C   to AccentBlue,
    SectionType.MAIN_A    to AccentYellow,
    SectionType.MAIN_B    to AccentYellow,
    SectionType.MAIN_C    to AccentYellow,
    SectionType.MAIN_D    to AccentYellow,
    SectionType.BREAK     to AccentOrange,
    SectionType.ENDING_A  to AccentRed,
    SectionType.ENDING_B  to AccentRed,
    SectionType.ENDING_C  to AccentRed,
)

val SectionLabels = mapOf(
    SectionType.INTRO_A   to "INTRO A",
    SectionType.INTRO_B   to "INTRO B",
    SectionType.INTRO_C   to "INTRO C",
    SectionType.MAIN_A    to "MAIN A",
    SectionType.MAIN_B    to "MAIN B",
    SectionType.MAIN_C    to "MAIN C",
    SectionType.MAIN_D    to "MAIN D",
    SectionType.BREAK     to "BREAK",
    SectionType.ENDING_A  to "ENDING A",
    SectionType.ENDING_B  to "ENDING B",
    SectionType.ENDING_C  to "ENDING C",
)

// ─── Main Screen ──────────────────────────────────────────────────────────────
@Composable
fun MainScreen(
    uiState: AppUiState,
    onSectionClicked: (SectionType) -> Unit,
    onStartStop: () -> Unit,
    onBpmChanged: (Int) -> Unit,
    onOpenFilePicker: () -> Unit,
    onDismissError: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            StyleHeader(uiState, onOpenFilePicker)

            // Beat indicator
            BeatShower(
                currentBeat = uiState.currentBeat,
                timeSigNumerator = uiState.timeSigNumerator,
                isPlaying = uiState.isPlaying
            )

            // Section grid
            SectionGrid(
                uiState = uiState,
                onSectionClicked = onSectionClicked
            )

            // Transport controls
            TransportRow(
                uiState = uiState,
                onStartStop = onStartStop,
                onBpmChanged = onBpmChanged
            )

            Spacer(Modifier.height(32.dp))
        }

        // Error snackbar
        if (uiState.errorMessage != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                ErrorBar(uiState.errorMessage, onDismissError)
            }
        }
    }
}

// ─── Style Header ─────────────────────────────────────────────────────────────
@Composable
fun StyleHeader(uiState: AppUiState, onOpenFilePicker: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                "SX Style Player",
                color = AccentYellow,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                uiState.loadedStyleName ?: "No style loaded",
                color = if (uiState.loadedStyleName != null) TextPrimary else TextSecondary,
                fontSize = 13.sp
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = AccentYellow,
                    strokeWidth = 2.dp
                )
            }
            IconButton(
                onClick = onOpenFilePicker,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceElevated)
            ) {
                Icon(Icons.Default.FolderOpen, "Open style", tint = AccentYellow)
            }
        }
    }
}

// ─── Beat Shower ─────────────────────────────────────────────────────────────
@Composable
fun BeatShower(
    currentBeat: Int,
    timeSigNumerator: Int,
    isPlaying: Boolean
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard)
    ) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "BEAT INDICATOR",
                color = TextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth()
            ) {
                for (beat in 0 until timeSigNumerator.coerceAtMost(8)) {
                    BeatCircle(
                        beat = beat,
                        isActive = isPlaying && beat == currentBeat,
                        isDownbeat = beat == 0
                    )
                }
            }
        }
    }
}

@Composable
fun BeatCircle(beat: Int, isActive: Boolean, isDownbeat: Boolean) {
    val targetColor = when {
        isActive && isDownbeat -> BeatDownbeat
        isActive -> BeatNormal
        else -> BeatInactive
    }

    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = if (isActive) 50 else 150),
        label = "beatColor"
    )

    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.25f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "beatScale"
    )

    val glowAlpha by animateFloatAsState(
        targetValue = if (isActive) 0.6f else 0f,
        animationSpec = tween(durationMillis = if (isActive) 30 else 200),
        label = "glowAlpha"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .scale(scale)
    ) {
        // Glow ring
        if (glowAlpha > 0.01f) {
            Box(
                Modifier
                    .size(44.dp)
                    .drawBehind {
                        drawCircle(
                            color = targetColor.copy(alpha = glowAlpha * 0.5f),
                            radius = size.minDimension / 2 + 8.dp.toPx()
                        )
                    }
            )
        }

        // Main circle
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(animatedColor)
        ) {}

        // Beat number
        Text(
            "${beat + 1}",
            color = if (isActive) Color.Black else TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ─── Section Grid ─────────────────────────────────────────────────────────────
@Composable
fun SectionGrid(
    uiState: AppUiState,
    onSectionClicked: (SectionType) -> Unit
) {
    val rows = listOf(
        listOf(SectionType.INTRO_A, SectionType.INTRO_B, SectionType.INTRO_C),
        listOf(SectionType.MAIN_A, SectionType.MAIN_B, SectionType.MAIN_C, SectionType.MAIN_D),
        listOf(SectionType.BREAK),
        listOf(SectionType.ENDING_A, SectionType.ENDING_B, SectionType.ENDING_C)
    )

    val rowLabels = listOf("INTRO", "MAIN", "BREAK", "ENDING")

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEachIndexed { rowIdx, sections ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Row label
                    Text(
                        rowLabels[rowIdx],
                        color = TextSecondary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                        modifier = Modifier.width(42.dp),
                        textAlign = TextAlign.Center
                    )

                    sections.forEach { section ->
                        SectionButton(
                            section = section,
                            isActive = uiState.currentSection == section,
                            isQueued = uiState.queuedSection == section,
                            isAvailable = uiState.availableSections.contains(section)
                                    || uiState.loadedStyleName != null,
                            onClick = { onSectionClicked(section) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SectionButton(
    section: SectionType,
    isActive: Boolean,
    isQueued: Boolean,
    isAvailable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val baseColor = SectionColors[section] ?: AccentYellow

    // Pulsing glow animation for active state
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )

    val bgColor by animateColorAsState(
        targetValue = when {
            isActive -> baseColor.copy(alpha = 0.25f)
            isQueued -> baseColor.copy(alpha = 0.12f)
            else -> SurfaceElevated
        },
        animationSpec = tween(200),
        label = "bgColor"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isActive -> baseColor
            isQueued -> baseColor.copy(alpha = 0.6f)
            isAvailable -> baseColor.copy(alpha = 0.3f)
            else -> Color.Transparent
        },
        animationSpec = tween(200),
        label = "borderColor"
    )

    val glowRadius: Dp = if (isActive) (12 * glowPulse).dp else if (isQueued) 6.dp else 0.dp

    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .drawBehind {
                if (isActive && glowRadius.value > 0) {
                    drawRoundRect(
                        color = baseColor.copy(alpha = 0.3f * glowPulse),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                            10.dp.toPx() + glowRadius.toPx()
                        ),
                        size = androidx.compose.ui.geometry.Size(
                            size.width + glowRadius.toPx() * 2,
                            size.height + glowRadius.toPx() * 2
                        ),
                        topLeft = androidx.compose.ui.geometry.Offset(
                            -glowRadius.toPx(), -glowRadius.toPx()
                        )
                    )
                }
            }
            .background(bgColor)
            .border(
                width = if (isActive) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(enabled = isAvailable) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = SectionLabels[section] ?: section.name,
                color = when {
                    isActive -> baseColor
                    isQueued -> baseColor.copy(alpha = 0.8f)
                    isAvailable -> TextPrimary
                    else -> TextSecondary
                },
                fontSize = 11.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
            if (isQueued && !isActive) {
                Text("QUEUED", color = baseColor.copy(alpha = 0.7f), fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            }
        }
    }
}

// ─── Transport Controls ───────────────────────────────────────────────────────
@Composable
fun TransportRow(
    uiState: AppUiState,
    onStartStop: () -> Unit,
    onBpmChanged: (Int) -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Start/Stop button
            Button(
                onClick = onStartStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isPlaying) AccentRed else AccentGreen
                )
            ) {
                Icon(
                    if (uiState.isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (uiState.isPlaying) "STOP" else "START",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
            }

            // BPM Slider
            BpmControl(
                bpm = uiState.currentBpm,
                timeSig = "${uiState.timeSigNumerator}/${uiState.timeSigDenominator}",
                onBpmChanged = onBpmChanged
            )
        }
    }
}

@Composable
fun BpmControl(bpm: Int, timeSig: String, onBpmChanged: (Int) -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "TEMPO",
                color = TextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    timeSig,
                    color = AccentBlue,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "$bpm BPM",
                    color = AccentYellow,
                    fontSize = 22.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Slider(
            value = bpm.toFloat(),
            onValueChange = { onBpmChanged(it.toInt()) },
            valueRange = 40f..240f,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = AccentYellow,
                activeTrackColor = AccentYellow,
                inactiveTrackColor = SurfaceElevated
            )
        )

        // Quick BPM buttons
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(80, 100, 120, 140, 160).forEach { preset ->
                OutlinedButton(
                    onClick = { onBpmChanged(preset) },
                    modifier = Modifier.weight(1f).height(32.dp),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, AccentYellow.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        "$preset",
                        color = if (bpm == preset) AccentYellow else TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

// ─── Error Bar ────────────────────────────────────────────────────────────────
@Composable
fun ErrorBar(message: String, onDismiss: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AccentRed.copy(alpha = 0.9f))
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Warning, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text(message, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, null, tint = Color.White)
            }
        }
    }
}

// Easing function
private val EaseInOutSine = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
