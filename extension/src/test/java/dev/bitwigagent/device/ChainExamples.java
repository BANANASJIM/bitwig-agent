package dev.bitwigagent.device;

import static dev.bitwigagent.device.DeviceFixture.*;

import com.google.gson.JsonObject;

/** Applied chains.set and chains.insert results on the simulated native document, for the result contract. */
public final class ChainExamples {
    private ChainExamples() {}

    private static JsonObject run(String method, String key, Object value) {
        DeviceFixture f = new DeviceFixture();
        f.top.seed("Rack");
        DeviceFixture.Rack rack = f.rack(0);
        rack.layer(0, LAYER_A, "Pad", "Delay");
        f.start();
        JsonObject params =
                method.equals("chains.set") ? params("target", rack.chainRef(0), key, value) : params("to", rack.ref());
        return f.finish(f.submit(f.services.chainEdits(), method, params));
    }

    public static JsonObject set() {
        return run("chains.set", "mute", true);
    }

    public static JsonObject insert() {
        return run("chains.insert", null, null);
    }
}
