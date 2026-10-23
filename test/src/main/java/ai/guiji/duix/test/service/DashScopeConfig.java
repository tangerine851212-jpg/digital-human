package ai.guiji.duix.test.service;

import ai.guiji.duix.test.BuildConfig;

public final class DashScopeConfig {

    public static final String DEFAULT_API_KEY = BuildConfig.DASHSCOPE_API_KEY;
    public static final String REALTIME_BASE_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";
    public static final String MULTIMODAL_GENERATION_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    public static final String PSYCHOLOGY_SUBAGENT_MODEL = "qwen3.6-plus";
    public static final String PSYCHOLOGY_KNOWLEDGE_ASSET_PATH = "knowledge/who_icope_2024.txt";
    public static final String VOICE_CLONE_URL = "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/customization";
    public static final String VOICE_CLONE_MODEL = "qwen-voice-enrollment";
    public static final String VOICE_CLONE_TTS_MODEL = "qwen3-tts-vc-realtime-2026-01-15";
    public static final String MONITOR_BASE_URL = "https://digital-human.xn--876a.net";
    public static final int AUDIO_SAMPLE_RATE = 24000;

    private DashScopeConfig() {
    }
}