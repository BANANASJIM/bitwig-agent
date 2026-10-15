package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Public API device operations, addressed through fixed, initialization-time bank proxies. */
final class DeviceModule implements HostModule {
    static final Set<String> METHODS = Set.of("devices.get", "devices.set", "devices.select", "devices.insert",
            "devices.catalog", "devices.remote.get", "devices.remote.page", "devices.remote.set");
    private static final Set<String> TARGET = Set.of("trackId", "deviceIndex", "expectedDeviceName");
    private final HostServices s;
    private final Source[][] sources = new Source[HostServices.TRACKS][HostServices.DEVICES];
    private final InsertionPoint[] ends = new InsertionPoint[HostServices.TRACKS];
    private final boolean[] countObserved = new boolean[HostServices.TRACKS];
    private final long[] countSequence = new long[HostServices.TRACKS];
    private final String[] observedTrackIds = new String[HostServices.TRACKS];
    private final boolean[] trackExistsObserved = new boolean[HostServices.TRACKS];
    private final boolean[] observedTrackExists = new boolean[HostServices.TRACKS];
    private HostOperation mutation;
    private final String sessionToken = UUID.randomUUID().toString();
    private final JsonObject deviceCatalog = catalog();

    DeviceModule(HostServices services) {
        s = services;
        for (int t = 0; t < HostServices.TRACKS; t++) {
            final int slot = t;
            Track track = (Track) s.tracks.getItemAt(t);
            ends[t] = track.endOfDeviceChainInsertionPoint();
            s.devices[t].itemCount().addValueObserver((IntegerValueChangedCallback) value -> {
                countObserved[slot] = true;
                countSequence[slot]++;
            });
            for (int d = 0; d < HostServices.DEVICES; d++) {
                Source source = new Source(track, (Device) s.devices[t].getItemAt(d), d);
                sources[t][d] = source;
                source.device.exists().addValueObserver((BooleanValueChangedCallback) value -> {
                    if (source.identityEstablished && source.lastExists != value) invalidateRemotes(source);
                    source.lastExists = value;
                    source.existsObserved = true;
                    source.generation++;
                    establishIdentity(slot, source);
                });
                source.device.name().addValueObserver((StringValueChangedCallback) value -> {
                    source.nameObserved = true;
                    source.nameSequence++;
                    source.generation++;
                    establishIdentity(slot, source);
                });
                source.device.position().addValueObserver((IntegerValueChangedCallback) value -> {
                    if (source.identityEstablished && source.lastPosition != value) invalidateRemotes(source);
                    source.lastPosition = value;
                    source.positionObserved = true;
                    source.generation++;
                    establishIdentity(slot, source);
                });
                source.device.deviceType().addValueObserver((EnumValueChangedCallback) value -> {
                    // Native enum callbacks can use audio_to_audio while the public getter returns audio-effect.
                    String type = (String) source.device.deviceType().get();
                    if (source.identityEstablished && !java.util.Objects.equals(source.lastType, type)) invalidateRemotes(source);
                    source.lastType = type;
                    source.typeObserved = true;
                    source.generation++;
                    establishIdentity(slot, source);
                });
                source.device.isEnabled().addValueObserver((BooleanValueChangedCallback) value -> {
                    source.enabledObserved = true;
                    source.enabledSequence++;
                });
                initRemotes(source, t, d);
            }
            track.channelId().addValueObserver((StringValueChangedCallback) value -> {
                if (observedTrackIds[slot] != null && !observedTrackIds[slot].equals(value)) invalidate(slot);
                observedTrackIds[slot] = (String) value;
                for (Source source : sources[slot]) establishIdentity(slot, source);
            });
            track.exists().addValueObserver((BooleanValueChangedCallback) value -> {
                if (trackExistsObserved[slot] && observedTrackExists[slot] != value) invalidate(slot);
                trackExistsObserved[slot] = true;
                observedTrackExists[slot] = value;
                for (Source source : sources[slot]) establishIdentity(slot, source);
            });
        }
    }

