package dev.bitwigagent;

import java.nio.file.Path;

public final class TestPaths {
    private TestPaths() {}

    /** The host's own absolute spelling of a POSIX-looking literal: unchanged on POSIX, drive-qualified on Windows. */
    public static String absolute(String literal) {
        return Path.of(literal).toAbsolutePath().toString();
    }
}
