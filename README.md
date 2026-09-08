# vantage-patches

Vantage-only [morphe](https://github.com/MorpheApp) patches, published as a
`.mpp` bundle that [pmaxhogan/vantage](https://github.com/pmaxhogan/vantage)
**stacks on top of** the anddea bundle. `morphe-cli patch` accepts repeated
`--patches`, so this bundle adds patches without forking anddea.

Everything here is deliberately small. Anything that belongs upstream should go
upstream instead; this repo is for patches that only Vantage wants.

## Patches

### Keep playback on activity destroy (YouTube Music)

Keeps background playback and its media foreground service alive when the system
destroys `MusicActivity` to reclaim memory. A user-initiated close still stops
playback.

Why the activity dies without the app finishing it: `ActivityThread` installs a
`BinderInternal` GC watcher at attach time. After a GC, if activities have
changed and the app's own ART heap sits above 3/4 of `Runtime.maxMemory()`, it
calls `ActivityTaskManager.getService().releaseSomeActivities(mAppThread)`. The
system then runs `WindowProcessController.releaseSomeActivities("low-mem")`,
which calls `ActivityRecord.destroyImmediately("low-mem")` on every activity of
that process that is non-visible, stopped and holds saved state. It is the only
path that destroys a **stopped, non-visible** activity without finishing it, so
`Activity.isFinishing()` is false there and true for every user-initiated close.
It is not the only destroy-without-finish in the framework, though: a
configuration-change relaunch also reports `isFinishing()` false, so the gate
checks `isChangingConfigurations()` too and suppresses nothing on a rotation.

YouTube Music reacts to that destroy by deactivating its media session, running
`MedialibPlayer.stopVideo` with `STOPPAGE_DIRECTOR_RESET_INTERNALLY`, dropping
the foreground service and going cached, even though the player itself lives in
an application-scoped component and had been playing happily with the activity
merely stopped.

The patch opens a short suppression window in `MusicActivity.onDestroy` when
`isFinishing()` is false, and swallows exactly two calls while it is open: the
player's `stopVideo`, and the media session's `setActive(false)`. It also closes
the window in `BackgroundPlayerService.onTaskRemoved`, so a swipe from recents
landing seconds after a system destroy still stops playback. Everything else
about the destroy proceeds untouched, so the activity is still released and its
memory still reclaimed.

## Layout

    patches/      the morphe patch declarations (Kotlin)
    extensions/   the code injected into the app (Java, namespace app.vantage.extension.*)

The extension namespace must **not** be `app.morphe.extension.*`: this bundle is
merged into the same APK as the anddea bundle, which already ships classes under
that prefix.

## Build

    export GITHUB_ACTOR=<your github user>
    export GITHUB_TOKEN=<a token with read:packages>   # the morphe maven repo
    ./gradlew :patches:buildAndroid

The bundle lands at `patches/build/libs/patches-<version>.mpp`.

`.github/workflows/build-mpp.yml` builds it on every push to `main` and
publishes it as a release asset. The repository variable `MPP_PRERELEASE`
("true"/"false") gates whether the release is a prerelease; vantage reads
`/releases/latest`, which skips prereleases.

## Consuming it from vantage

`config/build.env`:

    VANTAGE_PATCHES_REPO=pmaxhogan/vantage-patches
    VANTAGE_PATCHES_CHANNEL=release

`scripts/build.sh` resolves the latest release the same way it resolves piko,
then passes a second `--patches` to the Music variant only.