    private void initRemotes(Source source, int track, int device) {
        source.page = source.device.createCursorRemoteControlsPage("BitwigAgent-" + track + "-" + device, 8, "");
        source.page.pageNames().addValueObserver((StringArrayValueChangedCallback) names -> {
            if (!remoteSourcePresent(source)) return;
            source.pageNamesObserved = true; source.remoteGeneration++;
        });
        source.page.pageCount().addValueObserver((IntegerValueChangedCallback) value -> {
            if (!remoteSourcePresent(source)) return;
            source.pageCountObserved = true; source.remoteGeneration++;
        });
        source.page.selectedPageIndex().addValueObserver((IntegerValueChangedCallback) value -> {
            if (!remoteSourcePresent(source)) return;
            if (source.identityEstablished && source.pageIndexObserved && source.lastPageIndex != value)
                invalidateControls(source);
            source.lastPageIndex = value;
            source.pageIndexObserved = true; source.pageIndexFresh = true;
            source.remoteGeneration++; source.pageSequence++;
        });
        source.page.getName().addValueObserver((StringValueChangedCallback) value -> {
            if (!remoteSourcePresent(source)) return;
            source.pageNameObserved = true; source.remoteGeneration++;
        });
        for (int i = 0; i < 8; i++) {
            final int index = i;
            RemoteControl control = source.page.getParameter(i);
            source.controls[i] = control;
            control.exists().addValueObserver((BooleanValueChangedCallback) value -> {
                if (!remoteSourcePresent(source)) return;
                if (source.controlPresenceEstablished[index] && source.lastControlExists[index] != value)
                    invalidateControl(source, index);
                source.lastControlExists[index] = value;
                if (value) source.controlPresenceEstablished[index] = true;
                if (source.controlRebindPending[index]) requestRemoteFlush(source);
                source.controlExistsObserved[index] = true; source.remoteGeneration++;
            });
            control.name().addValueObserver((StringValueChangedCallback) value -> {
                if (!remoteControlPresent(source, index)) return;
                if (source.identityEstablished && source.controlNamesObserved[index]) invalidateControl(source, index);
                source.controlNamesObserved[index] = true; source.remoteGeneration++;
            });
            control.isBeingMapped().addValueObserver((BooleanValueChangedCallback) value -> {
                if (!remoteControlPresent(source, index)) return;
                if (value) invalidateControl(source, index);
                else if (source.controlRebindPending[index]) requestRemoteFlush(source);
                source.controlMappingObserved[index] = true; source.remoteGeneration++;
            });
            control.value().addValueObserver((DoubleValueChangedCallback) value -> {
                if (!remoteControlPresent(source, index)) return;
                source.controlValuesObserved[index] = true;
                source.controlValuesFresh[index] = Double.isFinite(value) && !control.isBeingMapped().get();
                source.controlSequences[index]++;
            });
        }
    }

    private String remoteToken(Target target) {
        return sessionToken + ":" + target.trackSlot + ":" + target.index + ":"
                + target.source.generation + ":" + target.source.remoteGeneration;
    }

    private JsonObject remoteMetadata(Target target) {
        Source source = target.source;
        requireRemotePage(source);
        JsonObject result = metadata(target);
        result.addProperty("contextToken", remoteToken(target));
        result.addProperty("scope", "independent_device_remote_page");
        result.addProperty("followsUiPageSelection", false);
        result.addProperty("identityCaveat", "Unobserved same-name device replacement or control remapping cannot be detected.");
        result.addProperty("pageIndex", source.page.selectedPageIndex().get());
        result.addProperty("pageName", source.page.getName().get());
        result.addProperty("pageCount", source.page.pageCount().get());
        JsonArray pages = new JsonArray();
        String[] names = source.page.pageNames().get();
        for (int i = 0; i < Math.min(names.length, 4096); i++) {
            JsonObject page = new JsonObject();
            page.addProperty("pageIndex", i); page.addProperty("name", names[i]); pages.add(page);
        }
        result.add("pages", pages);
        result.add("pageWindow", HostServices.window(0, 4096, source.page.pageCount().get()));
        JsonArray controls = new JsonArray();
        boolean complete = true;
        for (int i = 0; i < 8; i++) {
            RemoteControl control = source.controls[i];
            JsonObject item = new JsonObject();
            item.addProperty("parameterIndex", i);
            boolean present = source.controlExistsObserved[i] && control.exists().get();
            boolean readable = present && !source.controlRebindPending[i];
            item.addProperty("exists", source.controlExistsObserved[i] ? control.exists().get() : null);
            item.addProperty("name", readable && source.controlNamesObserved[i] ? control.name().get() : null);
            item.addProperty("isBeingMapped", readable && source.controlMappingObserved[i] ? control.isBeingMapped().get() : null);
            boolean valueKnown = readable && source.controlValuesObserved[i]
                    && source.controlMappingObserved[i] && !control.isBeingMapped().get() && Double.isFinite(control.value().get());
            item.addProperty("normalizedValue", valueKnown ? control.value().get() : null);
            complete &= source.controlExistsObserved[i] && (!control.exists().get()
                    || (source.controlNamesObserved[i] && source.controlMappingObserved[i] && valueKnown));
            controls.add(item);
        }
        result.add("controls", controls);
        result.addProperty("complete", complete);
        result.addProperty("units", "normalized_0_to_1");
        return result;
    }

