package com.ptport.android;

import android.content.Context;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The main menu's sound: a low drone that loops, the buzz of the flickering bulb, now and then a creak of wood, and now and then
 * footsteps on a wooden floor in another room. The files are res/raw/menu_*.wav, made by tools/android/menu_sounds.py.
 *
 * One thread mixes everything into one AudioTrack, so that the buzz can follow the bulb sample by sample: MenuBackdrop tells it the
 * bulb's brightness on every frame (setLight), the buzz is loud while the bulb is stuttering and almost gone while it is out, and the
 * bulb's dips and returns add a pop and a snap. The sound fades in when the menu comes up and out when it goes away.
 */
final class MenuSound {
    private static final int RATE = 22050;
    // frames mixed at a time: 11.6 ms, the finest the buzz follows the bulb at
    private static final int BLOCK = 256;
    private static final int POP = 1;
    private static final int ZAP = 2;
    private static final float MUSIC_GAIN = 0.42f;
    private static final float HUM_GAIN = 0.045f;
    private static final float BUZZ_GAIN = 0.55f;

    /** Everything decoded, kept for the rest of the run: about 5 MB. */
    private static final class Samples {
        short[] music;   // stereo, interleaved
        short[] buzz;    // mono
        short[][] zaps;
        short[][] pops;
        short[][] creaks;
        short[][] steps;
    }

    private static final class Voice {
        final short[] data;
        final float gain;
        final float panFrom;
        final float panTo;
        int position;

        Voice(short[] data, float gain, float panFrom, float panTo) {
            this.data = data;
            this.gain = gain;
            this.panFrom = panFrom;
            this.panTo = panTo;
        }
    }

    private static Samples samples;
    // where the music was when the menu last went away: it goes on from there
    private static int musicFrame = -1;

    private final Context context;
    private final ConcurrentLinkedQueue<Integer> events = new ConcurrentLinkedQueue<Integer>();
    private volatile float light = 1f;
    private long lastPop;
    private long lastZap;
    private Run current;

    MenuSound(Context context) {
        this.context = context.getApplicationContext();
    }

    /** The sound begins (and fades in). Does nothing when it is already going. */
    void start() {
        if (current != null) return;
        Run run = new Run();
        current = run;
        Thread thread = new Thread(run, "menu-sound");
        thread.start();
    }

    /** The sound fades out and ends; the thread lets the audio go by itself. */
    void stop() {
        Run run = current;
        current = null;
        if (run != null) run.stopRequested = true;
    }

    /** The bulb's brightness now (1 is full): the buzz follows it, and a fall or a return makes a pop or a snap. */
    void setLight(float level) {
        float before = light;
        light = level;
        if (current == null) return;
        long now = SystemClock.uptimeMillis();
        if (before >= 0.7f && level < 0.5f && now - lastPop > 70) {
            lastPop = now;
            events.add(POP);
        } else if (before < 0.5f && level >= 0.7f && now - lastZap > 70) {
            lastZap = now;
            events.add(ZAP);
        }
    }

    // ---- the mixer ----

