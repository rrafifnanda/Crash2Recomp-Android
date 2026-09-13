package io.github.crash2recomp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

final class BuildState {
    enum Stage { EXTRACT, GENERATE, COMPILE, VERIFY, DONE }

    private final Path file;

    BuildState(Path file) {
        this.file = file;
    }

    Stage nextStage(BuildIdentity current) throws IOException {
        Properties stored = load();
        if (stored.isEmpty() || !current.discSha256.equals(stored.getProperty("disc"))) {
            return Stage.EXTRACT;
        }

        Stage next = after(Stage.valueOf(stored.getProperty("stage")));
        if (!current.framework.equals(stored.getProperty("framework"))
                || !current.configSha256.equals(stored.getProperty("config"))) {
            next = earlier(next, Stage.GENERATE);
        }
        if (!current.compiler.equals(stored.getProperty("compiler"))) {
            next = earlier(next, Stage.COMPILE);
        }
        return next;
    }

    void complete(Stage stage, BuildIdentity identity) throws IOException {
        if (stage == Stage.DONE) throw new IllegalArgumentException("DONE is derived from VERIFY");
        Properties state = new Properties();
        state.setProperty("stage", stage.name());
        state.setProperty("disc", identity.discSha256);
        state.setProperty("framework", identity.framework);
        state.setProperty("compiler", identity.compiler);
        state.setProperty("config", identity.configSha256);

        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            state.store(output, "Crash2Recomp Android build state");
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Properties load() throws IOException {
        Properties state = new Properties();
        if (!Files.isRegularFile(file)) return state;
        try (InputStream input = Files.newInputStream(file)) {
            state.load(input);
        }
        return state;
    }

    private static Stage after(Stage completed) {
        switch (completed) {
            case EXTRACT: return Stage.GENERATE;
            case GENERATE: return Stage.COMPILE;
            case COMPILE: return Stage.VERIFY;
            case VERIFY:
            case DONE: return Stage.DONE;
            default: throw new AssertionError(completed);
        }
    }

    private static Stage earlier(Stage left, Stage right) {
        return left.ordinal() < right.ordinal() ? left : right;
    }
}
