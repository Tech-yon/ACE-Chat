package cn.acechat.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 通讯音效合成器：用代码生成写实风格的「无线电开始 / 结束通讯」音效（不依赖任何音频文件）。
 *
 * <p>写实感来自几层叠加：按键爆音（crackle）、低通后的本底噪声（对讲机底噪）、
 * 幅度抖动（AM 抖动，模拟信号不稳）、蜂鸣（带二次谐波）、以及收尾的 squelch 静电尾。
 *
 * <p>三个参数的作用：
 * <ul>
 *   <li><b>音调</b>：granular overlap-add 变调，<b>不改播放速度</b>（时长不变）；</li>
 *   <li><b>播放速度</b>：重采样，改变时长（与音调互相独立）；</li>
 *   <li><b>淡入 / 淡出 / 音量</b>：包络与响度。</li>
 * </ul>
 */
public final class CommsSoundSynth {
    /** 出现音（开始通讯）预设。 */
    public static final String[] APPEAR_PRESETS = {"radio_open", "radio_short", "morse", "scifi", "soft"};
    /** 消失音（结束通讯）预设。 */
    public static final String[] GONE_PRESETS = {"radio_close", "radio_click", "morse_down", "scifi_down", "soft_down"};

    private static final int RATE = 44100;
    private static final double ATTACK_MS = 2.5D;
    /** granular 变调的颗粒长度（毫秒）与重叠步长比例。 */
    private static final double GRAIN_MS = 42.0D;
    private static final double HOP_RATIO = 0.5D;

    private CommsSoundSynth() {
    }

    /**
     * 渲染预设。
     *
     * @param volume 0~2（1 = 100%）
     * @param pitch  音调百分比（25~400），granular 变调，<b>时长不变</b>
     * @param speed  播放速度百分比（25~400），重采样，<b>时长随之变化</b>
     */
    public static byte[] render(String preset, float volume, int pitch, int speed, int fadeInMs, int fadeOutMs) {
        double[] samples = renderPreset(preset == null ? "" : preset);
        samples = pitchShift(samples, Math.max(0.25D, Math.min(4.0D, pitch / 100.0D)));
        samples = resample(samples, Math.max(0.25D, Math.min(4.0D, speed / 100.0D)));
        applyFades(samples, fadeInMs, fadeOutMs);
        scale(samples, Math.max(0.0D, Math.min(4.0D, volume)));

        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int value = (int) Math.round(Math.max(-1.0D, Math.min(1.0D, samples[i])) * 32767.0D);
            pcm[i * 2] = (byte) (value & 0xFF);
            pcm[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
        }

        return toWav(pcm, 1, RATE);
    }

    /** 预设 → 归一化浮点样本。 */
    private static double[] renderPreset(String preset) {
        return switch (preset) {
            case "radio_short" -> join(tone(70, 1000, 0.55D), squelch(35, 0.14D));
            case "morse" -> join(tone(80, 1000, 0.5D), silence(55), tone(80, 1000, 0.5D), silence(55),
                    tone(190, 1000, 0.5D), squelch(50, 0.14D));
            case "scifi" -> join(sweep(150, 500, 1500, 0.5D), squelch(40, 0.12D));
            case "soft" -> join(tone(180, 660, 0.45D), squelch(30, 0.07D));
            // 结束通讯：蜂鸣 → 很长的 squelch 静电尾（写实对讲机收尾）
            case "radio_close" -> join(click(12, 0.22D), tone(80, 700, 0.5D), squelch(320, 0.34D));
            case "radio_click" -> join(click(20, 0.36D), squelch(90, 0.26D));
            case "morse_down" -> join(tone(190, 900, 0.45D), silence(55), tone(90, 700, 0.5D), squelch(70, 0.18D));
            case "scifi_down" -> join(sweep(150, 1200, 450, 0.5D), squelch(50, 0.13D));
            case "soft_down" -> join(tone(200, 520, 0.45D), squelch(40, 0.08D));
            // radio_open：默认的「开始通讯」——按键爆音 + 载波底噪接通 + 蜂鸣 + squelch 尾
            default -> join(
                    click(16, 0.40D),
                    carrier(170, 0.30D),
                    tone(85, 1200, 0.52D),
                    squelch(150, 0.30D)
            );
        };
    }

    // ------------------------------------------------------------------ 写实素材

    /** 按键/静音开关的爆音：很短的低通噪声脉冲。 */
    private static double[] click(double milliseconds, double amplitude) {
        double[] burst = noise(milliseconds, amplitude);
        lowPass(burst, 0.35D);
        return burst;
    }

