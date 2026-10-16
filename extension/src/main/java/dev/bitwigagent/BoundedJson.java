package dev.bitwigagent;

import com.google.gson.*;
import java.io.*;
import static dev.bitwigagent.HostServices.failure;

/** Immutable cross-queue JSON handoff with explicit nulls and a hard character budget. */
final class BoundedJson {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private BoundedJson() { }

    static String serialize(JsonObject value, int maximum, String code, String message) {
        StringWriter buffer = new StringWriter();
        Writer bounded = new Writer() {
            int count;
            @Override public void write(char[] chars, int offset, int length) throws IOException {
                if (length > maximum - count) throw new IOException("Response bound exceeded");
                buffer.write(chars, offset, length); count += length;
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        try { JSON.toJson(value, bounded); return buffer.toString(); }
        catch (JsonIOException error) { throw failure(code, message); }
    }
}
