package com.example.ui.overlay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.service.AssistantForegroundService
import com.example.service.AssistantRuntimeState
import com.example.ui.theme.TelefonimTheme

class VoiceOverlayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TelefonimTheme {
                val state by AssistantRuntimeState.state.collectAsStateWithLifecycle()

                LaunchedEffect(state.isOverlayVisible) {
                    if (!state.isOverlayVisible) {
                        finish()
                    }
                }

                Box(
                    contentAlignment = Alignment.BottomCenter,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            AssistantForegroundService.dismissOverlay(this@VoiceOverlayActivity)
                            finish()
                        }
                ) {
                    FloatingOrbOverlayContent(
                        onDismiss = {
                            AssistantForegroundService.dismissOverlay(this@VoiceOverlayActivity)
                            finish()
                        }
                    )
                }
            }
        }
    }
}
