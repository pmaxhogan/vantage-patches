package app.vantage.extension.music;

import android.app.Activity;
import android.util.Log;

/**
 * Keeps YouTube Music's background playback alive when the SYSTEM destroys
 * MusicActivity to reclaim memory.
 *
 * <p>Why the activity dies at all: ActivityThread installs a BinderInternal GC
 * watcher at attach time. After any GC, if activities have changed and the
 * app's own ART heap is above 3/4 of Runtime.maxMemory(), it calls
 * ActivityTaskManager.getService().releaseSomeActivities(mAppThread). The system
 * then runs WindowProcessController.releaseSomeActivities("low-mem"), which
 * calls ActivityRecord.destroyImmediately("low-mem") on every activity of that
 * process that is non-visible, stopped and has saved state. That is the only
 * framework path that destroys an activity WITHOUT finishing it, so
 * Activity.isFinishing() is false there and true for every user-initiated close.
 *
 * <p>What YouTube Music then does: MusicActivity.onDestroy tears down its peer
 * graph, the media session is deactivated, MedialibPlayer.stopVideo runs with
 * STOPPAGE_DIRECTOR_RESET_INTERNALLY, the media foreground service drops and
 * the process goes cached - so playback stops even though the player itself
 * lives in an application-scoped component and had been happily playing with
 * the activity merely stopped.
 *
 * <p>What this class does: it opens a short suppression window when a destroy
 * arrives that the user did not ask for, and swallows the two teardown calls
 * that stop playback while that window is open. Everything else about the
 * destroy proceeds normally, so the activity is still released and its memory
 * still reclaimed - only the playback teardown is skipped.
 */
public final class KeepPlayback {

    private static final String TAG = "VKEEP";

    /**
     * How long after a system-initiated destroy the teardown calls are
     * swallowed. The observed teardown lands within ~40 ms of onDestroy, but
     * parts of it are posted through the main-thread Handler and through Rx
     * observers, so the window has to outlive the current message. It is kept
     * short so a genuine later stop (a pause or dismiss from the notification)
     * is never affected.
     */
    private static final long WINDOW_MS = 5_000L;

    /**
     * STOPPAGE_DIRECTOR_RESET_INTERNALLY, the reason the failing teardown passes
     * to stopVideo. Derived from the obfuscated reason-name mapper (caor.a(I) in
     * 9.15.51): its packed-switch starts at 1 and this is the fifth label, and
     * the reproduction logs read "MedialibPlayer.stopVideo(),
     * STOPPAGE_DIRECTOR_RESET_INTERNALLY". Only this reason is ever swallowed,
     * so a user asking the app to stop (reason 33 from the STOP media key,
     * observed on the emulator) is never affected.
     */
    private static final int STOPPAGE_DIRECTOR_RESET_INTERNALLY = 5;

    /** Set true to log every hook with a stack trace. */
    private static final boolean DIAG = false;

    /** Main-thread only in practice; volatile so a player thread sees it. */
    private static volatile long suppressUntil = 0L;

    private KeepPlayback() {}

    /**
     * Called first thing in MusicActivity.onDestroy().
     *
     * @param activity the MusicActivity being destroyed
     */
    public static void onActivityDestroy(Object activity) {
        boolean finishing = true;
        try {
            if (activity instanceof Activity) {
                finishing = ((Activity) activity).isFinishing();
            }
        } catch (Exception ex) {
            // Fail closed: treat an unreadable state as a user-initiated close
            // so playback stops the way it does today.
            Log.e(TAG, "isFinishing() failed, not suppressing", ex);
            finishing = true;
        }

        if (finishing) {
            // Back press, or a swipe from recents: the user closed the app, so
            // let the normal teardown stop playback.
            suppressUntil = 0L;
        } else {
            suppressUntil = System.currentTimeMillis() + WINDOW_MS;
        }

        // Logged unconditionally: a MusicActivity destroy is rare, and this one
        // line is what tells a "playback died again" report apart from a
        // user-initiated close.
        Log.i(TAG, "MusicActivity destroy: finishing=" + finishing
                + " suppressPlaybackTeardown=" + !finishing);
        if (DIAG) {
            Log.w(TAG, "onActivityDestroy stack", new Throwable("onDestroy"));
        }
    }

    /**
     * Called first thing in BackgroundPlayerService.onTaskRemoved(), which is
     * how a swipe from recents stops playback. Closing the window there means a
     * swipe that lands seconds after a system destroy still stops playback.
     */
    public static void onPlayerServiceTaskRemoved() {
        Log.i(TAG, "BackgroundPlayerService.onTaskRemoved: closing the suppression window");
        suppressUntil = 0L;
    }

    /**
     * Called first thing in MedialibPlayer.stopVideo(int reason).
     *
     * @param reason the STOPPAGE_* enum ordinal
     * @return true to swallow the stop
     */
    public static boolean onBeforeStopVideo(int reason) {
        boolean suppress = reason == STOPPAGE_DIRECTOR_RESET_INTERNALLY && isWindowOpen();
        if (DIAG) {
            Log.w(TAG, "stopVideo reason=" + reason + " suppress=" + suppress,
                    new Throwable("stopVideo"));
        }
        if (suppress) {
            Log.i(TAG, "kept playback: swallowed stopVideo(" + reason
                    + ") after a system-initiated MusicActivity destroy");
        }
        return suppress;
    }

    /**
     * Called first thing in the media-session activate/deactivate method.
     * Only a deactivation is ever swallowed.
     *
     * @param active the requested active state
     * @return true to swallow the call
     */
    public static boolean onBeforeSetMediaSessionActive(boolean active) {
        boolean suppress = !active && isWindowOpen();
        if (DIAG) {
            Log.w(TAG, "setMediaSessionActive " + active + " suppress=" + suppress,
                    new Throwable("setActive"));
        }
        if (suppress) {
            Log.i(TAG, "kept playback: swallowed MediaSession setActive(false) "
                    + "after a system-initiated MusicActivity destroy");
        }
        return suppress;
    }

    private static boolean isWindowOpen() {
        long until = suppressUntil;
        return until != 0L && System.currentTimeMillis() < until;
    }
}
