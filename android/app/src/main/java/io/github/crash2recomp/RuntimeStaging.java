package io.github.crash2recomp;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

final class RuntimeStaging {
    interface Progress {
        void update(String path, int copiedFiles);
    }

    private RuntimeStaging() {}

    static File ensureStaged(Context context, Progress progress) throws IOException {
        AssetManager assets = context.getAssets();
        String version = readText(assets, "runtime-toolchain/built.txt").trim();
        File files = context.getFilesDir();
        File live = new File(files, "toolchain");
        File marker = new File(live, "built.txt");
        if (marker.isFile()
                && version.equals(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim())) {
            return live;
        }

        File staged = new File(files, "toolchain.staging");
        deleteTree(staged);
        if (!staged.mkdirs()) throw new IOException("cannot create toolchain staging directory");
        int[] copied = {0};
        copyTree(assets, "runtime-toolchain", staged, progress, copied);

        File previous = new File(files, "toolchain.previous");
        deleteTree(previous);
        if (live.exists() && !live.renameTo(previous)) {
            throw new IOException("cannot replace the previous toolchain");
        }
        if (!staged.renameTo(live)) {
            previous.renameTo(live);
            throw new IOException("cannot activate the staged toolchain");
        }
        deleteTree(previous);
        return live;
    }

    private static void copyTree(AssetManager assets, String assetPath, File destination,
                                 Progress progress, int[] copied) throws IOException {
        String[] children = assets.list(assetPath);
        if (children != null && children.length > 0) {
            if (!destination.isDirectory() && !destination.mkdirs()) {
                throw new IOException("cannot create " + destination.getName());
            }
            for (String child : children) {
                copyTree(assets, assetPath + "/" + child, new File(destination, child), progress, copied);
            }
            return;
        }
        try (InputStream input = assets.open(assetPath);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        }
        copied[0]++;
        if (progress != null) progress.update(assetPath, copied[0]);
    }

    private static String readText(AssetManager assets, String path) throws IOException {
        try (InputStream input = assets.open(path)) {
            byte[] buffer = new byte[256];
            int count = input.read(buffer);
            return count < 0 ? "" : new String(buffer, 0, count, StandardCharsets.UTF_8);
        }
    }

    private static void deleteTree(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        if (!file.delete()) throw new IOException("cannot remove stale " + file.getName());
    }
}
