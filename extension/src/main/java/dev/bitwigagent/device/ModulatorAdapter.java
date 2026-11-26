package dev.bitwigagent.device;

import com.google.gson.JsonObject;
import dev.bitwigagent.host.DeviceSources;
import java.util.UUID;

/** Read-only primary modulator discovery, independent of the Grid domain. */
public interface ModulatorAdapter {
    void execute(Runnable task);

    JsonObject list(DeviceSources.Source source, int offset, int limit, Runnable check);

    JsonObject parameter(DeviceSources.Source source, UUID modulatorId, String parameterId, Runnable check);
}
