package ai.guiji.duix.test.audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式麦克风录音器，用于实时将音频发送到 OmniRealtime API
 * 16kHz, 16bit, mono - 符合 API 输入格式要求
 */
public class StreamingAudioRecorder {

    private static final String TAG = "StreamingAudioRecorder";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    // 20ms 一帧: 16000 * 2 * 0.02 = 640 bytes, 使用 3200 与文档示例一致(100ms)
    private static final int CHUNK_SIZE = 3200;

    private final Context mContext;
    private AudioRecord mAudioRecord;
    private final Executor mExecutor = Executors.newSingleThreadExecutor();
    private final StreamingCallback callback;
    private final AtomicBoolean isRecording = new AtomicBoolean(false);

    public StreamingAudioRecorder(Context context, StreamingCallback callback) {
        this.mContext = context;
        this.callback = callback;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        if (isRecording.get()) {
            Log.w(TAG, "Already recording");
            return;
        }
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = Math.max(minBufferSize, CHUNK_SIZE * 2);

        mAudioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
        );

        if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed");
            if (callback != null) {
                callback.onError("麦克风初始化失败");
            }
            return;
        }

        isRecording.set(true);
        mAudioRecord.startRecording();

        mExecutor.execute(() -> {
            byte[] buffer = new byte[CHUNK_SIZE];
            while (isRecording.get() && mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                int read = mAudioRecord.read(buffer, 0, buffer.length);
                if (read > 0 && callback != null) {
                    byte[] data = new byte[read];
                    System.arraycopy(buffer, 0, data, 0, read);
                    callback.onAudioData(data);
                }
            }
            Log.d(TAG, "Recording thread ended");
        });
    }

    public void stop() {
        isRecording.set(false);
        if (mAudioRecord != null) {
            if (mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                mAudioRecord.stop();
            }
        }
    }

    public void release() {
        stop();
        if (mAudioRecord != null) {
            mAudioRecord.release();
            mAudioRecord = null;
        }
    }

    public boolean isRecording() {
        return isRecording.get();
    }

    public interface StreamingCallback {
        void onAudioData(byte[] pcmData);

        void onError(String message);
    }
}
