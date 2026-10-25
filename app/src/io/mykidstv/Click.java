package io.mykidstv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The home screen's sounds, synthesized by the app and written once as WAVs in the cache, played quietly through
 * SoundPool, only on the home screen (never inside apps). In the spirit of Apple TV's: moving the focus is a soft,
 * rounded "tok", felt more than heard (a short low note whose pitch drops as it fades, like a finger on wood);
 * opening something is a gentle bubble "pop" that rises, friendly for a child and never shrill.
 */
final class Click {
    private static final int RATE = 48_000;
    private SoundPool pool;
    private int move, open;

    Click(Context c) {
        try {
            pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            move = pool.load(wav(c, "tok-v3.wav", 0.05f, Click::tok), 1);
            open = pool.load(wav(c, "pop-v3.wav", 0.16f, Click::pop), 1);
        } catch (IOException | RuntimeException e) {
            AppLog.w("click sound unavailable: " + e.getMessage());
            release();
        }
    }

    void move() {
        if (pool != null && Prefs.sounds()) pool.play(move, 0.16f, 0.16f, 1, 0, 1f);
    }

    void open() {
        if (pool != null && Prefs.sounds()) pool.play(open, 0.2f, 0.2f, 1, 0, 1f);
    }

    void release() {
        if (pool != null) pool.release();
        pool = null;
    }

    interface Synth { double at(double t); }

    /** Soft tok: 620 Hz falling to 430 Hz in a few ms, a rounded body at half that, gone in ~40 ms. */
    private static double tok(double t) {
        double attack = Math.min(1, t / 0.0012);
        double f = 430 + 190 * Math.exp(-t / 0.006);
        double phase = 2 * Math.PI * (430 * t + 190 * 0.006 * (1 - Math.exp(-t / 0.006)));   // the integral of f
        return attack * Math.exp(-t / 0.011) * (0.8 * Math.sin(phase) + 0.2 * Math.sin(0.5 * phase)) * (f > 0 ? 1 : 0);
    }

    /** Bubble pop: 520 Hz gliding up to 880 Hz in ~25 ms with a quick fade, and a quiet octave that sparkles. */
    private static double pop(double t) {
        double attack = Math.min(1, t / 0.002);
        double phase = 2 * Math.PI * (880 * t - 360 * 0.012 * (1 - Math.exp(-t / 0.012)));   // 520 → 880 Hz
        return attack * Math.exp(-t / 0.045) * (0.85 * Math.sin(phase) + 0.15 * Math.sin(2 * phase) * Math.exp(-t / 0.015));
    }

    private static String wav(Context c, String name, float seconds, Synth synth) throws IOException {
        File f = new File(c.getCacheDir(), name);
        if (f.exists() && f.length() > 44) return f.getPath();
        int n = Math.round(RATE * seconds);
        ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + n * 2).put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes("US-ASCII")).putInt(n * 2);
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE, fade = Math.min(1, (seconds - t) / 0.004);   // no click at the very end
            b.putShort((short) Math.round(Math.max(-1, Math.min(1, synth.at(t) * fade * 0.8)) * Short.MAX_VALUE));
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b.array());
        }
        return f.getPath();
    }
}
