package com.socialmaster.app.ui.screens

import android.media.AudioDeviceInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.socialmaster.app.audio.InputChannelMode
import com.socialmaster.app.audio.InputLevels
import com.socialmaster.app.audio.InputSourceProfile
import com.socialmaster.app.ui.components.AppButton
import com.socialmaster.app.ui.components.SectionLabel

/**
 * First-run setup, one question per screen. It edits the same settings the Recording setup
 * dialog edits, so nothing it shows can disagree with the rest of the app.
 */
object WizardStep {
    const val WELCOME = 0
    const val PERMISSIONS = 1
    const val INPUT = 2
    const val SOUND = 3
    const val PLAYALONG = 4
    const val READY = 5
    const val LAST = READY
}

@Composable
fun SetupWizard(
    step: Int,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    devices: List<AudioDeviceInfo>,
    selectedDeviceId: Int?,
    onDeviceSelected: (Int?) -> Unit,
    mode: InputChannelMode,
    onModeSelected: (InputChannelMode) -> Unit,
    sourceProfile: InputSourceProfile,
    onSourceProfileSelected: (InputSourceProfile) -> Unit,
    monitorInput: Boolean,
    onMonitorInputChanged: (Boolean) -> Unit,
    isTesting: Boolean,
    onToggleTest: () -> Unit,
    levels: InputLevels,
    routeInfo: String,
    outputRoute: String,
    backingName: String?,
    onLoadBacking: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    Dialog(onDismissRequest = onSkip, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            shape = com.socialmaster.app.ui.theme.AppCardShape,
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.padding(20.dp).fillMaxSize()) {
                WizardProgress(step)
                Spacer(Modifier.height(18.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    when (step) {
                        WizardStep.WELCOME -> WelcomeStep()
                        WizardStep.PERMISSIONS -> PermissionsStep(hasPermissions, onRequestPermissions)
                        WizardStep.INPUT -> InputStep(
                            devices, selectedDeviceId, onDeviceSelected, mode, onModeSelected,
                            sourceProfile, onSourceProfileSelected, isTesting, onToggleTest, levels, routeInfo
                        )
                        WizardStep.SOUND -> SoundStep(monitorInput, onMonitorInputChanged, outputRoute)
                        WizardStep.PLAYALONG -> PlayAlongStep(backingName, onLoadBacking)
                        WizardStep.READY -> ReadyStep(hasPermissions, devices, selectedDeviceId, monitorInput, backingName)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (step > WizardStep.WELCOME) {
                        TextButton(onClick = onBack) { Text("Back") }
                    }
                    TextButton(onClick = onSkip) { Text(if (step == WizardStep.READY) "Close" else "Skip") }
                    Spacer(Modifier.weight(1f))
                    if (step < WizardStep.LAST) {
                        AppButton("Next", onNext)
                    } else {
                        AppButton("Start recording", onNext)
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardProgress(step: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (index in 0..WizardStep.LAST) {
            Box(
                Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(
                    if (index <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }
}

@Composable
private fun WelcomeStep() {
    WizardTitle("Make a studio video of you playing", "Record yourself, mix it fast, and post it loud enough for the platform.")
    Spacer(Modifier.height(18.dp))
    WizardPoint(Icons.Filled.SettingsInputComponent, "Use a pro interface", "Plug in your audio interface and it becomes your microphone, straight into the phone.")
    WizardPoint(Icons.Filled.Mic, "Hear yourself while you play", "Monitor through headphones so you can sing or play without the speakers in the way.")
    WizardPoint(Icons.AutoMirrored.Filled.QueueMusic, "Play along", "Add a backing track and record your part over it.")
    WizardPoint(Icons.Filled.PhotoCamera, "Post straight away", "The mix is mastered to social media loudness and exported vertical, ready to upload.")
    Spacer(Modifier.height(10.dp))
    Text(
        "No interface? The phone microphone works too.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun PermissionsStep(hasPermissions: Boolean, onRequestPermissions: () -> Unit) {
    WizardTitle("Two permissions", "SocialMaster needs the camera to film you and the microphone to record you.")
    Spacer(Modifier.height(16.dp))
    WizardPoint(Icons.Filled.PhotoCamera, "Camera", if (hasPermissions) "Allowed" else "Not allowed yet")
    WizardPoint(Icons.Filled.Mic, "Microphone", if (hasPermissions) "Allowed" else "Not allowed yet")
    Spacer(Modifier.height(16.dp))
    if (!hasPermissions) {
        AppButton("Allow camera and microphone", onRequestPermissions, modifier = Modifier.fillMaxWidth())
    } else {
        Text(
            "All set. You can change these any time in the phone settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InputStep(
    devices: List<AudioDeviceInfo>,
    selectedDeviceId: Int?,
    onDeviceSelected: (Int?) -> Unit,
    mode: InputChannelMode,
    onModeSelected: (InputChannelMode) -> Unit,
    sourceProfile: InputSourceProfile,
    onSourceProfileSelected: (InputSourceProfile) -> Unit,
    isTesting: Boolean,
    onToggleTest: () -> Unit,
    levels: InputLevels,
    routeInfo: String
) {
    WizardTitle("Pick your input", "If you plugged in an interface, choose it here so the app records from it.")
    Spacer(Modifier.height(12.dp))
    SectionLabel("INPUT")
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selectedDeviceId == null, onClick = { onDeviceSelected(null) })
        Text("Phone microphone", modifier = Modifier.weight(1f))
    }
    devices.forEach { device ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selectedDeviceId == device.id, onClick = { onDeviceSelected(device.id) })
            Column(Modifier.weight(1f)) {
                Text(device.productName?.toString() ?: "Audio input")
                Text(
                    "${device.channelCounts.joinToString(" / ") { "$it ch" }} · ${device.sampleRates.joinToString(" / ") { "${it / 1000} kHz" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    SectionLabel("CHANNELS")
    InputChannelMode.values().forEach { option ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(mode == option, onClick = { onModeSelected(option) })
            Text(option.label)
        }
    }
    if (selectedDeviceId != null && devices.any { it.id == selectedDeviceId }) {
        Spacer(Modifier.height(10.dp))
        SectionLabel("HOW IT IS RECORDED")
        InputSourceProfile.values().forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(sourceProfile == option, onClick = { onSourceProfileSelected(option) })
                Text(option.label)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppButton(if (isTesting) "Stop test" else "Test this input", onToggleTest)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (levels.left > 0.01f || levels.right > 0.01f) {
                Text("Signal is coming in", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            } else if (isTesting) {
                Text("Play something and watch the meter", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (routeInfo.isNotBlank()) {
                Text(routeInfo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SoundStep(monitorInput: Boolean, onMonitorInputChanged: (Boolean) -> Unit, outputRoute: String) {
    WizardTitle("How do you hear yourself?", "Monitoring sends your input straight to your headphones while you play.")
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Monitor in headphones")
            Text(
                "Turn this on if you are wearing headphones. Off is safer with speakers.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(monitorInput, onMonitorInputChanged)
    }
    Spacer(Modifier.height(16.dp))
    WizardPoint(Icons.Filled.Headphones, "Your sound leaves through", outputRoute.ifBlank { "the phone" })
    Spacer(Modifier.height(10.dp))
    Text(
        "If you hear nothing, check that your interface is set to send the USB return to its headphones.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun PlayAlongStep(backingName: String?, onLoadBacking: () -> Unit) {
    WizardTitle("Play along?", "Record your part over a backing track. You can skip this and add one later.")
    Spacer(Modifier.height(16.dp))
    WizardPoint(
        Icons.AutoMirrored.Filled.QueueMusic,
        if (backingName.isNullOrBlank()) "No backing track yet" else backingName,
        "The track plays in your headphones and starts with your recording."
    )
    Spacer(Modifier.height(16.dp))
    AppButton("Choose a backing track", onLoadBacking, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ReadyStep(
    hasPermissions: Boolean,
    devices: List<AudioDeviceInfo>,
    selectedDeviceId: Int?,
    monitorInput: Boolean,
    backingName: String?
) {
    WizardTitle("You are ready", "Everything is set. The record button is on the next screen.")
    Spacer(Modifier.height(16.dp))
    ReadyLine("Camera and microphone", if (hasPermissions) "Allowed" else "Not allowed")
    ReadyLine(
        "Input",
        devices.firstOrNull { it.id == selectedDeviceId }?.productName?.toString() ?: "Phone microphone"
    )
    ReadyLine("Headphone monitoring", if (monitorInput) "On" else "Off")
    ReadyLine("Backing track", backingName ?: "None")
    Spacer(Modifier.height(16.dp))
    Text(
        "Want to change something later? Open Recording setup from the gear icon on the record screen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ReadyLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    }
}

@Composable
private fun WizardTitle(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(6.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WizardPoint(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp).padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
