package dev.bitwigagent.device;

import com.google.gson.*;
import dev.bitwigagent.core.BoundedJson;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.PortHandle;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.host.DeviceSources;
import java.util.Set;
import java.util.UUID;

/** modulators.list and modulators.get: the modulators of a root device with their refs, controls, mappings and string panels. */
public final class ModulatorModule implements HostModule {
    public static final Set<String> METHODS = Set.of("modulators.list", "modulators.get");
    static final int MAX_RESPONSE_CHARS = 240000;
    private final Scheduler scheduler;
    private final DeviceSources sources;
    private final PortHandle<ModulatorAdapter> adapters;
    private final ModulatorEditModule edits;
    private ModulatorAdapter adapter;

    public ModulatorModule(
            Scheduler scheduler,
            DeviceSources sources,
            PortHandle<ModulatorAdapter> adapters,
            ModulatorEditModule edits) {
        this.scheduler = scheduler;
        this.sources = sources;
        this.adapters = adapters;
        this.edits = edits;
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject params) {}

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        ModulatorEditor editor = edits.editor(op);
        adapter = adapters.get(op);
        boolean one = method.equals("modulators.get");
        JsonObject modulatorRef = one ? p.getAsJsonObject("target") : null;
        DeviceSources.Source device =
                sources.device(one ? modulatorRef.getAsJsonObject("device") : p.getAsJsonObject("target"), false);
        final DeviceSources.RootReadTicket target = sources.rootRead(device);
        final JsonObject deviceRef = device.ref();
        final UUID only = one ? UUID.fromString(modulatorRef.get("id").getAsString()) : null;
        final int offset = p.has("offset") ? p.get("offset").getAsInt() : 0;
        final int limit = p.has("limit") ? p.get("limit").getAsInt() : 64;
        adapter.execute(() -> {
            try {
                // Never read Controller API getters or mutable observation fields on this queue.
                Runnable check = () -> {
                    scheduler.check(op);
                    target.check();
                };
                check.run();
                JsonObject value = editor.read(device, deviceRef, offset, limit, only, check);
                addPanels(value, device, check);
                JsonObject result = one ? single(value) : value;
                check.run();
                final String serialized = BoundedJson.serialize(
                        result,
                        MAX_RESPONSE_CHARS,
                        Reason.OUT_OF_BOUNDS,
                        "Modulator response exceeds bounded transport size; reduce limit. Values are never truncated.");
                check.run();
                scheduler.schedule(
                        op,
                        () -> {
                            sources.guardRootRead(target);
                            scheduler.check(op);
                            op.result.complete(
                                    JsonParser.parseString(serialized).getAsJsonObject());
                        },
                        0);
            } catch (RuntimeException error) {
                op.fail(
                        error instanceof RpcException
                                ? error
                                : Errors.notApplied(
                                        Reason.NATIVE_UNAVAILABLE,
                                        "Modulator read failed: "
                                                + error.getClass().getSimpleName()
                                                + "; inspect the exact host binding."));
            } catch (Error error) {
                op.fail(Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "Native modulator read failed; inspect the loaded host binding."));
            }
        });
    }

    /** The one modulator as the whole result; a read never compares the name, it reports the current ref. */
    private static JsonObject single(JsonObject page) {
        JsonObject row = page.getAsJsonArray("modulators").get(0).getAsJsonObject();
        JsonObject result = row.deepCopy();
        result.add("parent", page.get("target")); // the modulator's device; `target` of a get is the modulator itself
        for (String key : new String[] {"complete", "unsupported", "nativeMutation"}) result.add(key, page.get(key));
        return result;
    }

    /** Each modulator's string panels as read-only rows; Bitwig shows them as text fields that have no ref to write. */
    private void addPanels(JsonObject page, DeviceSources.Source device, Runnable check) {
        JsonArray rows = page.getAsJsonArray("modulators");
        if (rows.size() == 0) return;
        int first = rows.get(0).getAsJsonObject().get("index").getAsInt();
        JsonObject listed = adapter.list(device, first, rows.size(), check);
        for (JsonElement described : listed.getAsJsonArray("modulators")) {
            JsonObject descriptor = described.getAsJsonObject();
            JsonObject row = row(rows, descriptor.get("modulatorId").getAsString());
            JsonElement parameters = descriptor.get("parameters");
            if (row == null || parameters == null || !parameters.isJsonArray()) continue;
            JsonArray panels = new JsonArray();
            for (JsonElement parameter : parameters.getAsJsonArray()) {
                JsonObject panel = parameter.getAsJsonObject();
                if (!panel.get("supported").getAsBoolean()) continue;
                String id = panel.get("parameterId").getAsString();
                JsonObject read = adapter.parameter(
                        device, UUID.fromString(descriptor.get("modulatorId").getAsString()), id, check);
                JsonObject item = new JsonObject();
                item.addProperty("id", id);
                item.addProperty("kind", "string");
                item.add("value", read.get("value"));
                panels.add(item);
            }
            row.add("panels", panels);
        }
    }

    private static JsonObject row(JsonArray rows, String modulatorId) {
        for (JsonElement row : rows)
            if (row.getAsJsonObject()
                    .getAsJsonObject("ref")
                    .get("id")
                    .getAsString()
                    .equalsIgnoreCase(modulatorId)) return row.getAsJsonObject();
        return null;
    }
}
