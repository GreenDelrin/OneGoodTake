package com.socialmaster.app.ui.screens

import android.media.AudioDeviceInfo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.socialmaster.app.audio.InputChannelMode
import com.socialmaster.app.audio.InputLevels
import com.socialmaster.app.audio.InputSourceProfile

@Composable
fun InputSetupDialog(
    devices: List<AudioDeviceInfo>,
    selectedDeviceId: Int?,
    onDeviceSelected: (Int?) -> Unit,
    mode: InputChannelMode,
    onModeSelected: (InputChannelMode) -> Unit,
    sourceProfile: InputSourceProfile,
    onSourceProfileSelected: (InputSourceProfile) -> Unit,
    isTesting: Boolean,
    onToggleTest: () -> Unit,
    monitorInput: Boolean,
    onMonitorInputChanged: (Boolean) -> Unit,
    levels: InputLevels,
    routedDevice: String,
    outputRoute: String,
    onTestOutput: () -> Unit,
    diagnostics: String,
    statusMessage: String,
    keepScreenOn: Boolean,
    onKeepScreenOnChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recording setup") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Text("INPUT DEVICE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selectedDeviceId == null, onClick = { onDeviceSelected(null) })
                    Text("System default", modifier = Modifier.weight(1f))
                }
                devices.forEach { device ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selectedDeviceId == device.id, onClick = { onDeviceSelected(device.id) })
                        Column(Modifier.weight(1f)) {
                            Text(device.productName?.toString() ?: "Audio input")
                            Text("${device.channelCounts.joinToString(" / ") { "$it ch" }} · ${device.sampleRates.joinToString(" / ") { "${it / 1000} kHz" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (device.channelIndexMasks.isNotEmpty()) Text("Channel indexes: ${device.channelIndexMasks.joinToString { "0x${it.toString(16)}" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("CHANNELS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                InputChannelMode.values().forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(mode == option, onClick = { onModeSelected(option) })
                        Text(option.label)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("CAPTURE PROFILE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                InputSourceProfile.values().filter { option ->
                    option != InputSourceProfile.USB_DIRECT || devices.any {
                        it.id == selectedDeviceId && it.type in intArrayOf(
                            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY
                        )
                    }
                }.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(sourceProfile == option, onClick = { onSourceProfileSelected(option) })
                        Column {
                            Text(option.label)
                            if (option == InputSourceProfile.COMPATIBLE) Text("Fallback path for interfaces silent in Raw mode", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (option == InputSourceProfile.USB_DIRECT) Text("UAC2 direct capture; requests exclusive USB access", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("LIVE INPUT LEVELS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                InputMeter("L / Input 1", levels.left)
                InputMeter("R / Input 2", levels.right)
                if (levels.channelPeaks.size > 2) {
                    Text("USB CHANNEL SCAN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    levels.channelPeaks.forEachIndexed { index, peak -> InputMeter("USB ch ${index + 1}", peak) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Monitor input to headphones", style = MaterialTheme.typography.bodySmall)
                        Text("Use headphones to avoid feedback", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(monitorInput, onMonitorInputChanged)
                }
                Text(if (routedDevice.isBlank()) "No route detected" else "Android route: $routedDevice", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Test output", style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (outputRoute.isBlank()) "Plays a 1 kHz tone so you can check the headphones"
                            else outputRoute,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = onTestOutput) { Text("Play tone") }
                }
                if (diagnostics.isNotBlank()) Text(diagnostics, style = MaterialTheme.typography.labelSmall, color = if (diagnostics.contains("digital silence")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (statusMessage.isNotBlank()) Text(statusMessage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep screen awake", modifier = Modifier.weight(1f))
                    Switch(keepScreenOn, onKeepScreenOnChanged)
                }
            }
        },
        confirmButton = {
            Button(onClick = onToggleTest) { Text(if (isTesting) "Stop test" else "Test input") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun InputMeter(label: String, level: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.width(92.dp), style = MaterialTheme.typography.labelSmall)
        LinearProgressIndicator(progress = { level.coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(8.dp))
        Text("${(level * 100).toInt()}%", modifier = Modifier.width(42.dp), style = MaterialTheme.typography.labelSmall)
    }
}
