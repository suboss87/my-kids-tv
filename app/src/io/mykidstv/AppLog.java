package io.mykidstv;

import android.util.Log;

/** The app's log (adb logcat -s MyKidsTV). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("MyKidsTV", message);
    }
}
