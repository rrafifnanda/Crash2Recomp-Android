package io.github.crash2recomp;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DiscImporter {
    private static final long RAW_SECTOR = 2352;
    private static final Pattern FILE = Pattern.compile(
            "(?im)^\\s*FILE\\s+\"([^\"]+)\"\\s+\\S+");
    private static final Pattern TRACK = Pattern.compile(
            "(?im)^\\s*TRACK\\s+\\d+\\s+\\S+");
    private static final Pattern BOOT = Pattern.compile(
            "(?i)BOOT\\s*=\\s*cdrom:\\\\?([A-Z]{4})_(\\d{3})\\.(\\d{2})");

    private DiscImporter() {}

    interface Progress {
        boolean update(long copied, long total);
    }

    static DiscSelection importUris(
            ContentResolver resolver, List<Uri> uris, java.io.File destination,
            Progress progress) throws IOException {
        if (uris.isEmpty()) throw new IllegalArgumentException("select a .chd or .cue with its .bin files");
        Path target = destination.toPath();
        Path staged = target.resolveSibling(target.getFileName() + ".importing");
        deleteTree(staged);
        Files.createDirectories(staged);

        long total = 0;
        List<String> names = new ArrayList<>();
        for (Uri uri : uris) {
            String name = displayName(resolver, uri);
            if (name.isEmpty() || name.contains("/") || name.contains("\\")) {
                throw new IllegalArgumentException("unsafe selected filename: " + name);
            }
            names.add(name);
            total += displaySize(resolver, uri);
        }

        long copied = 0;
        byte[] buffer = new byte[1024 * 1024];
        try {
            for (int i = 0; i < uris.size(); i++) {
                try (InputStream input = resolver.openInputStream(uris.get(i));
                     OutputStream output = Files.newOutputStream(staged.resolve(names.get(i)))) {
                    if (input == null) throw new IOException("cannot open " + names.get(i));
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, count);
                        copied += count;
                        if (progress != null && !progress.update(copied, total)) {
                            throw new IOException("disc import cancelled");
                        }
                    }
                }
            }

            Path entry = findEntry(staged, names);
            DiscSelection selection;
            if (entry.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cue")) {
                selection = inspectCue(entry);
                if (!"SCUS-94154".equals(selection.serial)) {
                    throw new IllegalArgumentException(
                            "unsupported disc serial: " + (selection.serial.isEmpty() ? "not found" : selection.serial));
                }
            } else {
                List<Path> files = new ArrayList<>();
                files.add(entry);
                selection = new DiscSelection(entry, files, "", hash(files), Files.size(entry));
            }
            installImportedDirectory(staged, target);
            Path installedEntry = target.resolve(entry.getFileName());
            List<Path> installedFiles = new ArrayList<>();
            for (Path file : selection.files) installedFiles.add(target.resolve(file.getFileName()));
            return new DiscSelection(installedEntry, installedFiles, selection.serial,
                    selection.sha256, selection.totalBytes);
        } catch (IOException | RuntimeException error) {
            deleteTree(staged);
            throw error;
        }
    }

    static DiscSelection inspectCue(Path cue) throws IOException {
        String text = new String(Files.readAllBytes(cue), StandardCharsets.UTF_8);
        Matcher fileMatcher = FILE.matcher(text);
        List<Path> tracks = new ArrayList<>();
        long total = 0;

        while (fileMatcher.find()) {
            String name = fileMatcher.group(1);
            if (name.contains("/") || name.contains("\\") || name.equals("..")) {
                throw new IllegalArgumentException("unsafe track name: " + name);
            }
            Path track = cue.getParent().resolve(name).normalize();
            if (!Files.isRegularFile(track)) {
                throw new IllegalArgumentException("track file is missing: " + name);
            }
            tracks.add(track);
            total += Files.size(track);
        }

        if (tracks.isEmpty()) {
            throw new IllegalArgumentException("cue lists no FILE entries");
        }
        if (!TRACK.matcher(text).find()) {
            throw new IllegalArgumentException("cue lists no TRACK entries");
        }
        if (total == 0 || total % RAW_SECTOR != 0) {
            throw new IllegalArgumentException(
                    "disc image is not a whole number of 2352-byte sectors");
        }

        return new DiscSelection(cue, tracks, readSerial(tracks.get(0)), hash(tracks), total);
    }

    static void installImportedDirectory(Path staged, Path destination) throws IOException {
        Path backup = destination.resolveSibling(destination.getFileName() + ".previous");
        deleteTree(backup);
        if (Files.exists(destination)) move(destination, backup);
        try {
            move(staged, destination);
        } catch (IOException error) {
            if (Files.exists(backup)) move(backup, destination);
            throw error;
        }
        deleteTree(backup);
    }

    private static Path findEntry(Path directory, List<String> names) {
        Path cue = null;
        Path chd = null;
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".cue")) cue = directory.resolve(name);
            if (lower.endsWith(".chd")) chd = directory.resolve(name);
        }
        if (cue != null && chd == null) return cue;
        if (chd != null && cue == null && names.size() == 1) return chd;
        throw new IllegalArgumentException("select one .chd or one .cue with its .bin files");
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? "" : segment;
    }

    private static long displaySize(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[] {OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        }
        return 0;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(path);
            }
        }
    }

    private static String readSerial(Path track) throws IOException {
        byte[] bytes = new byte[4 * 1024 * 1024];
        String tail = "";
        long remaining = 48L * 1024 * 1024;
        try (InputStream input = Files.newInputStream(track)) {
            while (remaining > 0) {
                int count = input.read(bytes, 0, (int) Math.min(bytes.length, remaining));
                if (count < 0) break;
                remaining -= count;
                String chunk = tail + new String(bytes, 0, count, StandardCharsets.ISO_8859_1);
                Matcher match = BOOT.matcher(chunk);
                if (match.find()) {
                    return (match.group(1) + "-" + match.group(2) + match.group(3))
                            .toUpperCase(Locale.ROOT);
                }
                tail = chunk.substring(Math.max(0, chunk.length() - 64));
            }
        }
        return "";
    }

    private static String hash(List<Path> paths) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        byte[] buffer = new byte[8 * 1024 * 1024];
        for (Path path : paths) {
            try (InputStream input = Files.newInputStream(path)) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02x", value));
        return result.toString();
    }
}
