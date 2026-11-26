package dev.bitwigagent.mix;

import static dev.bitwigagent.core.HostLimits.*;
import static dev.bitwigagent.core.Results.*;

import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.host.TrackRefs;
import dev.bitwigagent.host.TrackWindow;
import java.util.*;

/** tracks.list: the tracks of the window in project order, with their group tree. */
public final class TrackListModule implements HostModule {
    static final Set<String> METHODS = Set.of("tracks.list");
    private final TrackWindow window;
    private final TrackRefs trackRefs;

    public TrackListModule(TrackWindow window, TrackRefs trackRefs) {
        this.window = window;
        this.trackRefs = trackRefs;
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject params) {}

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
            JsonObject item = new JsonObject();
            item.add("ref", trackRefs.ref(node.slot));
            item.addProperty("index", node.position);
            tree.describe(item, node, trackRefs::ref);
            items.add(item);
        }
        result.add("tracks", items);
        result.add("tree", tree.summary());
        result.add("window", window(window.offset(), TRACKS, window.total()));
        result.addProperty("scope", "flat_track_bank");
        return result;
    }
}
