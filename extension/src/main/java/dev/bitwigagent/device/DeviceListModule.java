package dev.bitwigagent.device;

import static dev.bitwigagent.core.HostLimits.*;
import static dev.bitwigagent.core.Results.*;

import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.host.TrackRefs;
import dev.bitwigagent.host.TrackWindow;
import java.util.*;

/** devices.list: the root devices of a track or the devices of one chain, each with its ref. */
public final class DeviceListModule implements HostModule {
    static final Set<String> METHODS = Set.of("devices.list");
    private final TrackWindow window;
    private final TrackRefs trackRefs;
    private final NativeChains chains;

    public DeviceListModule(TrackWindow window, TrackRefs trackRefs, NativeChains chains) {
        this.window = window;
        this.trackRefs = trackRefs;
        this.chains = chains;
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
        if ("chain".equals(Refs.kind(parent))) listChain(operation, parent);
        else operation.result.complete(listTrack(parent));
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

    /** Every device of the chain, read natively in one pass. */
    private void listChain(HostOperation operation, JsonObject parent) {
        chains.run(operation, parent, walk -> {
            NativeChains.Chain chain = walk.chain(parent, false);
            JsonArray items = new JsonArray();
            int shown = Math.min(chain.devices.size(), NativeChains.MAX_ITEMS);
            for (int i = 0; i < shown; i++) {
                NativeChains.Device device = walk.at(chain, i);
                JsonObject row = row(device.ref, device.name, walk.a.deviceType(device.device));
                row.addProperty("available", true);
                items.add(row);
            }
            JsonObject result = new JsonObject();
            result.add("parent", chain.ref);
            result.add("devices", items);
            result.add("window", nativeWindow(chain.devices.size()));
            result.addProperty("scope", "chain");
            return result;
        });
    }

    /** A natively read container: everything up to {@link NativeChains#MAX_ITEMS} rows, at once. */
    static JsonObject nativeWindow(int total) {
        JsonObject result = window(0, NativeChains.MAX_ITEMS, total);
        result.addProperty("exists", true);
        result.addProperty("readiness", "ready");
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