    private static void requireRemotePage(Source source) {
        if (source.remoteRebindPending)
            throw HostServices.failure("not_ready", "Remote source rebind awaits a host flush.");
        if (!source.pageNamesObserved || !source.pageCountObserved || !source.pageIndexObserved || !source.pageNameObserved)
            throw HostServices.failure("not_ready", "Remote page metadata has not been observed.");
    }

    private void remoteWrite(HostOperation op, String method, JsonObject p, Target target) {
        Source source = target.source;
        requireRemotePage(source);
        if (!remoteToken(target).equals(p.get("contextToken").getAsString()))
            throw HostServices.failure("stale_context", "Remote page/control observations changed; read a fresh context.");
        String pageName = p.get("expectedPageName").getAsString();
        if (method.equals("devices.remote.page")) {
            int index = p.get("pageIndex").getAsInt();
            String[] names = source.page.pageNames().get();
            if (index >= source.page.pageCount().get() || index >= names.length || !pageName.equals(names[index]))
                throw HostServices.failure("stale_context", "Requested remote page index/name is not in the observed page catalog.");
            if (source.pageIndexFresh && source.page.selectedPageIndex().get() == index && pageName.equals(source.page.getName().get())) {
                JsonObject result = remoteMetadata(target);
                result.addProperty("verification", "already_observed_no_write"); op.result.complete(result); return;
            }
            long sequence = source.pageSequence;
            guard(op, target);
            op.dispatched = true;
            source.page.selectedPageIndex().set(index);
            observePage(op, target, index, pageName, sequence, observationEnd(op));
            return;
        }
        int index = p.get("parameterIndex").getAsInt();
        RemoteControl control = source.controls[index];
        String name = p.get("expectedParameterName").getAsString();
        if (!pageName.equals(source.page.getName().get()) || !source.controlExistsObserved[index]
                || !source.controlNamesObserved[index] || !source.controlMappingObserved[index]
                || source.controlRebindPending[index] || !source.controlValuesObserved[index] || !control.exists().get()
                || control.isBeingMapped().get() || !Double.isFinite(control.value().get()) || !name.equals(control.name().get()))
            throw HostServices.failure("stale_context", "Remote control is absent, unobserved, being remapped, or differs from its expected name/page.");
        double value = p.get("normalizedValue").getAsDouble();
        if (source.controlValuesFresh[index] && Math.abs(control.value().get() - value) <= HostServices.TOLERANCE) {
            JsonObject result = remoteMetadata(target);
            result.addProperty("verification", "already_observed_no_write"); op.result.complete(result); return;
        }
        String token = remoteToken(target);
        long sequence = source.controlSequences[index];
        guard(op, target);
        if (!token.equals(p.get("contextToken").getAsString()))
            throw HostServices.failure("stale_context", "Remote control context changed before dispatch.");
        s.requirePlaybackSafe();
        op.dispatched = true;
        // Explicit absolute RPC writes must not wait for physical-controller takeover.
        control.value().setImmediately(value);
        observeRemote(op, target, index, value, token, sequence, observationEnd(op));
    }

    private void observePage(HostOperation op, Target target, int index, String name, long sequence, long end) {
        if (!matches(target)) { uncertain(op, "target_changed_after_dispatch"); return; }
        Source source = target.source;
        boolean observed = source.pageSequence > sequence && source.page.selectedPageIndex().get() == index
                && name.equals(source.page.getName().get());
        if (observed || System.nanoTime() >= end) {
            JsonObject result = remoteMetadata(target);
            result.addProperty("status", observed ? "observed" : "dispatched");
            result.addProperty("verification", observed ? "page_observer_after_write" : "postcondition_not_observed");
            op.result.complete(result);
        } else s.schedule(op, () -> observePage(op, target, index, name, sequence, end), 25);
    }

