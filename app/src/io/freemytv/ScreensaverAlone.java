package io.freemytv;

/**
 * The screensaver on Android 11 and 12. There the system's DreamActivity runs in the app's default process and only
 * works with the DreamService in that same process, which can't also hold a home activity (#10; on a Sony BRAVIA VH22
 * with Android 12, Screensaver in :home crashed with "BinderProxy cannot be cast to DreamActivityCallbacks"). So
 * those versions get this copy, in the main process; the others keep Screensaver in :home (values-v30, values-v33).
 */
public class ScreensaverAlone extends Screensaver {
    @Override public void onAttachedToWindow() {
        Prefs.reload();   // the settings screen saves from :home; this process keeps what it first read
        super.onAttachedToWindow();
    }
}
