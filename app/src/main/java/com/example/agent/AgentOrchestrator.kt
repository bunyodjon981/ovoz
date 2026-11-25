package com.example.agent

import com.example.data.CommandHistoryDao
import com.example.data.CommandHistoryEntity
import com.example.data.SettingsDataStore
import com.example.data.groq.GroqRepository
import com.example.service.AssistantRuntimeState
import com.example.service.OrbPhase
import com.example.service.PhoneControlAccessibilityService
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

data class AgentExecutionResult(
    val userCommand: String,
    val replyUzbek: String,
    val actionsSummary: String,
    val modelUsed: String,
    val isSuccess: Boolean
)

class AgentOrchestrator(
    private val groqRepository: GroqRepository,
    private val settingsDataStore: SettingsDataStore,
    private val phoneCapabilities: PhoneCapabilities,
    private val historyDao: CommandHistoryDao
) {

    suspend fun handleCommand(rawCommand: String): AgentExecutionResult {
        val command = rawCommand.trim()
        if (command.isBlank()) {
            return AgentExecutionResult(
                userCommand = command,
                replyUzbek = "Buyruq eshitilmadi, iltimos qaytadan ayting.",
                actionsSummary = "",
                modelUsed = "",
                isSuccess = false
            )
        }

        val settings = settingsDataStore.getSnapshot()
        if (settings.groqApiKey.isBlank()) {
            val msg = "Groq API kaliti kiritilmagan. Iltimos ilova sozlamalariga API kalitini kiriting."
            return AgentExecutionResult(
                userCommand = command,
                replyUzbek = msg,
                actionsSummary = "API kalit yo'q",
                modelUsed = "",
                isSuccess = false
            )
        }

        AssistantRuntimeState.update {
            it.copy(
                orbPhase = OrbPhase.THINKING,
                liveTranscript = command,
                aiResponse = "O'ylayapman..."
            )
        }

        // Auto-discover free models if list is empty
        var activeModel = settings.activeModel
        var discoveredModels = settings.discoveredModels
        if (discoveredModels.isEmpty() && settings.autoFreeModel) {
            val discovery = groqRepository.fetchAvailableFreeModels(settings.groqApiKey)
            if (discovery.isSuccess) {
                activeModel = discovery.bestModel
                discoveredModels = discovery.models
                settingsDataStore.saveActiveAndDiscoveredModels(activeModel, discoveredModels)
            }
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", buildSystemPrompt(settings.wakeWord))
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", command)
            })
        }

        val tools = buildToolsSchema()
        val executedSteps = mutableListOf<String>()
        var finalModelUsed = activeModel
        var finalReply = ""
        var isSuccess = true

        // Multi-step Autonomous Agent Loop (max 6 turns)
        for (step in 1..6) {
            val response = groqRepository.chatWithTools(
                apiKey = settings.groqApiKey,
                preferredModel = finalModelUsed,
                candidateModels = discoveredModels,
                autoFallback = settings.autoFreeModel,
                messages = messages,
                tools = tools
            )

            if (response.errorMessage != null) {
                finalReply = response.errorMessage
                isSuccess = false
                break
            }

            finalModelUsed = response.modelUsed
            if (finalModelUsed != activeModel && settings.autoFreeModel) {
                settingsDataStore.saveActiveAndDiscoveredModels(finalModelUsed, discoveredModels)
            }

            if (response.toolCalls.isEmpty()) {
                finalReply = response.content?.trim().orEmpty()
                    .ifBlank { "Buyruq bajarildi." }
                break
            }

            // Append assistant tool-call message
            val assistantMsg = JSONObject().apply {
                put("role", "assistant")
                put("content", response.content ?: JSONObject.NULL)
                put("tool_calls", JSONArray().apply {
                    response.toolCalls.forEach { tc ->
                        put(JSONObject().apply {
                            put("id", tc.id)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", tc.name)
                                put("arguments", tc.argumentsJson)
                            })
                        })
                    }
                })
            }
            messages.put(assistantMsg)

            AssistantRuntimeState.update {
                it.copy(orbPhase = OrbPhase.EXECUTING)
            }

            for (tc in response.toolCalls) {
                val toolOutput = executeTool(tc.name, tc.argumentsJson)
                executedSteps.add("${tc.name}: $toolOutput")
                AssistantRuntimeState.update {
                    it.copy(lastActionSummary = toolOutput)
                }

                messages.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", tc.id)
                    put("name", tc.name)
                    put("content", toolOutput)
                })
            }

            // Small pause after UI actions so new window settles before next read
            if (response.toolCalls.any {
                    it.name in listOf("open_app", "click_element", "type_text", "scroll_screen", "system_navigation")
                }
            ) {
                delay(750L)
            }
        }

        if (finalReply.isBlank()) {
            finalReply = if (executedSteps.isNotEmpty()) {
                executedSteps.last()
            } else {
                "Buyruq bajarildi."
            }
        }

        val actionsText = executedSteps.joinToString(" | ")
        historyDao.insert(
            CommandHistoryEntity(
                userCommand = command,
                aiResponse = finalReply,
                executedActions = actionsText,
                modelUsed = finalModelUsed,
                isSuccess = isSuccess
            )
        )

        AssistantRuntimeState.update {
            it.copy(
                aiResponse = finalReply,
                lastActionSummary = actionsText
            )
        }

        return AgentExecutionResult(
            userCommand = command,
            replyUzbek = finalReply,
            actionsSummary = actionsText,
            modelUsed = finalModelUsed,
            isSuccess = isSuccess
        )
    }

    private suspend fun executeTool(name: String, argsJson: String): String {
        val args = try {
            JSONObject(argsJson)
        } catch (_: Exception) {
            JSONObject()
        }

        return when (name) {
            "get_screen_content" -> {
                val acc = PhoneControlAccessibilityService.instance
                acc?.captureScreenSummary()
                    ?: "Ekran nazorati (Accessibility Service) yoqilmagan. Foydalanuvchiga Sozlamalar -> Imkoniyatlar bo'limidan Ekran nazoratini yoqishni ayting."
            }

            "open_app" -> {
                val appName = args.optString("app_name")
                phoneCapabilities.openApp(appName)
            }

            "list_installed_apps" -> {
                val apps = phoneCapabilities.getInstalledLaunchableApps()
                if (apps.isEmpty()) "Ilovalar topilmadi."
                else apps.joinToString(", ") { it.label }
            }

            "click_element" -> {
                val target = args.optString("target")
                val acc = PhoneControlAccessibilityService.instance
                acc?.clickElement(target)
                    ?: "Ekranni bosish uchun Ekran nazorati (Accessibility) yoqilmagan."
            }

            "type_text" -> {
                val text = args.optString("text")
                val targetField = args.optString("target_field", "").ifBlank { null }
                val acc = PhoneControlAccessibilityService.instance
                acc?.typeText(text, targetField)
                    ?: "Matn yozish uchun Ekran nazorati (Accessibility) yoqilmagan."
            }

            "scroll_screen" -> {
                val dir = args.optString("direction", "down")
                val acc = PhoneControlAccessibilityService.instance
                acc?.scrollScreen(dir)
                    ?: "Ekranni aylantirish uchun Ekran nazorati (Accessibility) yoqilmagan."
            }

            "system_navigation" -> {
                val action = args.optString("action")
                val acc = PhoneControlAccessibilityService.instance
                acc?.performSystemAction(action)
                    ?: "Tizim tugmasi uchun Ekran nazorati (Accessibility) yoqilmagan."
            }

            "search_contacts" -> {
                val query = args.optString("query")
                val contacts = phoneCapabilities.searchContacts(query)
                if (contacts.isEmpty()) "'$query' bo'yicha kontakt topilmadi."
                else contacts.joinToString("; ") { "${it.name}: ${it.phoneNumber}" }
            }

            "make_call" -> {
                val target = args.optString("contact_or_number")
                phoneCapabilities.makePhoneCall(target)
            }

            "send_sms" -> {
                val target = args.optString("contact_or_number")
                val message = args.optString("message")
                phoneCapabilities.sendSms(target, message)
            }

            "control_device" -> {
                val feature = args.optString("feature")
                val value = args.optString("value")
                when (feature.lowercase()) {
                    "flashlight", "chiroq" -> phoneCapabilities.toggleFlashlight(
                        value.lowercase() in listOf("on", "yoq", "true", "1")
                    )
                    "volume", "ovoz" -> phoneCapabilities.controlVolume(value)
                    "settings", "sozlamalar" -> phoneCapabilities.openSystemSettings(value)
                    "web_search", "qidiruv" -> phoneCapabilities.webSearch(value)
                    "alarm", "budilnik" -> {
                        val parts = value.split(":")
                        val h = parts.getOrNull(0)?.toIntOrNull() ?: 7
                        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
                        phoneCapabilities.setAlarm(h, m, "Telefonim AI")
                    }
                    else -> "Noma'lum qurilma funksiyasi: $feature"
                }
            }

            else -> "Noma'lum vosita: $name"
        }
    }

    private fun buildSystemPrompt(wakeWord: String): String {
        val accEnabled = PhoneControlAccessibilityService.isConnected()
        return """
Sen "$wakeWord" (Telefonim AI) nomli o'zbek tilidagi aqlli telefon yordamchisi va avtonom agentisan.
Foydalanuvchi senga ovoz yoki matn orqali har qanday savol berishi yoki telefondagi istalgan amalni bajarishni buyurishi mumkin.

MUHIM QOIDALAR:
1. Har doim faqat chiroyli, tabiiy va qisqa O'ZBEK TILIDA (lotin alifbosida) javob ber — chunki javobing ovoz bilan o'qib eshittiriladi (1-3 jumla eng yaxshi).
2. Foydalanuvchi biror ilovani ochishni ("Telegramni och"), qo'ng'iroq qilishni ("Oyimga telefon qil", "90 123 45 67 ga qo'ng'iroq qil"), xabar yuborishni ("Oyimga salom deb yubor"), chiroqni yoqishni, ekranni ko'rishni ("Ekranda nima bor?") yoki boshqa ilova ichida biror ish qilishni so'rasa — DARHOL tegishli vositalarni (tools) chaqir!
3. Agar foydalanuvchi "Telegramda Aliga salom yoz" kabi ilova ichidagi ko'p bosqichli amal so'rasa:
   - Avval `open_app("Telegram")` ni chaqir.
   - Keyin `get_screen_content()` bilan ekranni o'qi.
   - Kerakli chat yoki qidiruv tugmasini `click_element` bilan bos, `type_text` bilan yoz va Yuborish tugmasini bos.
   - Agar foydalanuvchi ilova nomini aytmay shunchaki "Oyimga salom deb yubor" desa, SMS (`send_sms`) orqali yubor yoki kontakti topilmasa tushuntir.
4. Agar foydalanuvchi savol bersa (masalan ob-havo, tarix, matematika, tarjima, maslahat), vosita chaqirmasdan to'g'ridan-to'g'ri aqlli va aniq javob ber.
5. Hozirgi Ekran Nazorati (Accessibility Service) holati: ${if (accEnabled) "YONIQ (ekranni ko'rish va boshqarish mumkin)" else "O'CHIQ"}.
        """.trimIndent()
    }

    private fun buildToolsSchema(): JSONArray {
        fun tool(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()): JSONObject {
            return JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", name)
                    put("description", desc)
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", props)
                        if (required.isNotEmpty()) {
                            put("required", JSONArray(required))
                        }
                    })
                })
            }
        }

        fun strProp(desc: String) = JSONObject().apply {
            put("type", "string")
            put("description", desc)
        }

        return JSONArray().apply {
            put(tool("get_screen_content", "Hozirgi telefon ekranidagi barcha matnlar, tugmalar va maydonlarni o'qiydi.", JSONObject()))
            put(tool("open_app", "Telefonda o'rnatilgan ilovani nomi bo'yicha ochadi (masalan: Telegram, YouTube, Sozlamalar, Kamera).", JSONObject().apply {
                put("app_name", strProp("Ochiladigan ilova nomi"))
            }, listOf("app_name")))
            put(tool("list_installed_apps", "Telefonda o'rnatilgan barcha ilovalar ro'yxatini oladi.", JSONObject()))
            put(tool("click_element", "Ekrandagi tugma yoki elementni matni, tavsifi yoki indeks raqami (#0, #1...) bo'yicha bosadi.", JSONObject().apply {
                put("target", strProp("Element matni yoki indeks raqami"))
            }, listOf("target")))
            put(tool("type_text", "Ekrandagi matn kiritish maydoniga matn yozadi.", JSONObject().apply {
                put("text", strProp("Yoziladigan matn"))
                put("target_field", strProp("Ixtiyoriy: maydon nomi yoki indeksi"))
            }, listOf("text")))
            put(tool("scroll_screen", "Ekranni yuqoriga, pastga, chapga yoki o'ngga aylantiradi.", JSONObject().apply {
                put("direction", strProp("Yo'nalish: up, down, left, right"))
            }, listOf("direction")))
            put(tool("system_navigation", "Telefonning tizim tugmalarini bosadi: back, home, recents, notifications, quick_settings, screenshot.", JSONObject().apply {
                put("action", strProp("Tizim harakati nomi"))
            }, listOf("action")))
            put(tool("search_contacts", "Telefon kontaktlaridan ism bo'yicha raqam qidiradi.", JSONObject().apply {
                put("query", strProp("Kontakt ismi (masalan: Oyim, Ali)"))
            }, listOf("query")))
            put(tool("make_call", "Kontakt ismi yoki telefon raqamiga qo'ng'iroq qiladi.", JSONObject().apply {
                put("contact_or_number", strProp("Kontakt ismi yoki telefon raqami"))
            }, listOf("contact_or_number")))
            put(tool("send_sms", "Kontakt yoki raqamga SMS xabar yuboradi.", JSONObject().apply {
                put("contact_or_number", strProp("Qabul qiluvchi ismi yoki raqami"))
                put("message", strProp("Xabar matni"))
            }, listOf("contact_or_number", "message")))
            put(tool("control_device", "Qurilma funksiyalarini boshqaradi: flashlight (on/off), volume (up/down/mute/max), settings (wifi/bluetooth/sound/display/battery/general), alarm (HH:MM), web_search (matn).", JSONObject().apply {
                put("feature", strProp("Funksiya: flashlight, volume, settings, alarm, web_search"))
                put("value", strProp("Qiymat: on/off, up/down, wifi, 07:30, yoki qidiruv matni"))
            }, listOf("feature", "value")))
        }
    }
}
