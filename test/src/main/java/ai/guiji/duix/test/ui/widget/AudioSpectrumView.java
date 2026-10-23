package ai.guiji.duix.test.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 淡雅音频频谱可视化 View
 * 支持两种模式：
 * 1. 麦克风模式 — 用真实 PCM 数据驱动频谱柱
 * 2. AI 说话模式 — 柔和呼吸脉动动画（与数字人口型同步，由 SDK 回调驱动）
 */
public class AudioSpectrumView extends View {

    private static final int BAR_COUNT = 32;
    private static final float SMOOTHING = 0.35f;
    private static final float DECAY = 0.92f;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] magnitudes = new float[BAR_COUNT];
    private final float[] displayMagnitudes = new float[BAR_COUNT];
    private LinearGradient micGradient;
    private LinearGradient aiGradient;
    private int lastWidth = 0;
    private int lastHeight = 0;

    /** AI 说话脉动状态 */
    private boolean aiSpeaking = false;
    private long aiSpeakStartTime = 0;

    public AudioSpectrumView(Context context) {
        super(context);
        init();
    }

    public AudioSpectrumView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AudioSpectrumView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        barPaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setAlpha(40);
    }

    /**
     * 接收 PCM 16bit mono 音频数据（麦克风），提取频谱能量
     */
    public void updateAudio(byte[] pcmData) {
        if (pcmData == null || pcmData.length < 4) return;
        if (aiSpeaking) return; // AI 说话时忽略麦克风数据

        int sampleCount = pcmData.length / 2;
        int samplesPerBar = Math.max(1, sampleCount / BAR_COUNT);

        for (int i = 0; i < BAR_COUNT; i++) {
            long sum = 0;
            int start = i * samplesPerBar;
            int end = Math.min(start + samplesPerBar, sampleCount);
            for (int j = start; j < end; j++) {
                int idx = j * 2;
                if (idx + 1 >= pcmData.length) break;
                short sample = (short) ((pcmData[idx] & 0xFF) | (pcmData[idx + 1] << 8));
                sum += Math.abs(sample);
            }
            float avg = (float) sum / Math.max(1, end - start);
            float normalized = Math.min(1f, avg / 8000f);
            float boost = 1f + (float) i / BAR_COUNT * 1.0f;
            magnitudes[i] = Math.min(1f, normalized * boost);
        }

        postInvalidateOnAnimation();
    }

    /** 通知 AI 数字人开始说话（由 DUIX SDK AUDIO_PLAY_START 回调触发） */
    public void setAiSpeaking(boolean speaking) {
        this.aiSpeaking = speaking;
        if (speaking) {
            aiSpeakStartTime = System.currentTimeMillis();
            // 清除麦克风残留数据
            for (int i = 0; i < BAR_COUNT; i++) {
                magnitudes[i] = 0f;
            }
        }
        postInvalidateOnAnimation();
    }

    public boolean isAiSpeaking() {
        return aiSpeaking;
    }

    /** 清除频谱 */
    public void clear() {
        aiSpeaking = false;
        for (int i = 0; i < BAR_COUNT; i++) {
            magnitudes[i] = 0f;
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        if (micGradient == null || lastWidth != w || lastHeight != h) {
            lastWidth = w;
            lastHeight = h;
            // 麦克风：暖橙渐变
            micGradient = new LinearGradient(
                    0, h, 0, 0,
                    new int[]{0x00E8926F, 0x66E8926F, 0x99F0B89A},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP
            );
            // AI 说话：柔和蓝紫渐变
            aiGradient = new LinearGradient(
                    0, h, 0, 0,
                    new int[]{0x009BB5E8, 0x669BB5E8, 0x99C4B5F0},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP
            );
        }

        // 选择渐变色
        LinearGradient activeGradient = aiSpeaking ? aiGradient : micGradient;
        barPaint.setShader(activeGradient);
        glowPaint.setShader(activeGradient);

        float totalBarWidth = w * 0.85f;
        float sideMargin = (w - totalBarWidth) / 2f;
        float gap = totalBarWidth * 0.02f;
        float barWidth = (totalBarWidth - gap * (BAR_COUNT - 1)) / BAR_COUNT;
        float cornerRadius = barWidth / 2f;
        float maxBarHeight = h * 0.96f;
        float minBarHeight = h * 0.05f;

        if (aiSpeaking) {
            drawAiPulse(canvas, w, h, sideMargin, gap, barWidth, cornerRadius, maxBarHeight, minBarHeight);
        } else {
            drawMicSpectrum(canvas, h, sideMargin, gap, barWidth, cornerRadius, maxBarHeight, minBarHeight);
        }
    }

    private void drawMicSpectrum(Canvas canvas, int h, float sideMargin, float gap,
                                  float barWidth, float cornerRadius, float maxBarHeight, float minBarHeight) {
        boolean hasActivity = false;
        for (int i = 0; i < BAR_COUNT; i++) {
            float target = magnitudes[i];
            if (target > displayMagnitudes[i]) {
                displayMagnitudes[i] += (target - displayMagnitudes[i]) * (1f - SMOOTHING);
            } else {
                displayMagnitudes[i] *= DECAY;
            }

            float barH = minBarHeight + displayMagnitudes[i] * maxBarHeight;
            float left = sideMargin + i * (barWidth + gap);
            float top = h - barH;
            float right = left + barWidth;

            canvas.drawRoundRect(left - 1, top - 2, right + 1, h, cornerRadius, cornerRadius, glowPaint);
            canvas.drawRoundRect(left, top, right, h, cornerRadius, cornerRadius, barPaint);

            if (displayMagnitudes[i] > 0.001f) hasActivity = true;
        }
        if (hasActivity) {
            postInvalidateOnAnimation();
        }
    }

    private void drawAiPulse(Canvas canvas, int w, int h, float sideMargin, float gap,
                              float barWidth, float cornerRadius, float maxBarHeight, float minBarHeight) {
        float elapsed = (System.currentTimeMillis() - aiSpeakStartTime) / 1000f;
        float center = BAR_COUNT / 2f;

        for (int i = 0; i < BAR_COUNT; i++) {
            // 多重正弦波叠加，模拟自然呼吸感
            float dist = Math.abs(i - center) / center; // 0~1, 离中心的距离
            float wave1 = (float) Math.sin(elapsed * 2.5 - dist * 3.0) * 0.5f + 0.5f;
            float wave2 = (float) Math.sin(elapsed * 1.7 + i * 0.4) * 0.3f + 0.5f;
            float wave3 = (float) Math.sin(elapsed * 4.0 - i * 0.2) * 0.15f + 0.5f;

            // 中间高两边低的包络
            float envelope = 1f - dist * 0.6f;
            float target = (wave1 * 0.5f + wave2 * 0.3f + wave3 * 0.2f) * envelope;
            target = Math.max(0.05f, Math.min(1f, target));

            // 平滑过渡
            displayMagnitudes[i] += (target - displayMagnitudes[i]) * 0.15f;

            float barH = minBarHeight + displayMagnitudes[i] * maxBarHeight * 0.9f;
            float left = sideMargin + i * (barWidth + gap);
            float top = h - barH;
            float right = left + barWidth;

            canvas.drawRoundRect(left - 1, top - 2, right + 1, h, cornerRadius, cornerRadius, glowPaint);
            canvas.drawRoundRect(left, top, right, h, cornerRadius, cornerRadius, barPaint);
        }
        postInvalidateOnAnimation();
    }
}
