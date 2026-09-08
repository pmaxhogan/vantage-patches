package app.vantage.patches.music.keepplayback

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch

private const val EXTENSION_CLASS = "Lapp/vantage/extension/music/KeepPlayback;"

private val COMPATIBILITY_MUSIC =
    Compatibility(
        name = "YouTube Music",
        packageName = "com.google.android.apps.youtube.music",
        targets = listOf(AppTarget(version = "9.15.51")),
    )

@Suppress("unused")
val keepPlaybackOnActivityDestroyPatch =
    bytecodePatch(
        name = "Keep playback on activity destroy",
        description = "Keeps background playback and its media foreground service alive when " +
            "the system destroys MusicActivity to reclaim memory. A user-initiated close " +
            "(back press or swipe from recents) still stops playback.",
    ) {
        compatibleWith(COMPATIBILITY_MUSIC)

        extendWith("extensions/music.mpe")

        execute {
            // 1. Record whether this destroy is system-initiated. p0 is `this`
            //    (the MusicActivity), so the extension can call isFinishing().
            musicActivityOnDestroyFingerprint.method.addInstruction(
                0,
                "invoke-static { p0 }, $EXTENSION_CLASS->onActivityDestroy(Ljava/lang/Object;)V",
            )

            // 2. Swallow the director's stopVideo inside that window.
            stopVideoFingerprint.method.addInstructionsWithLabels(
                0,
                """
                invoke-static { p1 }, $EXTENSION_CLASS->onBeforeStopVideo(I)Z
                move-result v0
                if-eqz v0, :vantage_keep_continue
                return-void
                :vantage_keep_continue
                nop
                """,
            )

            // 3. Close the window when the user swipes the task from recents,
            //    so a swipe landing seconds after a system destroy still stops
            //    playback the way it does today.
            playerServiceOnTaskRemovedFingerprint.method.addInstruction(
                0,
                "invoke-static { }, $EXTENSION_CLASS->onPlayerServiceTaskRemoved()V",
            )

            // 4. Swallow MediaSession setActive(false) inside the same window.
            setMediaSessionActiveFingerprint.method.addInstructionsWithLabels(
                0,
                """
                invoke-static { p1 }, $EXTENSION_CLASS->onBeforeSetMediaSessionActive(Z)Z
                move-result v0
                if-eqz v0, :vantage_keep_session_continue
                return-void
                :vantage_keep_session_continue
                nop
                """,
            )
        }
    }
