package com.onegoodtake.app.ui.screens

import android.content.Intent
import android.media.AudioDeviceInfo
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.onegoodtake.app.audio.InputChannelMode
import com.onegoodtake.app.audio.InputLevels
import com.onegoodtake.app.audio.InputSourceProfile
import com.onegoodtake.app.audio.UsbAudioInterface
import com.onegoodtake.app.audio.isUsbAudioInput
import com.onegoodtake.app.audio.normalisedDeviceName
import com.onegoodtake.app.camera.VideoQuality
import com.onegoodtake.app.ui.components.AppButton
import com.onegoodtake.app.BuildConfig
import com.onegoodtake.app.ui.components.SectionLabel

private const val DONATION_URL = "https://giannichiarello.com/product/donation/"

@Composable
fun InputSetupDialog(
    devices: List<AudioDeviceInfo>,
    selectedDeviceId: Int?,
    onDeviceSelected: (Int?) -> Unit,
    usbInterfaces: List<UsbAudioInterface> = emptyList(),
    selectedUsbKey: String? = null,
    onUsbInterfaceSelected: (String?) -> Unit = {},
    mode: InputChannelMode,
    onModeSelected: (InputChannelMode) -> Unit,
    sourceProfile: InputSourceProfile,
    onSourceProfileSelected: (InputSourceProfile) -> Unit,
    isTesting: Boolean,
    onToggleTest: () -> Unit,
    monitorInput: Boolean,
    monitorPan: Float = 0f,
    onMonitorPanChanged: (Float) -> Unit = {},
    onMonitorInputChanged: (Boolean) -> Unit,
    videoQuality: VideoQuality = VideoQuality.FHD_1080,
    supportedVideoQualities: List<VideoQuality> = VideoQuality.values().toList(),
    onVideoQualitySelected: (VideoQuality) -> Unit = {},
    levels: InputLevels,
    routedDevice: String,
    diagnostics: String,
    statusMessage: String,
    keepScreenOn: Boolean,
    onKeepScreenOnChanged: (Boolean) -> Unit,
    onRunSetupWizard: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recording setup", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                SectionLabel("INPUT DEVICE")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selectedDeviceId == null, onClick = { onDeviceSelected(null) })
                    Text("System default", modifier = Modifier.weight(1f))
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
                            if (device.channelIndexMasks.isNotEmpty()) {
                                Text(
                                    "Channel indexes: ${device.channelIndexMasks.joinToString { "0x${it.toString(16)}" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (usbInterfaces.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    SectionLabel("USB INTERFACES · DIRECT CAPTURE")
                    Text(
                        "Capture straight from the interface, for one the phone does not list as an input.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    usbInterfaces.forEach { usb ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selectedUsbKey == usb.key, onClick = { onUsbInterfaceSelected(usb.key) })
                            Column(Modifier.weight(1f)) {
                                Text(usb.name)
                                Text(
                                    "%04X:%04X".format(usb.vendorId, usb.productId) +
                                        if (androidListsInput(usb, devices)) " · Android also lists it" else " · Android lists no input",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!usb.permissionGranted && selectedUsbKey == usb.key) {
                                    Text(
                                        "USB access is requested when you test the input",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (selectedUsbKey != null) {
                        TextButton(onClick = { onUsbInterfaceSelected(null) }) { Text("Stop using direct USB") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SectionLabel("CHANNELS")
                InputChannelMode.values().forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(mode == option, onClick = { onModeSelected(option) })
                        Text(option.label)
                    }
                }
                Spacer(Modifier.height(12.dp))
                SectionLabel("CAPTURE PROFILE")
                InputSourceProfile.values().filter { option ->
                    option != InputSourceProfile.USB_DIRECT || selectedUsbKey != null ||
                        devices.any { it.id == selectedDeviceId && isUsbAudioInput(it) }
                }.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(sourceProfile == option, onClick = { onSourceProfileSelected(option) })
                        Column {
                            Text(option.label)
                            if (option == InputSourceProfile.COMPATIBLE) {
                                Text(
                                    "Fallback path for interfaces silent in Raw mode",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (option == InputSourceProfile.USB_DIRECT) {
                                Text(
                                    "UAC2 direct capture; requests exclusive USB access",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SectionLabel("VIDEO QUALITY")
                supportedVideoQualities.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(videoQuality == option, onClick = { onVideoQualitySelected(option) })
                        Column {
                            Text(option.label)
                            if (option == VideoQuality.UHD_4K) {
                                Text(
                                    "4K — large files, supported devices only",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SectionLabel("LIVE INPUT LEVELS")
                InputMeter("L / Input 1", levels.left)
                InputMeter("R / Input 2", levels.right)
                if (levels.channelPeaks.size > 2) {
                    Text(
                        "USB CHANNEL SCAN",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    levels.channelPeaks.forEachIndexed { index, peak -> InputMeter("USB ch ${index + 1}", peak) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Monitor input to headphones")
                        Text(
                            "Use headphones to avoid feedback",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(monitorInput, onMonitorInputChanged)
                }
                if (monitorInput) {
                    Text("Monitor pan / balance", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = monitorPan,
                        onValueChange = onMonitorPanChanged,
                        valueRange = -1f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        when {
                            monitorPan < -0.05f -> "Pan left"
                            monitorPan > 0.05f -> "Pan right"
                            else -> "Centre"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    if (routedDevice.isBlank()) "No route detected" else "Android route: $routedDevice",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (diagnostics.isNotBlank()) {
                    Text(
                        diagnostics,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (diagnostics.contains("digital silence")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (statusMessage.isNotBlank()) {
                    Text(statusMessage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep screen awake", modifier = Modifier.weight(1f))
                    Switch(keepScreenOn, onKeepScreenOnChanged)
                }
                TextButton(onClick = onRunSetupWizard) { Text("Run the setup wizard again") }
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DONATION_URL))) }) {
                    Text("Support the developer", style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            AppButton(
                if (isTesting) "Stop test" else "Test input",
                onToggleTest,
                containerColor = if (isTesting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                contentColor = if (isTesting) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/** True when the phone's own audio stack also exposes this interface as an input. */
private fun androidListsInput(usb: UsbAudioInterface, devices: List<AudioDeviceInfo>): Boolean {
    val wanted = normalisedDeviceName(usb.name)
    return devices.any { isUsbAudioInput(it) && normalisedDeviceName(it.productName?.toString()) == wanted }
}

@Composable
private fun InputMeter(label: String, level: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(92.dp))
        LinearProgressIndicator(progress = { level.coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(8.dp))
        Text("${(level * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(42.dp))
    }
}