    /** 载波底噪：低通白噪声 + AM 抖动 + 零星爆音，像对讲机接通后的底噪。 */
    private static double[] carrier(double milliseconds, double amplitude) {
        double[] base = noise(milliseconds, amplitude);
        lowPass(base, 0.20D);
        amJitter(base, 6.5D, 0.32D);
        double[] crackle = crackle(milliseconds, 0.022D, amplitude * 1.6D);
        for (int i = 0; i < base.length && i < crackle.length; i++) {
            base[i] += crackle[i];
        }

        return base;
    }

    /** squelch 静电尾：噪声 + 低通 + 衰减。 */
    private static double[] squelch(double milliseconds, double amplitude) {
        double[] tail = noise(milliseconds, amplitude);
        lowPass(tail, 0.28D);
        amJitter(tail, 3.0D, 0.20D);
        double length = Math.max(1.0D, tail.length);
        for (int i = 0; i < tail.length; i++) {
            tail[i] *= Math.pow(1.0D - i / length, 1.6D);
        }

        return tail;
    }

    /** 零星爆音（对讲机的噼啪声）。 */
    private static double[] crackle(double milliseconds, double density, double amplitude) {
        double[] out = new double[samples(milliseconds)];
        Random random = new Random(0xBEEF17L);
        for (int i = 0; i < out.length; i++) {
            if (random.nextDouble() < density) {
                double sign = random.nextBoolean() ? 1.0D : -1.0D;
                out[i] = sign * amplitude * (0.4D + random.nextDouble() * 0.6D);
            }

            if (i > 0) {
                out[i] += out[i - 1] * 0.35D;
            }
        }

        return out;
    }

    /** 一段正弦音（起停包络 + 一点二次谐波，更像蜂鸣器）。 */
    private static double[] tone(double milliseconds, double frequency, double amplitude) {
        int length = samples(milliseconds);
        double[] out = new double[length];
        double attack = Math.max(1.0D, samples(ATTACK_MS));
        double release = Math.max(1.0D, length * 0.35D);
        for (int i = 0; i < length; i++) {
            double phase = 2.0D * Math.PI * frequency * i / RATE;
            out[i] = (Math.sin(phase) * 0.82D + Math.sin(phase * 2.0D) * 0.18D) * amplitude
                    * envelope(i, length, attack, release);
        }

        return out;
    }

    /** 一段扫频。 */
    private static double[] sweep(double milliseconds, double startFrequency, double endFrequency, double amplitude) {
        int length = samples(milliseconds);
        double[] out = new double[length];
        double phase = 0.0D;
        double attack = Math.max(1.0D, samples(ATTACK_MS));
        double release = Math.max(1.0D, length * 0.35D);
        for (int i = 0; i < length; i++) {
            double progress = length <= 1 ? 0.0D : i / (double) (length - 1);
            phase += 2.0D * Math.PI * (startFrequency + (endFrequency - startFrequency) * progress) / RATE;
            out[i] = Math.sin(phase) * amplitude * envelope(i, length, attack, release);
        }

        return out;
    }

    /** 一段白噪声（两端做短包络，避免爆音）。 */
    private static double[] noise(double milliseconds, double amplitude) {
        int length = samples(milliseconds);
        double[] out = new double[length];
        Random random = new Random(0x5A17L);
        for (int i = 0; i < length; i++) {
            out[i] = (random.nextDouble() * 2.0D - 1.0D) * amplitude;
        }

        double edge = Math.max(1.0D, samples(1.5D));
        for (int i = 0; i < length && i < edge; i++) {
            double factor = i / edge;
            out[i] *= factor;
            out[length - 1 - i] *= factor;
        }

        return out;
    }

    private static double[] silence(double milliseconds) {
        return new double[samples(milliseconds)];
    }

    /** 幅度抖动（AM）：让噪声有起伏，更像真实信号。 */
    private static void amJitter(double[] samples, double rateHz, double depth) {
        for (int i = 0; i < samples.length; i++) {
            double wobble = 1.0D - depth * 0.5D * (1.0D - Math.sin(2.0D * Math.PI * rateHz * i / RATE));
            samples[i] *= wobble;
        }
    }

    /** 单极点低通（cutoffRatio 越小越闷，像对讲机小喇叭）。 */
    private static void lowPass(double[] samples, double cutoffRatio) {
        double alpha = Math.max(0.01D, Math.min(1.0D, cutoffRatio));
        double previous = 0.0D;
        for (int i = 0; i < samples.length; i++) {
            previous += alpha * (samples[i] - previous);
            samples[i] = previous;
        }
    }

    private static double envelope(int index, int length, double attack, double release) {
        return Math.max(0.0D, Math.min(1.0D, Math.min(index / attack, (length - index) / release)));
    }

    // ------------------------------------------------------------------ 变调 / 变速

