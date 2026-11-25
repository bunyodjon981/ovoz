package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.AssistantSettings
import com.example.ui.SetupUiState
import com.example.ui.theme.CoralError
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceBorder
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AssistantSettings,
    setupState: SetupUiState,
    onSaveWakeWord: (String) -> Unit,
    onUpdateApiKey: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onSelectModel: (String) -> Unit,
    onToggleAutoFreeModel: (Boolean) -> Unit,
    onToggleVoiceFeedback: (Boolean) -> Unit,
    onToggleAutoDismiss: (Boolean) -> Unit,
    onToggleContinuousWake: (Boolean) -> Unit,
    onTestVoice: () -> Unit
) {
    var wakeWordDraft by rememberSaveable(settings.wakeWord) { mutableStateOf(settings.wakeWord) }
    var wakeSavedToast by rememberSaveable { mutableStateOf(false) }
    var editingKey by rememberSaveable { mutableStateOf(false) }
    var newApiKeyDraft by rememberSaveable { mutableStateOf("") }

    val maskedKey = if (settings.groqApiKey.length > 10) {
        "${settings.groqApiKey.take(6)}••••••${settings.groqApiKey.takeLast(4)}"
    } else "Saqlangan"

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text(
                text = "Sozlamalar",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary
            )

            // 1. Custom Wake Word Section
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(ObsidianSurface)
                    .border(1.dp, ObsidianSurfaceBorder, RoundedCornerShape(22.dp))
                    .padding(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.RecordVoiceOver,
                        contentDescription = null,
                        tint = ElectricCyan
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Chaqiruv nomi (Wake Word)",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = "Ilovadan tashqarida ham shu so'zni aytsangiz, animatsiyali oyna chiqib sizni tinglaydi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = wakeWordDraft,
                        onValueChange = {
                            wakeWordDraft = it
                            wakeSavedToast = false
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_wake_word_input"),
                        singleLine = true,
                        label = { Text("Chaqiruv so'zi") },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = ObsidianSurfaceBorder,
                            focusedContainerColor = ObsidianSurfaceElevated,
                            unfocusedContainerColor = ObsidianSurfaceElevated
                        )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = {
                            onSaveWakeWord(wakeWordDraft)
                            wakeSavedToast = true
                        },
                        modifier = Modifier
                            .height(54.dp)
                            .testTag("save_wake_word_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color(0xFF002026)
                        )
                    ) {
                        Icon(imageVector = Icons.Rounded.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Saqlash", fontWeight = FontWeight.Bold)
                    }
                }

                if (wakeSavedToast) {
                    Text(
                        text = "Chaqiruv nomi saqlandi: \"${settings.wakeWord}\"",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldActive
                    )
                }
            }

            // 2. Groq API & Free Models Auto-Selection
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(ObsidianSurface)
                    .border(1.dp, ObsidianSurfaceBorder, RoundedCornerShape(22.dp))
                    .padding(18.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Key,
                            contentDescription = null,
                            tint = ElectricCyan
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Groq API va Bepul Modellar",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ObsidianSurfaceElevated)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column {
                        Text(
                            text = "Saqlangan kalit",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = maskedKey,
                            style = MaterialTheme.typography.bodyMedium,
                            color = EmeraldActive,
                            fontFamily = JetBrainsMonoFontFamily
                        )
                    }
                    OutlinedButton(
                        onClick = { editingKey = !editingKey },
                        modifier = Modifier.testTag("toggle_edit_api_key")
                    ) {
                        Text(if (editingKey) "Bekor" else "Almashtirish")
                    }
                }

                if (editingKey) {
                    OutlinedTextField(
                        value = newApiKeyDraft,
                        onValueChange = { newApiKeyDraft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("new_api_key_input"),
                        label = { Text("Yangi Groq API kaliti (gsk_...)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Button(
                        onClick = {
                            onUpdateApiKey(newApiKeyDraft)
                            editingKey = false
                            newApiKeyDraft = ""
                        },
                        enabled = !setupState.isChecking && newApiKeyDraft.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("confirm_new_api_key_button")
                    ) {
                        Text("Yangi kalitni saqlash")
                    }
                }

                SettingSwitchRow(
                    title = "Avtomatik bepul model",
                    subtitle = "Limit to'lsa boshqa bepul Groq modeliga avtomatik o'tadi",
                    checked = settings.autoFreeModel,
                    onCheckedChange = onToggleAutoFreeModel
                )

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Faol model: ${settings.activeModel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan,
                        fontFamily = JetBrainsMonoFontFamily,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onRefreshModels,
                        enabled = !setupState.isChecking,
                        modifier = Modifier.testTag("refresh_models_button")
                    ) {
                        if (setupState.isChecking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Yangilash")
                    }
                }

                if (setupState.statusMessage != null) {
                    Text(
                        text = setupState.statusMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldActive
                    )
                }
                if (setupState.errorMessage != null) {
                    Text(
                        text = setupState.errorMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = CoralError
                    )
                }

                if (settings.discoveredModels.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        settings.discoveredModels.forEach { modelId ->
                            val selected = modelId == settings.activeModel
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (selected) ElectricCyan.copy(alpha = 0.2f)
                                        else ObsidianSurfaceElevated
                                    )
                                    .border(
                                        1.dp,
                                        if (selected) ElectricCyan else ObsidianSurfaceBorder,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { onSelectModel(modelId) }
                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            tint = ElectricCyan,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = modelId.substringAfterLast("/"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) ElectricCyan else TextSecondary,
                                        fontFamily = JetBrainsMonoFontFamily
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Voice & Background Behavior
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(ObsidianSurface)
                    .border(1.dp, ObsidianSurfaceBorder, RoundedCornerShape(22.dp))
                    .padding(18.dp)
            ) {
                SettingSwitchRow(
                    title = "Ovozli javob (TTS)",
                    subtitle = "AI javoblarini o'zbekcha ovozda o'qib eshittiradi",
                    checked = settings.voiceFeedbackEnabled,
                    onCheckedChange = onToggleVoiceFeedback
                )
                SettingSwitchRow(
                    title = "24/7 Orqa fonda tinglash",
                    subtitle = "Ilovadan chiqqanda ham chaqiruv so'zini kutib turadi",
                    checked = settings.continuousWakeListening,
                    onCheckedChange = onToggleContinuousWake
                )
                SettingSwitchRow(
                    title = "Avtomatik yopish",
                    subtitle = "Javob berilgandan keyin suzuvchi aylana o'zi yo'qoladi",
                    checked = settings.autoDismissOverlay,
                    onCheckedChange = onToggleAutoDismiss
                )

                OutlinedButton(
                    onClick = onTestVoice,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("test_tts_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VolumeUp,
                        contentDescription = null,
                        tint = ElectricCyan
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ovozni sinab ko'rish")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF002026),
                checkedTrackColor = ElectricCyan
            )
        )
    }
}
