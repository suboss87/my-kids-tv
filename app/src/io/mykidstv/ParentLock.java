package io.mykidstv;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

/**
 * The parent PIN in front of the settings, drawn by SettingsView on the right of its screen. Number keys type it
 * where the remote has them; the keypad (arrows and OK) works on every remote. The first time, with no PIN yet, it
 * asks for a new one twice. Back cancels. After 5 wrong tries it waits 30 s (kept in Prefs, so closing and opening
 * again doesn't reset it).
 */
final class ParentLock {
    interface Listener {
        void unlocked();
        void cancelled();
    }

    private static final int ENTER = 0, CREATE = 1, CONFIRM = 2;
    private static final int TRIES = 5;
    private static final long WAIT = 30_000;
    /** The keypad, row by row: digits, then Cancel and Delete around 0. */
    private static final int CANCEL = -1, DELETE = -2;
    private static final int[] KEYS = {1, 2, 3, 4, 5, 6, 7, 8, 9, CANCEL, 0, DELETE};
    private static final float CELL_W = 150, CELL_H = 84, CELL_GAP = 12, SLOT_W = 84, SLOT_H = 96, SLOT_GAP = 20;
    private static final int INK = 0xFF1C1C1E, AMBER = 0xFFFFC266;

    private final View host;
    private final Listener listener;
    private final Spring[] pop = new Spring[KEYS.length];
    private final StringBuilder typed = new StringBuilder();
    private final RectF r = new RectF();
    private String first = "";   // CREATE's PIN, while CONFIRM asks for it again
    private String message = "";
    private int step, focus = 4;   // on 5, the middle of the keypad
    private boolean okDown, backDown;
    private float k = -1;
    private Paint heading, prompt, warn, hint, digit, word, white, shadow, slot, dot;

