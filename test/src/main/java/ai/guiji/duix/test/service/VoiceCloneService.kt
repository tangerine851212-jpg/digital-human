package ai.guiji.duix.test.service

import android.util.Base64
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class VoiceCloneService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun createVoice(
        sampleFile: File,
        preferredName: String,
        targetModel: String = DashScopeConfig.VOICE_CLONE_TTS_MODEL,
        callback: (Result<VoiceCloneResult>) -> Unit
    ) {
        if (!sampleFile.exists()) {
            callback(Result.failure(IllegalStateException("录音文件不存在")))
            return
        }
        val payload = JSONObject()
        val input = JSONObject()
        val audio = JSONObject()
        audio.put("data", buildDataUri(sampleFile))
        input.put("action", "create")
        input.put("target_model", targetModel)
        input.put("preferred_name", normalizePreferredName(preferredName))
        input.put("audio", audio)
        payload.put("model", DashScopeConfig.VOICE_CLONE_MODEL)
        payload.put("input", input)

        val request = Request.Builder()
            .url(DashScopeConfig.VOICE_CLONE_URL)
            .addHeader("Authorization", "Bearer " + DashScopeConfig.DEFAULT_API_KEY)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        callback(Result.failure(IllegalStateException(parseErrorMessage(body).ifEmpty { "保存熟悉声音失败" })))
                        return
                    }
                    try {
                        val json = JSONObject(body)
                        val output = json.getJSONObject("output")
                        val voice = output.optString("voice")
                        if (voice.isBlank()) {
                            callback(Result.failure(IllegalStateException("没有拿到可用音色")))
                            return
                        }
                        callback(
                            Result.success(
                                VoiceCloneResult(
                                    voice = voice,
                                    targetModel = output.optString("target_model", targetModel)
                                )
                            )
                        )
                    } catch (e: Exception) {
                        callback(Result.failure(e))
                    }
                }
            }
        })
    }

    fun deleteVoice(voice: String) {
        if (voice.isBlank()) {
            return
        }
        val payload = JSONObject()
        val input = JSONObject()
        input.put("action", "delete")
        input.put("voice", voice)
        payload.put("model", DashScopeConfig.VOICE_CLONE_MODEL)
        payload.put("input", input)

        val request = Request.Builder()
            .url(DashScopeConfig.VOICE_CLONE_URL)
            .addHeader("Authorization", "Bearer " + DashScopeConfig.DEFAULT_API_KEY)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    private fun buildDataUri(file: File): String {
        val mimeType = when (file.extension.lowercase(Locale.US)) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            else -> "audio/mp4"
        }
        val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        return "data:$mimeType;base64,$encoded"
    }

    private fun normalizePreferredName(source: String): String {
        val normalized = source.lowercase(Locale.US)
            .replace("[^a-z0-9_]".toRegex(), "_")
            .trim('_')
            .ifBlank { "family_voice" }
        return normalized.take(16)
    }

    private fun parseErrorMessage(body: String): String {
        return try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    data class VoiceCloneResult(
        val voice: String,
        val targetModel: String
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}