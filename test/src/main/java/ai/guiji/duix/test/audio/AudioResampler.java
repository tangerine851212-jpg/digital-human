package ai.guiji.duix.test.audio;

/**
 * 简单线性插值重采样：24kHz 16bit mono -> 16kHz 16bit mono
 * 用于将 Qwen-Omni-Realtime 输出的 24kHz 音频转换为 DUIX 所需的 16kHz
 */
public class AudioResampler {

    private static final int INPUT_RATE = 24000;
    private static final int OUTPUT_RATE = 16000;
    private static final double RATIO = (double) OUTPUT_RATE / INPUT_RATE; // 2/3

    /**
     * 将 24kHz 16bit 单声道 PCM 重采样为 16kHz
     *
     * @param input 24kHz 16bit mono PCM
     * @return 16kHz 16bit mono PCM
     */
    public static byte[] resample24kTo16k(byte[] input) {
        if (input == null || input.length < 2) return new byte[0];

        int inputSamples = input.length / 2; // 16bit = 2 bytes per sample
        int outputSamples = (int) Math.ceil(inputSamples * RATIO);

        byte[] output = new byte[outputSamples * 2];

        for (int i = 0; i < outputSamples; i++) {
            double srcPos = i / RATIO;
            int idx0 = (int) Math.floor(srcPos);
            int idx1 = Math.min(idx0 + 1, inputSamples - 1);
            double frac = srcPos - idx0;

            short s0 = getShortLE(input, idx0 * 2);
            short s1 = getShortLE(input, idx1 * 2);
            short interpolated = (short) (s0 + (s1 - s0) * frac);
            putShortLE(output, i * 2, interpolated);
        }
        return output;
    }

    private static short getShortLE(byte[] data, int offset) {
        if (offset + 1 >= data.length) return 0;
        return (short) ((data[offset] & 0xFF) | (data[offset + 1] << 8));
    }

    private static void putShortLE(byte[] data, int offset, short value) {
        if (offset + 1 < data.length) {
            data[offset] = (byte) (value & 0xFF);
            data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        }
    }
}
