package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.TelefonimApplication
import com.example.ui.overlay.OverlayOrbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AssistantForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.example.action.START_ASSISTANT"
        const val ACTION_STOP = "com.example.action.STOP_ASSISTANT"
        const val ACTION_LISTEN_NOW = "com.example.action.LISTEN_NOW"
        const val ACTION_DISMISS_OVERLAY = "com.example.action.DISMISS_OVERLAY"
        const val ACTION_EXECUTE_TEXT = "com.example.action.EXECUTE_TEXT"
        const val EXTRA_TEXT_COMMAND = "extra_text_command"
        private const val NOTIFICATION_ID = 4101

        fun startAssistant(context: Context) {
            val intent = Intent(context, AssistantForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopAssistant(context: Context) {
            val intent = Intent(context, AssistantForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun triggerActiveListen(context: Context) {
            val intent = Intent(context, AssistantForegroundService::class.java).apply {
                action = ACTION_LISTEN_NOW
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun dismissOverlay(context: Context) {
            val intent = Intent(context, AssistantForegroundService::class.java).apply {
                action = ACTION_DISMISS_OVERLAY
            }
            context.startService(intent)
        }

        fun executeTextCommand(context: Context, command: String) {
            val intent = Intent(context, AssistantForegroundService::class.java).apply {
                action = ACTION_EXECUTE_TEXT
                putExtra(EXTRA_TEXT_COMMAND, command)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var overlayManager: OverlayOrbManager
    private lateinit var voiceEngine: UzbekVoiceEngine

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningActiveCommand = false
    private var isExecutingCommand = false
    private var currentWakeWord = "Telefonim"
    private var continuousWakeEnabled = true
    private var autoDismissOverlay = true
    private var voiceFeedbackEnabled = true
    private var hasAnnouncedStart = false

    override fun onCreate() {
        super.onCreate()
        overlayManager = OverlayOrbManager(this)
        voiceEngine = UzbekVoiceEngine(this)

        val app = application as TelefonimApplication
        serviceScope.launch {
            app.settingsDataStore.settingsFlow.collectLatest { settings ->
                currentWakeWord = settings.wakeWord
                continuousWakeEnabled = settings.continuousWakeListening
                autoDismissOverlay = settings.autoDismissOverlay
                voiceFeedbackEnabled = settings.voiceFeedbackEnabled
                AssistantRuntimeState.update { it.copy(wakeWord = settings.wakeWord) }
                updateNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()

        when (intent?.action) {
            ACTION_STOP -> {
                shutdownService()
                return START_NOT_STICKY
            }

            ACTION_DISMISS_OVERLAY -> {
                overlayManager.hideOverlay()
                isListeningActiveCommand = false
                if (!isExecutingCommand && continuousWakeEnabled) {
                    resumePassiveWakeListening()
                }
            }

            ACTION_LISTEN_NOW -> {
                AssistantRuntimeState.update { it.copy(isServiceRunning = true) }
                beginActiveCommandCapture()
            }

            ACTION_EXECUTE_TEXT -> {
                val cmd = intent.getStringExtra(EXTRA_TEXT_COMMAND).orEmpty()
                if (cmd.isNotBlank()) {
                    AssistantRuntimeState.update { it.copy(isServiceRunning = true) }
                    serviceScope.launch {
                        processUserCommand(cmd, showOverlay = false)
                    }
                }
            }

            else -> {
                // ACTION_START or OS restart
                AssistantRuntimeState.update { it.copy(isServiceRunning = true) }
                if (!hasAnnouncedStart) {
                    hasAnnouncedStart = true
                    serviceScope.launch {
                        if (voiceFeedbackEnabled) {
                            AssistantRuntimeState.update {
                                it.copy(
                                    aiResponse = "Ishga tushdim! Men tayyorman.",
                                    orbPhase = OrbPhase.SPEAKING
                                )
                            }
                            voiceEngine.speakAndAwait("Ishga tushdim! Men tayyorman.")
                        }
                        if (continuousWakeEnabled) {
                            startPassiveWakeListening()
                        } else {
                            AssistantRuntimeState.setPhase(OrbPhase.IDLE)
                        }
                    }
                } else if (continuousWakeEnabled && !isExecutingCommand && !isListeningActiveCommand) {
                    startPassiveWakeListening()
                }
            }
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (!AssistantRuntimeState.state.value.isServiceRunning) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AssistantForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val listenIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, AssistantForegroundService::class.java).apply { action = ACTION_LISTEN_NOW },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, TelefonimApplication.CHANNEL_ASSISTANT_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Telefonim AI • 24/7 Faol")
            .setContentText("Chaqiruv so'zi: \"$currentWakeWord\" — tinglamoqdaman")
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Gapirish", listenIntent)
            .addAction(0, "To'xtatish", stopIntent)
            .build()
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startPassiveWakeListening() {
        if (!continuousWakeEnabled || isExecutingCommand || !hasMicPermission()) {
            if (!hasMicPermission()) {
                AssistantRuntimeState.setPhase(OrbPhase.IDLE)
            }
            return
        }
        isListeningActiveCommand = false
        AssistantRuntimeState.setPhase(OrbPhase.PASSIVE_WAKE_LISTENING)
        startRecognizerInternal(isActiveCommand = false)
    }

    private fun resumePassiveWakeListening() {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (AssistantRuntimeState.state.value.isServiceRunning && continuousWakeEnabled && !isExecutingCommand) {
                startPassiveWakeListening()
            } else {
                AssistantRuntimeState.setPhase(OrbPhase.IDLE)
            }
        }, 400L)
    }

    private fun beginActiveCommandCapture() {
        if (!hasMicPermission()) {
            AssistantRuntimeState.update {
                it.copy(
                    orbPhase = OrbPhase.ERROR,
                    aiResponse = "Mikrofon ruxsati berilmagan."
                )
            }
            return
        }

        mainHandler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        voiceEngine.stopSpeaking()

        isListeningActiveCommand = true
        AssistantRuntimeState.update {
            it.copy(
                orbPhase = OrbPhase.ACTIVE_LISTENING,
                liveTranscript = "",
                aiResponse = ""
            )
        }
        overlayManager.showOverlay {
            dismissOverlay(this)
        }
        playChime()
        startRecognizerInternal(isActiveCommand = true)
    }

    private fun startRecognizerInternal(isActiveCommand: Boolean) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            AssistantRuntimeState.update {
                it.copy(
                    orbPhase = if (isActiveCommand) OrbPhase.ERROR else OrbPhase.IDLE,
                    aiResponse = if (isActiveCommand) {
                        "Qurilmada ovoz aniqlash xizmati topilmadi. Matn orqali buyruq bering."
                    } else ""
                )
            }
            return
        }

        mainHandler.post {
            destroyRecognizer()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    if (isListeningActiveCommand) {
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1f)
                        AssistantRuntimeState.setAudioLevel(normalized)
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    AssistantRuntimeState.setAudioLevel(0f)
                }

                override fun onError(error: Int) {
                    AssistantRuntimeState.setAudioLevel(0f)
                    if (!AssistantRuntimeState.state.value.isServiceRunning) return

                    if (isActiveCommand) {
                        isListeningActiveCommand = false
                        serviceScope.launch {
                            val msg = "Ovoz eshitilmadi."
                            AssistantRuntimeState.update {
                                it.copy(orbPhase = OrbPhase.ERROR, aiResponse = msg)
                            }
                            delay(1400L)
                            if (autoDismissOverlay) overlayManager.hideOverlay()
                            resumePassiveWakeListening()
                        }
                    } else if (continuousWakeEnabled && !isExecutingCommand) {
                        val backoff = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200L else 550L
                        mainHandler.postDelayed({
                            if (AssistantRuntimeState.state.value.isServiceRunning && !isListeningActiveCommand && !isExecutingCommand) {
                                startPassiveWakeListening()
                            }
                        }, backoff)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val bestText = matches.firstOrNull().orEmpty()

                    if (isActiveCommand) {
                        isListeningActiveCommand = false
                        if (bestText.isNotBlank()) {
                            serviceScope.launch {
                                processUserCommand(bestText, showOverlay = true)
                            }
                        } else {
                            if (autoDismissOverlay) overlayManager.hideOverlay()
                            resumePassiveWakeListening()
                        }
                    } else {
                        val handled = handlePassiveSpeechCandidates(matches, isPartial = false)
                        if (!handled && continuousWakeEnabled && !isExecutingCommand) {
                            resumePassiveWakeListening()
                        }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val partial = matches.firstOrNull().orEmpty()
                    if (isActiveCommand) {
                        if (partial.isNotBlank()) {
                            AssistantRuntimeState.update { it.copy(liveTranscript = partial) }
                        }
                    } else {
                        handlePassiveSpeechCandidates(matches, isPartial = true)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uz-UZ")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "uz-UZ")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            try {
                recognizer.startListening(intent)
            } catch (_: Exception) {
                if (!isActiveCommand) {
                    mainHandler.postDelayed({ startPassiveWakeListening() }, 1500L)
                }
            }
        }
    }

    private fun handlePassiveSpeechCandidates(candidates: List<String>, isPartial: Boolean): Boolean {
        for (phrase in candidates) {
            val afterWake = extractAfterWakeWord(phrase, currentWakeWord)
            if (afterWake != null) {
                // Wake word heard!
                if (afterWake.isNotBlank() && !isPartial) {
                    // User said "Telefonim <command>" in a single breath
                    destroyRecognizer()
                    serviceScope.launch {
                        processUserCommand(afterWake, showOverlay = true)
                    }
                    return true
                } else if (afterWake.isBlank() || !isPartial) {
                    // User said just "Telefonim" -> open animated orb and listen for command
                    beginActiveCommandCapture()
                    return true
                }
            }
        }
        return false
    }

    private fun extractAfterWakeWord(transcript: String, wakeWord: String): String? {
        val lowerText = transcript.lowercase().trim()
        val lowerWake = wakeWord.lowercase().trim()
        if (lowerText.isBlank() || lowerWake.isBlank()) return null

        val variants = buildSet {
            add(lowerWake)
            if (lowerWake == "telefonim") {
                addAll(listOf("telefonim", "tilifonim", "telefon", "telifonim"))
            }
        }

        for (v in variants) {
            val idx = lowerText.indexOf(v)
            if (idx >= 0) {
                return transcript.substring(idx + v.length).trim(' ', ',', '.', '!', '?')
            }
        }
        return null
    }

    private suspend fun processUserCommand(command: String, showOverlay: Boolean) {
        if (isExecutingCommand) return
        isExecutingCommand = true
        destroyRecognizer()

        if (showOverlay) {
            overlayManager.showOverlay { dismissOverlay(this) }
        }

        val app = application as TelefonimApplication
        val result = app.agentOrchestrator.handleCommand(command)

        if (voiceFeedbackEnabled) {
            voiceEngine.speakAndAwait(result.replyUzbek)
        } else {
            AssistantRuntimeState.setPhase(OrbPhase.SPEAKING)
            delay(1800L)
        }

        if (showOverlay && autoDismissOverlay) {
            delay(650L)
            overlayManager.hideOverlay()
        }

        isExecutingCommand = false
        if (AssistantRuntimeState.state.value.isServiceRunning && continuousWakeEnabled) {
            resumePassiveWakeListening()
        } else {
            AssistantRuntimeState.setPhase(OrbPhase.IDLE)
        }
    }

    private fun playChime() {
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)
                .startTone(ToneGenerator.TONE_PROP_BEEP, 130)
        } catch (_: Exception) {
        }
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }

    private fun shutdownService() {
        mainHandler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        voiceEngine.shutdown()
        overlayManager.hideOverlay()
        AssistantRuntimeState.update {
            it.copy(
                isServiceRunning = false,
                orbPhase = OrbPhase.IDLE,
                audioLevel = 0f,
                isOverlayVisible = false
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        voiceEngine.shutdown()
        overlayManager.hideOverlay()
        serviceScope.cancel()
        AssistantRuntimeState.update {
            it.copy(isServiceRunning = false, orbPhase = OrbPhase.IDLE)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
