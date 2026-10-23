package ai.guiji.duix.test.service

import ai.guiji.duix.test.App
import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PsychologyKnowledgeAgentService(
    context: Context,
) {

    private val appContext = context.applicationContext
    private val client = App.getOkHttpClient().newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private val knowledgeText: String by lazy {
        appContext.assets.open(DashScopeConfig.PSYCHOLOGY_KNOWLEDGE_ASSET_PATH)
            .bufferedReader(Charsets.UTF_8)
            .use { reader ->
                reader.readText()
                    .replace('\u000c', '\n')
                    .replace("\u0000", "")
            }
    }

    fun consultKnowledge(
        question: String,
        focus: String? = null,
    ): KnowledgeAnswer {
        val normalizedQuestion = question.trim()
        require(normalizedQuestion.isNotEmpty()) { "问题不能为空" }

        val request = Request.Builder()
            .url(DashScopeConfig.MULTIMODAL_GENERATION_URL)
            .addHeader("Authorization", "Bearer " + DashScopeConfig.DEFAULT_API_KEY)
            .addHeader("Content-Type", "application/json")
            .post(
                buildRequestPayload(
                    normalizedQuestion,
                    focus?.trim().takeIf { !it.isNullOrEmpty() },
                ).toString().toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(parseErrorMessage(body).ifEmpty { "心理学知识子代理调用失败" })
            }

            val json = JSONObject(body)
            val message = json.optJSONObject("output")
                ?.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
            val content = extractMessageContent(message)
            if (content.isBlank()) {
                throw IllegalStateException("心理学知识子代理没有返回内容")
            }

            val usage = json.optJSONObject("usage")
            return KnowledgeAnswer(
                outputText = content.trim(),
                model = DashScopeConfig.PSYCHOLOGY_SUBAGENT_MODEL,
                requestId = json.optString("request_id").ifBlank { null },
                inputTokens = usage?.optInt("input_tokens") ?: 0,
                outputTokens = usage?.optInt("output_tokens") ?: 0,
            )
        }
    }

    private fun buildRequestPayload(
        question: String,
        focus: String?,
    ): JSONObject {
        return JSONObject().apply {
            put("model", DashScopeConfig.PSYCHOLOGY_SUBAGENT_MODEL)
            put(
                "input",
                JSONObject().apply {
                    put(
                        "messages",
                        JSONArray()
                            .put(
                                JSONObject().apply {
                                    put("role", "system")
                                    put("content", buildSystemPrompt())
                                }
                            )
                            .put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("content", buildUserPrompt(question, focus))
                                }
                            )
                    )
                }
            )
            put(
                "parameters",
                JSONObject().apply {
                    put("result_format", "message")
                    put("enable_thinking", false)
                    put("temperature", 0.2)
                    put("max_tokens", 900)
                }
            )
        }
    }

    private fun buildSystemPrompt(): String {
        return buildString {
            append(knowledgeText)
            append("以上是完整知识库全文\n")
            append("你是一个面向老年陪伴场景的权威照护知识子代理。")
            append("你只能依据系统里提供的 WHO ICOPE 2024 全文回答，不要编造，不要输出与原文冲突的结论。")
            append("如果知识库未提供足够直接依据，要明确说明。")
            append("请使用简体中文输出，提供权威心理学指示和说明。不需要包括生活化语言，你只是一个查询专家，不负责指导。直接输出无格式纯文本，答案简练不超过100字。")
        }
    }

    private fun buildUserPrompt(
        question: String,
        focus: String?,
    ): String {
        return buildString {
            append("请基于上面的 WHO ICOPE 2024 全文，回答下面这个需要查询的问题。\n")
            append("核心问题：")
            append(question)
            if (!focus.isNullOrBlank()) {
                append("\n特别关注：")
                append(focus)
            }
            append("\n请避免空泛安慰，优先给出可落地的权威说明。")
        }
    }

    private fun extractMessageContent(message: JSONObject?): String {
        if (message == null) {
            return ""
        }
        val content = message.opt("content")
        return when (content) {
            is String -> content
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    val part = content.opt(index)
                    val text = when (part) {
                        is JSONObject -> part.optString("text")
                        is String -> part
                        else -> ""
                    }.trim()
                    if (text.isNotEmpty()) {
                        if (isNotEmpty()) {
                            append('\n')
                        }
                        append(text)
                    }
                }
            }
            else -> ""
        }
    }

    private fun parseErrorMessage(body: String): String {
        return try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message").orEmpty()
                .ifBlank { json.optString("message") }
        } catch (_: Exception) {
            ""
        }
    }

    data class KnowledgeAnswer(
        val outputText: String,
        val model: String,
        val requestId: String?,
        val inputTokens: Int,
        val outputTokens: Int,
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}