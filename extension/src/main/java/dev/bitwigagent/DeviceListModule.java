package dev.bitwigagent;

import static dev.bitwigagent.HostLimits.*;
import static dev.bitwigagent.Results.*;

import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.*;

/** devices.list and devices.layers.list: the root devices of a track and the layers of a root device. */
final class DeviceListModule implements HostModule {
    static final Set<String> METHODS = Set.of("devices.list", "devices.layers.list");
    private final TrackWindow window;
    private final DeviceSources sources;

    DeviceListModule(TrackWindow window, DeviceSources sources) {
        this.window = window;
        this.sources = sources;
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject params) {
        if (method.equals("devices.layers.list")) {
            DeviceSources.validateLayer(params, false, true);
            return;
        }
        Params.fields(params, Set.of("trackId"), Set.of());
        Params.string(params, "trackId");
    }

    @Override
    public void dispatch(HostOperation operation, String method, JsonObject params) {
        operation.result.complete(
                method.equals("devices.list")
                        ? listDevices(params.get("trackId").getAsString())
                        : sources.listLayers(params));
    }

    private JsonObject listDevices(String trackId) {
        int trackSlot = window.trackSlot(trackId);
        DeviceBank bank = window.devices(trackSlot);
        JsonArray items = new JsonArray();
        for (int i = 0; i < DEVICES; i++) {
            Device device = (Device) bank.getItemAt(i);
            if (!device.exists().get()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("deviceIndex", device.position().get());
            item.addProperty("name", device.name().get());
            item.addProperty("type", device.deviceType().get());
            items.add(item);
        }
        JsonObject result = new JsonObject();
        result.addProperty("trackId", trackId);
        result.add("devices", items);
        result.add(
                "window",
                window(bank.scrollPosition().get(), DEVICES, bank.itemCount().get()));
        result.addProperty("scope", "top_level");
        return result;
    }
}
