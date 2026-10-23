package ai.guiji.duix.test.realtime;

import ai.guiji.duix.test.service.DashScopeConfig;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Qwen-Omni-Realtime WebSocket 客户端
 * 默认接入 qwen3.5-omni-plus-realtime 模型
 */
public class OmniRealtimeClient {

    private static final String TAG = "OmniRealtimeClient";
    private static final String DEFAULT_API_KEY = DashScopeConfig.DEFAULT_API_KEY;
    public static final String DEFAULT_MODEL = "qwen3.5-omni-plus-realtime";
    private static final String DEFAULT_BASE_URL = DashScopeConfig.REALTIME_BASE_URL;
    private static final String INPUT_AUDIO_TRANSCRIPTION_MODEL = "gummy-realtime-v1";
    private static final int MAX_IMAGE_BYTES = 500 * 1024;
    private static final long MIN_IMAGE_APPEND_INTERVAL_MS = 800L;
    private static final String DEFAULT_INSTRUCTIONS = "你是一名面向中国老年群体的心理陪伴 AI。你的职责是为老人提供温和、耐心、尊重、稳定的情感陪伴、情绪支持、回忆唤起、生活关怀和风险识别服务。\n"
            + "\n"
            + "请始终遵循以下原则：\n"
            + "\n"
            + "先接住情绪，再给建议；\n"
            + "\n"
            + "使用简短、朴素、生活化的中文，始终用“您”称呼；\n"
            + "\n"
            + "一次不提供超过两个建议；\n"
            + "\n"
            + "鼓励老人表达感受，可适度引导回忆人生经历、兴趣、家庭和过往成就；\n"
            + "\n"
            + "你的回复风格应温和、平稳、尊重、有耐心。每次回答尽量包含：一句共情、一句理解或复述、一句温和追问或一个微小建议。核心目标是让老人感到：我被听见了，我被尊重了，有人愿意陪我。";

    private final OkHttpClient okHttpClient;
    private WebSocket webSocket;
    private final Callback callback;
    private volatile SessionOptions sessionOptions;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean userSpeaking = new AtomicBoolean(false);
    private final AtomicBoolean assistantResponding = new AtomicBoolean(false);
    private final AtomicBoolean imageWindowOpen = new AtomicBoolean(false);
    private final AtomicBoolean hasSentAnyAudio = new AtomicBoolean(false);
    private final AtomicLong lastImageAppendAtMs = new AtomicLong(0L);
    private final AtomicBoolean assistantTextFinalized = new AtomicBoolean(false);
    private final StringBuilder assistantTextBuffer = new StringBuilder();

    public static String getDefaultInstructions() {
        return DEFAULT_INSTRUCTIONS;
    }

