package dev.bitwigagent;

import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.function.Consumer;

/** Version-specific document operations. Native objects remain document-thread-only, including deferred observation. */
interface GridAdapter {
    JsonObject capabilities();
    void execute(Runnable operation);
    JsonObject catalog(String query, int offset, int limit, Runnable check);
    JsonObject snapshot(Object deviceProxy, UUID expectedTrackId, int offset, int limit, boolean parameters, Runnable check);
    JsonObject parameter(Object deviceProxy, UUID expectedTrackId, UUID moduleId, String parameterId, Runnable check);
    JsonObject setParameter(Object deviceProxy, UUID expectedTrackId, UUID moduleId, String parameterId,
            GridParameterWriter.Request request, Runnable check, Runnable dispatch);
    void moveModule(Object deviceProxy, UUID expectedTrackId, UUID moduleId, GridModuleWriter.Move request,
            Runnable check, Runnable dispatch, Consumer<JsonObject> complete, Consumer<Throwable> fail);
    void deleteModule(Object deviceProxy, UUID expectedTrackId, UUID moduleId,
            Runnable check, Runnable dispatch, Consumer<JsonObject> complete, Consumer<Throwable> fail);
    void renameModule(Object deviceProxy, UUID expectedTrackId, UUID moduleId, GridModuleNameWriter.Request request,
            Runnable check, Runnable dispatch, Consumer<JsonObject> complete, Consumer<Throwable> fail);
    void insertModule(Object deviceProxy, UUID expectedTrackId, GridInsertionWriter.Request request,
            Runnable check, Runnable dispatch, Consumer<JsonObject> complete, Consumer<Throwable> fail);
    void setConnection(Object deviceProxy, UUID expectedTrackId, GridConnectionWriter.Request request,
            Runnable check, Runnable dispatch, Consumer<JsonObject> complete, Consumer<Throwable> fail);
}
