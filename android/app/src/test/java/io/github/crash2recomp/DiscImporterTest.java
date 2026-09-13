package io.github.crash2recomp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class DiscImporterTest {
    @Test
    public void acceptsCaseInsensitiveCueAndReadsCrash2Serial() throws Exception {
        Path dir = Files.createTempDirectory("crash2-cue");
        byte[] track = new byte[2352];
        byte[] boot = "BOOT = cdrom:\\SCUS_941.54;1".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(boot, 0, track, 100, boot.length);
        Files.write(dir.resolve("Crash 2.bin"), track);
        Path cue = writeCue(dir, "file \"Crash 2.bin\" binary\n  track 01 mode2/2352\n");

        DiscSelection selection = DiscImporter.inspectCue(cue);

        assertEquals("SCUS-94154", selection.serial);
        assertEquals(2352, selection.totalBytes);
        assertEquals(1, selection.files.size());
        assertEquals(cue, selection.entryFile);
        assertEquals(64, selection.sha256.length());
    }

    @Test
    public void rejectsMissingCueTrack() throws Exception {
        Path dir = Files.createTempDirectory("crash2-cue-missing");
        Path cue = writeCue(dir, "FILE \"missing.bin\" BINARY\n  TRACK 01 MODE2/2352\n");

        assertRejected(cue, "missing.bin");
    }

    @Test
    public void rejectsTrackThatIsNotSectorAligned() throws Exception {
        Path dir = Files.createTempDirectory("crash2-cue-short");
        Files.write(dir.resolve("track.bin"), new byte[2351]);
        Path cue = writeCue(dir, "FILE \"track.bin\" BINARY\n  TRACK 01 MODE2/2352\n");

        assertRejected(cue, "2352-byte sectors");
    }

    @Test
    public void rejectsCuePathTraversal() throws Exception {
        Path dir = Files.createTempDirectory("crash2-cue-traversal");
        Path cue = writeCue(dir, "FILE \"../outside.bin\" BINARY\n  TRACK 01 MODE2/2352\n");

        assertRejected(cue, "unsafe track name");
    }

    @Test
    public void replacesImportedDirectoryOnlyAfterStagingSucceeds() throws Exception {
        Path root = Files.createTempDirectory("crash2-import");
        Path destination = Files.createDirectories(root.resolve("disc"));
        Files.write(destination.resolve("old.chd"), new byte[] {1});
        Path staged = Files.createDirectories(root.resolve("disc.importing"));
        Files.write(staged.resolve("new.chd"), new byte[] {2});

        DiscImporter.installImportedDirectory(staged, destination);

        assertTrue(Files.exists(destination.resolve("new.chd")));
        assertTrue(!Files.exists(destination.resolve("old.chd")));
        assertTrue(!Files.exists(root.resolve("disc.previous")));
    }

    private static void assertRejected(Path cue, String expectedMessage) throws Exception {
        try {
            DiscImporter.inspectCue(cue);
            fail("expected rejection containing: " + expectedMessage);
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage(), error.getMessage().contains(expectedMessage));
        }
    }

    private static Path writeCue(Path dir, String text) throws Exception {
        return Files.write(dir.resolve("Crash 2.cue"), text.getBytes(StandardCharsets.UTF_8));
    }
}