    private void observeRemote(HostOperation op, Target target, int index, double value,
                               String token, long sequence, long end) {
        if (!matches(target) || !token.equals(remoteToken(target))) {
            uncertain(op, "remote_context_changed_after_dispatch"); return;
        }
        Source source = target.source;
        boolean observed = source.controlValuesFresh[index] && source.controlSequences[index] > sequence
                && Math.abs(source.controls[index].value().get() - value) <= HostServices.TOLERANCE;
        if (observed || System.nanoTime() >= end) {
            JsonObject result = remoteMetadata(target);
            result.addProperty("status", observed ? "observed" : "dispatched");
            result.addProperty("verification", observed ? "observer_after_write" : "postcondition_not_observed");
            result.addProperty("requestedNormalizedValue", value);
            op.result.complete(result);
        } else s.schedule(op, () -> observeRemote(op, target, index, value, token, sequence, end), 25);
    }

    private void invalidate(int trackSlot) {
        for (Source source : sources[trackSlot]) {
            source.generation++;
            if (source.identityEstablished) invalidateRemotes(source);
        }
    }

    private void establishIdentity(int trackSlot, Source source) {
        // Default empty/absent initialization is not a replacement. Callback order across proxies is unspecified.
        if (!source.identityEstablished && trackExistsObserved[trackSlot] && observedTrackExists[trackSlot]
                && observedTrackIds[trackSlot] != null && !observedTrackIds[trackSlot].isEmpty()
                && source.existsObserved && source.lastExists && source.positionObserved && source.lastPosition == source.deviceIndex
                && source.typeObserved && source.lastType != null && !source.lastType.isEmpty()
                && source.nameObserved && !source.device.name().get().isEmpty())
            source.identityEstablished = true;
        if (source.remoteFlushPending) requestRemoteFlush(source);
    }

    private static boolean remoteSourcePresent(Source source) {
        // Retain first-population observations even if existence/UUID callbacks have not arrived yet.
        // Later absent callbacks cannot establish freshness; history is reconciled only at flush.
        return !source.identityEstablished || (source.track.exists().get() && source.device.exists().get());
    }

    private static boolean remoteControlPresent(Source source, int index) {
        // Initial absent placeholders may precede the first real control's metadata/value callbacks.
        // After that first presence, callbacks during absence cannot acknowledge a replacement binding.
        return remoteSourcePresent(source)
                && (!source.controlPresenceEstablished[index] || source.lastControlExists[index]);
    }

    /** Observation history belongs to the continuous proxy; a rebind invalidates readiness and write freshness. */
    private void invalidateRemotes(Source source) {
        source.remoteRebindPending = true;
        source.pageIndexFresh = false;
        invalidateControls(source);
        source.remoteGeneration++;
    }

    private void invalidateControls(Source source) {
        java.util.Arrays.fill(source.controlRebindPending, true);
        java.util.Arrays.fill(source.controlValuesFresh, false);
        requestRemoteFlush(source);
    }

    private void invalidateControl(Source source, int index) {
        source.controlRebindPending[index] = true;
        source.controlValuesFresh[index] = false;
        requestRemoteFlush(source);
    }

    private void requestRemoteFlush(Source source) {
        source.remoteFlushPending = true;
        s.host.requestFlush();
    }

    @Override public void flush() {
        for (int t = 0; t < sources.length; t++) {
            for (Source source : sources[t]) {
                if (!source.remoteFlushPending) continue;
                if (!source.identityEstablished || !trackExistsObserved[t] || !observedTrackExists[t]
                        || observedTrackIds[t] == null || observedTrackIds[t].isEmpty()
                        || !s.trackMatches(observedTrackIds[t], source.track)
                        || !source.lastExists || !source.device.exists().get()
                        || source.lastPosition != source.deviceIndex || source.device.position().get() != source.deviceIndex
                        || !java.util.Objects.equals(source.lastType, source.device.deviceType().get())
                        || source.device.name().get().isEmpty()) continue;
                // Host flush is the interested-value update boundary, not a timer or replayed callback.
                // Reads use current getters only for previously observed sources. Do not advance any
                // observer sequence or fresh-write flag: unchanged getters cannot acknowledge a write.
                source.remoteRebindPending = false;
                source.remoteFlushPending = false;
                if (source.pageIndexObserved) source.lastPageIndex = source.page.selectedPageIndex().get();
                for (int i = 0; i < source.controls.length; i++) {
                    if (!source.controlRebindPending[i] || !source.controlExistsObserved[i]) continue;
                    RemoteControl control = source.controls[i];
                    source.lastControlExists[i] = control.exists().get();
                    source.controlPresenceEstablished[i] |= source.lastControlExists[i];
                    if (source.lastControlExists[i] && source.controlMappingObserved[i] && !control.isBeingMapped().get())
                        source.controlRebindPending[i] = false;
                }
            }
        }
    }

