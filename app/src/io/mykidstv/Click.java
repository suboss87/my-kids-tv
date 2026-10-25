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
 * SoundPool, only on the home screen (never inside apps): moving the focus is a crisp glass tick, a few milliseconds
 * long; opening something is a soft two-note marimba bloom, rising a fifth.
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
            move = pool.load(wav(c, "tick-v2.wav", 0.035f, Click::tick), 1);
            open = pool.load(wav(c, "bloom-v2.wav", 0.32f, Click::bloom), 1);
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

    /** Glass tick: a bright 2.4 kHz ping that dies in a few milliseconds over a short, rounder 1.2 kHz body. */
    private static double tick(double t) {
        double attack = Math.min(1, t / 0.0006);
        return attack * (0.55 * Math.sin(2 * Math.PI * 2400 * t) * Math.exp(-t / 0.0035)
                + 0.45 * Math.sin(2 * Math.PI * 1200 * t) * Math.exp(-t / 0.009));
    }

    /** Marimba bloom: E5 then B5 45 ms later, each a soft mallet (fundamental and its 3.93× overtone, which fades first). */
    private static double bloom(double t) {
        return 0.55 * mallet(t, 659.25) + 0.45 * mallet(t - 0.045, 987.77);
    }

    private static double mallet(double t, double f) {
        if (t < 0) return 0;
        double attack = Math.min(1, t / 0.002);
        return attack * (Math.sin(2 * Math.PI * f * t) * Math.exp(-t / 0.075)
                + 0.18 * Math.sin(2 * Math.PI * f * 3.93 * t) * Math.exp(-t / 0.012));
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
