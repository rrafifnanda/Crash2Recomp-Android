package io.github.crash2recomp;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

final class DiscSelection {
    final Path entryFile;
    final List<Path> files;
    final String serial;
    final String sha256;
    final long totalBytes;

    DiscSelection(Path entryFile, List<Path> files, String serial, String sha256, long totalBytes) {
        this.entryFile = entryFile;
        this.files = Collections.unmodifiableList(files);
        this.serial = serial;
        this.sha256 = sha256;
        this.totalBytes = totalBytes;
    }
}
