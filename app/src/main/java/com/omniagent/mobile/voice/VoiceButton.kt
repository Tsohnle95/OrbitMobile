package com.omniagent.mobile.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.omniagent.mobile.ui.theme.LocalOmniColors

@Composable
fun VoiceButton(
    speech: SpeechManager,
    disabled: Boolean,
    onTranscribed: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOmniColors.current
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var pendingSamples by remember { mutableStateOf<ShortArray?>(null) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted && speech.startRecording()) recording = true
    }

    val pulse = rememberInfiniteTransition(label = "recPulse").animateFloat(
        initialValue = 0.85f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "recScale",
    )

    LaunchedEffect(downloading) {
        if (!downloading) return@LaunchedEffect
        val ok = speech.downloadModel { p -> progress = p }
        downloading = false
        if (ok) {
            pendingSamples?.let { samples ->
                val text = speech.transcribe(samples)
                if (text.isNotBlank()) onTranscribed(text)
            }
            statusText = null
        } else {
            statusText = speech.lastError ?: "Voice model download failed."
        }
        pendingSamples = null
    }

    DisposableEffect(Unit) {
        onDispose {
            if (recording) speech.stopRecordingAndGetSamples()
        }
    }

    fun startOrRequest() {
        statusText = null
        if (hasPermission) {
            if (speech.startRecording()) recording = true
            else statusText = "Could not access microphone."
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun stopAndTranscribe() {
        val samples = speech.stopRecordingAndGetSamples()
        recording = false
        when {
            samples.size < 3200 -> statusText = "Too short — hold a little longer."
            !speech.isModelReady() -> {
                pendingSamples = samples
                progress = 0f
                downloading = true
            }
            else -> {
                transcribing = true
                pendingSamples = samples
            }
        }
    }

    LaunchedEffect(transcribing) {
        if (!transcribing) return@LaunchedEffect
        val samples = pendingSamples
        pendingSamples = null
        val text = samples?.let { speech.transcribe(it) } ?: ""
        transcribing = false
        if (text.isNotBlank()) onTranscribed(text)
        else statusText = speech.lastError ?: "Nothing recognized."
    }

    Column(modifier = modifier) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            when {
                downloading -> CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.5.dp,
                    color = colors.accent,
                )
                recording -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .scale(pulse.value)
                            .clip(CircleShape)
                            .background(colors.red),
                    )
                    Spacer(Modifier.size(7.dp))
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(colors.bgActivePill)
                            .clickable { stopAndTranscribe() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Stop recording",
                            tint = colors.text,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
                else -> Icon(
                    Icons.Rounded.Mic,
                    contentDescription = "Start voice input",
                    tint = if (disabled) colors.textFaint else colors.textDim,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(enabled = !disabled) { startOrRequest() },
                )
            }
        }
        statusText?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.labelMedium,
                color = colors.red,
                modifier = Modifier
                    .fillMaxWidth(2.4f)
                    .padding(top = 4.dp),
            )
        }
    }
}
