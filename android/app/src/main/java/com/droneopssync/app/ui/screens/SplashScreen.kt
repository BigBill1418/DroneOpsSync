package com.droneopssync.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droneopssync.app.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Animated splash screen — plays for ~1.6 seconds total.
 *
 * Sequence:
 *   0–400ms   Drone icon flies in from left, rotating slightly
 *   400–900ms Logo fades up and scales into place
 *   900–1200ms Subtitle and tagline fade in
 *   1200–1600ms Hold, then fire [onFinished]
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {

    // ── Animation timeline ────────────────────────────────────────────────────
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        started = true
        delay(1600)
        onFinished()
    }

    // Drone fly-in (0–400ms)
    val droneOffsetX by animateFloatAsState(
        targetValue = if (started) 0f else -300f,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "droneX"
    )
    val droneAlpha by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(350),
        label = "droneAlpha"
    )
    val droneRotation by animateFloatAsState(
        targetValue = if (started) 0f else -25f,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "droneRotation"
    )

    // Logo scale + fade (delayed 300ms)
    val logoScale by animateFloatAsState(
        targetValue = if (started) 1f else 0.6f,
        animationSpec = tween(500, delayMillis = 300, easing = FastOutSlowInEasing),
        label = "logoScale"
    )
    val logoAlpha by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(450, delayMillis = 300),
        label = "logoAlpha"
    )

    // Subtitle fade (delayed 700ms)
    val subtitleAlpha by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(400, delayMillis = 700),
        label = "subtitleAlpha"
    )

    // Tagline fade (delayed 900ms)
    val taglineAlpha by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(350, delayMillis = 900),
        label = "taglineAlpha"
    )

    // ── Layout ────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    0.0f  to Color(0xFF0A1628),
                    0.5f  to Color(0xFF070D18),
                    1.0f  to DocDeep,
                    center = Offset.Unspecified,
                    radius = 1200f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            // ── Drone icon flying in ──────────────────────────────────────────
            Icon(
                Icons.Default.Flight,
                contentDescription = null,
                tint = DocCyan,
                modifier = Modifier
                    .size(64.dp)
                    .offset(x = droneOffsetX.dp)
                    .alpha(droneAlpha)
                    .rotate(droneRotation)
            )

            Spacer(Modifier.height(20.dp))

            // ── Logo image or text ────────────────────────────────────────────
            val context = LocalContext.current
            val logoResId = remember {
                context.resources.getIdentifier("barnard_hq_logo", "drawable", context.packageName)
            }

            Box(
                modifier = Modifier
                    .scale(logoScale)
                    .alpha(logoAlpha)
            ) {
                if (logoResId != 0) {
                    Image(
                        painter = painterResource(logoResId),
                        contentDescription = "BarnardHQ",
                        modifier = Modifier
                            .fillMaxWidth(0.65f)
                            .aspectRatio(1.5f),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = DocWhite)) { append("Barnard") }
                            withStyle(SpanStyle(color = DocCyan))  { append("HQ") }
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 42.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Subtitle ──────────────────────────────────────────────────────
            Text(
                text = "DRONEOPS SYNC",
                color = DocCyan,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(subtitleAlpha)
            )

            Spacer(Modifier.height(8.dp))

            // ── Tagline ───────────────────────────────────────────────────────
            Text(
                text = "Professional Aerial Operations",
                color = DocMuted,
                fontSize = 12.sp,
                letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(taglineAlpha)
            )
        }
    }
}
