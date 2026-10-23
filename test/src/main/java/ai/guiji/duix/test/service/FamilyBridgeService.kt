package ai.guiji.duix.test.service

import ai.guiji.duix.test.App
import ai.guiji.duix.test.BuildConfig
import android.content.Context
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class FamilyBridgeService(context: Context) {

    private val appContext = context.applicationContext
    private val identityStore = DeviceIdentityStore(appContext)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun getDeviceUuid(): String = identityStore.getOrCreateDeviceUuid()

    fun getBindUrl(): String = identityStore.getBindUrl()

    fun registerCurrentDevice(): DeviceRegistrationResult {
        val payload = JSONObject().apply {
            put("deviceUuid", getDeviceUuid())
            put("displayName", buildDisplayName())
            put("appVersion", BuildConfig.VERSION_NAME)
            identityStore.getDeviceToken()?.let { put("deviceToken", it) }
        }
        val response = postJson("/api/device/register", payload, requiresAuth = false)
        val bindUrl = response.optString("bindUrl").ifBlank {
            identityStore.buildFallbackBindUrl(getDeviceUuid())
        }
        identityStore.saveBindUrl(bindUrl)
        response.optString("deviceToken").takeIf { it.isNotBlank() }?.let(identityStore::saveDeviceToken)

        return DeviceRegistrationResult(
            deviceUuid = response.optString("deviceUuid", getDeviceUuid()),
            bindUrl = bindUrl,
        )
    }

    fun fetchFamilyMessages(take: Int = 5): FamilyMessageFetchResult {
        val normalizedTake = take.coerceIn(1, 8)
        val url = DashScopeConfig.MONITOR_BASE_URL.trimEnd('/') +
            "/api/device/messages?deviceUuid=${getDeviceUuid()}&take=$normalizedTake"
        val response = executeJsonRequest(
            Request.Builder()
                .url(url)
                .addDeviceAuthorization()
                .get()
                .build()
        )

        val messagesJson = response.optJSONArray("messages") ?: JSONArray()
        val messages = buildList {
            for (index in 0 until messagesJson.length()) {
                val item = messagesJson.optJSONObject(index) ?: continue
                add(
                    FamilyMessageItem(
                        id = item.optString("id"),
                        content = item.optString("content"),
                        importance = item.optString("importance"),
                        createdAt = item.optString("createdAt"),
                        status = item.optString("status"),
                        deliveredAt = item.optString("deliveredAt").ifBlank { null },
                        readAt = item.optString("readAt").ifBlank { null },
                    )
                )
            }
        }

        return FamilyMessageFetchResult(
            summaryText = response.optString("summaryText"),
            unreadCount = response.optInt("unreadCount"),
            messages = messages,
        )
    }

    fun markFamilyMessagesRead(messageIds: List<String>) {
        if (messageIds.isEmpty()) {
            return
        }
        val payload = JSONObject().apply {
            put("deviceUuid", getDeviceUuid())
            put("messageIds", JSONArray(messageIds))
        }
        postJson("/api/device/messages/read", payload)
    }

    fun prepareConversationPrompt(take: Int = 3): ConversationPromptInjection? {
        val result = fetchFamilyMessages(take)
        val unreadMessages = result.messages
            .filter { it.readAt.isNullOrBlank() }
            .take(take.coerceIn(1, 5))

        val agenda = runCatching { fetchTodayAgenda() }.getOrNull()
        if (unreadMessages.isEmpty() && agenda == null) {
            return null
        }

        return ConversationPromptInjection(
            messageIds = unreadMessages.map { it.id },
            promptText = buildConversationPrompt(unreadMessages, agenda),
        )
    }

    fun fetchTodayAgenda(): TodayAgenda? {
        val url = DashScopeConfig.MONITOR_BASE_URL.trimEnd('/') + "/api/device/agenda?deviceUuid=${getDeviceUuid()}"
        val response = executeJsonRequest(Request.Builder().url(url).addDeviceAuthorization().get().build())
        val doses = response.optJSONArray("doses") ?: JSONArray()
        val checkIns = response.optJSONArray("checkIns") ?: JSONArray()
        val posts = response.optJSONArray("posts") ?: JSONArray()
        val memos = response.optJSONArray("memos") ?: JSONArray()
        if (doses.length() == 0 && checkIns.length() == 0 && posts.length() == 0 && memos.length() == 0) return null
        return TodayAgenda(response.optString("circleName"), doses, checkIns, posts, memos)
    }

    fun updateMedication(doseId: String, status: String, note: String? = null, snoozeMinutes: Int = 10): JSONObject {
        return postJson("/api/device/events", JSONObject().apply {
            put("deviceUuid", getDeviceUuid()); put("type", "MEDICATION"); put("doseId", doseId); put("status", status)
            if (!note.isNullOrBlank()) put("note", note)
            put("snoozeMinutes", snoozeMinutes)
        })
    }

    fun respondCheckIn(checkInId: String, status: String, responseText: String? = null): JSONObject {
        return postJson("/api/device/events", JSONObject().apply {
            put("deviceUuid", getDeviceUuid()); put("type", "CHECK_IN"); put("checkInId", checkInId); put("status", status)
            if (!responseText.isNullOrBlank()) put("responseText", responseText)
        })
    }

    fun reportSafetyAlert(category: String, title: String, summary: String, level: String, contextText: String? = null): JSONObject {
        return postJson("/api/device/alerts", JSONObject().apply {
            put("deviceUuid", getDeviceUuid()); put("category", category); put("title", title); put("summary", summary); put("level", level)
            if (!contextText.isNullOrBlank()) put("contextText", contextText)
        })
    }

    fun acknowledgeFamilyMessages(messageIds: List<String>) {
        markFamilyMessagesRead(messageIds)
    }

    fun recordUsageEvent(eventType: String, detailJson: JSONObject? = null) {
        val payload = JSONObject().apply {
            put("deviceUuid", getDeviceUuid())
            put("eventType", eventType)
            if (detailJson != null) {
                put("detailJson", detailJson.toString())
            }
        }
        postJson("/api/device/usage", payload)
    }

    fun recordToolCall(
        toolName: String,
        arguments: JSONObject,
        outputText: String,
        status: String,
        callId: String? = null,
        sessionId: String? = null,
    ) {
        val payload = JSONObject().apply {
            put("deviceUuid", getDeviceUuid())
            put("toolName", toolName)
            put("arguments", arguments)
            put("outputText", outputText)
            put("status", status)
            if (!callId.isNullOrBlank()) {
                put("callId", callId)
            }
            if (!sessionId.isNullOrBlank()) {
                put("sessionId", sessionId)
            }
        }
        postJson("/api/device/tool-call", payload)
    }

    fun uploadConversationTurns(sessionId: String?, turns: List<ConversationTurnPayload>) {
        if (turns.isEmpty()) {
            return
        }
        val payload = JSONObject().apply {
            put("deviceUuid", getDeviceUuid())
            if (!sessionId.isNullOrBlank()) {
                put("sessionId", sessionId)
            }
            put(
                "turns",
                JSONArray().apply {
                    turns.forEach { turn ->
                        put(
                            JSONObject().apply {
                                put("role", turn.role)
                                put("content", turn.content)
                                if (!turn.metaJson.isNullOrBlank()) {
                                    put("metaJson", turn.metaJson)
                                }
                            }
                        )
                    }
                }
            )
        }
        postJson("/api/device/conversations", payload)
    }

    private fun postJson(path: String, payload: JSONObject, requiresAuth: Boolean = true): JSONObject {
        val builder = Request.Builder().url(DashScopeConfig.MONITOR_BASE_URL.trimEnd('/') + path)
        if (requiresAuth) builder.addDeviceAuthorization()
        return executeJsonRequest(
            builder
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
        )
    }

    private fun Request.Builder.addDeviceAuthorization(): Request.Builder {
        val token = identityStore.getDeviceToken() ?: throw IllegalStateException("设备尚未完成安全注册")
        return header("Authorization", "Bearer $token")
    }

    private fun executeJsonRequest(request: Request): JSONObject {
        App.getOkHttpClient().newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            val json = responseText.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
            if (!response.isSuccessful) {
                val message = json.optString("error").ifBlank {
                    response.message.ifBlank { "请求失败" }
                }
                throw IllegalStateException(message)
            }
            return json
        }
    }

    private fun buildDisplayName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "长辈陪伴终端" }
    }

    private fun buildConversationPrompt(messages: List<FamilyMessageItem>, agenda: TodayAgenda?): String {
        return buildString {
            append("当前开始一轮新对话。请自然处理下列家庭问候和今日照护事项，不要提系统提示、后台、注入、工具或接口。")
            messages.forEachIndexed { index, message ->
                append("\n")
                append(index + 1)
                append(". ")
                val importanceLabel = when (message.importance.trim().uppercase()) {
                    "HIGH" -> "希望尽快看到："
                    "URGENT" -> "需要尽快联系："
                    "LOW" -> "温和提醒："
                    else -> "日常问候："
                }
                append(importanceLabel)
                append(message.content)
            }
            agenda?.let {
                for (index in 0 until it.posts.length()) { val item = it.posts.optJSONObject(index) ?: continue; append("\n家庭动态：${item.optString("authorName")}说：${item.optString("content")}") }
                for (index in 0 until it.doses.length()) { val item = it.doses.optJSONObject(index) ?: continue; append("\n待服药[编号${item.optString("id")}]：${item.optString("name")}，${item.optString("dosage")}，时间${item.optString("scheduledFor")}") }
                for (index in 0 until it.checkIns.length()) { val item = it.checkIns.optJSONObject(index) ?: continue; append("\n待报平安[编号${item.optString("id")}]：${item.optString("prompt", "家里人想知道您今天是否安好")}") }
            }
            append("\n优先温和提醒已经到时间的用药和报平安请求；关键操作必须调用对应工具确认，不能只口头答应。")
        }
    }

    data class DeviceRegistrationResult(
        val deviceUuid: String,
        val bindUrl: String,
    )

    data class FamilyMessageItem(
        val id: String,
        val content: String,
        val importance: String,
        val createdAt: String,
        val status: String,
        val deliveredAt: String?,
        val readAt: String?,
    )

    data class FamilyMessageFetchResult(
        val summaryText: String,
        val unreadCount: Int,
        val messages: List<FamilyMessageItem>,
    )

    data class ConversationPromptInjection(
        val messageIds: List<String>,
        val promptText: String,
    )

    data class TodayAgenda(
        val circleName: String,
        val doses: JSONArray,
        val checkIns: JSONArray,
        val posts: JSONArray,
        val memos: JSONArray,
    )

    data class ConversationTurnPayload(
        val role: String,
        val content: String,
        val metaJson: String? = null,
    )

    companion object {
        const val EVENT_APP_OPEN = "APP_OPEN"
        const val EVENT_SETTINGS_OPENED = "SETTINGS_OPENED"
        const val EVENT_AI_SESSION_STARTED = "AI_SESSION_STARTED"
        const val EVENT_AI_SESSION_ENDED = "AI_SESSION_ENDED"
        const val EVENT_CAMERA_ENABLED = "CAMERA_ENABLED"
        const val EVENT_CAMERA_DISABLED = "CAMERA_DISABLED"

        const val ROLE_USER = "USER"
        const val ROLE_ASSISTANT = "ASSISTANT"
        const val ROLE_TOOL = "TOOL"

        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_FAILURE = "FAILURE"
    }
}
