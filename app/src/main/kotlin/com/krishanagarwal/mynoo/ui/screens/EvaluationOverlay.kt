package com.krishanagarwal.mynoo.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun EvaluationOverlay(
    show: Boolean,
    category: String, // full_marks, partial_marks, no_marks, retry_available
    earnedMarks: Double,
    maxMarks: Double,
    soundText: String = "",
    variationIndex: Int = 0,
    onDismiss: () -> Unit,
    onRetryClicked: (() -> Unit)? = null,
) {
    if (!show) return

    val infiniteTransition = rememberInfiniteTransition(label = "eval_anim")
    
    // Scale entrance animation
    val scaleAnim by animateFloatAsState(
        targetValue = if (show) 1f else 0.8f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "scale"
    )

    // Pulse animation
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Rotation animation for retry/full marks rings
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val formatMark: (Double) -> String = { m ->
        if (m % 1.0 == 0.0) m.toInt().toString() else String.format("%.1f", m)
    }

    val earnedFormatted = formatMark(earnedMarks)
    val maxFormatted = formatMark(maxMarks)

    val (titleText, mainEmoji, themeColors, cardBgGradient) = when (category) {
        "full_marks" -> {
            val title = when (variationIndex % 4) {
                0 -> "🌟 PERFECT SCORE!"
                1 -> "🎉 FULL MARKS!"
                2 -> "🏆 OUTSTANDING!"
                else -> "✨ BRILLIANT!"
            }
            val emoji = when (variationIndex % 4) {
                0 -> "💯"
                1 -> "🌟"
                2 -> "🥇"
                else -> "🎆"
            }
            Quadruple(
                title,
                emoji,
                listOf(Color(0xFF27AE60), Color(0xFF2ECC71), Color(0xFFF1C40F)),
                Brush.verticalGradient(listOf(Color(0xFFE8F8F0), Color(0xFFFFFFFF)))
            )
        }
        "partial_marks" -> {
            val title = when (variationIndex % 4) {
                0 -> "⚡ PARTIAL MARKS!"
                1 -> "📈 GOOD EFFORT!"
                2 -> "👍 SOLID TRY!"
                else -> "⭐ ALMOST THERE!"
            }
            Quadruple(
                title,
                "🌗",
                listOf(Color(0xFFE67E22), Color(0xFFF39C12), Color(0xFFF1C40F)),
                Brush.verticalGradient(listOf(Color(0xFFFEF9E7), Color(0xFFFFFFFF)))
            )
        }
        "retry_available" -> {
            Quadruple(
                "🔄 RETRY AVAILABLE!",
                "🎯",
                listOf(Color(0xFF2980B9), Color(0xFF3498DB), Color(0xFF1ABC9C)),
                Brush.verticalGradient(listOf(Color(0xFFEBF5FB), Color(0xFFFFFFFF)))
            )
        }
        else -> { // no_marks
            val title = when (variationIndex % 4) {
                0 -> "💡 KEEP LEARNING!"
                1 -> "💪 DON'T GIVE UP!"
                2 -> "📚 PRACTICE MAKES PERFECT!"
                else -> "🌱 KEEP GOING!"
            }
            Quadruple(
                title,
                "💪",
                listOf(Color(0xFFC0392B), Color(0xFFE74C3C), Color(0xFF9B59B6)),
                Brush.verticalGradient(listOf(Color(0xFFFDEDEC), Color(0xFFFFFFFF)))
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center
        ) {
            // Background Animation Overlay Canvas (Confetti / Particles)
            if (category == "full_marks" || category == "partial_marks") {
                ParticleCanvas(category = category, rotationAngle = rotationAngle)
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .scale(scaleAnim)
                    .border(2.dp, themeColors[0].copy(alpha = 0.6f), RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBgGradient)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Top Emoji Badge with Rotating/Pulsing Outer Ring
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(96.dp)
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize().rotate(rotationAngle)) {
                                drawCircle(
                                    brush = Brush.sweepGradient(themeColors),
                                    style = Stroke(width = 6.dp.toPx())
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = themeColors[0].copy(alpha = 0.12f),
                                modifier = Modifier
                                    .size(80.dp)
                                    .scale(pulseScale)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(text = mainEmoji, fontSize = 42.sp)
                                }
                            }
                        }

                        // Title
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.5.sp
                            ),
                            color = themeColors[0],
                            textAlign = TextAlign.Center
                        )

                        // Marks Display Container
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = themeColors[0].copy(alpha = 0.08f),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, themeColors[0].copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Marks Scored",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF7F8C8D)
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = earnedFormatted,
                                        style = MaterialTheme.typography.headlineLarge.copy(
                                            fontWeight = FontWeight.Black,
                                            fontSize = 36.sp
                                        ),
                                        color = themeColors[0]
                                    )
                                    Text(
                                        text = " / $maxFormatted",
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 24.sp
                                        ),
                                        color = Color(0xFF7F8C8D),
                                        modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
                                    )
                                }
                            }
                        }

                        // Sound Prompt Spoken Text
                        if (soundText.isNotBlank()) {
                            Text(
                                text = "🗣️ \"$soundText\"",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                ),
                                color = Color(0xFF555555),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }

                        // Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (category == "retry_available" && onRetryClicked != null) {
                                Button(
                                    onClick = {
                                        onDismiss()
                                        onRetryClicked()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = themeColors[0]),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                ) {
                                    Text("🔄 Retry Question", fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = onDismiss,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                ) {
                                    Text("Continue")
                                }
                            } else {
                                Button(
                                    onClick = onDismiss,
                                    colors = ButtonDefaults.buttonColors(containerColor = themeColors[0]),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                ) {
                                    Text("Continue ✓", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
private fun ParticleCanvas(category: String, rotationAngle: Float) {
    val particles = remember {
        List(24) {
            val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
            val distance = Random.nextFloat() * 180f + 60f
            val size = Random.nextFloat() * 12f + 6f
            val color = when (it % 4) {
                0 -> Color(0xFFFFD700)
                1 -> Color(0xFF2ECC71)
                2 -> Color(0xFFE74C3C)
                else -> Color(0xFF3498DB)
            }
            Triple(angle, distance, Pair(size, color))
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radOffset = Math.toRadians(rotationAngle.toDouble()).toFloat()

        particles.forEach { (angle, dist, sizeAndColor) ->
            val (pSize, pColor) = sizeAndColor
            val currentAngle = angle + radOffset
            val x = center.x + cos(currentAngle) * dist
            val y = center.y + sin(currentAngle) * dist
            drawCircle(
                color = pColor.copy(alpha = 0.75f),
                radius = pSize,
                center = Offset(x, y)
            )
        }
    }
}
