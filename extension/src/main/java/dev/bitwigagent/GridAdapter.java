package dev.bitwigagent;

import com.google.gson.JsonObject;
import java.util.UUID;

/** Version-specific document operations. Native objects never leave their document callback. */
interface GridAdapter {
    JsonObject capabilities();
    void execute(Runnable operation);
    JsonObject catalog(String query, int offset, int limit, Runnable check);
    JsonObject snapshot(Object deviceProxy, UUID expectedTrackId, int offset, int limit, boolean parameters, Runnable check);
    JsonObject parameter(Object deviceProxy, UUID expectedTrackId, UUID moduleId, String parameterId, Runnable check);
    JsonObject setParameter(Object deviceProxy, UUID expectedTrackId, UUID moduleId, String parameterId,
            GridParameterWriter.Request request, Runnable check, Runnable dispatch);
}