    /**
     * 音调平移（granular overlap-add）：<b>时长不变</b>，只改音高。
     * 把声音切成带 Hann 窗的颗粒，每颗按比例重采样后按原步长重叠相加。
     */
    /** 对外：不变速变调（给自定义音频文件复用）。 */
    static double[] shiftPitch(double[] input, double ratio) {
        return pitchShift(input, ratio);
    }

    /** 对外：变速（重采样，给自定义音频文件复用）。 */
    static double[] changeSpeed(double[] input, double ratio) {
        return resample(input, ratio);
    }

    private static double[] pitchShift(double[] input, double ratio) {
        if (Math.abs(ratio - 1.0D) < 0.005D || input.length < 64) {
            return input;
        }

        int grain = Math.max(32, samples(GRAIN_MS));
        int hop = Math.max(16, (int) (grain * HOP_RATIO));
        double[] out = new double[input.length + grain];
        double[] window = new double[grain];
        for (int i = 0; i < grain; i++) {
            window[i] = 0.5D - 0.5D * Math.cos(2.0D * Math.PI * i / (grain - 1.0D));
        }

        for (int start = 0; start + grain < input.length; start += hop) {
            for (int i = 0; i < grain; i++) {
                double source = start + i * ratio;
                int index = (int) source;
                double fraction = source - index;
                double a = index < input.length ? input[index] : 0.0D;
                double b = index + 1 < input.length ? input[index + 1] : a;
                int at = start + i;
                if (at < out.length) {
                    out[at] += (a + (b - a) * fraction) * window[i];
                }
            }
        }

        // 重叠相加会整体放大，按 hop/grain 比例回调
        double compensate = hop / (double) grain * 2.0D;
        double[] result = new double[input.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = out[i] * compensate;
        }

        return result;
    }

    /** 播放速度：重采样（>100% 变快变短，<100% 变慢变长）。 */
    private static double[] resample(double[] input, double ratio) {
        if (Math.abs(ratio - 1.0D) < 0.005D) {
            return input;
        }

        int length = Math.max(1, (int) Math.round(input.length / ratio));
        double[] out = new double[length];
        for (int i = 0; i < length; i++) {
            double position = i * ratio;
            int index = (int) position;
            double fraction = position - index;
            double a = index < input.length ? input[index] : 0.0D;
            double b = index + 1 < input.length ? input[index + 1] : a;
            out[i] = a + (b - a) * fraction;
        }

        return out;
    }

    /** 淡入淡出（毫秒）。 */
    private static void applyFades(double[] samples, int fadeInMs, int fadeOutMs) {
        int fadeIn = Math.min(samples.length, samples(Math.max(0, fadeInMs)));
        int fadeOut = Math.min(samples.length, samples(Math.max(0, fadeOutMs)));
        for (int i = 0; i < fadeIn; i++) {
            samples[i] *= i / (double) Math.max(1, fadeIn);
        }

        for (int i = 0; i < fadeOut; i++) {
            int index = samples.length - 1 - i;
            if (index >= 0) {
                samples[index] *= i / (double) Math.max(1, fadeOut);
            }
        }
    }

    private static void scale(double[] samples, double factor) {
        for (int i = 0; i < samples.length; i++) {
            samples[i] *= factor;
        }
    }

    private static int samples(double milliseconds) {
        return Math.max(1, (int) Math.round(milliseconds * RATE / 1000.0D));
    }

    private static double[] join(double[]... parts) {
        List<double[]> list = new ArrayList<>();
        int total = 0;
        for (double[] part : parts) {
            list.add(part);
            total += part.length;
        }

        double[] result = new double[total];
        int offset = 0;
        for (double[] part : list) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }

        return result;
    }

    /** 16 位 PCM → WAV 字节。 */
    public static byte[] toWav(byte[] pcm, int channels, int sampleRate) {
        int dataSize = pcm.length;
        byte[] header = new byte[44];
        ascii(header, 0, "RIFF");
        int32(header, 4, 36 + dataSize);
        ascii(header, 8, "WAVE");
        ascii(header, 12, "fmt ");
        int32(header, 16, 16);
        int16(header, 20, 1);
        int16(header, 22, channels);
        int32(header, 24, sampleRate);
        int32(header, 28, sampleRate * channels * 2);
        int16(header, 32, channels * 2);
        int16(header, 34, 16);
        ascii(header, 36, "data");
        int32(header, 40, dataSize);

        byte[] result = new byte[44 + dataSize];
        System.arraycopy(header, 0, result, 0, 44);
        System.arraycopy(pcm, 0, result, 44, dataSize);
        return result;
    }

    private static void ascii(byte[] target, int offset, String value) {
        for (int i = 0; i < value.length(); i++) {
            target[offset + i] = (byte) value.charAt(i);
        }
    }

    private static void int32(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void int16(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }
}