    private final class Run implements Runnable {
        volatile boolean stopRequested;

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            try {
                mix(this);
            } catch (RuntimeException | OutOfMemoryError ignored) {
                // no sound is better than no menu
            }
        }
    }

    private void mix(Run run) {
        Samples all = load(context.getResources());
        if (all == null || run.stopRequested) return;
        int minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(minimum, BLOCK * 4 * 6))
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            return;
        }
        Random random = new Random();
        List<Voice> voices = new ArrayList<Voice>();
        short[] out = new short[BLOCK * 2];
        float[] left = new float[BLOCK];
        float[] right = new float[BLOCK];
        int musicFrames = all.music.length / 2;
        int musicAt = musicFrame >= 0 && musicFrame < musicFrames ? musicFrame : random.nextInt(musicFrames);
        int buzzAt = random.nextInt(all.buzz.length);
        float buzzGain = 0f;
        float master = 0f;
        long now = SystemClock.uptimeMillis();
        long nextCreak = now + 5000 + random.nextInt(9000);
        long nextSteps = now + 20000 + random.nextInt(25000);
        track.play();
        try {
            while (true) {
                if (run.stopRequested) {
                    master -= BLOCK / (0.35f * RATE);
                    if (master <= 0f) break;
                } else {
                    master = Math.min(1f, master + BLOCK / (2.5f * RATE));
                }
                now = SystemClock.uptimeMillis();

                Integer event;
                while ((event = events.poll()) != null) {
                    boolean pop = event == POP;
                    short[][] pool = pop ? all.pops : all.zaps;
                    voices.add(new Voice(pool[random.nextInt(pool.length)], pop ? 0.4f : 0.6f, random.nextFloat() * 0.5f - 0.25f, 0f));
                }
                if (!run.stopRequested && now >= nextCreak) {
                    float pan = random.nextFloat() * 1.6f - 0.8f;
                    voices.add(new Voice(all.creaks[random.nextInt(all.creaks.length)], 0.28f + random.nextFloat() * 0.2f, pan, pan));
                    nextCreak = now + 9000 + random.nextInt(15000);
                }
                if (!run.stopRequested && now >= nextSteps) {
                    float from = (random.nextBoolean() ? 1f : -1f) * (0.3f + random.nextFloat() * 0.5f);
                    voices.add(new Voice(all.steps[random.nextInt(all.steps.length)], 0.6f, from, random.nextFloat() * 1.6f - 0.8f));
                    nextSteps = now + 35000 + random.nextInt(45000);
                }

                // the buzz: a faint hum while the bulb is steady, loud while it is stuttering, almost nothing while it is out
                float level = light;
                float deviation = Math.min(1f, Math.abs(1f - level) * 1.6f);
                float buzzTarget = level < 0.1f ? 0.004f : HUM_GAIN + BUZZ_GAIN * deviation;

                for (int i = 0; i < BLOCK; ++i) {
                    float rise = buzzTarget > buzzGain ? 0.02f : 0.004f;
                    buzzGain += (buzzTarget - buzzGain) * rise;
                    float hum = all.buzz[buzzAt] * buzzGain;
                    if (++buzzAt >= all.buzz.length) buzzAt = 0;
                    float l = all.music[musicAt * 2] * MUSIC_GAIN + hum;
                    float r = all.music[musicAt * 2 + 1] * MUSIC_GAIN + hum;
                    if (++musicAt >= musicFrames) musicAt = 0;
                    left[i] = l;
                    right[i] = r;
                }
                for (int v = voices.size() - 1; v >= 0; --v) {
                    Voice voice = voices.get(v);
                    int length = voice.data.length;
                    for (int i = 0; i < BLOCK && voice.position < length; ++i, ++voice.position) {
                        float s = voice.data[voice.position] * voice.gain;
                        float pan = voice.panFrom + (voice.panTo - voice.panFrom) * voice.position / length;
                        // pan -1 is all in the left ear, +1 all in the right; the far ear keeps a little
                        left[i] += s * ((1f - pan) * 0.5f + 0.35f);
                        right[i] += s * ((1f + pan) * 0.5f + 0.35f);
                    }
                    if (voice.position >= length) voices.remove(v);
                }
                for (int i = 0; i < BLOCK; ++i) {
                    out[i * 2] = clip(left[i] * master);
                    out[i * 2 + 1] = clip(right[i] * master);
                }
                if (track.write(out, 0, out.length) < 0) break;
            }
        } finally {
            musicFrame = musicAt;
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
                // already stopped
            }
            track.release();
        }
    }

    private static short clip(float sample) {
        return (short) Math.max(-32000f, Math.min(32000f, sample));
    }

    // ---- the files ----

    private static synchronized Samples load(Resources resources) {
        if (samples != null) return samples;
        try {
            Samples loaded = new Samples();
            loaded.music = wav(resources, R.raw.menu_music);
            loaded.buzz = wav(resources, R.raw.menu_buzz);
            loaded.zaps = new short[][] { wav(resources, R.raw.menu_zap_1), wav(resources, R.raw.menu_zap_2), wav(resources, R.raw.menu_zap_3) };
            loaded.pops = new short[][] { wav(resources, R.raw.menu_pop_1), wav(resources, R.raw.menu_pop_2) };
            loaded.creaks = new short[][] {
                wav(resources, R.raw.menu_creak_1), wav(resources, R.raw.menu_creak_2), wav(resources, R.raw.menu_creak_3),
                wav(resources, R.raw.menu_creak_4), wav(resources, R.raw.menu_creak_5), wav(resources, R.raw.menu_creak_6) };
            loaded.steps = new short[][] {
                wav(resources, R.raw.menu_steps_1), wav(resources, R.raw.menu_steps_2), wav(resources, R.raw.menu_steps_3),
                wav(resources, R.raw.menu_steps_4) };
            if (loaded.music.length < 4 || loaded.buzz.length < 2) return null;
            // the music is stereo: an odd count of samples would put the channels out of step
            if (loaded.music.length % 2 != 0) return null;
            samples = loaded;
            return loaded;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The samples of a 16 bit PCM WAV file (the channels interleaved, as they are in the file). */
    private static short[] wav(Resources resources, int id) throws IOException {
        byte[] bytes;
        InputStream in = resources.openRawResource(id);
        try {
            ByteArrayOutputStream all = new ByteArrayOutputStream(1 << 16);
            byte[] chunk = new byte[1 << 16];
            int n;
            while ((n = in.read(chunk)) > 0) all.write(chunk, 0, n);
            bytes = all.toByteArray();
        } finally {
            in.close();
        }
        int at = 12;
        while (at + 8 <= bytes.length) {
            int length = (bytes[at + 4] & 0xFF) | (bytes[at + 5] & 0xFF) << 8 | (bytes[at + 6] & 0xFF) << 16 | (bytes[at + 7] & 0xFF) << 24;
            boolean data = bytes[at] == 'd' && bytes[at + 1] == 'a' && bytes[at + 2] == 't' && bytes[at + 3] == 'a';
            if (data) {
                int start = at + 8;
                int count = Math.min(length < 0 ? Integer.MAX_VALUE : length, bytes.length - start) / 2;
                short[] samples = new short[count];
                for (int i = 0; i < count; ++i) {
                    samples[i] = (short) ((bytes[start + i * 2] & 0xFF) | (bytes[start + i * 2 + 1] << 8));
                }
                return samples;
            }
            at += 8 + length + (length & 1);
        }
        throw new IOException("no data in the WAV file");
    }
}