    @Override public Set<String> methods() { return METHODS; }
    @Override public void validate(String method, JsonObject params) { validateParams(method, params); }

    static void validateParams(String method, JsonObject p) {
        switch (method) {
            case "devices.catalog": Params.fields(p, Set.of(), Set.of()); return;
            case "devices.get":
            case "devices.remote.get":
            case "devices.select": Params.fields(p, TARGET, Set.of()); validateTarget(p); return;
            case "devices.set":
                Params.fields(p, Set.of("trackId", "deviceIndex", "expectedDeviceName", "enabled"), Set.of());
                validateTarget(p); Params.bool(p, "enabled"); return;
            case "devices.insert":
                Params.fields(p, Set.of("trackId", "expectedDeviceCount", "kind"), Set.of("id", "path"));
                Params.string(p, "trackId");
                Params.integer(p, "expectedDeviceCount", 0, HostServices.DEVICES - 1);
                validateInsertion(p); return;
            case "devices.remote.page":
                Params.fields(p, Set.of("trackId", "deviceIndex", "expectedDeviceName", "contextToken",
                        "pageIndex", "expectedPageName"), Set.of());
                validateTarget(p); Params.string(p, "contextToken"); Params.string(p, "expectedPageName");
                Params.integer(p, "pageIndex", 0, 4095); return;
            case "devices.remote.set":
                Params.fields(p, Set.of("trackId", "deviceIndex", "expectedDeviceName", "contextToken",
                        "expectedPageName", "parameterIndex", "expectedParameterName", "normalizedValue"), Set.of());
                validateTarget(p); Params.string(p, "contextToken"); Params.string(p, "expectedPageName");
                Params.string(p, "expectedParameterName"); Params.integer(p, "parameterIndex", 0, 7);
                Params.number(p, "normalizedValue", 0, 1); return;
            default: throw HostServices.failure("unknown_method", "Unknown device operation.");
        }
    }

    private static void validateTarget(JsonObject p) {
        Params.string(p, "trackId");
        Params.integer(p, "deviceIndex", 0, HostServices.DEVICES - 1);
        Params.string(p, "expectedDeviceName");
    }

