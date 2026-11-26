package dev.bitwigagent;

import com.google.gson.JsonObject;
import java.util.UUID;

/** Read-only primary modulator discovery, independent of the Grid domain. */
interface ModulatorAdapter {
    JsonObject capabilities();

    void execute(Runnable task);

    JsonObject list(DeviceSources.Source source, int offset, int limit, Runnable check);

    JsonObject parameter(DeviceSources.Source source, UUID modulatorId, String parameterId, Runnable check);
}
