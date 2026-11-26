package dev.bitwigagent;

import static dev.bitwigagent.HostLimits.*;
import static dev.bitwigagent.Results.*;

import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.*;

/** tracks.list: the tracks of the window in project order, with their group tree. */
final class TrackListModule implements HostModule {
    static final Set<String> METHODS = Set.of("tracks.list");
    private final TrackWindow window;

    TrackListModule(TrackWindow window) {
        this.window = window;
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject params) {
        Params.fields(params, Set.of(), Set.of());
    }

    @Override
    public void dispatch(HostOperation operation, String method, JsonObject params) {
        operation.result.complete(listTracks());
    }

    private JsonObject listTracks() {
        if (!window.observed()) throw Errors.notApplied(Reason.NOT_READY, "Track bank has not reported its size yet.");
        JsonObject result = new JsonObject();
        JsonArray items = new JsonArray();
        TrackTree tree = TrackTree.capture(window);
        for (TrackTree.Node node : tree.nodes) {
            Track track = window.at(node.slot);
            JsonObject item = new JsonObject();
            item.addProperty("trackId", node.id);
            item.addProperty("index", node.position);
            item.addProperty("name", track.name().get());
            tree.describe(item, node);
            items.add(item);
        }
        result.add("tracks", items);
        result.add("tree", tree.summary());
        result.add("window", window(window.offset(), TRACKS, window.total()));
        result.addProperty("scope", "flat_track_bank");
        return result;
    }
}