    private static void validateInsertion(JsonObject p) {
        String kind = Params.string(p, "kind");
        if (kind.equals("file")) {
            if (!p.has("path") || p.has("id")) invalid("File insertion requires only path, not id.");
            Params.absoluteFilePath(p, "path");
            return;
        }
        if (!p.has("id") || p.has("path")) invalid("Device insertion requires only id, not path.");
        String id = Params.string(p, "id");
        switch (kind) {
            case "bitwig":
                if (!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                    invalid("Bitwig id must be a canonical UUID.");
                return;
            case "vst2":
                if (!id.matches("-?(0|[1-9][0-9]{0,9})")) invalid("VST2 id must be a signed int32 decimal string.");
                try { Integer.parseInt(id); } catch (NumberFormatException error) { invalid("VST2 id is outside int32."); }
                return;
            case "vst3":
                if (!id.matches("[0-9a-fA-F]{32}")) invalid("VST3 id must contain exactly 32 hexadecimal digits.");
                return;
            case "clap":
                if (!id.matches("[!-~]{1,256}")) invalid("CLAP id must be 1..256 printable non-whitespace ASCII characters.");
                return;
            default: invalid("kind must be bitwig, vst2, vst3, clap, or file.");
        }
    }

    private static void invalid(String message) { throw HostServices.failure("invalid_params", message); }

    @Override public void dispatch(HostOperation op, String method, JsonObject p) {
        if (method.equals("devices.catalog")) { op.result.complete(deviceCatalog); return; }
        if (method.equals("devices.get")) { op.result.complete(metadata(target(p))); return; }
        if (method.equals("devices.remote.get")) { op.result.complete(remoteMetadata(target(p))); return; }
        if (mutation != null && !mutation.result.isDone())
            throw HostServices.failure("busy", "Another device mutation is awaiting observations.");
        mutation = op;
        if (method.equals("devices.insert")) { insert(op, p); return; }
        Target target = target(p);
        if (method.startsWith("devices.remote.")) { remoteWrite(op, method, p, target); return; }
        if (method.equals("devices.select")) {
            guard(op, target);
            op.dispatched = true;
            target.source.device.selectInEditor();
            JsonObject result = metadata(target);
            result.addProperty("status", "dispatched");
            result.addProperty("completion", "not_observed");
            result.addProperty("verification", "selection_has_no_reliable_public_observer");
            op.result.complete(result);
            return;
        }
        boolean enabled = p.get("enabled").getAsBoolean();
        if (target.source.device.isEnabled().get() == enabled) {
            JsonObject result = metadata(target);
            result.addProperty("verification", "already_observed_no_write");
            op.result.complete(result);
            return;
        }
        long sequence = target.source.enabledSequence;
        guard(op, target);
        op.dispatched = true;
        target.source.device.isEnabled().set(enabled);
        observeEnabled(op, target, enabled, sequence, observationEnd(op));
    }

    private Target target(JsonObject p) {
        String id = p.get("trackId").getAsString();
        int t = s.trackSlot(id);
        int index = p.get("deviceIndex").getAsInt();
        if (s.devices[t].scrollPosition().get() != 0)
            throw HostServices.failure("stale_context", "Device bank is not at the advertised zero-based window.");
        Source source = sources[t][index];
        if (!source.ready()) throw HostServices.failure("not_ready", "Device metadata has not been observed.");
        if (!source.device.exists().get() || source.device.position().get() != index)
            throw HostServices.failure("target_not_found", "Device is absent at the explicit index.");
        String name = p.get("expectedDeviceName").getAsString();
        if (!name.equals(source.device.name().get()))
            throw HostServices.failure("stale_context", "Device name differs from expectedDeviceName.");
        return new Target(id, t, index, name, source, source.generation);
    }

    private boolean matches(Target target) {
        Device device = target.source.device;
        return s.trackMatches(target.trackId, (Track) s.tracks.getItemAt(target.trackSlot))
                && s.devices[target.trackSlot].scrollPosition().get() == 0
                && target.source.generation == target.generation && device.exists().get()
                && device.position().get() == target.index && target.name.equals(device.name().get());
    }

    private void guard(HostOperation op, Target target) {
        s.check(op);
        if (!matches(target)) throw HostServices.failure("stale_context", "Device target changed before dispatch.");
    }

    private JsonObject metadata(Target target) {
        JsonObject result = HostServices.observed();
        JsonObject identity = new JsonObject();
        identity.addProperty("trackId", target.trackId);
        identity.addProperty("deviceIndex", target.index);
        identity.addProperty("expectedDeviceName", target.name);
        identity.addProperty("identityScope", "observed_bank_proxy_not_persistent_device_identity");
        result.add("target", identity);
        Device device = target.source.device;
        result.addProperty("name", device.name().get());
        result.addProperty("enabled", device.isEnabled().get());
        result.addProperty("type", device.deviceType().get());
        result.addProperty("scope", "top_level_device_bank");
        result.add("window", HostServices.window(s.devices[target.trackSlot].scrollPosition().get(),
                HostServices.DEVICES, s.devices[target.trackSlot].itemCount().get()));
        result.addProperty("remoteControlsAvailable", true);
        result.addProperty("nameWritable", false);
        return result;
    }

    private void observeEnabled(HostOperation op, Target target, boolean enabled, long sequence, long end) {
        if (!matches(target)) { uncertain(op, "target_changed_after_dispatch"); return; }
        if (target.source.enabledSequence > sequence && target.source.device.isEnabled().get() == enabled) {
            JsonObject result = metadata(target);
            result.addProperty("verification", "observer_after_write");
            op.result.complete(result);
        } else if (System.nanoTime() >= end) {
            JsonObject result = metadata(target);
            result.addProperty("status", "dispatched");
            result.addProperty("verification", "postcondition_not_observed");
            result.addProperty("requestedEnabled", enabled);
            op.result.complete(result);
        } else s.schedule(op, () -> observeEnabled(op, target, enabled, sequence, end), 25);
    }

    private void insert(HostOperation op, JsonObject p) {
        String id = p.get("trackId").getAsString();
        int t = s.trackSlot(id);
        int count = p.get("expectedDeviceCount").getAsInt();
        if (!countObserved[t]) throw HostServices.failure("not_ready", "Device count has not been observed.");
        if (s.devices[t].scrollPosition().get() != 0 || s.devices[t].itemCount().get() != count)
            throw HostServices.failure("stale_context", "Device count or bank window differs from expectedDeviceCount.");
        long[] prefix = new long[count];
        JsonArray before = new JsonArray();
        for (int d = 0; d < HostServices.DEVICES; d++) {
            Source source = sources[t][d];
            if (!source.existsObserved || (d < count && !source.ready()))
                throw HostServices.failure("not_ready", "Device bank has not reported the complete insertion window.");
            if (source.device.exists().get() != (d < count)
                    || (d < count && source.device.position().get() != d))
                throw HostServices.failure("stale_context", "Device count and bank contents disagree.");
            if (d < count) { prefix[d] = source.generation; before.add(summary(source, d)); }
        }
        long sequence = countSequence[t];
        long newNameSequence = sources[t][count].nameSequence;
        s.check(op);
        if (!s.trackMatches(id, (Track) s.tracks.getItemAt(t)) || !prefixMatches(t, prefix)
                || s.devices[t].itemCount().get() != count || s.devices[t].scrollPosition().get() != 0)
            throw HostServices.failure("stale_context", "Insertion target changed before dispatch.");
        String kind = p.get("kind").getAsString();
        String value = p.get(kind.equals("file") ? "path" : "id").getAsString();
        op.dispatched = true;
        switch (kind) {
            case "bitwig": ends[t].insertBitwigDevice(UUID.fromString(value)); break;
            case "vst2": ends[t].insertVST2Device(Integer.parseInt(value)); break;
            case "vst3": ends[t].insertVST3Device(value); break;
            case "clap": ends[t].insertCLAPDevice(value); break;
            case "file": ends[t].insertFile(value); break;
            default: throw new IllegalStateException("Validated insertion kind changed");
        }
        observeInsertion(op, id, t, prefix, before, sequence, newNameSequence, observationEnd(op));
    }

    private boolean prefixMatches(int t, long[] prefix) {
        for (int d = 0; d < prefix.length; d++) {
            Source source = sources[t][d];
            if (source.generation != prefix[d] || !source.device.exists().get() || source.device.position().get() != d)
                return false;
        }
        return true;
    }

    private void observeInsertion(HostOperation op, String trackId, int t, long[] prefix,
                                  JsonArray before, long sequence, long newNameSequence, long end) {
        if (!s.trackMatches(trackId, (Track) s.tracks.getItemAt(t)) || s.devices[t].scrollPosition().get() != 0) {
            uncertain(op, "insertion_track_or_window_changed"); return;
        }
        int count = s.devices[t].itemCount().get();
        String verification = null;
        boolean identified = false;
        if (!prefixMatches(t, prefix) || count < prefix.length || count > prefix.length + 1)
            verification = "ambiguous_chain_change";
        else if (count == prefix.length + 1 && countSequence[t] > sequence) {
            Source added = sources[t][prefix.length];
            boolean complete = added.ready() && added.device.exists().get()
                    && added.device.position().get() == prefix.length && added.nameSequence > newNameSequence
                    && !added.device.name().get().isEmpty();
            for (int d = count; d < HostServices.DEVICES; d++) complete &= !sources[t][d].device.exists().get();
            if (complete) { identified = true; verification = "observed_count_increment_and_unchanged_prefix"; }
        }
        if (verification == null && System.nanoTime() < end) {
            s.schedule(op, () -> observeInsertion(op, trackId, t, prefix, before, sequence, newNameSequence, end), 25);
            return;
        }
        JsonObject result = HostServices.observed();
        result.addProperty("status", identified ? "observed" : "dispatched");
        result.addProperty("verification", verification == null ? "insertion_not_observed" : verification);
        result.addProperty("trackId", trackId);
        result.addProperty("beforeCount", prefix.length);
        result.addProperty("afterCount", count);
        result.addProperty("identityScope", "observed_bank_delta_not_persistent_device_identity");
        result.addProperty("requestedIdentifierVerified", false);
        result.add("before", before);
        JsonArray after = new JsonArray();
        for (int d = 0; d < HostServices.DEVICES; d++)
            if (sources[t][d].device.exists().get()) after.add(summary(sources[t][d], d));
        result.add("after", after);
        if (identified) result.add("inserted", summary(sources[t][prefix.length], prefix.length));
        else result.add("inserted", JsonNull.INSTANCE);
        op.result.complete(result);
    }

    private static JsonObject summary(Source source, int index) {
        JsonObject result = new JsonObject();
        result.addProperty("deviceIndex", index);
        result.addProperty("name", source.device.name().get());
        result.addProperty("type", source.device.deviceType().get());
        return result;
    }

    private static long observationEnd(HostOperation op) {
        return Math.min(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750), op.deadline - TimeUnit.MILLISECONDS.toNanos(30));
    }

