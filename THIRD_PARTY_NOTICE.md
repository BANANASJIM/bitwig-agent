# Third-party notices

## Bundled Gson dependency

The extension bundles Google Gson 2.10 runtime classes, distributed under the
Apache License, Version 2.0. Gson project: https://github.com/google/gson/tree/gson-parent-2.10

Artifact: https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.10/gson-2.10.jar

SHA-256: `0cdd163ce3598a20fc04eee71b140b24f6f2a3b35f0a499dbbdd9852e83fbfaf`

The build downloads this exact artifact and verifies its SHA-256 before use.
No dependency manager or ambient Gradle/Maven cache is required.

Gson copyright notice: Copyright 2008 Google Inc.
The full Apache 2.0 license is included in `third-party/GSON-LICENSE.txt` and in
the extension archive. See https://www.apache.org/licenses/LICENSE-2.0.

## Separately installed Bitwig Studio

The installed Bitwig `bitwig.jar` is used for compilation and runtime
interoperability; it is not redistributed in the resulting extension.
Bitwig Studio remains subject to its own license. Set `BITWIG_API_JAR` to use
the required installed host API JAR. Compilation targets Java 11 bytecode;
the compiler itself must be able to read the selected host JAR's class-file version.

## Grid interoperability research reference

Development consulted [bitwig-grid-bridge](https://github.com/critx-jeti/bitwig-grid-bridge)
at commit `ab7fc2348e4f5a1a98a58498915944475c668c6e` for native Grid
interoperability research. This acknowledgment does not describe the project as
an implementation port or imply upstream endorsement. The pinned upstream MIT
notice is retained below as conservative attribution:

MIT License

Copyright (c) 2025-2026 bitwig-grid-bridge maintainers

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
