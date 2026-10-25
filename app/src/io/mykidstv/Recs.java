package io.mykidstv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Okko, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, meta = "", app, pkg;
        String link;            // opens the title in its app (looked up with Watchmode), or null: the app's home
        String badge = "";      // why it's worth watching, from the catalog: "must", "learn", "loved" or ""
        String tagline = "";    // the story in a line, for the window (TMDB's tagline, or a short synopsis)
        boolean detailed;
        java.io.File photo;     // one of your own photos (Photos): no title, no caption
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    /** The backdrops: on a 720p UI, TMDB's 1280-wide copy shows the same and is lighter to download and decode. */
    private static final String IMAGES = "https://image.tmdb.org/t/p/" + (Backdrop.W <= 1280 ? "w1280" : "original");
    /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with hide_animation=true. */
    private static final int ANIMATION = 16;
    private static final long LIST_EVERY = 3600_000L;   // a new set of 60 titles from the catalog every hour
    static final long SPOT_EVERY = 10_000L, ROTATE_EVERY = 3 * SPOT_EVERY;   // the window moves on every 10 s; a new set after all 3
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 14;   // 14 = vetted titles only, looked up in parallel, history no longer hiding what's available
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"prime video", "prime", "com.amazon.firebat"},
        {"prime video", "prime", "com.amazon.avod"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"hulu", "hulu", "com.hulu.plus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"paramount", "paramount", "com.cbs.ca"},      // Paramount+ outside the US (a Sony in the UK; Canada)
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"peacock", "peacock", "com.peacock.peacockfiretv"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"max", "max", "com.hbo.hbonow"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"apple tv", "appletv", "com.apple.atve.sony.appletv"},   // Sony's own build
        {"apple tv", "appletv", "com.apple.atve.amazon.appletv"},
        {"hotstar", "hotstar", "in.startv.hotstar"},   // TMDB: "JioHotstar" (earlier "Disney Plus Hotstar")
        {"crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"},
        {"now tv", "now", "com.bskyb.nowtv.beta"},     // UK: Sky's NOW (TMDB: "Now TV", "Now TV Cinema")
        {"oneplay", "oneplay", "net.cme.voyo.cz.tvapp"},    // Czechia: Voyo and O2 TV became Oneplay, same app
        // Russia (TMDB's watch providers for RU)
        {"okko", "okko", "ru.more.play"},
        {"kinopoisk", "kinopoisk", "ru.kinopoisk.tv"},
        {"premier", "premier", "gpm.tnt_premier"},
        {"amediateka", "amediateka", "ru.amediateka"},
        {"more tv", "wink", "ru.rt.video.app.tv"},   // TMDB: "More TV" (more.tv is part of Rostelecom's Wink now)
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
        SERVICE_NAME.put("hotstar", "JioHotstar");
        SERVICE_NAME.put("okko", "Okko");
        SERVICE_NAME.put("crunchyroll", "Crunchyroll");
        SERVICE_NAME.put("now", "NOW");
        SERVICE_NAME.put("oneplay", "Oneplay");
        SERVICE_NAME.put("kinopoisk", "Кинопоиск");
        SERVICE_NAME.put("premier", "Premier");
        SERVICE_NAME.put("amediateka", "Амедиатека");
        SERVICE_NAME.put("wink", "Wink");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    static final String REGION = Place.region();
    /** The errors the settings say in words: TMDB lists none of the TV's apps in REGION, or the TV has none we know. */
    static final String NO_APPS = "none of the TV's streaming services are on TMDB";
    static final String NO_KNOWN_APPS = "none of the TV's streaming apps can give picks";

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>();   // this hour's, built and ready to draw
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp, linkRetryAt;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts
    private volatile String listBand = "";       // age band the saved picks were chosen for

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
            listBand = cache.getString("band", "");
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        int p = period(System.currentTimeMillis());
        return p == MANHA ? Ui.tr("THIS MORNING") : p == TARDE ? Ui.tr("THIS AFTERNOON") : Ui.tr("TONIGHT");
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** Which of the 3 the window shows while nobody is browsing (0 to 2). */
    static int spot() {
        return (int) ((System.currentTimeMillis() % ROTATE_EVERY) / SPOT_EVERY);
    }

    /** The tab's next set, so its pictures are ready before it comes in. */
    List<Pick> next(String kind) {
        return at(kind, System.currentTimeMillis() / ROTATE_EVERY + 1);
    }

    /** This set's 3 (a new set every ROTATE_EVERY), movies only ("movie") or shows only ("tv"); null = both (the tabs). */
    List<Pick> now(String kind) {
        return at(kind, System.currentTimeMillis() / ROTATE_EVERY);
    }

    /**
     * The 3 for one tab in rotation slot `slot` (a new one every ROTATE_EVERY). The tabs never share a title: in each
     * slot Movies takes the next 3 movies, Shows the next 3 shows and Explore the 2 movies and 1 show after those.
     */
    private List<Pick> at(String kind, long slot) {
        List<Pick> movies = new ArrayList<>(), shows = new ArrayList<>();
        if (!hasKey(context)) return movies;    // no TMDB key: no recommendations
        Map<String, Boolean> have = new HashMap<>();   // one look per app, not per title (this runs every 15 s)
        for (Pick p : picks) {
            if (!p.detailed || (p.pkg != null && have.computeIfAbsent(p.pkg, this::installed)))   // app missing: skip
                ("movie".equals(p.kind) ? movies : shows).add(p);
        }
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if ("movie".equals(kind)) take(out, seen, movies, slot * 3, 3);
        else if ("tv".equals(kind)) take(out, seen, shows, slot * 3, 3);
        else {
            // What Movies and Shows show this slot, so Explore skips them (and their franchises).
            take(new ArrayList<>(), seen, movies, slot * 3, 3);
            take(new ArrayList<>(), seen, shows, slot * 3, 3);
            List<Pick> mixed = new ArrayList<>();
            take(mixed, new java.util.HashSet<>(seen), movies, slot * 3 + 3, 2);
            take(mixed, new java.util.HashSet<>(seen), shows, slot * 3 + 3, 1);
            for (Pick p : mixed) if (out.size() < 3 && seen.add(franchise(p.title))) out.add(p);
            if (out.size() < 3) {   // a small list: whatever's left
                List<Pick> all = new ArrayList<>(movies);
                all.addAll(shows);
                take(out, seen, all, slot * 3, 3 - out.size());
            }
        }
        return out;
    }

    /** Adds up to `n` from `list`, starting at `from` (wrapping), never two from the same franchise or one in `seen`. */
    private static void take(List<Pick> out, java.util.Set<String> seen, List<Pick> list, long from, int n) {
        int added = 0;
        for (int i = 0; i < list.size() && added < n; i++) {
            Pick p = list.get((int) ((from + i) % list.size()));
            if (seen.add(franchise(p.title))) {
                out.add(p);
                added++;
            }
        }
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replace("/", ""));
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = sourceStamp();   // a new key, country, age band, animation choice or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        // Another age band (Home Settings): the old picks may be too old for the child, so none show until the new ones come.
        if (!listBand.isEmpty() && !listBand.equals(Prefs.ageBand())) {
            picks = new ArrayList<>();
            listBand = "";
        }
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.size() < 9   // a thin list: again
                || !listLanguage.equals(Ui.language);
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick) || needsLink(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) fetchList(p);
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (pick.pkg != null) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                    findLink(pick);
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && have.computeIfAbsent(p.pkg, this::installed) && hasImage(p)
                    && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** This set's picks for the two tabs (Movies, Shows) and the next set's, without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        long slot = System.currentTimeMillis() / ROTATE_EVERY;
        for (long s = slot; s <= slot + 1; s++) {
            for (String kind : new String[] {"movie", "tv"}) {
                for (Pick p : at(kind, s)) if (seen.add(key(p))) out.add(p);
            }
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        // None of the services we know is on the TV (or hide_services hides them all): no region would change that.
        boolean any = false;
        for (String[] s : SERVICES) any |= installed(s[2]);
        if (!any) throw new IOException(NO_KNOWN_APPS);
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"), o.optInt("provider_id"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty()) throw new IOException(NO_APPS);
        // Kids only: the age band's vetted catalog, the titles not shown lately first.
        java.util.Set<String> history = history();
        loadCatalog();
        List<Pick> handMovies = handPicked(catalogMovies, "movie", history, 40);
        List<Pick> handShows = handPicked(catalogShows, "tv", history, 20);
        String band = Prefs.ageBand();
        // Only vetted titles, ever: no "highly rated" fallback from TMDB at large (it once let an R-rated anime in).
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        alternate(list, seen, handMovies, handShows, 60);
        AppLog.w("picks: " + handMovies.size() + " movies and " + handShows.size() + " shows from the " + band + " catalog");
        // A bad moment for TMDB (rate limits, an ISP resetting connections): keep the list we have and try again later
        // rather than show a thin one.
        if (list.size() < 9 && !picks.isEmpty()) throw new IOException("only " + list.size() + " titles came back from TMDB");
        remember(list);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
        listBand = band;
    }

    /** Language of titles and genres (TMDB's "fr-FR", "zh-CN"…); the services come from the region. */
    private static String lang() {
        String lang = Ui.language;
        if (lang.equals("en")) return "en-US";
        if (lang.equals("pt")) return "pt-BR";
        if (lang.equals("es")) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (lang.equals("de")) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        if (lang.indexOf('-') > 0) return lang;
        return lang + "-" + Ui.locale().getCountry();
    }

    /**
     * The vetted catalog for the child's age band (app/assets/catalog/<band>.json, built by catalog/pipeline.py: TMDB age
     * ratings, Jev's judgments and a person's review). Ids by kind, loaded once per band (the band can change in Home
     * Settings).
     */
    private static int[] catalogMovies, catalogShows;
    private static final Map<String, String> catalogBadges = new HashMap<>();   // "movie:12" -> "must"
    // Titles nearly every family has seen already (The Lion King, Frozen): only a few per list, so the less known
    // gems of the catalog make most of what the child sees.
    private static final java.util.Set<String> catalogFamous = new java.util.HashSet<>();
    private static final int FAMOUS_MOVIES = 2, FAMOUS_SHOWS = 1;
    private static final Map<String, Double> catalogScores = new HashMap<>();   // Jev's 0-1 score: higher shows up more
    private static String catalogBand;

    private void loadCatalog() {
        String band = Prefs.ageBand();
        if (catalogMovies != null && band.equals(catalogBand)) return;
        catalogBand = band;
        catalogBadges.clear();
        catalogFamous.clear();
        catalogScores.clear();
        List<Integer> movies = new ArrayList<>(), shows = new ArrayList<>();
        try (java.io.InputStream in = context.getAssets().open("catalog/" + band + ".json")) {
            JSONArray titles = new JSONObject(read(in)).getJSONArray("titles");
            for (int i = 0; i < titles.length(); i++) {
                JSONObject t = titles.getJSONObject(i);
                ("movie".equals(t.optString("kind")) ? movies : shows).add(t.getInt("id"));
                if (!t.optString("badge").isEmpty()) catalogBadges.put(t.optString("kind") + ":" + t.getInt("id"), t.optString("badge"));
                if (t.optBoolean("famous")) catalogFamous.add(t.optString("kind") + ":" + t.getInt("id"));
                catalogScores.put(t.optString("kind") + ":" + t.getInt("id"), t.optDouble("score", 0.7));
            }
        } catch (Exception e) {
            AppLog.w("catalog " + band + ": " + e.getMessage());
        }
        catalogMovies = movies.stream().mapToInt(Integer::intValue).toArray();
        catalogShows = shows.stream().mapToInt(Integer::intValue).toArray();
    }

    private static final int HISTORY_MAX = 240;

    /**
     * Titles the child opened from a card: never recommended again (kept on the TV only). Only when the catalog runs
     * nearly dry do they come back.
     */
    void markWatched(Pick p) {
        if (p == null || p.kind == null) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences("recs_history", Context.MODE_PRIVATE);
        java.util.Set<String> all = new java.util.HashSet<>(prefs.getStringSet("watched", new java.util.HashSet<>()));
        if (!all.add(key(p))) return;
        prefs.edit().putStringSet("watched", all).apply();
        // Out of the list now, so the cards move on to something new.
        List<Pick> left = new ArrayList<>(picks);
        left.remove(p);
        picks = left;
        save();
        notifyListeners();
    }

    /** Catalog titles none of the TV's apps had when last looked up, for a week ("movie:12"). */
    private java.util.Set<String> unavailable() {
        long today = System.currentTimeMillis() / 86_400_000L;
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String e : context.getSharedPreferences("recs_history", Context.MODE_PRIVATE).getStringSet("away", new java.util.HashSet<>())) {
            int at = e.lastIndexOf('@');
            if (at > 0 && today - Long.parseLong(e.substring(at + 1)) < 7) out.add(e.substring(0, at));
        }
        return out;
    }

    private void rememberUnavailable(java.util.Set<String> keys) {
        if (keys.isEmpty()) return;
        long today = System.currentTimeMillis() / 86_400_000L;
        android.content.SharedPreferences prefs = context.getSharedPreferences("recs_history", Context.MODE_PRIVATE);
        java.util.Set<String> all = new java.util.HashSet<>();
        for (String e : prefs.getStringSet("away", new java.util.HashSet<>())) {   // the expired ones go
            int at = e.lastIndexOf('@');
            if (at > 0 && today - Long.parseLong(e.substring(at + 1)) < 7) all.add(e);
        }
        for (String k : keys) all.add(k + "@" + today);
        prefs.edit().putStringSet("away", all).apply();
    }

    private java.util.Set<String> watched() {
        return context.getSharedPreferences("recs_history", Context.MODE_PRIVATE).getStringSet("watched", new java.util.HashSet<>());
    }

    /**
     * Up to `max` unseen hand-picked titles that are on one of the TV's services (looked up one by one): the less known
     * ones in a new order each time, with at most a couple of the famous ones mixed in at random places.
     */
    private List<Pick> handPicked(int[] ids, String kind, java.util.Set<String> history, int max) {
        List<Integer> gems = new ArrayList<>(), famous = new ArrayList<>();
        java.util.Set<String> watched = watched();
        List<Integer> fresh = new ArrayList<>();
        for (int id : ids) if (!watched.contains(kind + ":" + id)) fresh.add(id);
        if (fresh.size() < 12) for (int id : ids) if (!fresh.contains(id)) fresh.add(id);   // nearly all watched
        // Not on the TV's apps when last looked up (within a week): skipped, no need to ask TMDB again.
        java.util.Set<String> away = unavailable();
        List<Integer> seenLately = new ArrayList<>();
        for (int id : fresh) {
            String k = kind + ":" + id;
            if (away.contains(k)) continue;
            if (history.contains(k)) {
                if (!catalogFamous.contains(k)) seenLately.add(id);
            } else {
                (catalogFamous.contains(k) ? famous : gems).add(id);
            }
        }
        weightedShuffle(gems, kind);
        weightedShuffle(famous, kind);
        weightedShuffle(seenLately, kind);
        // Not shown lately first; then the ones shown lately, so the list still fills (the history must never hide
        // everything the TV's apps actually have).
        List<Integer> order = new ArrayList<>(gems);
        int few = "movie".equals(kind) ? FAMOUS_MOVIES : FAMOUS_SHOWS;
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; i < Math.min(few, famous.size()); i++) order.add(rnd.nextInt(Math.min(order.size(), max) + 1), famous.get(i));
        order.addAll(seenLately);
        if (order.size() < max) for (int id : famous) if (!order.contains(id)) order.add(id);   // a small catalog
        // Looked up six at a time (on a connection that drops half the requests, one by one took minutes), in batches,
        // keeping the order, until there are enough.
        List<Pick> out = new ArrayList<>();
        int tried = 0, notHere = 0, noImage = 0, failed = 0;
        java.util.Set<String> nowAway = new java.util.HashSet<>();
        String lastError = "";
        java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            int limit = Math.min(order.size(), max * 6);
            for (int from = 0; from < limit && out.size() < max; from += 12) {
                List<java.util.concurrent.Future<Pick>> batch = new ArrayList<>();
                for (int i = from; i < Math.min(limit, from + 12); i++) {
                    final Pick p = new Pick();
                    p.id = order.get(i);
                    p.kind = kind;
                    String badge = catalogBadges.get(kind + ":" + p.id);
                    if (badge != null) p.badge = badge;
                    batch.add(pool.submit(() -> {
                        fetchDetails(p);
                        return p;
                    }));
                }
                for (java.util.concurrent.Future<Pick> f : batch) {
                    tried++;
                    try {
                        Pick p = f.get();
                        if (p.pkg == null) {
                            notHere++;
                            nowAway.add(kind + ":" + p.id);
                        }
                        else if (p.backdrop == null) noImage++;
                        else if (out.size() < max) out.add(p);
                    } catch (Exception e) {
                        failed++;   // this one didn't come back: the others still count
                        Throwable t = e.getCause() == null ? e : e.getCause();
                        lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        rememberUnavailable(nowAway);
        AppLog.w(kind + ": " + order.size() + " to look up, " + tried + " looked up, " + out.size() + " kept, " + notHere
                + " not on the TV's apps, " + noImage + " without a picture, " + failed + " failed" + (failed > 0 ? " (" + lastError + ")" : ""));
        return out;
    }

    /**
     * A new order each time, but the best (Jev's score: lasting value, must-watch, fun) come early more often: each id
     * draws random^(1/weight), weight = score^4 (Efraimidis-Spirakis), and the highest draws go first.
     */
    private static void weightedShuffle(List<Integer> ids, String kind) {
        java.util.Random rnd = new java.util.Random();
        final Map<Integer, Double> draw = new HashMap<>();
        for (int id : ids) {
            Double score = catalogScores.get(kind + ":" + id);
            double w = Math.pow(Math.max(0.05, score == null ? 0.7 : score), 4);
            draw.put(id, Math.pow(rnd.nextDouble(), 1 / w));
        }
        java.util.Collections.sort(ids, (a, b) -> Double.compare(draw.get(b), draw.get(a)));
    }

    private static List<Pick> unseen(List<Pick> picks, java.util.Set<String> history) {
        List<Pick> out = new ArrayList<>();
        for (Pick p : picks) if (!history.contains(p.kind + ":" + p.id)) out.add(p);
        return out;
    }

    private java.util.Set<String> history() {
        String saved = context.getSharedPreferences("recs_history", Context.MODE_PRIVATE).getString("shown", "");
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String k : saved.split(",")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    /** Remembers the newest HISTORY_MAX titles shown, oldest dropped first. */
    private void remember(List<Pick> list) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("recs_history", Context.MODE_PRIVATE);
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        for (String k : prefs.getString("shown", "").split(",")) if (!k.isEmpty()) all.add(k);
        for (Pick p : list) {
            all.remove(p.kind + ":" + p.id);
            all.add(p.kind + ":" + p.id);
        }
        List<String> keep = new ArrayList<>(all);
        if (keep.size() > HISTORY_MAX) keep = keep.subList(keep.size() - HISTORY_MAX, keep.size());
        prefs.edit().putString("shown", String.join(",", keep)).apply();
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        if (Config.HIDE_ANIMATION && animated(o)) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        list.add(p);
    }

    private static boolean animated(JSONObject o) {
        JSONArray genres = o.optJSONArray("genre_ids");
        for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return true;
        return false;
    }

    /** The story in one line: TMDB's tagline, or the synopsis's first sentence when it's short. */
    private static String storyLine(JSONObject o) {
        String t = o.optString("tagline").trim();
        if (!t.isEmpty()) return t;
        String first = o.optString("overview").split("(?<=[.!?。])\\s", 2)[0].trim();
        return first.length() <= 120 ? first : "";
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images&include_image_language=null," + Ui.language.replaceAll("-.*", "") + ",en");
        p.title = title(p, o);
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(genre(name));
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0) parts.add(minutes >= 60 ? Ui.trf("{h}h {m}m", "h", minutes / 60, "m", minutes % 60) : Ui.trf("{m}m", "m", minutes));
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0) parts.add(Ui.plural("plural|one:{n} season|other:{n} seasons", seasons));
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"), flat.getJSONObject(i).optInt("provider_id"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        if (p.app != null) parts.add(SERVICE_NAME.get(p.app));
        p.meta = String.join(" · ", parts);
        p.tagline = storyLine(o);
        p.detailed = true;
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private String title(Pick p, JSONObject o) {
        String field = p.kind.equals("movie") ? "title" : "name";
        String own = o.optString(field, p.title);
        String original = o.optString("original_language");
        String lang = Ui.language.replaceAll("-.*", "");   // TMDB's original_language has no country: "zh", not "zh-CN"
        if (lang.equals("en") || own.isEmpty() || !own.equals(o.optString("original_" + field))
                || lang.equals(original) || "en".equals(original)) return own;
        try {
            String english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
            return english.isEmpty() ? own : english;
        } catch (Exception e) {
            return own;
        }
    }

    /** TV genres that TMDB returns in English even in other languages (pt-BR, es, de…). */
    private static String genre(String name) {
        switch (name) {
            case "Action & Adventure": return Ui.tr("genre|Action & Adventure");
            case "Sci-Fi & Fantasy": return Ui.tr("genre|Sci-Fi & Fantasy");
            case "War & Politics": return Ui.tr("genre|War & Politics");
            case "Kids": return Ui.tr("genre|Kids");
            case "News": return Ui.tr("genre|News");
            case "Soap": return Ui.tr("genre|Soap");
            case "Talk": return Ui.tr("genre|Talk");
            default: return name;
        }
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), the closest to 1920 wide
     * (larger only costs download, disk and decoding) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 1920) * 100 - Math.max(0, width - 1920) + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    /** The SERVICES row for a TMDB name: the installed one first, when a service has more than one package. */
    private String[] service(String name, int providerId) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        // "HBO Max Amazon Channel", "Apple TV Amazon Channel"...: a subscription sold inside another app, not this one.
        if (n.contains(" channel")) return null;
        for (String[] s : SERVICES) {
            // Apple TV: only the streaming service (TMDB provider 350); provider 2 is the Apple TV Store (rent or buy).
            if (s[1].equals("appletv") && providerId != 350) continue;
            if (!(s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0]))) continue;
            if (installed(s[2])) return s;
            if (first == null) first = s;
        }
        return first;
    }

    /** Services named in hide_services (config.properties), trimmed and lowercased: never a source of picks. */
    private static final Set<String> HIDDEN = new HashSet<>();
    static {
        for (String s : Config.HIDE_SERVICES.split(",")) {
            String key = s.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) HIDDEN.add(key);
        }
    }

    /** The service's app is on the TV, and the service isn't hidden (an app the TV ships without a subscription, say). */
    private boolean installed(String pkg) {
        if (!HIDDEN.isEmpty()) for (String[] s : SERVICES) if (s[2].equals(pkg) && HIDDEN.contains(s[1])) return false;
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private long sourceStamp() {
        long stamp = ((Config.TMDB_KEY.hashCode() * 31L + REGION.hashCode()) * 31L + (Config.HIDE_ANIMATION ? 1 : 0)) * 31L
                + Prefs.ageBand().hashCode();
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
        return stamp;
    }

    // MARK: - Watchmode (the title's own page in its app; the free plan's web addresses are what the apps open)

    private static String linkKey(Pick p) {
        return "wm:" + p.kind + p.id + p.app;
    }

    private boolean needsLink(Pick p) {
        return linksOn() && p.pkg != null && p.link == null
                && cache.getString(linkKey(p), null) == null && System.currentTimeMillis() >= linkRetryAt;
    }

    /** A key is set and Watchmode hasn't refused it (a refused key isn't tried again until it changes). */
    private boolean linksOn() {
        String key = Config.WATCHMODE_KEY.trim();
        return !key.isEmpty() && cache.getInt("wm:refused", 0) != key.hashCode();
    }

    /** Sets p.link from what was looked up before, or looks it up (2 requests of the free plan's 2,500 a month). */
    private void findLink(Pick p) {
        if (!linksOn() || p.pkg == null || p.link != null) return;
        String saved = cache.getString(linkKey(p), null);
        if (saved != null) {
            p.link = saved.isEmpty() ? null : saved;
            return;
        }
        if (System.currentTimeMillis() < linkRetryAt) return;
        try {
            String link = "";
            JSONArray found = new JSONObject(watchmode("/search/?search_field=tmdb_" + p.kind + "_id&search_value=" + p.id))
                    .optJSONArray("title_results");
            if (found != null && found.length() > 0) {
                JSONArray sources = new JSONArray(watchmode("/title/" + found.getJSONObject(0).getInt("id") + "/sources/?regions=" + REGION));
                String[] service = null;
                for (String[] s : SERVICES) if (s[1].equals(p.app)) service = s;
                for (int i = 0; service != null && i < sources.length() && link.isEmpty(); i++) {
                    JSONObject s = sources.getJSONObject(i);
                    String name = s.optString("name").toLowerCase(Locale.ROOT).replace(" ", "");
                    String want = service[0].replace(" ", "");
                    boolean same = want.equals("max") ? name.equals("max") : name.contains(want);
                    if (same && s.optString("type").equals("sub") && s.optString("web_url").startsWith("https://")) link = s.optString("web_url");
                }
            }
            cache.edit().putString(linkKey(p), link).apply();
            p.link = link.isEmpty() ? null : link;
        } catch (Exception e) {
            if ("Watchmode returned HTTP 401".equals(e.getMessage())) {
                cache.edit().putInt("wm:refused", Config.WATCHMODE_KEY.trim().hashCode()).apply();
            }
            linkRetryAt = System.currentTimeMillis() + 10 * 60_000L;   // quota or network: not again right away
            AppLog.w("watchmode: " + e.getMessage());
        }
    }

    private static String watchmode(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.watchmode.com/v1" + path + (path.contains("?") ? "&" : "?")
                + "apiKey=" + URLEncoder.encode(Config.WATCHMODE_KEY.trim(), "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Watchmode returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /**
     * Some ISPs (India) reset half or more of the connections to TMDB, and on Android a reset shows up as several kinds
     * of IOException; so any network failure is retried a few times. TMDB's own answers (a refused key, a missing
     * title) are not.
     */
    private JSONObject get(String path) throws IOException, org.json.JSONException {
        for (int attempt = 1; ; attempt++) {
            try {
                return getOnce(path);
            } catch (IOException e) {
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (attempt >= 8 || m.startsWith("TMDB rejected") || m.startsWith("TMDB returned HTTP") || m.equals("no TMDB key")) throw e;
                try {
                    Thread.sleep(250L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private JSONObject getOnce(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code == 429) throw new java.net.SocketTimeoutException("TMDB rate limit");   // retried, with a pause
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private String readKey() {
        String key = Config.TMDB_KEY.trim();
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        File f = imageFile(p);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(IMAGES + p.backdrop).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the photo (≥ the UI's width) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight + " → " + Backdrop.W + "×" + Backdrop.H);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                while (images.size() > (forLight ? 12 : 4)) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    void trim() {
        // Movies' picks stay: the home screen opens on that tab, so its cards are never gray when you come back.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Pick p : now("movie")) if (p.backdrop != null) keep.add(p.backdrop);
        synchronized (images) {
            images.keySet().retainAll(keep);
        }
    }

    private static void prune(File dir) {
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null || files.length <= 24) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 24; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop).put("link", p.link == null ? "" : p.link)
                        .put("meta", p.meta).put("badge", p.badge).put("tagline", p.tagline).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("detailed", p.detailed));
            }
            SharedPreferences.Editor e = cache.edit().putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt)
                    .putString("language", listLanguage).putString("band", listBand)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION);
            // Watchmode links of titles no longer in the list go (the refused-key mark stays).
            Set<String> keep = new HashSet<>();
            for (Pick p : picks) keep.add(linkKey(p));
            for (String k : cache.getAll().keySet()) if (k.startsWith("wm:") && !k.equals("wm:refused") && !keep.contains(k)) e.remove(k);
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.meta = o.optString("meta");
            p.badge = o.optString("badge");
            p.tagline = o.optString("tagline");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
