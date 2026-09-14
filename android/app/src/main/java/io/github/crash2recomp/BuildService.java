package io.github.crash2recomp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.StatFs;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BuildService extends Service {
    static final String ACTION_UPDATE = "io.github.crash2recomp.BUILD_UPDATE";
    static final String ACTION_START = "io.github.crash2recomp.START_BUILD";
    static final String ACTION_CANCEL = "io.github.crash2recomp.CANCEL_BUILD";
    static final String EXTRA_DISC = "disc";
    static final String EXTRA_DISC_SHA256 = "disc_sha256";
    static final String EXTRA_STAGE = "stage";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_DONE = "done";
    static final String EXTRA_SUCCESS = "success";

    private static final String CHANNEL = "crash2_build";
    private static final int NOTIFICATION = 94154;
    static final String FRAMEWORK = "bc5b8561";
    static final String COMPILER = "tinycc-0fb54300-abi2";
    static final String CONFIG = "crash2-android-v1";
    private static final long REQUIRED_FREE_BYTES = 3L * 1024 * 1024 * 1024;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Game build", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            BuilderNative.cancel();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        startForeground(NOTIFICATION, notification("Preparing build…"));
        String disc = intent.getStringExtra(EXTRA_DISC);
        String hash = intent.getStringExtra(EXTRA_DISC_SHA256);
        worker.execute(() -> runBuild(disc, hash));
        return START_NOT_STICKY;
    }

    private void runBuild(String discPath, String discHash) {
        try {
            cancelled = false;
            File disc = requireFile(discPath, "Select a valid Crash 2 disc first.");
            if (discHash == null || discHash.length() != 64) {
                throw new IOException("The imported disc hash is missing. Import the disc again.");
            }
            File external = getExternalFilesDir(null);
            if (external == null) throw new IOException("App storage is unavailable.");
            if (new StatFs(external.getPath()).getAvailableBytes() < REQUIRED_FREE_BYTES) {
                throw new IOException("At least 3 GB of free space is required to build the game.");
            }

            update("Prepare", "Unpacking the Android toolchain…", false, false);
            File toolchain = RuntimeStaging.ensureStaged(this,
                    (path, copied) -> update("Prepare", "Unpacking toolchain file " + copied + "…", false, false));
            File buildRoot = new File(external, "build");
            if (!buildRoot.isDirectory() && !buildRoot.mkdirs()) {
                throw new IOException("Cannot create the build directory.");
            }
            BuildIdentity identity = BuildIdentity.from(discHash, FRAMEWORK, COMPILER, CONFIG);
            BuildState state = new BuildState(new File(buildRoot, "state.properties").toPath());
            BuildState.Stage stage = state.nextStage(identity);

            if (stage.ordinal() <= BuildState.Stage.EXTRACT.ordinal()) {
                checkCancelled();
                update("Extract", "Checking imported disc and free space…", false, false);
                state.complete(BuildState.Stage.EXTRACT, identity);
                stage = BuildState.Stage.GENERATE;
            }
            if (stage.ordinal() <= BuildState.Stage.GENERATE.ordinal()) {
                checkCancelled();
                update("Generate", "Translating the game to C. This can take several minutes…", false, false);
                File project = new File(buildRoot, "project");
                deleteTree(project.toPath());
                runGenerator(disc, project, toolchain, new File(buildRoot, "build.log"));
                String generatedConfig = new String(
                        Files.readAllBytes(new File(project, "game.toml").toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (!generatedConfig.contains("id = \"SCUS-94154\"")) {
                    deleteTree(project.toPath());
                    throw new IOException(
                            "This disc is not Crash Bandicoot 2 USA (SCUS-94154). Import the supported release.");
                }
                state.complete(BuildState.Stage.GENERATE, identity);
                stage = BuildState.Stage.COMPILE;
            }
            if (stage.ordinal() <= BuildState.Stage.COMPILE.ordinal()) {
                checkCancelled();
                update("Compile", "Compiling the ARM64 game module…", false, false);
                compileGame(new File(buildRoot, "project"), toolchain,
                        new File(buildRoot, "build.log"));
                state.complete(BuildState.Stage.COMPILE, identity);
                stage = BuildState.Stage.VERIFY;
            }
            if (stage.ordinal() <= BuildState.Stage.VERIFY.ordinal()) {
                checkCancelled();
                update("Verify", "Checking the generated game module…", false, false);
                File module = new File(getFilesDir(), "build/output/libcrash2_game.so");
                File host = new File(getApplicationInfo().nativeLibraryDir, "libmain.so");
                String error = BuilderNative.verifyModule(host.getPath(), module.getPath());
                if (error != null) throw new IOException(error);
                state.complete(BuildState.Stage.VERIFY, identity);
            }
            getSharedPreferences("launcher", MODE_PRIVATE).edit()
                    .putString("built_disc_sha256", discHash).apply();
            update("Done", "Build complete. The game is ready to play.", true, true);
        } catch (Exception error) {
            String message = cancelled
                    ? "Build cancelled. Completed stages were kept; tap Build to resume."
                    : error.getMessage();
            if (message == null) message = error.toString();
            File external = getExternalFilesDir(null);
            if (external != null) {
                appendLog(new File(new File(external, "build"), "build.log"),
                        "build error: " + message);
            }
            update("Stopped", message, true, false);
        } finally {
            stopForeground(true);
            stopSelf();
        }
    }

    private void runGenerator(File disc, File project, File toolchain, File log) throws IOException {
        File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
        File executable = new File(nativeDir, "libpsxrecomp.so");
        File framework = new File(toolchain, "framework");
        File bios = new File(framework, "bios/openbios.bin");
        File seeds = new File(toolchain, "crash2/functions.txt");
        String[] argv = {
                executable.getPath(), "build", "--disc", disc.getPath(),
                "--bios", bios.getPath(), "--output", project.getPath(),
                "--name", "Crash Bandicoot 2"};
        int result = BuilderNative.run(argv, project.getParent(), log.getPath(), environment(
                nativeDir, toolchain,
                "PSXRECOMP_FRAMEWORK_DIR=" + framework.getPath(),
                "PSXRECOMP_GAME_SEEDS=" + seeds.getPath()));
        if (result != 0) throw new IOException(
                "Game translation failed (exit " + result + "). Open Log for details, then retry.");
    }

    private void compileGame(File project, File toolchain, File log) throws IOException {
        File generated = new File(project, "generated");
        File[] sources = generated.listFiles((directory, name) ->
                name.endsWith(".c") && (name.contains("_full") || name.endsWith("_dispatch.c")));
        if (sources == null || sources.length == 0) {
            throw new IOException("Generated C files are missing. Rebuild from the Generate stage.");
        }
        Arrays.sort(sources, Comparator.comparing(File::getName));
        File outputDir = new File(getFilesDir(), "build/output");
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
            throw new IOException("Cannot create the game output directory.");
        }
        File temporary = new File(outputDir, "libcrash2_game.so.tmp");
        File module = new File(outputDir, "libcrash2_game.so");
        File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
        File tcc = new File(nativeDir, "libtcc-bin.so");
        // This TinyCC has no --sysroot option, and its baked-in CRT/sysroot
        // paths ("/lib", "/include") do not exist on-device, so drive the
        // link explicitly: -nostdlib plus staged CRT objects, libtcc1, NDK
        // headers, and the device's own /system/lib64 for libc/libm.
        File tccDir = new File(toolchain, "tcc");
        File sysroot = new File(toolchain, "sysroot");
        File syslib = new File(sysroot, "lib/aarch64-linux-android/28");
        File crtBegin = new File(syslib, "crtbegin_so.o");
        File crtEnd = new File(syslib, "crtend_so.o");
        File libtcc1 = new File(tccDir, "libtcc1.a");
        for (File required : new File[]{crtBegin, crtEnd, libtcc1}) {
            if (!required.isFile()) {
                throw new IOException("Toolchain file is missing: " + required.getName()
                        + ". Reinstall the app and retry.");
            }
        }
        List<String> args = new ArrayList<>();
        args.add(tcc.getPath());
        args.add("-shared");
        args.add("-nostdlib");
        args.add("-fPIC");
        args.add("-Wl,-Bsymbolic");
        args.add("-B" + tccDir.getPath());
        args.add("-D__ANDROID_API__=28");
        // Must mirror the host runtime's compile definitions (see DEFINES in
        // the runtime-bundle build.ninja): generated TUs include the same
        // headers, and e.g. without PSX_NO_DEBUG_TOOLS they emit an extern
        // reference to debug_server_log_call_entry which the Release host
        // does not export (its copy is static inline).
        args.add("-DPSX_NO_DEBUG_TOOLS=1");
        args.add("-DPSX_ENABLE_BLOCK_CYCLES=1");
        args.add("-DPSX_HAS_GAME_DISPATCH=1");
        args.add("-DPSX_NATIVE_BUILD=1");
        args.add("-DPSX_MAX_PLAYERS=2");
        args.add("-I" + new File(sysroot, "include").getPath());
        args.add("-I" + new File(sysroot, "include/aarch64-linux-android").getPath());
        args.add("-I" + new File(project, "psxrecomp/runtime/include").getPath());
        args.add("-I" + generated.getPath());
        args.add("-o");
        args.add(temporary.getPath());
        args.add(crtBegin.getPath());
        for (File source : sources) args.add(source.getPath());
        args.add(libtcc1.getPath());
        args.add("-L/system/lib64");
        args.add("-lc");
        args.add("-lm");
        // Record DT_NEEDED against the already-loaded host: the game module's
        // runtime imports then bind directly to libmain.so in every process,
        // instead of relying on RTLD_GLOBAL namespace promotion (which does
        // not happen for System.loadLibrary-loaded libs or across processes).
        args.add("-L" + nativeDir.getPath());
        args.add("-lmain");
        args.add(crtEnd.getPath());
        appendLog(log, "compile command: " + String.join(" ", args));
        int result = BuilderNative.run(args.toArray(new String[0]), project.getPath(), log.getPath(),
                environment(nativeDir, toolchain));
        appendLog(log, "compile exit: " + result);
        if (result != 0) throw new IOException(
                "ARM64 compilation failed (exit " + result + "). Open Log for details, then retry.");
        try {
            Files.move(temporary.toPath(), module.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary.toPath(), module.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String[] environment(File nativeDir, File toolchain, String... extra) {
        List<String> values = new ArrayList<>();
        values.add("PATH=/system/bin");
        values.add("HOME=" + getFilesDir().getPath());
        values.add("TMPDIR=" + getCacheDir().getPath());
        values.add("LD_LIBRARY_PATH=" + nativeDir.getPath());
        values.add("TCCDIR=" + new File(toolchain, "tcc").getPath());
        for (String value : extra) values.add(value);
        return values.toArray(new String[0]);
    }

    private static void appendLog(File log, String text) {
        if (log == null) return;
        try {
            File parent = log.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            Files.write(log.toPath(),
                    (text + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Logging must never fail the build itself.
        }
    }

    private static File requireFile(String path, String message) throws IOException {
        if (path == null) throw new IOException(message);
        File file = new File(path);
        if (!file.isFile()) throw new IOException(message);
        return file;
    }

    private void checkCancelled() throws IOException {
        if (cancelled) throw new IOException("cancelled");
    }

    private void update(String stage, String message, boolean done, boolean success) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION, notification(message));
        Intent update = new Intent(ACTION_UPDATE).setPackage(getPackageName());
        update.putExtra(EXTRA_STAGE, stage);
        update.putExtra(EXTRA_MESSAGE, message);
        update.putExtra(EXTRA_DONE, done);
        update.putExtra(EXTRA_SUCCESS, success);
        sendBroadcast(update);
    }

    private Notification notification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setContentTitle("Crash 2 game build")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(path);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
