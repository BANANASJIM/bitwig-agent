package dev.bitwigagent;

import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.function.Consumer;

/** Version-specific document operations. Native objects remain document-thread-only, including deferred observation. */
interface GridAdapter {
    JsonObject capabilities();

    void execute(Runnable operation);

    JsonObject catalog(String query, int offset, int limit, Runnable check);

    JsonObject snapshot(
            DeviceSources.Source source,
            int offset,
            int limit,
            boolean parameters,
            boolean diagnostics,
            java.util.Set<String> moduleIds,
            boolean fullSteps,
            Runnable check);

    JsonObject parameter(
            DeviceSources.Source source, UUID moduleId, String parameterId, boolean fullSteps, Runnable check);

    JsonObject voicing(DeviceSources.Source source, Runnable check);

    JsonObject setVoicing(
            DeviceSources.Source source, GridVoicingWriter.Request request, Runnable check, Runnable dispatch);

    JsonObject defineRemotePage(
            DeviceSources.Source source, GridRemotePageWriter.Request request, Runnable check, Runnable dispatch);

    JsonObject deleteRemotePage(DeviceSources.Source source, String pageName, Runnable check, Runnable dispatch);

    JsonObject setParameter(
            DeviceSources.Source source,
            UUID moduleId,
            String parameterId,
            GridParameterWriter.Request request,
            Runnable check,
            Runnable dispatch);

    JsonObject setParameters(
            DeviceSources.Source source,
            java.util.List<GridParameterWriter.Entry> writes,
            Runnable check,
            Runnable dispatch);

    void moveModule(
            DeviceSources.Source source,
            UUID moduleId,
            GridModuleWriter.Move request,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail);

    void deleteModule(
            DeviceSources.Source source,
            UUID moduleId,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail);

    void renameModule(
            DeviceSources.Source source,
            UUID moduleId,
            GridModuleNameWriter.Request request,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail);

    void setConnections(
            DeviceSources.Source source,
            java.util.List<GridConnectionWriter.Request> requests,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail);

    void insertModule(
            DeviceSources.Source source,
            GridInsertionWriter.Request request,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail);

    void insertModules(
            DeviceSources.Source source,
            java.util.List<GridInsertionWriter.Request> requests,
            java.util.function.LongSupplier timeLeft,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail);
    /** Re-verifies a held device and releases its topology hold if consistent; null when the proxy is another device. */
    JsonObject ackTopology(DeviceSources.Source source, String device, Runnable check);

    void setConnection(
            DeviceSources.Source source,
            GridConnectionWriter.Request request,
            Runnable check,
            Runnable dispatch,
            Consumer<JsonObject> complete,
            Consumer<Throwable> fail);
}