    private static void uncertain(HostOperation op, String reason) {
        JsonObject result = new JsonObject();
        result.addProperty("status", "dispatched");
        result.addProperty("verification", reason);
        result.addProperty("completion", "unknown");
        op.result.complete(result);
    }

    private static JsonObject catalog() {
        JsonObject result = HostServices.observed();
        result.addProperty("scope", "installed_grid_device_files");
        result.addProperty("completePluginCatalog", false);
        result.addProperty("verification", "local_file_presence_not_insertion_or_audio_proof");
        JsonArray devices = new JsonArray();
        result.add("devices", devices);
        String installation = System.getProperty("installationRoot");
        if (installation == null || installation.isEmpty()) {
            result.addProperty("supported", false);
            result.addProperty("reason", "Host did not provide its installationRoot; no paths were guessed.");
            return result;
        }
        try {
            Path directory = Path.of(installation).resolve("Library").resolve("devices").normalize();
            if (!directory.isAbsolute()) throw new InvalidPathException(installation, "Installation root is not absolute");
            for (String name : new String[]{"Poly Grid", "FX Grid", "Note Grid"}) {
                Path path = directory.resolve(name + ".bwdevice");
                if (!Files.isRegularFile(path)) continue;
                JsonObject item = new JsonObject();
                item.addProperty("name", name);
                item.addProperty("kind", "file");
                item.addProperty("path", path.toString());
                item.addProperty("sourceKind", "installed_bitwig_factory_device");
                devices.add(item);
            }
            result.addProperty("supported", true);
        } catch (InvalidPathException error) {
            result.addProperty("supported", false);
            result.addProperty("reason", "Host installationRoot is not an absolute filesystem path.");
        }
        return result;
    }

