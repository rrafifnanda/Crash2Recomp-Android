package io.github.crash2recomp;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class BuildStateTest {
    private static final BuildIdentity FIRST =
            BuildIdentity.from("disc-a", "framework-a", "compiler-a", "config-a");

    @Test
    public void cleanBuildStartsAtExtract() throws Exception {
        BuildState state = new BuildState(Files.createTempDirectory("build-state").resolve("state.properties"));

        assertEquals(BuildState.Stage.EXTRACT, state.nextStage(FIRST));
    }

    @Test
    public void completedStagesResumeInOrderAcrossInstances() throws Exception {
        Path file = Files.createTempDirectory("build-state").resolve("state.properties");
        BuildState state = new BuildState(file);
        state.complete(BuildState.Stage.EXTRACT, FIRST);
        state.complete(BuildState.Stage.GENERATE, FIRST);

        assertEquals(BuildState.Stage.COMPILE, new BuildState(file).nextStage(FIRST));
    }

    @Test
    public void changedDiscInvalidatesEveryStage() throws Exception {
        Path file = completedBuild(FIRST);
        BuildIdentity changed = BuildIdentity.from(
                "disc-b", FIRST.framework, FIRST.compiler, FIRST.configSha256);

        assertEquals(BuildState.Stage.EXTRACT, new BuildState(file).nextStage(changed));
    }

    @Test
    public void frameworkOrConfigChangeInvalidatesGenerateOnward() throws Exception {
        Path file = completedBuild(FIRST);
        BuildIdentity framework = BuildIdentity.from(
                FIRST.discSha256, "framework-b", FIRST.compiler, FIRST.configSha256);
        BuildIdentity config = BuildIdentity.from(
                FIRST.discSha256, FIRST.framework, FIRST.compiler, "config-b");

        assertEquals(BuildState.Stage.GENERATE, new BuildState(file).nextStage(framework));
        assertEquals(BuildState.Stage.GENERATE, new BuildState(file).nextStage(config));
    }

    @Test
    public void compilerChangeInvalidatesCompileOnward() throws Exception {
        Path file = completedBuild(FIRST);
        BuildIdentity changed = BuildIdentity.from(
                FIRST.discSha256, FIRST.framework, "compiler-b", FIRST.configSha256);

        assertEquals(BuildState.Stage.COMPILE, new BuildState(file).nextStage(changed));
    }

    private static Path completedBuild(BuildIdentity identity) throws Exception {
        Path file = Files.createTempDirectory("build-state").resolve("state.properties");
        BuildState state = new BuildState(file);
        state.complete(BuildState.Stage.EXTRACT, identity);
        state.complete(BuildState.Stage.GENERATE, identity);
        state.complete(BuildState.Stage.COMPILE, identity);
        state.complete(BuildState.Stage.VERIFY, identity);
        return file;
    }
}
