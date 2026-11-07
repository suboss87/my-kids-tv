package io.mykidstv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the child was in the middle of, as the streaming apps publish it to Android TV's "Continue watching" (Watch
 * Next) rows. Read with READ_TV_LISTINGS, a runtime permission the switch grants through ADB (CleanYourTV's
 * docs/watch-next.md: a sideloaded app sees its own rows and every row an app marked searchable; Netflix publishes
 * none on many sets, Prime Video, Max, Crunchyroll and Jellyfin do). Only rows from the streaming apps Recs knows,
 * and Recs then keeps only titles in the child's catalog: nothing a parent watched can reach the child's screen.
 * Read the whole table with a named projection and filter here: a selection, or a null projection, fails on some
 * TVs (a Philips on Android 12 has no split_id column).
 */
final class WatchNext {
    private WatchNext() {}

    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    /** Rows older than this, or nearly finished, are over: the child moved on. */
    private static final long FRESH_MS = 14 * 86_400_000L;
    private static final float DONE = 0.95f;

    static final class Row {
        String pkg, title, episodeTitle, intent, poster;
        int season, episode, type;
        long engagedAt, position, duration;

        /** "S2 E4", or "" for a film. */
        String where() {
            if (season <= 0 && episode <= 0) return "";
            return (season > 0 ? "S" + season + " " : "") + (episode > 0 ? "E" + episode : "");
        }

        /** Minutes left, or 0 when the row doesn't say. */
        int minutesLeft() {
            if (duration <= 0 || position < 0 || position > duration) return 0;
            return (int) Math.max(1, (duration - position) / 60_000L);
        }
    }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** The fresh "continue" and "next" rows from `packages`, newest engagement first; empty without the permission. */
    static List<Row> read(Context c, Set<String> packages) {
        List<Row> out = new ArrayList<>();
        if (!allowed(c) || packages.isEmpty()) return out;
        String[] projection = {
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME, TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
        };
        long now = System.currentTimeMillis();
        try (Cursor cur = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, projection, null, null, null)) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                Row r = new Row();
                r.pkg = cur.getString(0);
                if (r.pkg == null || !packages.contains(r.pkg)) continue;
                if (cur.getInt(11) == 0) continue;   // not browsable: the app took it back
                r.type = cur.getInt(5);
                if (r.type != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                        && r.type != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT) continue;
                r.title = cur.getString(1);
                if (r.title == null || r.title.trim().isEmpty()) continue;
                r.episodeTitle = cur.getString(2);
                r.season = number(cur.getString(3));
                r.episode = number(cur.getString(4));
                r.engagedAt = cur.getLong(6);
                if (r.engagedAt <= 0 || now - r.engagedAt > FRESH_MS) continue;
                r.position = cur.getLong(7);
                r.duration = cur.getLong(8);
                if (r.duration > 0 && r.position > 0 && (float) r.position / r.duration >= DONE) continue;
                r.intent = cur.getString(9);
                r.poster = cur.getString(10);
                out.add(r);
            }
        } catch (RuntimeException e) {   // SecurityException without the permission, SQLiteException on odd providers
            AppLog.w("continue watching: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(b.engagedAt, a.engagedAt));
        return out;
    }

    /** "2" or "Season 2" → 2; 0 when there is no number. */
    private static int number(String s) {
        if (s == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(s);
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }

    /** The year in a row's title ("Frozen (2013)"), or 0. */
    static int year(String title) {
        if (title == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[(\\[](\\d{4})[)\\]]\\s*$").matcher(title.trim());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * A title as the catalog and the apps may both spell it: lower case, no accents, no punctuation, one space, and
     * without a trailing "(2019)", ": Season 2" or "- Season 2".
     */
    static String normalize(String title) {
        if (title == null) return "";
        String t = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        t = t.replaceAll("\\s*[(\\[]\\d{4}[)\\]]\\s*$", "");
        t = t.replaceAll("\\s*[:\\-–]\\s*(season|series|part|volume)\\s*\\d+.*$", "");
        t = t.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return t;
    }
}