    public OmniRealtimeClient(SessionOptions sessionOptions, Callback callback) {
        this.sessionOptions = sessionOptions;
        this.callback = callback;
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.MINUTES)
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build();
    }

    public void updateSessionOptions(SessionOptions sessionOptions) {
        if (sessionOptions == null) {
            return;
        }
        this.sessionOptions = sessionOptions;
        if (isConnected.get()) {
            sendSessionUpdate();
        }
    }

    public void connect() {
        if (isConnected.get()) {
            Log.w(TAG, "Already connected");
            return;
        }
        SessionOptions options = sessionOptions;
        if (options == null || options.getApiKey().trim().isEmpty()) {
            if (callback != null) {
                callback.onError("未配置 DashScope API Key");
            }
            return;
        }
        Request request = new Request.Builder()
                .url(options.getWebSocketUrl())
                .addHeader("Authorization", "Bearer " + options.getApiKey())
                .build();

        webSocket = okHttpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.i(TAG, "WebSocket connected");
                isConnected.set(true);
                sendSessionUpdate();
                if (callback != null) {
                    callback.onConnected();
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleMessage(text);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.e(TAG, "WebSocket error: " + t.getMessage());
                isConnected.set(false);
                resetStreamingState();
                if (callback != null) {
                    callback.onError("连接失败: " + t.getMessage());
                }
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                Log.i(TAG, "WebSocket closing: " + code + " " + reason);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.i(TAG, "WebSocket closed: " + code + " " + reason);
                isConnected.set(false);
                resetStreamingState();
                if (callback != null) {
                    callback.onDisconnected();
                }
            }
        });
    }

    private void sendSessionUpdate() {
        SessionOptions options = sessionOptions;
        if (options == null) {
            return;
        }
        try {
            JSONObject session = new JSONObject();
            JSONArray modalities = new JSONArray().put("text");
            if (options.isAudioOutputEnabled()) {
                modalities.put("audio");
            }
            session.put("modalities", modalities);
            if (options.isAudioOutputEnabled()) {
                session.put("voice", options.getVoice());
                session.put("output_audio_format", "pcm");
            }
            session.put("input_audio_format", "pcm");
            session.put("instructions", options.getInstructions());
            session.put("enable_search", options.isEnableSearch() && !options.hasTools());

            if (options.hasTools()) {
                JSONArray tools = new JSONArray();
                for (ToolDefinition toolDefinition : options.getTools()) {
                    tools.put(toolDefinition.toJson());
                }
                session.put("tools", tools);
            }

            if (options.isEnableInputAudioTranscription()) {
                JSONObject inputAudioTranscription = new JSONObject();
                inputAudioTranscription.put("model", INPUT_AUDIO_TRANSCRIPTION_MODEL);
                session.put("input_audio_transcription", inputAudioTranscription);
            }

            if (options.isEnableTurnDetection()) {
                JSONObject turnDetection = new JSONObject();
                turnDetection.put("type", "server_vad");
                turnDetection.put("threshold", 0.5);
                turnDetection.put("silence_duration_ms", 800);
                session.put("turn_detection", turnDetection);
            } else {
                session.put("turn_detection", JSONObject.NULL);
            }

            JSONObject event = new JSONObject();
            event.put("event_id", nextEventId());
            event.put("type", "session.update");
            event.put("session", session);

            sendEvent(event);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build session.update: " + e.getMessage());
        }
    }

    private void handleMessage(String text) {
        try {
            JSONObject event = new JSONObject(text);
            String type = event.optString("type", "");

            switch (type) {
                case "session.created":
                case "session.updated":
                    Log.d(TAG, "Session: " + type);
                    break;

                case "input_audio_buffer.speech_started":
                    Log.d(TAG, "User started speaking");
                    userSpeaking.set(true);
                    imageWindowOpen.set(true);
                    if (callback != null) {
                        callback.onUserSpeechStarted();
                    }
                    break;

                case "input_audio_buffer.speech_stopped":
                    Log.d(TAG, "User stopped speaking");
                    userSpeaking.set(false);
                    imageWindowOpen.set(false);
                    if (callback != null) {
                        callback.onUserSpeechStopped();
                    }
                    break;

                case "input_audio_buffer.committed":
                    Log.d(TAG, "Audio buffer committed");
                    imageWindowOpen.set(false);
                    break;

                case "response.created":
                    assistantResponding.set(true);
                    imageWindowOpen.set(false);
                    resetAssistantTextState();
                    break;

                case "conversation.item.input_audio_transcription.completed":
                    String userTranscript = event.optString("transcript", "");
                    Log.d(TAG, "User said: " + userTranscript);
                    if (callback != null) {
                        callback.onUserTranscript(userTranscript);
                    }
                    break;

                case "response.audio_transcript.delta":
                    handleAssistantTextDelta(event.optString("delta", ""));
                    break;

                case "response.audio_transcript.done":
                    dispatchAssistantFinalText(event.optString("transcript", event.optString("text", "")));
                    break;

                case "response.text.delta":
                    handleAssistantTextDelta(event.optString("delta", ""));
                    break;

                case "response.text.done":
                    dispatchAssistantFinalText(event.optString("text", event.optString("transcript", "")));
                    break;

                case "response.function_call_arguments.done":
                    String toolName = event.optString("name", "");
                    String callId = event.optString("call_id", "");
                    String arguments = event.optString("arguments", "{}");
                    Log.d(TAG, "Tool call: " + toolName + ", callId=" + callId + ", arguments=" + arguments);
                    if (callback != null) {
                        callback.onToolCallRequested(toolName, callId, arguments);
                    }
                    break;

                case "response.output_item.done":
                    dispatchAssistantFinalTextIfNeeded(extractTextFromItem(event.optJSONObject("item")));
                    break;

                case "response.audio.delta":
                    String audioB64 = event.optString("delta", "");
                    if (!audioB64.isEmpty() && callback != null && !userSpeaking.get()) {
                        byte[] audioData = Base64.decode(audioB64, Base64.NO_WRAP);
                        callback.onAssistantAudioDelta(audioData);
                    }
                    break;

                case "response.audio.done":
                    Log.d(TAG, "Audio generation done");
                    if (callback != null) {
                        callback.onAssistantAudioDone();
                    }
                    break;

                case "response.done":
                    Log.d(TAG, "Response done");
                    assistantResponding.set(false);
                    imageWindowOpen.set(false);
                    dispatchAssistantFinalTextIfNeeded(extractTextFromResponse(event.optJSONObject("response")));
                    if (callback != null) {
                        callback.onResponseDone();
                    }
                    break;

                case "error":
                    handleServerError(event);
                    break;

                default:
                    Log.v(TAG, "Unhandled event: " + type);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Parse message error: " + e.getMessage());
        }
    }

    public void appendAudio(byte[] pcmData) {
        if (!isConnected.get() || webSocket == null || pcmData == null || pcmData.length == 0) {
            return;
        }
        try {
            String audioB64 = Base64.encodeToString(pcmData, Base64.NO_WRAP);
            JSONObject event = new JSONObject();
            event.put("event_id", nextEventId());
            event.put("type", "input_audio_buffer.append");
            event.put("audio", audioB64);
            if (sendEvent(event)) {
                hasSentAnyAudio.set(true);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to append audio: " + e.getMessage());
        }
    }

    /**
     * 向图像缓冲区追加图像数据（用于视频通话）
     * 图片格式: JPG/JPEG，推荐 480P 或 720P，单张不超过 500KB
     */
    public boolean appendImage(byte[] jpegData) {
        if (!canAppendImage(jpegData)) {
            return false;
        }
        try {
            String imageB64 = Base64.encodeToString(jpegData, Base64.NO_WRAP);
            JSONObject event = new JSONObject();
            event.put("event_id", nextEventId());
            event.put("type", "input_image_buffer.append");
            event.put("image", imageB64);
            if (sendEvent(event)) {
                lastImageAppendAtMs.set(System.currentTimeMillis());
                return true;
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to append image: " + e.getMessage());
        }
        return false;
    }

    private boolean canAppendImage(byte[] jpegData) {
        if (!isConnected.get() || webSocket == null || jpegData == null || jpegData.length == 0) {
            return false;
        }
        if (jpegData.length >= MAX_IMAGE_BYTES) {
            Log.w(TAG, "Skip image larger than 500KB: " + jpegData.length);
            return false;
        }
        if (!hasSentAnyAudio.get()) {
            Log.d(TAG, "Skip image before the first audio frame is sent");
            return false;
        }
        if (!imageWindowOpen.get()) {
            Log.v(TAG, "Skip image outside active speech window");
            return false;
        }
        if (assistantResponding.get()) {
            Log.v(TAG, "Skip image while assistant is responding");
            return false;
        }
        long now = System.currentTimeMillis();
        long lastAppendAt = lastImageAppendAtMs.get();
        return now - lastAppendAt >= MIN_IMAGE_APPEND_INTERVAL_MS;
    }

    private boolean sendEvent(JSONObject event) {
        if (webSocket != null && isConnected.get()) {
            return webSocket.send(event.toString());
        }
        return false;
    }

    public void disconnect() {
        if (webSocket != null) {
            webSocket.close(1000, "bye");
            webSocket = null;
        }
        isConnected.set(false);
        resetStreamingState();
    }

    public boolean isConnected() {
        return isConnected.get();
    }

    public boolean sendFunctionCallOutput(String callId, String output) {
        if (callId == null || callId.trim().isEmpty()) {
            return false;
        }
        try {
            JSONObject item = new JSONObject();
            item.put("type", "function_call_output");
            item.put("call_id", callId);
            item.put("output", output == null ? "" : output);

            JSONObject event = new JSONObject();
            event.put("event_id", nextEventId());
            event.put("type", "conversation.item.create");
            event.put("item", item);

            if (!sendEvent(event)) {
                return false;
            }

            JSONObject responseCreate = new JSONObject();
            responseCreate.put("event_id", nextEventId());
            responseCreate.put("type", "response.create");
            return sendEvent(responseCreate);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to send function_call_output: " + e.getMessage());
            return false;
        }
    }

    public boolean requestResponse() {
        try {
            JSONObject responseCreate = new JSONObject();
            responseCreate.put("event_id", nextEventId());
            responseCreate.put("type", "response.create");
            return sendEvent(responseCreate);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to send response.create: " + e.getMessage());
            return false;
        }
    }

    public boolean commitInputAudio() {
        try {
            JSONObject event = new JSONObject();
            event.put("event_id", nextEventId());
            event.put("type", "input_audio_buffer.commit");
            boolean sent = sendEvent(event);
            if (sent) {
                imageWindowOpen.set(false);
            }
            return sent;
        } catch (JSONException e) {
            Log.e(TAG, "Failed to send input_audio_buffer.commit: " + e.getMessage());
            return false;
        }
    }

    private void handleServerError(JSONObject event) {
        String errMsg = event.optJSONObject("error") != null
                ? event.optJSONObject("error").optString("message", "Unknown error")
                : "Unknown error";
        Log.e(TAG, "Server error: " + errMsg);
        if (isImageAppendError(errMsg)) {
            imageWindowOpen.set(false);
            lastImageAppendAtMs.set(System.currentTimeMillis());
        }
        assistantResponding.set(false);
        if (callback != null) {
            callback.onError(errMsg);
        }
    }

    private boolean isImageAppendError(String message) {
        String lowerMessage = message == null ? "" : message.toLowerCase();
        return lowerMessage.contains("image")
                && (lowerMessage.contains("append") || lowerMessage.contains("buffer"));
    }

    private void resetStreamingState() {
        userSpeaking.set(false);
        assistantResponding.set(false);
        imageWindowOpen.set(false);
        hasSentAnyAudio.set(false);
        lastImageAppendAtMs.set(0L);
        resetAssistantTextState();
    }

    private void handleAssistantTextDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        synchronized (assistantTextBuffer) {
            assistantTextBuffer.append(delta);
        }
        if (callback != null) {
            callback.onAssistantTextDelta(delta);
        }
    }

    private void dispatchAssistantFinalTextIfNeeded(String text) {
        if (assistantTextFinalized.get()) {
            return;
        }
        dispatchAssistantFinalText(text);
    }

    private void dispatchAssistantFinalText(String text) {
        String finalText = text;
        if (finalText == null || finalText.isEmpty()) {
            synchronized (assistantTextBuffer) {
                finalText = assistantTextBuffer.toString();
            }
        }
        if (finalText == null || finalText.isEmpty()) {
            return;
        }
        synchronized (assistantTextBuffer) {
            assistantTextBuffer.setLength(0);
            assistantTextBuffer.append(finalText);
        }
        assistantTextFinalized.set(true);
        Log.d(TAG, "Assistant said: " + finalText);
        if (callback != null) {
            callback.onAssistantTranscriptDone(finalText);
        }
    }

    private void resetAssistantTextState() {
        synchronized (assistantTextBuffer) {
            assistantTextBuffer.setLength(0);
        }
        assistantTextFinalized.set(false);
    }

    private String extractTextFromResponse(JSONObject response) {
        if (response == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        JSONArray output = response.optJSONArray("output");
        if (output == null) {
            return "";
        }
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            String itemText = extractTextFromItem(item);
            if (!itemText.isEmpty()) {
                builder.append(itemText);
            }
        }
        return builder.toString();
    }

    private String extractTextFromItem(JSONObject item) {
        if (item == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        JSONArray content = item.optJSONArray("content");
        if (content == null) {
            return "";
        }
        for (int i = 0; i < content.length(); i++) {
            JSONObject part = content.optJSONObject(i);
            if (part == null) {
                continue;
            }
            String text = part.optString("text", "");
            if (!text.isEmpty()) {
                builder.append(text);
            }
        }
        return builder.toString();
    }

    private String nextEventId() {
        return "event_" + UUID.randomUUID().toString().replace("-", "");
    }

    public static class SessionOptions {
        public enum OutputMode {
            AUDIO_AND_TEXT,
            TEXT_ONLY
        }

        private final String apiKey;
        private final String baseUrl;
        private final String model;
        private final String voice;
        private final String instructions;
        private final boolean enableSearch;
        private final boolean enableTurnDetection;
        private final boolean enableInputAudioTranscription;
        private final OutputMode outputMode;
        private final List<ToolDefinition> tools;

        public SessionOptions(String voice, boolean enableSearch) {
            this(voice, enableSearch, OutputMode.AUDIO_AND_TEXT);
        }

        public SessionOptions(String voice, boolean enableSearch, OutputMode outputMode) {
            this(voice, enableSearch, outputMode, null, null);
        }

        public SessionOptions(String voice, boolean enableSearch, OutputMode outputMode,
                String instructions, List<ToolDefinition> tools) {
            this(DEFAULT_API_KEY, DEFAULT_BASE_URL, DEFAULT_MODEL, voice, DEFAULT_INSTRUCTIONS,
                    enableSearch, true, true, outputMode, tools, instructions);
        }

        public SessionOptions(String apiKey, String baseUrl, String model, String voice,
                String instructions, boolean enableSearch, boolean enableTurnDetection,
                boolean enableInputAudioTranscription, OutputMode outputMode) {
            this(apiKey, baseUrl, model, voice, instructions, enableSearch,
                    enableTurnDetection, enableInputAudioTranscription, outputMode, null, null);
        }

        public SessionOptions(String apiKey, String baseUrl, String model, String voice,
                String instructions, boolean enableSearch, boolean enableTurnDetection,
                boolean enableInputAudioTranscription, OutputMode outputMode,
                List<ToolDefinition> tools, String ignoredInstructionsOverride) {
            this.apiKey = apiKey == null ? "" : apiKey;
            this.baseUrl = baseUrl == null || baseUrl.trim().isEmpty() ? DEFAULT_BASE_URL : baseUrl;
            this.model = model == null || model.trim().isEmpty() ? DEFAULT_MODEL : model;
            this.voice = voice == null || voice.trim().isEmpty() ? "Ethan" : voice;
            String resolvedInstructions = ignoredInstructionsOverride;
            if (resolvedInstructions == null || resolvedInstructions.trim().isEmpty()) {
                resolvedInstructions = instructions;
            }
            this.instructions = resolvedInstructions == null || resolvedInstructions.trim().isEmpty()
                    ? DEFAULT_INSTRUCTIONS : resolvedInstructions;
            this.enableSearch = enableSearch;
            this.enableTurnDetection = enableTurnDetection;
            this.enableInputAudioTranscription = enableInputAudioTranscription;
            this.outputMode = outputMode == null ? OutputMode.AUDIO_AND_TEXT : outputMode;
            this.tools = tools == null ? Collections.emptyList() : new ArrayList<>(tools);
        }

        public String getApiKey() {
            return apiKey;
        }

        public String getWebSocketUrl() {
            return baseUrl + "?model=" + model;
        }

        public String getVoice() {
            return voice;
        }

        public String getInstructions() {
            return instructions;
        }

        public boolean isEnableSearch() {
            return enableSearch;
        }

        public boolean isEnableTurnDetection() {
            return enableTurnDetection;
        }

        public boolean isEnableInputAudioTranscription() {
            return enableInputAudioTranscription;
        }

        public boolean isAudioOutputEnabled() {
            return outputMode == OutputMode.AUDIO_AND_TEXT;
        }

        public boolean hasTools() {
            return !tools.isEmpty();
        }

        public List<ToolDefinition> getTools() {
            return tools;
        }
    }

    public static class ToolDefinition {
        private final String name;
        private final String description;
        private final JSONObject parameters;

        public ToolDefinition(String name, String description, JSONObject parameters) {
            this.name = name == null ? "" : name;
            this.description = description == null ? "" : description;
            this.parameters = parameters == null ? new JSONObject() : parameters;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject function = new JSONObject();
            function.put("name", name);
            function.put("description", description);
            function.put("parameters", new JSONObject(parameters.toString()));

            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("function", function);
            return tool;
        }
    }

    public interface Callback {
        void onConnected();

        void onDisconnected();

        void onError(String message);

        void onUserSpeechStarted();

        void onUserSpeechStopped();

        void onUserTranscript(String transcript);

        void onAssistantTextDelta(String delta);

        void onAssistantTranscriptDone(String transcript);

        void onToolCallRequested(String toolName, String callId, String argumentsJson);

        void onAssistantAudioDelta(byte[] pcmData);

        void onAssistantAudioDone();

        void onResponseDone();
    }
}
