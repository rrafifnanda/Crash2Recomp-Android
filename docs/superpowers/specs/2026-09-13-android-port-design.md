# Crash2Recomp Android Port Design

## Goal

Add a self-contained Android version of Crash2Recomp. On Android 9 or newer,
an ARM64 user imports a legally owned North American Crash Bandicoot 2 disc
dump, builds the recompiled game entirely on the device, and launches it from
the same app. The repository and APK contain no game data or generated game
code.

## Supported target

- Android 9 / API 28 or newer.
- `arm64-v8a` only.
- Landscape phones, tablets, and handhelds.
- Crash Bandicoot 2 North American release, serial `SCUS-94154`.
- Input formats: `.chd`, or a `.cue` with every referenced `.bin` track.
- OpenGL ES through SDL3. Vulkan is outside the first release.

## User flow

The launcher has four destinations: Setup, Play, Settings, and Log.

1. Setup uses Android's Storage Access Framework to select a `.chd`, or a
   `.cue` together with all of its `.bin` files.
2. The importer copies the selected files into app-owned storage. It verifies
   the cue structure, track presence, sector alignment, boot serial, and known
   hashes when the source format permits it.
3. Build starts a foreground service and runs four resumable stages: extract,
   generate, compile/link, and verify.
4. Play remains disabled until verification successfully loads the resulting
   ARM64 library and confirms that its build identity matches the imported
   disc and bundled framework.
5. Play launches the SDL runtime in a separate `:game` process. Exiting the
   game returns to the launcher without requiring the app to reinitialize SDL
   in the launcher process.

Selecting a different disc or installing a framework update invalidates the
compiled runtime but never removes memory cards or settings.

## Architecture

### Android launcher

`LauncherActivity` owns navigation and displays build state. It delegates
disc copying to `DiscImporter`, build work to `BuildService`, and native work
to a small JNI boundary. Android APIs provide file selection, notifications,
and app-private storage; no UI framework dependency is needed.

### On-device builder

`libbuilder.so` exposes validation and build operations to `BuildService`. The
APK includes ARM64 builds of the PSXRecomp game generator and the minimum
compiler/linker payload required to produce the runtime library. Tools and
headers are immutable APK assets copied into executable app-owned locations
only where Android permits execution. Generated source and object files stay
in the app cache or files directory.

Each stage writes an atomic completion marker containing the disc hash,
framework version, compiler version, and relevant configuration hash. A retry
reuses only stages whose marker still matches. Cancellation terminates the
active child process, preserves completed stages, and leaves no library marked
playable until final verification succeeds.

Before implementation, a device probe must confirm the executable and dynamic
loading rules on API 28 and a current Android version. If direct execution from
app storage is rejected by modern Android, the packaged tools will be invoked
in-process through JNI instead of weakening platform security.

### Game runtime

`libmain.so` adapts the existing PSXRecomp runtime to Android using SDL3,
OpenGL ES, Android audio, and Android storage paths. It loads the user's
verified generated library from private storage. Game settings, logs, memory
cards, and build state live beneath the app's external-files directory; build
internals that should not be user-visible live in internal storage.

The runtime starts with the existing Crash2Recomp tuning patches that are
portable to Android. Windows-only launcher behavior, paths, and compiler
assumptions are not carried over.

## First-release controls and settings

SDL3 handles physical controllers. A native Android overlay supplies D-pad,
Cross, Circle, Square, Triangle, L1, R1, Start, and Select. Users can resize,
move, hide, and restore the overlay. Touch targets retain accessible minimum
sizes.

The launcher initially exposes:

- internal resolution scale;
- 4:3 or 16:9 presentation;
- volume and audio latency;
- vibration;
- touch-control size and position.

Rewind, Vulkan, runtime overlay compilation, the TCP debug server, and desktop
diagnostic controls are deferred until the basic port is playable.

## Storage and legal boundary

The source repository, build inputs, and distributable APK must not contain a
disc image, extracted executable, generated C, compiled game library, or native
overlay cache derived from the game. `.gitignore` and a distribution audit
enforce this boundary.

The user-owned disc copy, generated sources, compiled library, caches, and
saves remain local to that user's Android installation. Uninstalling the app
may remove app-owned data, so memory-card export is required before calling the
first release complete.

## Errors and recovery

Errors identify the failed stage and the next action. Required cases include:

- unsupported region or serial;
- incomplete cue/bin selection;
- malformed or truncated tracks;
- insufficient free space before a build begins;
- process cancellation or Android killing the build service;
- generator, compiler, linker, or library-verification failure;
- stale output after a disc, framework, compiler, or configuration change.

The Log page retains stdout, stderr, stage timings, and the final error. It can
export a text report without exposing game-derived files.

## Verification

Checks are deliberately small and cover the real boundaries:

1. A host test validates cue parsing, serial detection, build identities, and
   stage-resume decisions using synthetic fixtures.
2. Gradle and CMake build the ARM64 APK without any game data.
3. An APK audit fails if it contains known game-data extensions, generated
   game sources, compiled user game libraries, or overlay caches.
4. On a physical API 28+ ARM64 device, import a genuine `SCUS-94154` dump,
   complete a clean build, boot gameplay, verify physical and touch input,
   audio, vibration, memory-card persistence, exit, and relaunch.
5. Cancel and resume one build, then change a build identity input and confirm
   that only invalid stages are rebuilt.

## Delivery

The result is an Android Studio/Gradle project inside the Crash2Recomp
repository, build scripts for the native dependencies and APK, source-only
release documentation, and a reproducible unsigned/debug APK build. Signing
credentials and store publishing are not part of this port.
