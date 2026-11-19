package cn.acechat.client;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.Obuffer;
import javazoom.jl.decoder.SampleBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 自定义通讯音效：把音频文件放进 {@code config/acechat/sounds/} 就能替换内置音效。
 *
 * <p>文件名（放哪一个就用哪一个，两个可以分别替换）：
 * <ul>
 *   <li>{@code new_message.mp3|wav} —— 刚收到/发出新聊天消息时；</li>
 *   <li>{@code message_gone.mp3|wav} —— 那条聊天消息从屏幕上消失时。</li>
 * </ul>
 *
 * <p>格式要求（两种任选）：
 * <ul>
 *   <li><b>.mp3</b>：任意常见码率/采样率（模组内置 JLayer 解码器，转成 16 位 PCM 后播放）；</li>
 *   <li><b>.wav</b>：16 位 PCM，单声道或立体声，8k~48k 采样率。</li>
 * </ul>
 * 时长超过 {@value #MAX_SECONDS} 秒会截断（避免盖住后面的消息）。文件改完**不用重启游戏**，
 * 下次播放时会按修改时间自动重新读取。
 *
 * <p>没有自定义文件时，用 {@link CommsSoundSynth} **程序合成**无线电风格的音效（预设可选）：
 * 音量、音调、淡入、淡出四个参数对自定义文件与合成音一视同仁。
 * 播放走 {@code javax.sound.sampled}；总音量 = 本条音量 × 模组音量 × 原版「玩家音效」音量。
 */
public final class CustomSounds {
    private static final Logger LOGGER = LoggerFactory.getLogger("acechat");
    public static final int MAX_SECONDS = 10;

    private static final Map<String, Cached> CACHE = new HashMap<>();
    private static boolean loggedFailure;

    private CustomSounds() {
    }

    /** 自定义音效目录。 */
    public static Path soundDir() {
        return AceChatFolders.soundsDir();
    }

    /** 目录里现有的音频文件（界面里显示用）。 */
    public static String describeFiles() {
        StringBuilder builder = new StringBuilder();
        for (String base : new String[]{"new_message", "message_gone"}) {
            Path found = findFile(base);
            builder.append(base).append(found == null ? "=内置" : "=" + found.getFileName());
            if (!"message_gone".equals(base)) {
                builder.append("  ");
            }
        }

        return builder.toString();
    }

    /**
     * 播放通讯音效：有自定义文件就用文件，否则用程序合成的预设。
     *
     * @param baseName  new_message / message_gone
     * @param preset    合成预设 id（没有自定义文件时生效）
     * @param volume    本条音量（0~2，1 = 100%）
     * @param pitch     音调百分比（50~200），granular 变调，时长不变
     * @param speed     播放速度百分比（25~400），重采样，改变时长
     * @param fadeInMs  淡入毫秒
     * @param fadeOutMs 淡出毫秒
     * @return true = 已经播放（文件或合成音）；false = 都没成功（调用方回落到原版 SoundEvent）
     */
    public static boolean play(String baseName, String preset, float volume, int pitch, int speed, int fadeInMs, int fadeOutMs) {
        try {
            Pcm pcm = decodeFile(baseName);
            BaseSound sound = pcm != null
                    ? new BaseSound(pcm.data(), pcm.channels(), pcm.sampleRate())
                    : null;
            if (sound == null) {
                byte[] wav = CommsSoundSynth.render(preset, 1.0F, 100, 100, 0, 0);
                AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
                AudioFormat format = stream.getFormat();
                byte[] raw = stream.readAllBytes();
                stream.close();
                if (raw.length == 0) {
                    return false;
                }

                sound = new BaseSound(raw, format.getChannels(), (int) format.getSampleRate());
            }

            byte[] processed = applyEffects(sound, volume * sourceVolume(), pitch, speed, fadeInMs, fadeOutMs);
            AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(
                    CommsSoundSynth.toWav(processed, sound.channels(), sound.sampleRate())));
            Clip clip = AudioSystem.getClip();
            clip.open(stream);
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    clip.close();
                }
            });
            clip.start();
            return true;
        } catch (Exception | LinkageError error) {
            if (!loggedFailure) {
                loggedFailure = true;
                LOGGER.warn("[acechat] 通讯音效播放失败，已回落到原版音效（检查音效文件是否为 mp3/wav 且未损坏）", error);
            }

            return false;
        }
    }

    /**
     * 音调（granular 变调，<b>时长不变</b>）→ 播放速度（重采样，改变时长）→ 淡入淡出 → 音量。
     * 按帧处理，兼容单/立体声。
     */
    private static byte[] applyEffects(BaseSound sound, float volume, int pitchPercent, int speedPercent,
                                       int fadeInMs, int fadeOutMs) {
        int channels = Math.max(1, sound.channels());
        double[] frames = toFrames(sound.data(), channels);
        if (frames.length == 0) {
            return sound.data();
        }

        double pitch = Math.max(0.25D, Math.min(4.0D, pitchPercent / 100.0D));
        double speed = Math.max(0.25D, Math.min(4.0D, speedPercent / 100.0D));
        frames = CommsSoundSynth.shiftPitch(frames, pitch);      // 音调：时长不变
        frames = CommsSoundSynth.changeSpeed(frames, speed);     // 速度：时长随之变化

        int outFrames = frames.length;
        int fadeIn = Math.min(outFrames, (int) Math.round(Math.max(0, fadeInMs) * sound.sampleRate() / 1000.0D));
        int fadeOut = Math.min(outFrames, (int) Math.round(Math.max(0, fadeOutMs) * sound.sampleRate() / 1000.0D));
        byte[] out = new byte[outFrames * channels * 2];
        for (int frame = 0; frame < outFrames; frame++) {
            double envelope = volume;
            if (fadeIn > 0 && frame < fadeIn) {
                envelope *= frame / (double) fadeIn;
            }

            if (fadeOut > 0 && frame >= outFrames - fadeOut) {
                envelope *= (outFrames - frame) / (double) fadeOut;
            }

            int value = (int) Math.max(-32768.0D, Math.min(32767.0D,
                    Math.round(frames[frame] * envelope * 32767.0D)));
            for (int channel = 0; channel < channels; channel++) {
                int at = (frame * channels + channel) * 2;
                out[at] = (byte) (value & 0xFF);
                out[at + 1] = (byte) ((value >> 8) & 0xFF);
            }
        }

        return out;
    }

    /** 把交错的 16 位 PCM 转成「每帧一个值」的数组（多声道取平均，音效够用且更省）。 */
    private static double[] toFrames(byte[] data, int channels) {
        int frames = data.length / (channels * 2);
        double[] out = new double[Math.max(0, frames)];
        for (int frame = 0; frame < frames; frame++) {
            double sum = 0.0D;
            for (int channel = 0; channel < channels; channel++) {
                sum += readSample(data, channels, frame, channel);
            }

            out[frame] = sum / channels;
        }

        return out;
    }

    private static double readSample(byte[] data, int channels, int frame, int channel) {
        int at = (frame * channels + channel) * 2;
        if (frame < 0 || at + 1 >= data.length) {
            return 0.0D;
        }

        return (short) ((data[at] & 0xFF) | (data[at + 1] << 8)) / 32767.0D;
    }

    /** 读到的自定义音频（已转成 16 位 PCM）。 */
    private record Pcm(byte[] data, int channels, int sampleRate) {
    }

    private record BaseSound(byte[] data, int channels, int sampleRate) {
    }

    /** 模组音量 × 原版「玩家音效」音量。 */
    private static float sourceVolume() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.options != null) {
                return Math.max(0.0F, Math.min(1.0F, minecraft.options.getSoundSourceVolume(SoundSource.PLAYERS)));
            }
        } catch (RuntimeException ignored) {
            // 忽略
        }

        return 1.0F;
    }

    private static void applyGain(Clip clip, float volume) {
        float clamped = Math.max(0.0F, Math.min(1.0F, volume));
        try {
            FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            float db = (float) (20.0D * Math.log10(Math.max(0.0001D, clamped)));
            gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
        } catch (RuntimeException ignored) {
            // 没有音量控制就按原始音量播放
        }
    }

    /** 找到 {@code baseName.mp3} 或 {@code baseName.wav}。 */
    private static Path findFile(String baseName) {
        Path dir = soundDir();
        for (String extension : new String[]{".mp3", ".wav"}) {
            Path path = dir.resolve(baseName + extension);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }

        return null;
    }

    /** 取（必要时解码并缓存）自定义音频：mp3 用 JLayer 解码，wav 直接读。 */
    private static Pcm decodeFile(String baseName) throws Exception {
        Path path = findFile(baseName);
        if (path == null) {
            return null;
        }

        long modified = Files.getLastModifiedTime(path).toMillis();
        Cached cached = CACHE.get(baseName);
        if (cached != null && cached.path().equals(path) && cached.modified() == modified) {
            return cached.pcm();
        }

        byte[] raw = Files.readAllBytes(path);
        Pcm pcm = path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp3")
                ? decodeMp3(raw)
                : readWav(raw);
        if (pcm == null) {
            return null;
        }

        CACHE.put(baseName, new Cached(path, modified, pcm));
        return pcm;
    }

    /** 直接读 WAV：交给 javax.sound 解析格式，再统一转成 16 位 PCM。 */
    private static Pcm readWav(byte[] raw) {
        try {
            AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(raw));
            AudioFormat format = stream.getFormat();
            byte[] data = stream.readAllBytes();
            stream.close();
            int channels = Math.max(1, format.getChannels());
            int rate = Math.max(8000, (int) format.getSampleRate());
            int maxBytes = rate * channels * 2 * MAX_SECONDS;
            if (data.length > maxBytes) {
                byte[] trimmed = new byte[maxBytes];
                System.arraycopy(data, 0, trimmed, 0, maxBytes);
                data = trimmed;
            }

            return data.length == 0 ? null : new Pcm(data, channels, rate);
        } catch (Exception error) {
            LOGGER.warn("[acechat] WAV 读取失败（该文件已忽略，使用合成音效）", error);
            return null;
        }
    }

    /** MP3 → 16 位 PCM（用内置的 JLayer 解码器逐帧解码）。 */
    private static Pcm decodeMp3(byte[] mp3) {
        Bitstream bitstream = null;
        try {
            bitstream = new Bitstream(new ByteArrayInputStream(mp3));
            Decoder decoder = new Decoder();
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            int channels = 2;
            int sampleRate = 44100;
            int budget = 0;

            while (true) {
                Header header = bitstream.readFrame();
                if (header == null) {
                    break;
                }

                Obuffer buffer = decoder.decodeFrame(header, bitstream);
                if (buffer instanceof SampleBuffer samples) {
                    channels = Math.max(1, samples.getChannelCount());
                    sampleRate = Math.max(8000, samples.getSampleFrequency());
                    budget = Math.max(budget, sampleRate * channels * 2 * MAX_SECONDS);

                    short[] data = samples.getBuffer();
                    int count = Math.min(samples.getBufferLength(), data.length);
                    for (int i = 0; i < count; i++) {
                        short value = data[i];
                        pcm.write(value & 0xFF);
                        pcm.write((value >> 8) & 0xFF);
                    }
                }

                bitstream.closeFrame();
                if (budget > 0 && pcm.size() >= budget) {
                    break;   // 超过时长上限就截断
                }
            }

            byte[] data = pcm.toByteArray();
            if (data.length == 0) {
                return null;
            }

            LOGGER.info("[acechat] 已解码自定义 MP3 音效：{}Hz {}声道 {}ms", sampleRate, channels, data.length / 2 / channels * 1000 / sampleRate);
            return new Pcm(data, channels, sampleRate);
        } catch (Exception | LinkageError error) {
            LOGGER.warn("[acechat] MP3 解码失败（该文件已忽略，使用内置音效）", error);
            return null;
        } finally {
            if (bitstream != null) {
                try {
                    bitstream.close();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        }
    }

    private record Cached(Path path, long modified, Pcm pcm) {
    }
}