    private static final class Source {
        final Track track;
        final Device device;
        final int deviceIndex;
        CursorRemoteControlsPage page;
        final RemoteControl[] controls = new RemoteControl[8];
        final boolean[] controlExistsObserved = new boolean[8], controlNamesObserved = new boolean[8],
                controlMappingObserved = new boolean[8], controlValuesObserved = new boolean[8];
        final boolean[] controlRebindPending = new boolean[8], controlValuesFresh = new boolean[8];
        final boolean[] controlPresenceEstablished = new boolean[8], lastControlExists = new boolean[8];
        final long[] controlSequences = new long[8];
        boolean pageNamesObserved, pageIndexObserved, pageNameObserved, pageCountObserved;
        boolean remoteRebindPending, remoteFlushPending, pageIndexFresh;
        long remoteGeneration, pageSequence;
        int lastPageIndex;
        long generation, enabledSequence, nameSequence;
        boolean existsObserved, nameObserved, positionObserved, typeObserved, enabledObserved;
        boolean lastExists;
        boolean identityEstablished;
        int lastPosition;
        String lastType;
        Source(Track track, Device device, int deviceIndex) {
            this.track = track; this.device = device; this.deviceIndex = deviceIndex;
        }
        boolean ready() { return existsObserved && nameObserved && positionObserved && typeObserved && enabledObserved; }
    }

    private static final class Target {
        final String trackId, name;
        final int trackSlot, index;
        final Source source;
        final long generation;

        Target(String trackId, int trackSlot, int index, String name, Source source, long generation) {
            this.trackId = trackId;
            this.trackSlot = trackSlot;
            this.index = index;
            this.name = name;
            this.source = source;
            this.generation = generation;
        }
    }
}
