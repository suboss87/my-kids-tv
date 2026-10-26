package io.mykidstv;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * The dark sky behind the home screen and the settings: near-black indigo (never pure black, as on Apple TV, so the
 * pictures glow) rising to a faint violet horizon, three
 * huge, very faint clouds of color (violet high on the right, blue on the left, rose low in the middle), and a fine
 * field of stars, a few of them with a soft halo. Still, so it stays calm and costs the TV's GPU nothing between
 * frames; the story window's own light moves over it.
 */
final class SpaceSky {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), star = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader base, horizon;
    private Shader[] clouds;
    private float[] stars;   // x, y, radius, alpha (0-1), halo (0 or 1), in 1920 x 1080 units
    private int forW, forH;

    // x, y, radius (1920 x 1080 units), and each one's color with its alpha
    private static final float[][] CLOUDS = {{1520, 160, 980}, {200, 640, 900}, {1100, 1180, 860}, {760, 260, 620}};
    private static final int[] CLOUD_COLORS = {0x227B5CFF /* violet */, 0x183A7BFF /* blue */, 0x12FF6FA8 /* rose */,
            0x082EC4B6 /* a breath of teal */};

    private android.graphics.Bitmap cached;   // the whole sky, drawn once: redrawing it is then one copy
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);

    void draw(Canvas c, int w, int h, float alpha) {
        if (w == 0 || h == 0) return;
        if (cached == null || forW != w || forH != h) {
            build(w, h);
            try {
                cached = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                paint(new Canvas(cached), w, h, 1);
            } catch (OutOfMemoryError e) {
                cached = null;
            }
        }
        if (cached == null) {   // no memory for the picture: draw it live
            paint(c, w, h, alpha);
            return;
        }
        blit.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(cached, 0, 0, blit);
    }

    private void paint(Canvas c, int w, int h, float alpha) {
        if (base == null || forW != w || forH != h) build(w, h);
        int a = Math.round(255 * alpha);
        fill.setDither(true);   // smooth gradients, no banding in the dark
        fill.setShader(base);
        fill.setAlpha(a);
        c.drawRect(0, 0, w, h, fill);
        float k = w / 1920f;
        for (int i = 0; i < clouds.length; i++) {
            fill.setShader(clouds[i]);
            c.drawCircle(CLOUDS[i][0] * k, CLOUDS[i][1] * k, CLOUDS[i][2] * k, fill);
        }
        fill.setShader(horizon);
        c.drawRect(0, h * 0.62f, w, h, fill);
        fill.setShader(null);
        fill.setAlpha(255);
        for (int i = 0; i < stars.length; i += 5) {
            float x = stars[i] * k, y = stars[i + 1] * k, r = stars[i + 2] * k;
            if (stars[i + 4] > 0) {
                star.setColor(Ui.alpha(0xFFBFC8FF, 0.16f * stars[i + 3] * alpha));
                c.drawCircle(x, y, r * 3.2f, star);
            }
            star.setColor(Ui.alpha(0xFFFFFFFF, stars[i + 3] * alpha));
            c.drawCircle(x, y, r, star);
        }
    }

    private void build(int w, int h) {
        forW = w;
        forH = h;
        float k = w / 1920f;
        base = new LinearGradient(0, 0, 0, h, new int[] {0xFF03040C, 0xFF070918, 0xFF0D0B22},
                new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
        horizon = new LinearGradient(0, h, 0, h * 0.62f, 0x332A2060, 0x002A2060, Shader.TileMode.CLAMP);
        clouds = new Shader[CLOUDS.length];
        for (int i = 0; i < CLOUDS.length; i++) {
            int color = CLOUD_COLORS[i];
            clouds[i] = new RadialGradient(CLOUDS[i][0] * k, CLOUDS[i][1] * k, CLOUDS[i][2] * k,
                    new int[] {color, Ui.alpha(color | 0xFF000000, (color >>> 24) / 255f * 0.4f), color & 0x00FFFFFF},
                    new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
        }
        // The same stars every time (a fixed seed), more and brighter high up, fewer toward the horizon.
        java.util.Random rnd = new java.util.Random(20261025);
        int n = 96;
        stars = new float[n * 5];
        for (int i = 0; i < n; i++) {
            float y = (float) Math.pow(rnd.nextFloat(), 1.6) * 1000;
            stars[i * 5] = rnd.nextFloat() * 1920;
            stars[i * 5 + 1] = y;
            stars[i * 5 + 2] = 0.6f + rnd.nextFloat() * rnd.nextFloat() * 1.3f;
            stars[i * 5 + 3] = (0.2f + 0.5f * rnd.nextFloat()) * (1 - 0.5f * y / 1000);
            stars[i * 5 + 4] = rnd.nextFloat() < 0.08f ? 1 : 0;
        }
    }
}
