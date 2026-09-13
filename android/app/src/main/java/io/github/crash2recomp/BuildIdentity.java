package io.github.crash2recomp;

import java.util.Objects;

final class BuildIdentity {
    final String discSha256;
    final String framework;
    final String compiler;
    final String configSha256;

    private BuildIdentity(String discSha256, String framework, String compiler, String configSha256) {
        this.discSha256 = requireValue(discSha256, "disc hash");
        this.framework = requireValue(framework, "framework version");
        this.compiler = requireValue(compiler, "compiler version");
        this.configSha256 = requireValue(configSha256, "config hash");
    }

    static BuildIdentity from(
            String discSha256, String framework, String compiler, String configSha256) {
        return new BuildIdentity(discSha256, framework, compiler, configSha256);
    }

    private static String requireValue(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) throw new IllegalArgumentException(name + " is empty");
        return value;
    }
}