    ParentLock(View host, Listener listener) {
        this.host = host;
        this.listener = listener;
        for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, i == focus ? 1 : 0);
        step = Prefs.hasPin() ? ENTER : CREATE;
    }

    /** What TalkBack reads: the heading and what to do. */
    String spoken() {
        return title() + ". " + (message.isEmpty() ? subtitle() : message);
    }

    boolean moving() {
        for (Spring s : pop) if (!s.settled()) return true;
        return false;
    }

    private String title() {
        return step == CREATE ? Ui.tr("Create a parent PIN") : step == CONFIRM ? Ui.tr("Enter the same PIN again") : Ui.tr("Parent PIN");
    }

    private String subtitle() {
        return step == CREATE ? Ui.tr("Pick 4 digits that only grown-ups know.")
                : step == CONFIRM ? Ui.tr("To be sure you remember it.")
                : Ui.tr("Enter the parent PIN to open the settings.");
    }

    /** Milliseconds left before another try, 0 when a PIN can be entered. */
    private static long waitLeft() {
        long left = Prefs.pinWaitUntil() - SystemClock.elapsedRealtime();
        return left > 0 && left <= WAIT ? left : 0;   // longer: the TV restarted since (a new clock)
    }

    private void paints(float scale) {
        if (scale == k) return;
        k = scale;
        heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
        prompt = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
        warn = Ui.text(Ui.regular, 24 * k, AMBER);
        warn.setTextAlign(Paint.Align.CENTER);
        hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
        digit = Ui.numbers(Ui.medium, 40 * k, 0xF2FFFFFF);
        digit.setTextAlign(Paint.Align.CENTER);
        word = Ui.text(Ui.medium, 25 * k, 0xF2FFFFFF);
        word.setTextAlign(Paint.Align.CENTER);
        white = new Paint(Paint.ANTI_ALIAS_FLAG);
        white.setColor(0xFFF5F5F7);
        shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        slot = new Paint(Paint.ANTI_ALIAS_FLAG);
        slot.setStyle(Paint.Style.STROKE);
        slot.setStrokeWidth(2.5f * k);
        dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(0xFFFFFFFF);
    }

    /** Draws in the list's place: from `left` to `right` (1920-wide units), `top` the list's top. */
    void draw(Canvas c, float scale, float left, float right, float top, float dt) {
        paints(scale);
        for (int i = 0; i < pop.length; i++) {
            pop[i].target = i == focus ? 1 : 0;
            pop[i].step(dt);
        }
        float x = left + 30, cx = (left + right) / 2;
        c.drawText(title(), x * k, (top - 24) * k, heading);
        c.drawText(subtitle(), x * k, (top + 36) * k, prompt);

        // The four places, filled as digits come.
        float slotsW = 4 * SLOT_W + 3 * SLOT_GAP, sy = top + 80;
        for (int i = 0; i < 4; i++) {
            float sx = cx - slotsW / 2 + i * (SLOT_W + SLOT_GAP);
            r.set(sx * k, sy * k, (sx + SLOT_W) * k, (sy + SLOT_H) * k);
            slot.setColor(i == typed.length() ? 0xCCFFFFFF : 0x4DFFFFFF);
            c.drawRoundRect(r, 18 * k, 18 * k, slot);
            if (i < typed.length()) c.drawCircle(r.centerX(), r.centerY(), 12 * k, dot);
        }

        long wait = waitLeft();
        String line = wait > 0 ? Ui.trf("Too many tries. Wait {n} s.", "n", (wait + 999) / 1000) : message;
        if (!line.isEmpty()) c.drawText(line, cx * k, (sy + SLOT_H + 54) * k, warn);

        // The keypad.
        float padW = 3 * CELL_W + 2 * CELL_GAP, py = sy + SLOT_H + 96;
        for (int i = 0; i < KEYS.length; i++) {
            float kx = cx - padW / 2 + (i % 3) * (CELL_W + CELL_GAP), ky = py + (i / 3) * (CELL_H + CELL_GAP);
            r.set(kx * k, ky * k, (kx + CELL_W) * k, (ky + CELL_H) * k);
            float f = Ui.clamp(pop[i].value);
            float s = 1 + 0.04f * f, hw = r.width() * s / 2, hh = r.height() * s / 2, mx = r.centerX(), my = r.centerY();
            r.set(mx - hw, my - hh, mx + hw, my + hh);
            float radius = 18 * k * s;
            if (f > 0.01f) {
                r.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(r, radius, radius, shadow);
                r.offset(0, -10 * k * f);
            }
            white.setAlpha(Math.round(255 * (0.1f + 0.9f * f)));   // a faint key at rest, white when focused
            c.drawRoundRect(r, radius, radius, white);
            int key = KEYS[i];
            Paint p = key >= 0 ? digit : word;
            p.setColor(blend(wait > 0 && key != CANCEL ? 0x59FFFFFF : 0xF2FFFFFF, INK, f));
            String label = key >= 0 ? String.valueOf(key) : key == CANCEL ? Ui.tr("Cancel") : Ui.tr("Delete");
            c.drawText(label, r.centerX(), r.centerY() + Ui.halfCap(p), p);
        }
        c.drawText(Ui.tr("Number keys, or the arrows and OK. Back cancels."), x * k,
                (py + 4 * CELL_H + 3 * CELL_GAP + 60) * k, hint);
    }

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int code = e.getKeyCode();
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            if (down) type(code - KeyEvent.KEYCODE_0);
            return true;
        }
        if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) {
            if (down) type(code - KeyEvent.KEYCODE_NUMPAD_0);
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && focus >= 3) focus -= 3;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && focus + 3 < KEYS.length) focus += 3;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && focus % 3 > 0) focus--;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && focus % 3 < 2) focus++;
                break;
            case KeyEvent.KEYCODE_DEL:
                if (down) erase();
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // On release, as in the settings: a held OK doesn't type the same digit over and over.
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else {
                    if (okDown && !e.isCanceled()) press(KEYS[focus]);
                    okDown = false;
                }
                break;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) listener.cancelled();
                    backDown = false;
                }
                break;
            default:
                return false;
        }
        host.invalidate();
        return true;
    }

    private void press(int key) {
        if (key == CANCEL) listener.cancelled();
        else if (key == DELETE) erase();
        else type(key);
    }

    private void erase() {
        if (typed.length() > 0) typed.setLength(typed.length() - 1);
        host.invalidate();
    }

    private void type(int d) {
        if (waitLeft() > 0 || typed.length() >= 4) return;
        message = "";
        typed.append((char) ('0' + d));
        host.invalidate();
        if (typed.length() < 4) return;
        String pin = typed.toString();
        typed.setLength(0);
        if (step == CREATE) {
            first = pin;
            step = CONFIRM;
        } else if (step == CONFIRM) {
            if (pin.equals(first)) {
                Prefs.setPin(pin);
                Prefs.setPinFails(0, 0);
                listener.unlocked();
                return;
            }
            first = "";
            step = CREATE;
            message = Ui.tr("The PINs didn't match. Start again.");
        } else if (Prefs.checkPin(pin)) {
            Prefs.setPinFails(0, 0);
            listener.unlocked();
            return;
        } else {
            int fails = Prefs.pinFails() + 1;
            if (fails >= TRIES) Prefs.setPinFails(0, SystemClock.elapsedRealtime() + WAIT);
            else Prefs.setPinFails(fails, 0);
            message = Ui.tr("Wrong PIN. Try again.");
        }
        host.announceForAccessibility(spoken());
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
