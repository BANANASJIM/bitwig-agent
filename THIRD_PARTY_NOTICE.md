# Bundled dependency

The extension bundles Google Gson 2.10 runtime classes, distributed under the
Apache License, Version 2.0. Gson project: https://github.com/google/gson/tree/gson-parent-2.10

Artifact: https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.10/gson-2.10.jar

SHA-256: `0cdd163ce3598a20fc04eee71b140b24f6f2a3b35f0a499dbbdd9852e83fbfaf`

The build downloads this exact artifact and verifies its SHA-256 before use.
No dependency manager or ambient Gradle/Maven cache is required.

Gson copyright notice: Copyright 2008 Google Inc.
The full Apache 2.0 license is included in `third-party/GSON-LICENSE.txt` and in
the extension archive. See https://www.apache.org/licenses/LICENSE-2.0.

The installed Bitwig `bitwig.jar` is used only as a compile-time API dependency;
it is not redistributed in the resulting extension. Set `BITWIG_API_JAR` to use
a different installed host API jar. Compilation targets Java 11 bytecode; the
compiler itself must be able to read the selected host jar's class-file version.
