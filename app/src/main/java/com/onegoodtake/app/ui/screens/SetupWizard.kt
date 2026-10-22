package com.onegoodtake.app.ui.screens

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
import com.onegoodtake.app.audio.InputChannelMode
import com.onegoodtake.app.audio.InputLevels
import com.onegoodtake.app.audio.InputSourceProfile
import com.onegoodtake.app.camera.CameraOption
import com.onegoodtake.app.ui.Waveform
import com.onegoodtake.app.ui.components.AppButton
import com.onegoodtake.app.ui.components.SectionLabel

/**
 * First-run setup, one question per screen. It edits the same settings the Recording setup
 * dialog edits, so nothing it shows can disagree with the rest of the app.
 */
object WizardStep {
    const val WELCOME = 0
    const val PERMISSIONS = 1
    const val CAMERA = 2
    const val INPUT = 3
    const val SOUND = 4
    const val PLAYALONG = 5
    const val READY = 6
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
    soundHeard: Boolean?,
    onSoundHeard: (Boolean) -> Unit,
    isTesting: Boolean,
    onToggleTest: () -> Unit,
    levels: InputLevels,
    livePeaks: List<Float>,
    routeInfo: String,
    outputRoute: String,
    cameras: List<CameraOption>,
    cameraIndex: Int,
    onCameraSelected: (Int) -> Unit,
    backingName: String?,
    onLoadBacking: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    Dialog(onDismissRequest = onSkip, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            shape = com.onegoodtake.app.ui.theme.AppCardShape,
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.padding(20.dp).fillMaxSize()) {
                WizardProgress(step)
                Spacer(Modifier.height(18.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    when (step) {
                        WizardStep.WELCOME -> WelcomeStep()
                        WizardStep.PERMISSIONS -> PermissionsStep(hasPermissions, onRequestPermissions)
                        WizardStep.CAMERA -> CameraStep(cameras, cameraIndex, onCameraSelected)
                        WizardStep.INPUT -> InputStep(
                            devices, selectedDeviceId, onDeviceSelected, mode, onModeSelected,
                            sourceProfile, onSourceProfileSelected, isTesting, onToggleTest, levels, livePeaks, routeInfo
                        )
                        WizardStep.SOUND -> SoundStep(monitorInput, onMonitorInputChanged, outputRoute, soundHeard, onSoundHeard)
                        WizardStep.PLAYALONG -> PlayAlongStep(backingName, onLoadBacking)
                        WizardStep.READY -> ReadyStep(hasPermissions, devices, selectedDeviceId, monitorInput, cameras, cameraIndex, backingName)
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
                        // On the sound step the user has to confirm the sound arrived first.
                        AppButton("Next", onNext, enabled = step != WizardStep.SOUND || soundHeard == true)
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
    WizardTitle("Two permissions", "One Good Take needs the camera to film you and the microphone to record you.")
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
private fun CameraStep(cameras: List<CameraOption>, cameraIndex: Int, onCameraSelected: (Int) -> Unit) {
    WizardTitle("Which camera?", "Pick the lens you film with. You can change it any time with the camera button.")
    Spacer(Modifier.height(14.dp))
    if (cameras.isEmpty()) {
        Text(
            "No camera was found on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    cameras.forEachIndexed { index, camera ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(cameraIndex == index, onClick = { onCameraSelected(index) })
            Column(Modifier.weight(1f)) {
                Text(camera.label)
                Text(
                    if (camera.isFront) "Selfie camera" else "Main camera",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
    livePeaks: List<Float>,
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
            if (isTesting) {
                Text("Play something and watch the meter", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (routeInfo.isNotBlank()) {
                Text(routeInfo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    // The same live strip the record screen shows, so the test looks like the real thing.
    Box(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (livePeaks.isEmpty()) {
            Text(
                if (isTesting) "Listening…" else "Press the test button to see the signal",
                Modifier.padding(start = 10.dp).align(Alignment.CenterStart),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Waveform(livePeaks, MaterialTheme.colorScheme.primary, Modifier.fillMaxSize())
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("L", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(18.dp))
        LinearProgressIndicator(
            progress = { levels.left.coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(6.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text("R", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(18.dp))
        LinearProgressIndicator(
            progress = { levels.right.coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(6.dp)
        )
    }
}

@Composable
private fun SoundStep(
    monitorInput: Boolean,
    onMonitorInputChanged: (Boolean) -> Unit,
    outputRoute: String,
    soundHeard: Boolean?,
    onSoundHeard: (Boolean) -> Unit
) {
    WizardTitle("Can you hear yourself?", "Monitoring sends your input straight to your headphones while you play.")
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
    Spacer(Modifier.height(14.dp))
    WizardPoint(Icons.Filled.Headphones, "Your sound leaves through", outputRoute.ifBlank { "the phone" })
    Spacer(Modifier.height(14.dp))
    SectionLabel("CAN YOU HEAR IT?")
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(soundHeard == true, onClick = { onSoundHeard(true) })
        Text("Yes, I can hear it")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(soundHeard == false, onClick = { onSoundHeard(false) })
        Text("No, nothing comes out")
    }
    if (soundHeard == false) {
        Spacer(Modifier.height(12.dp))
        TroubleshootingCard()
    }
}

/** The app cannot hear the room, so the user has to say whether the sound arrived. */
@Composable
private fun TroubleshootingCard() {
    Column(
        Modifier.fillMaxWidth().clip(com.onegoodtake.app.ui.theme.AppCardShape)
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp)
    ) {
        Text("Try this", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text("1.  Are your headphones plugged into the interface and not into the phone?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        Text("2.  On a Scarlett, the headphone source has to be USB 1 or USB 2, not Monitor. That is the most common reason for silence.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp))
        Text("3.  Turn the interface's own headphone volume up, and the phone's media volume up.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp))
        Text("4.  Unplug the interface and test again. If the phone plays, the interface is the problem, not the app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp))
        Text("5.  Still stuck? The free One Good Take Check app shows exactly where the sound goes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            "You can skip this and fix it later from Recording setup.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
    cameras: List<CameraOption>,
    cameraIndex: Int,
    backingName: String?
) {
    WizardTitle("You are ready", "Everything is set. The record button is on the next screen.")
    Spacer(Modifier.height(16.dp))
    ReadyLine("Camera and microphone", if (hasPermissions) "Allowed" else "Not allowed")
    ReadyLine("Camera", cameras.getOrNull(cameraIndex)?.label ?: "Front")
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
