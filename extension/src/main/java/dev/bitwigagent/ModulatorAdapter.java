package dev.bitwigagent;

import com.google.gson.JsonObject;
import java.util.UUID;

/** Read-only primary modulator discovery, independent of the Grid domain. */
interface ModulatorAdapter {
    JsonObject capabilities();
    void execute(Runnable task);
    JsonObject list(Object proxy, UUID trackId, int offset, int limit, Runnable check);
    JsonObject parameter(Object proxy, UUID trackId, UUID modulatorId, String parameterId, Runnable check);
}
