package dev.bitwigagent;

import static dev.bitwigagent.HostLimits.*;
import static dev.bitwigagent.Results.*;

import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.Refs;
import java.util.*;

/** devices.list: the root devices of a track or the devices of one chain, each with its ref. */
final class DeviceListModule implements HostModule {
    static final Set<String> METHODS = Set.of("devices.list");
    private final TrackWindow window;
    private final TrackRefs trackRefs;
    private final DeviceSources sources;

    DeviceListModule(TrackWindow window, TrackRefs trackRefs, DeviceSources sources) {
        this.window = window;
        this.trackRefs = trackRefs;
        this.sources = sources;
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject params) {}

    @Override
    public void dispatch(HostOperation operation, String method, JsonObject params) {
        JsonObject parent = params.getAsJsonObject("parent");
        operation.result.complete("chain".equals(Refs.kind(parent)) ? listChain(parent) : listTrack(parent));
    }

    private JsonObject listTrack(JsonObject parent) {
        int slot = trackRefs.resolve(parent, false);
        JsonObject trackRef = trackRefs.ref(slot);
        DeviceBank bank = window.devices(slot);
        JsonArray items = new JsonArray();
        for (int i = 0; i < DEVICES; i++) {
            Device device = (Device) bank.getItemAt(i);
            if (!device.exists().get()) continue;
            items.add(row(
                    Refs.device(trackRef, device.position().get(), device.name().get()),
                    device.name().get(),
                    device.deviceType().get()));
        }
        JsonObject result = new JsonObject();
        result.add("parent", trackRef);
        result.add("devices", items);
        result.add(
                "window",
                window(bank.scrollPosition().get(), DEVICES, bank.itemCount().get()));
        result.addProperty("scope", "top_level");
        return result;
    }

    private JsonObject listChain(JsonObject parent) {
        DeviceSources.Layer layer = sources.chain(parent, false);
        JsonArray items = new JsonArray();
        for (DeviceSources.Source child : layer.children) {
            if (!child.present() || child.slot >= layer.window.count) continue;
            JsonObject row = row(child.ref(), child.name, child.type);
            row.addProperty("available", !child.rebound);
            items.add(row);
        }
        JsonObject result = new JsonObject();
        result.add("parent", layer.ref());
        result.add("devices", items);
        result.add("window", layer.window.json());
        result.addProperty("scope", "chain");
        return result;
    }

    private static JsonObject row(JsonObject ref, String name, String type) {
        JsonObject item = new JsonObject();
        item.add("ref", ref);
        item.addProperty("name", name);
        item.addProperty("type", type);
        return item;
    }
}
