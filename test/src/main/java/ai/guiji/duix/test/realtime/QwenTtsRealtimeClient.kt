package ai.guiji.duix.test.realtime

import ai.guiji.duix.test.service.DashScopeConfig
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class QwenTtsRealtimeClient(
    private val sessionOptions: SessionOptions,
    private val callback: Callback
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val connected = AtomicBoolean(false)
    private var webSocket: WebSocket? = null

    fun connect() {
        if (connected.get()) {
            return
        }
        val request = Request.Builder()
            .url(sessionOptions.baseUrl + "?model=" + sessionOptions.model)
            .addHeader("Authorization", "Bearer " + sessionOptions.apiKey)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true)
                sendSessionUpdate()
                callback.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false)
                callback.onError(t.message ?: "连接失败")
                callback.onDisconnected()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false)
                callback.onDisconnected()
            }
        })
    }

    fun appendText(text: String) {
        if (text.isBlank()) {
            return
        }
        val event = JSONObject()
        event.put("event_id", nextEventId())
        event.put("type", "input_text_buffer.append")
        event.put("text", text)
        sendEvent(event)
    }

    fun finish() {
        val event = JSONObject()
        event.put("event_id", nextEventId())
        event.put("type", "session.finish")
        sendEvent(event)
    }

    fun disconnect() {
        webSocket?.close(1000, "bye")
        webSocket = null
        connected.set(false)
    }

    private fun sendSessionUpdate() {
        val session = JSONObject()
        session.put("voice", sessionOptions.voice)
        session.put("mode", "server_commit")
        session.put("response_format", "pcm")
        session.put("sample_rate", DashScopeConfig.AUDIO_SAMPLE_RATE)

        val event = JSONObject()
        event.put("event_id", nextEventId())
        event.put("type", "session.update")
        event.put("session", session)
        sendEvent(event)
    }

    private fun handleMessage(text: String) {
        try {
            val event = JSONObject(text)
            when (event.optString("type")) {
                "response.audio.delta" -> {
                    val audioBase64 = event.optString("delta")
                    if (audioBase64.isNotBlank()) {
                        callback.onAudioDelta(Base64.decode(audioBase64, Base64.NO_WRAP))
                    }
                }
                "response.audio.done" -> callback.onAudioDone()
                "response.done" -> callback.onResponseDone()
                "session.finished" -> callback.onSessionFinished()
                "error" -> {
                    val error = event.optJSONObject("error")
                    callback.onError(error?.optString("message") ?: "语音合成失败")
                }
            }
        } catch (e: Exception) {
            callback.onError(e.message ?: "语音合成失败")
        }
    }

    private fun sendEvent(event: JSONObject): Boolean {
        return connected.get() && webSocket?.send(event.toString()) == true
    }

    private fun nextEventId(): String {
        return "event_" + UUID.randomUUID().toString().replace("-", "")
    }

    data class SessionOptions(
        val voice: String,
        val model: String = DashScopeConfig.VOICE_CLONE_TTS_MODEL,
        val apiKey: String = DashScopeConfig.DEFAULT_API_KEY,
        val baseUrl: String = DashScopeConfig.REALTIME_BASE_URL
    )

    interface Callback {
        fun onConnected()

        fun onDisconnected()

        fun onAudioDelta(pcmData: ByteArray)

        fun onAudioDone()

        fun onResponseDone()

        fun onSessionFinished()

        fun onError(message: String)
    }
}