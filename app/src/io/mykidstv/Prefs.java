package io.mykidstv;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (My Kids TV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    /** The Netflix button on Vestel TVs and Formovie projectors: STEM_1, which the TV hands to its Netflix key receiver (dumpsys window policy). */
    static final int KEY_STEM_NETFLIX = KeyEvent.KEYCODE_STEM_1;
    /** KTC's (NextStar) Tabii button: BUTTON_8, which the TV hands to its hot-key receiver (its log: "keycode = 195"). */
    static final int KEY_KTC_TABII = KeyEvent.KEYCODE_BUTTON_8;
    /** Xiaomi's Mi button: BUTTON_4, which the TV hands to its GlobalKeyReceiver for PatchWall (its log: "keyCode = 191"). */
    static final int KEY_MI = KeyEvent.KEYCODE_BUTTON_4;
    /** Sony's app buttons, as SONY_KEY + scan code (read from a BRAVIA's log; the key codes are shared). */
    static final int SONY_KEY = 100_000;
    static final int KEY_SONY_CORE = SONY_KEY + 496, KEY_SONY_NETFLIX = SONY_KEY + 583, KEY_SONY_DISNEY = SONY_KEY + 491,
            KEY_SONY_PRIME = SONY_KEY + 490, KEY_SONY_YOUTUBE = SONY_KEY + 485, KEY_SONY_CRUNCHYROLL = SONY_KEY + 507,
            KEY_SONY_PLAY = SONY_KEY + 587;   // the Google Play button of older remotes: BUTTON_8 (BRAVIA 4K UR3, Android 10)
    /**
     * Xiaomi's app buttons (remotes sold in Russia), handed to its hot-key receiver (its log: "dealWithHotkey
     * keycode:199"). None has a default here: "Xiaomi default" (empty) leaves the key to the TV.
     */
    static final int KEY_XIAOMI_OKKO = KeyEvent.KEYCODE_BUTTON_13, KEY_XIAOMI_VK = KeyEvent.KEYCODE_BUTTON_1,
            KEY_XIAOMI_RUTUBE = KeyEvent.KEYCODE_BUTTON_16, KEY_XIAOMI_KION = KeyEvent.KEYCODE_BUTTON_11,
            KEY_XIAOMI_WINK = KeyEvent.KEYCODE_BUTTON_10, KEY_XIAOMI_IVI = KeyEvent.KEYCODE_BUTTON_12;
    /**
     * Hisense's buttons (Q6N, MediaTek, Android TV 12 tested; read from the key filter): global keys for Google TV's
     * home app (launcherx), or Hisense's 607 for Disney+. The gear sends NOTIFICATION; ALL_APPS (284) and PROFILE_SWITCH
     * (288) are API 28/29 names, so numbers here.
     */
    static final int KEY_HISENSE_PRIME = 295, KEY_HISENSE_YOUTUBE = KeyEvent.KEYCODE_BUTTON_3, KEY_HISENSE_DISNEY = 607,
            KEY_HISENSE_APPS = 284, KEY_HISENSE_SETTINGS = KeyEvent.KEYCODE_NOTIFICATION, KEY_HISENSE_PROFILE = 288;
    /** A button's value that opens this home screen's All Apps. */
    static final String ALL_APPS = HomeMode.PKG + "#all";
    static final int[] KEYS = RemoteKeys.HISENSE ? new int[] {KEY_HISENSE_PRIME, KEY_HISENSE_YOUTUBE, KEY_HISENSE_DISNEY,
                    KEY_HISENSE_APPS, KEY_HISENSE_SETTINGS, KEY_HISENSE_PROFILE}
            : RemoteKeys.SONY
            ? new int[] {KEY_SONY_CORE, KEY_SONY_NETFLIX, KEY_SONY_DISNEY, KEY_SONY_PRIME, KEY_SONY_YOUTUBE, KEY_SONY_CRUNCHYROLL,
                    KEY_SONY_PLAY}
            : RemoteKeys.STEM_NETFLIX ? new int[] {KEY_STEM_NETFLIX}
            : RemoteKeys.KTC ? new int[] {KEY_KTC_TABII}
            : RemoteKeys.XIAOMI ? new int[] {KEY_MI, KEY_XIAOMI_OKKO, KEY_XIAOMI_VK, KEY_XIAOMI_RUTUBE, KEY_XIAOMI_KION,
                    KEY_XIAOMI_WINK, KEY_XIAOMI_IVI}
            : new int[] {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        migrateKeys();   // before anything reads the button choices
        importConfig(app);   // settings a computer sent (ConfigInbox), before anything reads them
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    // MARK: - Settings sent from a computer (tools/push-config.py, ConfigInbox)

    /**
     * Applies a config.properties that a computer sent (the same keys as the file build.sh reads) and deletes it. The
     * values go where the settings screen's go, so they win over the build's; parent_pin sets the PIN; tmdb_key and
     * the other keys stay on the TV only. true when something was applied (the screens then draw again). Only one
     * process imports a given file: the first to rename it. Nothing from the file is logged.
     */
    static boolean importConfig(Context c) {
        File inbox = ConfigInbox.inbox(c.getApplicationContext());
        File f = new File(inbox, ConfigInbox.NAME);
        if (!f.exists()) return reloadIfImportedElsewhere(inbox);
        File mine = new File(inbox, ConfigInbox.NAME + "." + android.os.Process.myPid());
        if (!f.renameTo(mine)) return reloadIfImportedElsewhere(inbox);   // another process took it
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStreamReader r = new java.io.InputStreamReader(new java.io.FileInputStream(mine), StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (IOException e) {
            AppLog.w("settings from the computer: unreadable file");
            //noinspection ResultOfMethodCallIgnored
            mine.delete();
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        mine.delete();
        if (app == null) app = c.getApplicationContext();
        SharedPreferences.Editor e = sp().edit();
        List<String> applied = new ArrayList<>();
        boolean pin = false;
        for (String name : props.stringPropertyNames()) {
            String v = props.getProperty(name, "").trim().replace("\"", "");
            switch (name) {
                case "age_band":
                    if (java.util.Arrays.asList(AGE_BANDS).contains(v)) { e.putString("age_band", v); applied.add(name); }
                    break;
                case "child_name": e.putString("child_name", v); applied.add(name); break;
                case "language": e.putString("language", v.isEmpty() ? "auto" : v); applied.add(name); break;
                case "temperature": e.putString("temperature", v.isEmpty() ? "auto" : v); applied.add(name); break;
                case "theme": e.putString("theme", v.isEmpty() ? "dark" : v); applied.add(name); break;
                case "city": e.putString("city", v); applied.add(name); break;
                case "latitude": case "longitude":
                    if (v.matches("-?[0-9]+(\\.[0-9]+)?")) { e.putString(name, v); applied.add(name); }
                    break;
                case "tmdb_key": case "watchmode_key": case "hide_services":
                    e.putString(name, v); applied.add(name);
                    break;
                case "tmdb_region":
                    if (v.isEmpty() || v.matches("[A-Za-z]{2}")) { e.putString(name, v.toUpperCase(Locale.ROOT)); applied.add(name); }
                    break;
                case "hide_animation": case "disable_assistant": case "continue_watching":
                    e.putBoolean(name, "true".equalsIgnoreCase(v)); applied.add(name);
                    break;
                case "block_youtube_button":
                    setBlockYoutubeButton("true".equalsIgnoreCase(v)); applied.add(name);
                    break;
                case "parent_pin":
                    if (v.matches("[0-9]{4}")) {
                        e.putString("pin", hashPin(v)).putInt("pinFails", 0).putLong("pinWaitUntil", 0);
                        pin = true;
                    }
                    break;
                default: break;   // keys build.sh doesn't know either, or none of the TV's business
            }
        }
        if (applied.isEmpty() && !pin) return false;
        // A PIN from the computer must not be undone by the build's parent_pin at the next check (applyPinPreset).
        if (pin && Config.PARENT_PIN.trim().matches("[0-9]{4}")) e.putString("pin_preset", hashPin(Config.PARENT_PIN.trim()));
        e.apply();
        version++;
        Ui.applyLanguage();
        stampImport(inbox);
        java.util.Collections.sort(applied);
        AppLog.w("settings from the computer: " + String.join(", ", applied) + (pin ? ", parent PIN" : ""));
        if (!applied.isEmpty()) HomeMode.notice(app, Ui.trf("New settings arrived from your computer: {list}.", "list", String.join(", ", applied)));
        if (pin) HomeMode.notice(app, Ui.tr("The parent PIN was set from your computer."));
        return true;
    }

    // Every process keeps its own copy of the settings in memory, and a write from one would put a stale copy over
    // another's import. The importer leaves a stamp; the others see a new stamp and read the file again (reload) before
    // they read or write anything, so a PIN or a key sent while the home screen was in the background still applies.
    private static String importStamp;

    private static File stampFile(File inbox) {
        return new File(inbox, "applied");
    }

    private static void stampImport(File inbox) {
        String token = android.os.Process.myPid() + ":" + System.currentTimeMillis();
        try (FileOutputStream out = new FileOutputStream(stampFile(inbox))) {
            out.write(token.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        importStamp = token;
    }

    /** true when another process imported since this one last looked (then the settings were read again). */
    private static boolean reloadIfImportedElsewhere(File inbox) {
        File f = stampFile(inbox);
        String token = "";
        if (f.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                token = String.valueOf(r.readLine()).trim();
            } catch (IOException ignored) {
            }
        }
        if (importStamp == null) {   // first look in this process: whatever is on disk is what it read at start
            importStamp = token;
            return false;
        }
        if (token.equals(importStamp)) return false;
        importStamp = token;
        reload();
        return true;
    }

    // MARK: - Keys and country (from the build, or sent from a computer; the TV keeps them, never the log)

    /** The TMDB key: the one sent from a computer, else the build's. "" without one (then no picks). */
    static String tmdbKey() {
        return app == null ? Config.TMDB_KEY.trim() : sp().getString("tmdb_key", Config.TMDB_KEY).trim();
    }

    static String watchmodeKey() {
        return app == null ? Config.WATCHMODE_KEY.trim() : sp().getString("watchmode_key", Config.WATCHMODE_KEY).trim();
    }

    /** The picks' streaming region (two letters), sent, else the build's, else the TV's time zone (Place). */
    static String region() {
        String v = app == null ? "" : sp().getString("tmdb_region", "").trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? Place.region() : v;
    }

    /** Services to leave out of the picks, comma separated ("hulu,peacock"). */
    static String hideServices() {
        return app == null ? Config.HIDE_SERVICES : sp().getString("hide_services", Config.HIDE_SERVICES);
    }

    static boolean hideAnimation() {
        return app == null ? Config.HIDE_ANIMATION : sp().getBoolean("hide_animation", Config.HIDE_ANIMATION);
    }

    static boolean disableAssistant() {
        return app == null ? Config.DISABLE_ASSISTANT : sp().getBoolean("disable_assistant", Config.DISABLE_ASSISTANT);
    }

    /** A title the child was in the middle of (Watch Next, from the streaming apps) comes first on the cards. */
    static boolean continueWatching() { return sp().getBoolean("continue_watching", true); }
    static void setContinueWatching(boolean on) { put("continue_watching", on); }

    /** The TV's own 12/24-hour setting (Settings → System → Date & time). */
    static boolean clock24() {
        return app != null && android.text.format.DateFormat.is24HourFormat(app);
    }

    /** The app's context in this process (null before App.onCreate). */
    static Context context() {
        return app;
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /**
     * Reads the file again if another process changed it. On Android 11 the screensaver runs in the main process
     * (ScreensaverAlone), which otherwise keeps the values it first read, whatever the settings screen in :home saves.
     */
    @SuppressWarnings("deprecation")
    static void reload() {
        String before = language();
        app.getSharedPreferences("settings", Context.MODE_MULTI_PROCESS);
        version++;
        if (!language().equals(before)) Ui.applyLanguage();
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto" or a language code as picked ("pt", "fr", "zh-CN"…); Ui.applyLanguage finds its catalogue. */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim();
        return v.isEmpty() || v.equalsIgnoreCase("auto") ? "auto" : v;
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", Config.THEME.trim().isEmpty() ? "dark" : Config.THEME.trim());
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    // MARK: - Kids

    static final String[] AGE_BANDS = {"4-6", "7-10", "11-13"};

    /** The child's age band: which vetted catalog (app/assets/catalog) the picks come from. */
    static String ageBand() {
        String v = sp().getString("age_band", Config.AGE_BAND.trim());
        for (String b : AGE_BANDS) if (b.equals(v)) return b;
        return "7-10";
    }

    static void setAgeBand(String v) { put("age_band", v); }

    /** The child's first name for the greeting ("Good morning, Viva"), or "" for none. */
    static String childName() { return sp().getString("child_name", Config.CHILD_NAME.trim()).trim(); }

    static void setChildName(String v) { put("child_name", v); }

    // MARK: - Parent PIN

    // Only a salted SHA-256 hash ("salt:hash", hex) is kept, never the PIN. No version++: nothing on screen shows it.
    // "pin_preset" is the hash parent_pin (config.properties) last set: a new parent_pin, built and installed again,
    // replaces the PIN (the way back for a parent who forgot it).

    /** A PIN is set (from parent_pin or made on the TV). */
    static boolean hasPin() {
        applyPinPreset();
        return !sp().getString("pin", "").isEmpty();
    }

    static boolean checkPin(String pin) {
        applyPinPreset();
        return matches(pin, sp().getString("pin", ""));
    }

    static void setPin(String pin) {
        sp().edit().putString("pin", hashPin(pin)).apply();
    }

    /** Wrong tries in a row, and until when (elapsedRealtime) entry waits after too many. */
    static int pinFails() { return sp().getInt("pinFails", 0); }
    static long pinWaitUntil() { return sp().getLong("pinWaitUntil", 0); }

    static void setPinFails(int fails, long waitUntil) {
        sp().edit().putInt("pinFails", fails).putLong("pinWaitUntil", waitUntil).apply();
    }

    private static void applyPinPreset() {
        String preset = Config.PARENT_PIN.trim();
        if (!preset.matches("[0-9]{4}") || matches(preset, sp().getString("pin_preset", ""))) return;
        String hash = hashPin(preset);
        sp().edit().putString("pin", hash).putString("pin_preset", hash).putInt("pinFails", 0).putLong("pinWaitUntil", 0).apply();
    }

    private static String hashPin(String pin) {
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        return hex(salt) + ":" + sha256(salt, pin);
    }

    private static boolean matches(String pin, String saltHash) {
        int cut = saltHash.indexOf(':');
        if (cut <= 0) return false;
        String salt = saltHash.substring(0, cut);
        byte[] bytes = new byte[salt.length() / 2];
        try {
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(salt.substring(2 * i, 2 * i + 2), 16);
        } catch (NumberFormatException e) {
            return false;
        }
        return java.security.MessageDigest.isEqual(sha256(bytes, pin).getBytes(StandardCharsets.US_ASCII),
                saltHash.substring(cut + 1).getBytes(StandardCharsets.US_ASCII));
    }

    private static String sha256(byte[] salt, String pin) {
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-256");
            d.update(salt);
            return hex(d.digest(pin.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every Android has SHA-256
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    // MARK: - City

    static String city() { return sp().getString("city", Place.city()).trim(); }
    static String latitude() { return sp().getString("latitude", Place.latitude()); }
    static String longitude() { return sp().getString("longitude", Place.longitude()); }

    static void setCity(String name, String latitude, String longitude) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply();
        version++;
    }

    /** The home screen's soft click when moving and opening. */
    static boolean sounds() { return sp().getBoolean("sounds", true); }
    static void setSounds(boolean on) { put("sounds", on); }

    // MARK: - Widgets

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }
    /** The weather card's forecast: "hours" (the next 6 hours) or "days" (the next 5 days). */
    static String forecast() { return "hours".equals(sp().getString("forecast", "days")) ? "hours" : "days"; }   // the week by default
    static void setForecast(String v) { put("forecast", v); }

    /** Your photos (Photos, sent from a computer) on the screensaver and behind the home screen, once imported. */
    static boolean photos() { return sp().getBoolean("photos", true); }
    static void setPhotos(boolean on) { put("photos", on); }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Sleep timer

    /** When the sleep timer ends (System.currentTimeMillis); SleepTimer's alarm says whether it's still on. */
    static long sleepAt() { return sp().getLong("sleepAt", 0); }

    /** When it was set, in uptimeMillis (dumpsys power's clock): a TV turned off since ended it (SleepTimer.check). */
    static long sleepFrom() { return sp().getLong("sleepFrom", 0); }

    // No version++ for the sleep settings: HomeActivity would rebuild the home screen and fetch the weather again for a
    // timer; HomeView reads what it needs on resume and every 15 s (updateSleep).
    static void setSleepAt(long at, long from) {
        sp().edit().putLong("sleepAt", at).putLong("sleepFrom", from).apply();
    }

    /** The sleep timer's minutes left on screen: over any app (SleepOverlay), or where this TV can't, on the home screen. */
    static boolean sleepShown() { return sp().getBoolean("sleepShown", true); }
    static void setSleepShown(boolean on) { sp().edit().putBoolean("sleepShown", on).apply(); }

    /** Where it shows over other apps: SleepOverlay's places, the top center first. */
    static int sleepPlace() { return sp().getInt("sleepPlace", 0); }

    static void setSleepPlace(int place) { sp().edit().putInt("sleepPlace", place).apply(); }

    // MARK: - Top buttons

    /**
     * The round buttons at the top right, in order: "live", "inputs" and/or "settings". null until the user edits
     * them (then HomeApps.TOP_DEFAULT applies); empty when they took all three off.
     */
    static List<String> topButtons() {
        String v = sp().getString("topButtons", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setTopButtons(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String p : keys) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("topButtons", sb.toString());
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        if (RemoteKeys.HISENSE) {
            switch (keyCode) {
                case KEY_HISENSE_PRIME: return "com.amazon.amazonvideo.livingroom";
                case KEY_HISENSE_YOUTUBE: return "com.google.android.youtube.tv";
                case KEY_HISENSE_DISNEY: return "com.disney.disneyplus";
                case KEY_HISENSE_APPS: return ALL_APPS;
                case KEY_HISENSE_SETTINGS: return "com.android.tv.settings";
                default: return "";
            }
        }
        switch (keyCode) {
            case KEY_NETFLIX: case KEY_STEM_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            case KEY_MI: return HomeMode.PKG;   // our home screen: the remote has no other Home button
            default: return "";
        }
    }

    // The key service runs in its own small process (:keys), so that a busy home screen can never delay the TV's keys.
    // SharedPreferences are not shared between processes, so these few values live in a tiny file both processes read.
    // The service asks on every key: the values stay in memory and the file is read again only when it changes.
    private static File keysFile() {
        return new File(app.getFilesDir(), "remote-keys.txt");
    }

    private static final String[] keyApps = new String[KEYS.length];   // null = the default
    private static Boolean blockYoutube;   // null = block_youtube_button from config.properties
    private static long keysStamp = Long.MIN_VALUE;
    private static final String BLOCK_YOUTUBE = "blockYoutube=";

    /** A temporary file per process (two can start together after an update), synced before it replaces the old one. */
    private static boolean writeKeys(String text) {
        File tmp = new File(app.getFilesDir(), "remote-keys." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return tmp.renameTo(keysFile());
    }

    /** Choices saved before they moved to the file (in SharedPreferences) are copied there once. */
    private static void migrateKeys() {
        if (keysFile().exists()) return;
        SharedPreferences old = sp();
        boolean any = false;
        for (int key : KEYS) any |= old.contains("key" + key);
        if (!any) return;
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(old.getString("key" + key, defaultApp(key))).append('\n');
        writeKeys(sb.toString());
    }

    /** Reads the file again if it changed (call holding keyApps). */
    private static void readKeys() {
        File f = keysFile();
        long stamp = f.lastModified() * 31 + f.length();   // 0 without the file: every key opens its default
        if (stamp == keysStamp) return;
        keysStamp = stamp;
        java.util.Arrays.fill(keyApps, null);
        blockYoutube = null;
        if (stamp == 0) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(BLOCK_YOUTUBE)) blockYoutube = "true".equals(line.substring(BLOCK_YOUTUBE.length()));
                for (int i = 0; i < KEYS.length; i++) {
                    String prefix = KEYS[i] + "=";
                    if (line.startsWith(prefix)) keyApps[i] = line.substring(prefix.length());
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) {
        synchronized (keyApps) {
            readKeys();
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode && keyApps[i] != null) return keyApps[i];
            return defaultApp(keyCode);
        }
    }

    /** The remote's YouTube button does nothing (a parent's choice; the YouTube app stays). */
    static boolean blockYoutubeButton() {
        synchronized (keyApps) {
            readKeys();
            return blockYoutube != null ? blockYoutube : Config.BLOCK_YOUTUBE_BUTTON;
        }
    }

    static void setKeyApp(int keyCode, String pkg) {
        synchronized (keyApps) {
            if (writeKeys(keysText(keyCode, pkg, blockYoutubeButton()))) version++;
        }
    }

    static void setBlockYoutubeButton(boolean on) {
        synchronized (keyApps) {
            if (writeKeys(keysText(0, null, on))) version++;
        }
    }

    /** Every button's app (keyCode's as pkg) and the YouTube block, as the file holds them. */
    private static String keysText(int keyCode, String pkg, boolean block) {
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(pkg != null && key == keyCode ? pkg : keyApp(key)).append('\n');
        return sb.append(BLOCK_YOUTUBE).append(block).append('\n').toString();
    }
}
