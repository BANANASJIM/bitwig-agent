package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/** Public API device operations, addressed through fixed, initialization-time bank proxies. */
final class DeviceModule implements HostModule {
    static final Set<String> METHODS = Set.of(
            "devices.get",
            "devices.set",
            "devices.select",
            "devices.insert",
            "devices.delete",
            "devices.move",
            "devices.layers.insert",
            "devices.catalog",
            "devices.remote.get",
            "devices.remote.page",
            "devices.remote.set");
    private static final Set<String> TARGET = Set.of("trackId", "deviceIndex", "expectedDeviceName");
    static final int MAX_LAYER_CHILDREN = 4096;
    private final ControllerHost host;
    private final Scheduler scheduler;
    private final TrackWindow window;
    private final DeviceSources deviceSources;
    private final TransportSafety transportSafety;
    private final SelectionLease selection;
    private final Source[][] sources = new Source[HostLimits.TRACKS][HostLimits.DEVICES];
    private final InsertionPoint[] ends = new InsertionPoint[HostLimits.TRACKS];
    private final boolean[] countObserved = new boolean[HostLimits.TRACKS];
    private final long[] countSequence = new long[HostLimits.TRACKS];
    private HostOperation mutation;
    private final String sessionToken = UUID.randomUUID().toString();
    private final JsonObject deviceCatalog = catalog();

    DeviceModule(
            ControllerHost host,
            Scheduler scheduler,
            TrackWindow window,
            DeviceSources deviceSources,
            TransportSafety transportSafety,
            SelectionLease selection) {
        this.host = host;
        this.scheduler = scheduler;
        this.window = window;
        this.deviceSources = deviceSources;
        this.transportSafety = transportSafety;
        this.selection = selection;
        for (int t = 0; t < HostLimits.TRACKS; t++) {
            final int slot = t;
            Track track = window.at(t);
            ends[t] = track.endOfDeviceChainInsertionPoint();
            window.devices(t).itemCount().addValueObserver((IntegerValueChangedCallback) value -> {
                countObserved[slot] = true;
                countSequence[slot]++;
            });
            for (int d = 0; d < HostLimits.DEVICES; d++) {
                Source source = new Source(deviceSources.roots[t][d], d);
                sources[t][d] = source;
                source.identity.listeners.add((structural, name) -> {
                    if (structural) invalidateRemotes(source);
                    if (source.remoteFlushPending) requestRemoteFlush(source);
                });
                source.device.isEnabled().addValueObserver((BooleanValueChangedCallback) value -> {
                    source.enabledObserved = true;
                    source.enabledSequence++;
                });
                initRemotes(source, t, d);
            }
        }
    }

    private void initRemotes(Source source, int track, int device) {
        source.page = source.device.createCursorRemoteControlsPage(
                "BitwigAgent-" + track + "-" + device, GridRemotePageWriter.SLOTS, "");
        source.page.pageNames().addValueObserver((StringArrayValueChangedCallback) names -> {
            if (!remoteSourcePresent(source)) return;
            source.pageNamesObserved = true;
            source.remoteGeneration++;
        });
        source.page.pageCount().addValueObserver((IntegerValueChangedCallback) value -> {
            if (!remoteSourcePresent(source)) return;
            source.pageCountObserved = true;
            source.remoteGeneration++;
        });
        source.page.selectedPageIndex().addValueObserver((IntegerValueChangedCallback) value -> {
            if (!remoteSourcePresent(source)) return;
            if (source.identity.established && source.pageIndexObserved && source.lastPageIndex != value)
                invalidateControls(source);
            source.lastPageIndex = value;
            source.pageIndexObserved = true;
            source.pageIndexFresh = true;
            source.remoteGeneration++;
            source.pageSequence++;
        });
        source.page.getName().addValueObserver((StringValueChangedCallback) value -> {
            if (!remoteSourcePresent(source)) return;
            source.pageNameObserved = true;
            source.remoteGeneration++;
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
                source.controlExistsObserved[index] = true;
                source.remoteGeneration++;
            });
            control.name().addValueObserver((StringValueChangedCallback) value -> {
                if (!remoteControlPresent(source, index)) return;
                if (source.identity.established && source.controlNamesObserved[index]) invalidateControl(source, index);
                source.controlNamesObserved[index] = true;
                source.remoteGeneration++;
            });
            control.isBeingMapped().addValueObserver((BooleanValueChangedCallback) value -> {
                if (!remoteControlPresent(source, index)) return;
                if (value) invalidateControl(source, index);
                else if (source.controlRebindPending[index]) requestRemoteFlush(source);
                source.controlMappingObserved[index] = true;
                source.remoteGeneration++;
            });
            control.value().addValueObserver((DoubleValueChangedCallback) value -> {
                if (!remoteControlPresent(source, index)) return;
                source.controlValuesObserved[index] = true;
                source.controlValuesFresh[index] =
                        Double.isFinite(value) && !control.isBeingMapped().get();
                source.controlSequences[index]++;
            });
        }
    }

    private String remoteToken(Target target) {
        return sessionToken + ":" + target.trackSlot + ":" + target.index + ":" + target.source.identity.revision + ":"
                + target.source.remoteGeneration;
    }

    private JsonObject remoteMetadata(Target target) {
        Source source = target.source;
        requireRemotePage(source);
        JsonObject result = metadata(target);
        result.addProperty("contextToken", remoteToken(target));
        result.addProperty("scope", "independent_device_remote_page");
        result.addProperty("followsUiPageSelection", false);
        result.addProperty(
                "identityCaveat", "Unobserved same-name device replacement or control remapping cannot be detected.");
        result.addProperty("pageIndex", source.page.selectedPageIndex().get());
        result.addProperty("pageName", source.page.getName().get());
        result.addProperty("pageCount", source.page.pageCount().get());
        JsonArray pages = new JsonArray();
        String[] names = source.page.pageNames().get();
        for (int i = 0; i < Math.min(names.length, 4096); i++) {
            JsonObject page = new JsonObject();
            page.addProperty("pageIndex", i);
            page.addProperty("name", names[i]);
            pages.add(page);
        }
        result.add("pages", pages);
        result.add("pageWindow", Results.window(0, 4096, source.page.pageCount().get()));
        JsonArray controls = new JsonArray();
        boolean complete = true;
        for (int i = 0; i < 8; i++) {
            RemoteControl control = source.controls[i];
            JsonObject item = new JsonObject();
            item.addProperty("parameterIndex", i);
            boolean present =
                    source.controlExistsObserved[i] && control.exists().get();
            boolean readable = present && !source.controlRebindPending[i];
            item.addProperty(
                    "exists", source.controlExistsObserved[i] ? control.exists().get() : null);
            item.addProperty(
                    "name",
                    readable && source.controlNamesObserved[i] ? control.name().get() : null);
            item.addProperty(
                    "isBeingMapped",
                    readable && source.controlMappingObserved[i]
                            ? control.isBeingMapped().get()
                            : null);
            boolean valueKnown = readable
                    && source.controlValuesObserved[i]
                    && source.controlMappingObserved[i]
                    && !control.isBeingMapped().get()
                    && Double.isFinite(control.value().get());
            item.addProperty("normalizedValue", valueKnown ? control.value().get() : null);
            complete &= source.controlExistsObserved[i]
                    && (!control.exists().get()
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
            throw Errors.notApplied(Reason.NOT_READY, "Remote source rebind awaits a host flush.");
        if (!source.pageNamesObserved
                || !source.pageCountObserved
                || !source.pageIndexObserved
                || !source.pageNameObserved)
            throw Errors.notApplied(Reason.NOT_READY, "Remote page metadata has not been observed.");
    }

    private void remoteWrite(HostOperation op, String method, JsonObject p, Target target) {
        Source source = target.source;
        requireRemotePage(source);
        if (!remoteToken(target).equals(p.get("contextToken").getAsString()))
            throw Errors.notApplied(
                    Reason.STALE_CONTEXT, "Remote page/control observations changed; read a fresh context.");
        String pageName = p.get("expectedPageName").getAsString();
        if (method.equals("devices.remote.page")) {
            int index = p.get("pageIndex").getAsInt();
            String[] names = source.page.pageNames().get();
            if (index >= source.page.pageCount().get() || index >= names.length || !pageName.equals(names[index]))
                throw Errors.notApplied(
                        Reason.STALE_CONTEXT, "Requested remote page index/name is not in the observed page catalog.");
            if (source.pageIndexFresh
                    && source.page.selectedPageIndex().get() == index
                    && pageName.equals(source.page.getName().get())) {
                op.result.complete(stamp(remoteMetadata(target), Outcome.ALREADY_MATCHED, null));
                return;
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
        if (!pageName.equals(source.page.getName().get())
                || !source.controlExistsObserved[index]
                || !source.controlNamesObserved[index]
                || !source.controlMappingObserved[index]
                || source.controlRebindPending[index]
                || !source.controlValuesObserved[index]
                || !control.exists().get()
                || control.isBeingMapped().get()
                || !Double.isFinite(control.value().get())
                || !name.equals(control.name().get()))
            throw Errors.notApplied(
                    Reason.STALE_CONTEXT,
                    "Remote control is absent, unobserved, being remapped, or differs from its expected name/page.");
        double value = p.get("normalizedValue").getAsDouble();
        if (source.controlValuesFresh[index] && Math.abs(control.value().get() - value) <= HostLimits.TOLERANCE) {
            op.result.complete(stamp(remoteMetadata(target), Outcome.ALREADY_MATCHED, null));
            return;
        }
        String token = remoteToken(target);
        long sequence = source.controlSequences[index];
        guard(op, target);
        if (!token.equals(p.get("contextToken").getAsString()))
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Remote control context changed before dispatch.");
        transportSafety.requirePlaybackSafe();
        op.dispatched = true;
        // Explicit absolute RPC writes must not wait for physical-controller takeover.
        control.value().setImmediately(value);
        observeRemote(op, target, index, value, token, sequence, observationEnd(op));
    }

    private void observePage(HostOperation op, Target target, int index, String name, long sequence, long end) {
        Runnable observed = () -> finishPage(op, target, true);
        scheduler.conclude(
                op,
                end,
                () -> {
                    if (!matches(target))
                        return () -> uncertain(op, Reason.TARGET_CHANGED, "Device target changed after dispatch.");
                    Source source = target.source;
                    return source.pageSequence > sequence
                                    && source.page.selectedPageIndex().get() == index
                                    && name.equals(source.page.getName().get())
                            ? observed
                            : null;
                },
                () -> finishPage(op, target, false));
    }

    private void finishPage(HostOperation op, Target target, boolean observed) {
        op.result.complete(observedOrNot(remoteMetadata(target), observed));
    }

    private void observeRemote(
            HostOperation op, Target target, int index, double value, String token, long sequence, long end) {
        Runnable observed = () -> finishRemote(op, target, value, true);
        scheduler.conclude(
                op,
                end,
                () -> {
                    if (!matches(target) || !token.equals(remoteToken(target)))
                        return () -> uncertain(
                                op, Reason.TARGET_CHANGED, "Remote page or control context changed after dispatch.");
                    Source source = target.source;
                    return source.controlValuesFresh[index]
                                    && source.controlSequences[index] > sequence
                                    && Math.abs(source.controls[index].value().get() - value) <= HostLimits.TOLERANCE
                            ? observed
                            : null;
                },
                () -> finishRemote(op, target, value, false));
    }

    private void finishRemote(HostOperation op, Target target, double value, boolean observed) {
        JsonObject result = observedOrNot(remoteMetadata(target), observed);
        result.addProperty("requestedNormalizedValue", value);
        op.result.complete(result);
    }

    private static boolean remoteSourcePresent(Source source) {
        // Retain first-population observations even if existence/UUID callbacks have not arrived yet.
        // Later absent callbacks cannot establish freshness; history is reconciled only at flush.
        return !source.identity.established
                || (source.track.exists().get() && source.device.exists().get());
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
        host.requestFlush();
    }

    @Override
    public void flush() {
        for (int t = 0; t < sources.length; t++) {
            for (Source source : sources[t]) {
                if (!source.remoteFlushPending) continue;
                if (!source.identity.established
                        || !source.identity.present()
                        || !window.trackMatches(source.identity.track.id, source.track)
                        || !source.device.exists().get()
                        || source.device.position().get() != source.deviceIndex
                        || !java.util.Objects.equals(
                                source.identity.type, source.device.deviceType().get())
                        || source.device.name().get().isEmpty()) continue;
                // Host flush is the interested-value update boundary, not a timer or replayed callback.
                // Reads use current getters only for previously observed sources. Do not advance any
                // observer sequence or fresh-write flag: unchanged getters cannot acknowledge a write.
                source.remoteRebindPending = false;
                source.remoteFlushPending = false;
                if (source.pageIndexObserved)
                    source.lastPageIndex = source.page.selectedPageIndex().get();
                for (int i = 0; i < source.controls.length; i++) {
                    if (!source.controlRebindPending[i] || !source.controlExistsObserved[i]) continue;
                    RemoteControl control = source.controls[i];
                    source.lastControlExists[i] = control.exists().get();
                    source.controlPresenceEstablished[i] |= source.lastControlExists[i];
                    if (source.lastControlExists[i]
                            && source.controlMappingObserved[i]
                            && !control.isBeingMapped().get()) source.controlRebindPending[i] = false;
                }
            }
        }
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject params) {
        validateParams(method, params);
    }

    static void validateParams(String method, JsonObject p) {
        switch (method) {
            case "devices.catalog":
                Params.fields(p, Set.of(), Set.of());
                return;
            case "devices.get":
            case "devices.remote.get":
            case "devices.select":
                Params.fields(p, TARGET, Set.of());
                validateTarget(p);
                return;
            case "devices.set":
                Params.fields(p, Set.of("trackId", "deviceIndex", "expectedDeviceName", "enabled"), Set.of());
                validateTarget(p);
                Params.bool(p, "enabled");
                return;
            case "devices.insert":
                Params.fields(
                        p,
                        Set.of("trackId", "expectedDeviceCount", "kind"),
                        Set.of("id", "path", "position", "deviceIndex", "expectedDeviceName"));
                Params.string(p, "trackId");
                Params.integer(p, "expectedDeviceCount", 0, HostLimits.DEVICES - 1);
                validateInsertion(p);
                validatePosition(p);
                return;
            case "devices.delete":
                Params.fields(p, TARGET, Set.of());
                validateTarget(p);
                return;
            case "devices.move":
                Params.fields(p, Set.of("trackId", "deviceIndex", "expectedDeviceName", "toIndex"), Set.of());
                validateTarget(p);
                if (Params.integer(p, "toIndex", 0, HostLimits.DEVICES - 1)
                        == p.get("deviceIndex").getAsInt()) invalid("toIndex equals deviceIndex; nothing to move.");
                return;
            case "devices.layers.insert":
                Params.fields(
                        p, Set.of("parent", "layerId", "expectedChildCount", "kind"), Set.of("id", "path", "position"));
                if (!p.get("parent").isJsonObject()) invalid("parent must be an object.");
                DeviceSources.validateParent(p.getAsJsonObject("parent"));
                if (!DeviceSources.canonicalUuid(Params.string(p, "layerId")))
                    invalid("layerId must be a canonical UUID.");
                Params.integer(p, "expectedChildCount", 0, MAX_LAYER_CHILDREN);
                validateInsertion(p);
                if (p.has("position") && !Set.of("start", "end").contains(Params.string(p, "position")))
                    invalid("position must be start or end for a layer chain.");
                return;
            case "devices.remote.page":
                Params.fields(
                        p,
                        Set.of(
                                "trackId",
                                "deviceIndex",
                                "expectedDeviceName",
                                "contextToken",
                                "pageIndex",
                                "expectedPageName"),
                        Set.of());
                validateTarget(p);
                Params.string(p, "contextToken");
                Params.string(p, "expectedPageName");
                Params.integer(p, "pageIndex", 0, 4095);
                return;
            case "devices.remote.set":
                Params.fields(
                        p,
                        Set.of(
                                "trackId",
                                "deviceIndex",
                                "expectedDeviceName",
                                "contextToken",
                                "expectedPageName",
                                "parameterIndex",
                                "expectedParameterName",
                                "normalizedValue"),
                        Set.of());
                validateTarget(p);
                Params.string(p, "contextToken");
                Params.string(p, "expectedPageName");
                Params.string(p, "expectedParameterName");
                Params.integer(p, "parameterIndex", 0, 7);
                Params.number(p, "normalizedValue", 0, 1);
                return;
            default:
                throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown device operation.");
        }
    }

    private static void validateTarget(JsonObject p) {
        Params.string(p, "trackId");
        Params.integer(p, "deviceIndex", 0, HostLimits.DEVICES - 1);
        Params.string(p, "expectedDeviceName");
    }

    private static void validatePosition(JsonObject p) {
        String position = p.has("position") ? Params.string(p, "position") : "end";
        boolean anchored = position.equals("before") || position.equals("after");
        if (!anchored && !position.equals("start") && !position.equals("end"))
            invalid("position must be start, end, before or after.");
        if (anchored) {
            if (!p.has("deviceIndex") || !p.has("expectedDeviceName"))
                invalid("position before/after requires deviceIndex and expectedDeviceName of the anchor device.");
            Params.integer(p, "deviceIndex", 0, HostLimits.DEVICES - 1);
            Params.string(p, "expectedDeviceName");
        } else if (p.has("deviceIndex") || p.has("expectedDeviceName"))
            invalid("deviceIndex and expectedDeviceName are only for position before/after.");
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
                try {
                    Integer.parseInt(id);
                } catch (NumberFormatException error) {
                    invalid("VST2 id is outside int32.");
                }
                return;
            case "vst3":
                if (!id.matches("[0-9a-fA-F]{32}")) invalid("VST3 id must contain exactly 32 hexadecimal digits.");
                return;
            case "clap":
                if (!id.matches("[!-~]{1,256}"))
                    invalid("CLAP id must be 1..256 printable non-whitespace ASCII characters.");
                return;
            default:
                invalid("kind must be bitwig, vst2, vst3, clap, or file.");
        }
    }

    private static void invalid(String message) {
        throw Errors.notApplied(Reason.INVALID_PARAMS, message);
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        if (method.equals("devices.catalog")) {
            op.result.complete(deviceCatalog);
            return;
        }
        if (method.equals("devices.get")) {
            op.result.complete(metadata(target(p)));
            return;
        }
        if (method.equals("devices.remote.get")) {
            op.result.complete(remoteMetadata(target(p)));
            return;
        }
        if (mutation != null && !mutation.result.isDone())
            throw Errors.notApplied(Reason.BUSY, "Another device mutation is awaiting observations.");
        mutation = op;
        if (method.equals("devices.insert")) {
            insert(op, p);
            return;
        }
        if (method.equals("devices.layers.insert")) {
            insertIntoLayer(op, p);
            return;
        }
        if (method.equals("devices.delete")) {
            delete(op, target(p));
            return;
        }
        if (method.equals("devices.move")) {
            move(op, target(p), p.get("toIndex").getAsInt());
            return;
        }
        Target target = target(p);
        if (method.startsWith("devices.remote.")) {
            remoteWrite(op, method, p, target);
            return;
        }
        if (method.equals("devices.select")) {
            guard(op, target);
            selection.requireFree();
            op.dispatched = true;
            target.source.device.selectInEditor();
            op.result.complete(stamp(metadata(target), Outcome.SENT, null));
            return;
        }
        boolean enabled = p.get("enabled").getAsBoolean();
        if (target.source.device.isEnabled().get() == enabled) {
            op.result.complete(stamp(metadata(target), Outcome.ALREADY_MATCHED, null));
            return;
        }
        long sequence = target.source.enabledSequence;
        guard(op, target);
        op.dispatched = true;
        target.source.device.isEnabled().set(enabled);
        observeEnabled(op, target, enabled, sequence, observationEnd(op));
    }

    private Target target(JsonObject p) {
        deviceSources.requireZeroWindow(p);
        DeviceSources.Source root = deviceSources.root(p);
        Source source =
                sources[window.trackSlot(root.track.id)][p.get("deviceIndex").getAsInt()];
        if (!source.ready()) throw Errors.notApplied(Reason.NOT_READY, "Device metadata has not been observed.");
        return new Target(root.track.id, root.track.slot, source.deviceIndex, root.name, source, root.revision);
    }

    private boolean matches(Target target) {
        DeviceSources.Source root = target.source.identity;
        return root.revision == target.generation
                && root.present()
                && window.devices(target.trackSlot).scrollPosition().get() == 0;
    }

    private void guard(HostOperation op, Target target) {
        scheduler.check(op);
        if (!matches(target)) throw Errors.notApplied(Reason.STALE_CONTEXT, "Device target changed before dispatch.");
    }

    private JsonObject metadata(Target target) {
        JsonObject result = new JsonObject();
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
        result.add(
                "window",
                Results.window(
                        window.devices(target.trackSlot).scrollPosition().get(),
                        HostLimits.DEVICES,
                        window.devices(target.trackSlot).itemCount().get()));
        result.addProperty("remoteControlsAvailable", true);
        result.addProperty("nameWritable", false);
        return result;
    }

    private void observeEnabled(HostOperation op, Target target, boolean enabled, long sequence, long end) {
        scheduler.conclude(
                op,
                end,
                () -> {
                    if (!matches(target))
                        return () -> uncertain(op, Reason.TARGET_CHANGED, "Device target changed after dispatch.");
                    if (target.source.enabledSequence <= sequence
                            || target.source.device.isEnabled().get() != enabled) return null;
                    return () -> {
                        op.result.complete(stamp(metadata(target), Outcome.APPLIED, null));
                    };
                },
                () -> {
                    JsonObject result = observedOrNot(metadata(target), false);
                    result.addProperty("requestedEnabled", enabled);
                    op.result.complete(result);
                });
    }

    /** One verified edit in flight: what was planned, how to read the chain, and what to add to the result. */
    private final class Edit {
        final String trackId;
        final int slot;
        final DeviceChainEdit.View view;
        final DeviceChainEdit.Plan plan;
        final long[] prefixRevisions;
        final java.util.function.BooleanSupplier context;
        final String contextLost;
        final JsonObject extra = new JsonObject();

        Edit(
                String trackId,
                int slot,
                DeviceChainEdit.View view,
                DeviceChainEdit.Plan plan,
                long[] prefixRevisions,
                java.util.function.BooleanSupplier context,
                String contextLost) {
            this.trackId = trackId;
            this.slot = slot;
            this.view = view;
            this.plan = plan;
            this.prefixRevisions = prefixRevisions;
            this.context = context;
            this.contextLost = contextLost;
        }
    }

    private void insert(HostOperation op, JsonObject p) {
        String id = p.get("trackId").getAsString();
        int t = window.trackSlot(id);
        int count = p.get("expectedDeviceCount").getAsInt();
        String position = p.has("position") ? p.get("position").getAsString() : "end";
        TopState state = captureTop(t, count, "Device count or bank window differs from expectedDeviceCount.");
        int anchor = -1, index;
        switch (position) {
            case "start":
                index = 0;
                break;
            case "end":
                index = count;
                break;
            default:
                anchor = p.get("deviceIndex").getAsInt();
                if (anchor >= count)
                    throw Errors.notApplied(
                            Reason.TARGET_NOT_FOUND, "Anchor device index is beyond the observed device count.");
                if (!sources[t][anchor]
                        .device
                        .name()
                        .get()
                        .equals(p.get("expectedDeviceName").getAsString()))
                    throw Errors.notApplied(
                            Reason.STALE_TARGET,
                            "The device at index " + anchor + " is named \""
                                    + sources[t][anchor].device.name().get() + "\", not expectedDeviceName.");
                index = position.equals("before") ? anchor : anchor + 1;
        }
        scheduler.check(op);
        if (!window.trackMatches(id, window.at(t))
                || !prefixMatches(t, state.revisions)
                || window.devices(t).itemCount().get() != count
                || window.devices(t).scrollPosition().get() != 0)
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Insertion target changed before dispatch.");
        String kind = p.get("kind").getAsString();
        String value = p.get(kind.equals("file") ? "path" : "id").getAsString();
        InsertionPoint point;
        switch (position) {
            case "start":
                point = (window.at(t)).startOfDeviceChainInsertionPoint();
                break;
            case "end":
                point = ends[t];
                break;
            case "before":
                point = sources[t][anchor].device.beforeDeviceInsertionPoint();
                break;
            default:
                point = sources[t][anchor].device.afterDeviceInsertionPoint();
        }
        Edit edit = topEdit(id, t, DeviceChainEdit.Plan.insert(index, state.before), state, index);
        edit.extra.addProperty("requestedIdentifierVerified", false);
        JsonObject placed = new JsonObject();
        placed.addProperty("mode", position);
        placed.addProperty("index", index);
        edit.extra.add("position", placed);
        op.dispatched = true;
        insertAt(point, kind, value);
        observeEdit(op, edit, observationEnd(op));
    }

    private static void insertAt(InsertionPoint point, String kind, String value) {
        switch (kind) {
            case "bitwig":
                point.insertBitwigDevice(UUID.fromString(value));
                break;
            case "vst2":
                point.insertVST2Device(Integer.parseInt(value));
                break;
            case "vst3":
                point.insertVST3Device(value);
                break;
            case "clap":
                point.insertCLAPDevice(value);
                break;
            case "file":
                point.insertFile(value);
                break;
            default:
                throw new IllegalStateException("Validated insertion kind changed");
        }
    }

    private void insertIntoLayer(HostOperation op, JsonObject p) {
        JsonObject parent = p.getAsJsonObject("parent");
        String layerId = p.get("layerId").getAsString();
        DeviceSources.Source root = deviceSources.root(parent);
        DeviceSources.Layer layer = deviceSources.layer(parent, layerId);
        int count = p.get("expectedChildCount").getAsInt();
        LayerView view = new LayerView(layer);
        if (view.count() != count)
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Layer child count differs from expectedChildCount.");
        DeviceChainEdit.Before before =
                DeviceChainEdit.Before.capture(view, "Layer child count and window contents disagree.");
        boolean start = p.has("position") && p.get("position").getAsString().equals("start");
        scheduler.check(op);
        String trackId = root.track.id, rootName = root.name;
        java.util.function.BooleanSupplier context = () -> Boolean.TRUE.equals(root.exists)
                && root.name.equals(rootName)
                && root.track.id.equals(trackId)
                && Boolean.TRUE.equals(layer.exists)
                && layerId.equals(layer.id);
        if (!context.getAsBoolean())
            throw Errors.notApplied(Reason.STALE_CONTEXT, "Layer target changed before dispatch.");
        String kind = p.get("kind").getAsString();
        String value = p.get(kind.equals("file") ? "path" : "id").getAsString();
        Edit edit = new Edit(
                trackId,
                root.track.slot,
                view,
                DeviceChainEdit.Plan.insert(start ? 0 : count, before),
                null,
                context,
                "Layer context changed after dispatch.");
        edit.extra.addProperty("requestedIdentifierVerified", false);
        edit.extra.add("parent", parent.deepCopy());
        edit.extra.addProperty("layerId", layerId);
        edit.extra.addProperty("layerName", layer.name);
        JsonObject placed = new JsonObject();
        placed.addProperty("mode", start ? "start" : "end");
        placed.addProperty("index", edit.plan.index);
        edit.extra.add("position", placed);
        op.dispatched = true;
        insertAt(
                start ? layer.proxy.startOfDeviceChainInsertionPoint() : layer.proxy.endOfDeviceChainInsertionPoint(),
                kind,
                value);
        observeEdit(op, edit, observationEnd(op));
    }

    private void delete(HostOperation op, Target target) {
        int t = target.trackSlot, index = target.index;
        transportSafety.requirePlaybackSafe();
        TopState state = captureTop(t, -1, "Device bank contents disagree with the observed device count.");
        if (index >= state.before.count)
            throw Errors.notApplied(Reason.TARGET_NOT_FOUND, "Device is beyond the observed device count.");
        guard(op, target);
        Edit edit = topEdit(target.trackId, t, DeviceChainEdit.Plan.delete(index, state.before), state, index);
        edit.extra.add("deleted", DeviceChainEdit.summary(index, state.before.names[index], state.before.types[index]));
        op.dispatched = true;
        target.source.device.deleteObject();
        observeEdit(op, edit, observationEnd(op));
    }

    private void move(HostOperation op, Target target, int to) {
        int t = target.trackSlot, from = target.index;
        transportSafety.requirePlaybackSafe();
        TopState state = captureTop(t, -1, "Device bank contents disagree with the observed device count.");
        int n = state.before.count;
        if (from >= n) throw Errors.notApplied(Reason.TARGET_NOT_FOUND, "Device is beyond the observed device count.");
        if (to >= n) throw Errors.notApplied(Reason.TARGET_NOT_FOUND, "toIndex is beyond the observed device count.");
        guard(op, target);
        DeviceChainEdit.Plan plan = DeviceChainEdit.Plan.move(from, to, state.before);
        Edit edit = topEdit(target.trackId, t, plan, state, 0);
        JsonObject moved = DeviceChainEdit.summary(from, state.before.names[from], state.before.types[from]);
        moved.addProperty("toIndex", to);
        edit.extra.add("moved", moved);
        // Moving up lands after the device at `to`, moving down lands before it: the device ends at index `to`.
        InsertionPoint point = to > from
                ? sources[t][to].device.afterDeviceInsertionPoint()
                : sources[t][to].device.beforeDeviceInsertionPoint();
        op.dispatched = true;
        point.moveDevices(target.source.device);
        if (plan.indistinguishable()) {
            edit.extra.addProperty(
                    "hint", "Same names and types in the same order: the host cannot show whether anything moved.");
            finishEdit(op, edit, DeviceChainEdit.Observation.ambiguous());
            return;
        }
        observeEdit(op, edit, observationEnd(op));
    }

    /** Complete, ready top-level window: count, scroll, per-slot existence and the revisions of every present device. */
    private TopState captureTop(int t, int expectedCount, String staleMessage) {
        if (!countObserved[t]) throw Errors.notApplied(Reason.NOT_READY, "Device count has not been observed.");
        int count = window.devices(t).itemCount().get();
        if (window.devices(t).scrollPosition().get() != 0 || (expectedCount >= 0 && count != expectedCount))
            throw Errors.notApplied(Reason.STALE_CONTEXT, staleMessage);
        if (count > HostLimits.DEVICES)
            throw Errors.notApplied(
                    Reason.OUTSIDE_WINDOW,
                    "The chain is longer than the 16-device window; only inserts at a bounded count are supported.");
        long[] revisions = new long[count];
        for (int d = 0; d < HostLimits.DEVICES; d++) {
            Source source = sources[t][d];
            if (d < count
                    && source.identity.exists != null
                    && source.device.exists().get()
                    && source.device.position().get() != d)
                throw Errors.notApplied(Reason.STALE_CONTEXT, "Device count and bank contents disagree.");
            if (d < count) revisions[d] = source.identity.revision;
        }
        return new TopState(count, revisions, DeviceChainEdit.Before.capture(new TopView(t), staleMessage));
    }

    private Edit topEdit(String trackId, int t, DeviceChainEdit.Plan plan, TopState state, int fixedPrefix) {
        long[] prefix = java.util.Arrays.copyOf(state.revisions, Math.min(fixedPrefix, state.revisions.length));
        return new Edit(
                trackId,
                t,
                new TopView(t),
                plan,
                prefix,
                () -> window.trackMatches(trackId, window.at(t))
                        && window.devices(t).scrollPosition().get() == 0,
                "Track or device window changed after dispatch.");
    }

    private boolean prefixMatches(int t, long[] prefix) {
        for (int d = 0; d < prefix.length; d++) {
            Source source = sources[t][d];
            if (source.identity.revision != prefix[d]
                    || !source.device.exists().get()
                    || source.device.position().get() != d) return false;
        }
        return true;
    }

    private void observeEdit(HostOperation op, Edit edit, long end) {
        scheduler.conclude(
                op,
                end,
                () -> {
                    if (!edit.context.getAsBoolean())
                        return () -> uncertain(op, Reason.TARGET_CHANGED, edit.contextLost);
                    DeviceChainEdit.Observation outcome = observedEdit(edit);
                    return outcome.pending ? null : () -> finishEdit(op, edit, outcome);
                },
                () -> finishEdit(op, edit, observedEdit(edit)));
    }

    private DeviceChainEdit.Observation observedEdit(Edit edit) {
        return edit.prefixRevisions != null && !prefixMatches(edit.slot, edit.prefixRevisions)
                ? DeviceChainEdit.Observation.ambiguous()
                : DeviceChainEdit.observe(edit.view, edit.plan);
    }

    private void finishEdit(HostOperation op, Edit edit, DeviceChainEdit.Observation outcome) {
        DeviceChainEdit.View view = edit.view;
        DeviceChainEdit.Plan plan = edit.plan;
        boolean insert = plan.kind == DeviceChainEdit.INSERT;
        JsonObject result = outcome.ok
                ? stamp(new JsonObject(), Outcome.APPLIED, null)
                : stamp(
                        new JsonObject(),
                        Outcome.UNKNOWN,
                        outcome.pending ? Reason.NOT_OBSERVED : Reason.READBACK_MISMATCH);
        if (outcome.ok) result.addProperty("verification", outcome.verification);
        result.addProperty("trackId", edit.trackId);
        result.addProperty("beforeCount", plan.before.count);
        result.addProperty("afterCount", view.count());
        result.addProperty("identityScope", "observed_bank_delta_not_persistent_device_identity");
        result.add("before", DeviceChainEdit.before(plan.before));
        result.add("after", DeviceChainEdit.after(view));
        for (String key : edit.extra.keySet()) result.add(key, edit.extra.get(key));
        if (insert) {
            boolean identified = outcome.ok && plan.index < view.window();
            result.add(
                    "inserted",
                    identified
                            ? DeviceChainEdit.summary(plan.index, view.name(plan.index), view.type(plan.index))
                            : JsonNull.INSTANCE);
        }
        op.result.complete(result);
    }

    private final class TopView implements DeviceChainEdit.View {
        final int t;

        TopView(int t) {
            this.t = t;
        }

        @Override
        public int count() {
            return window.devices(t).itemCount().get();
        }

        @Override
        public int window() {
            return HostLimits.DEVICES;
        }

        @Override
        public long countSequence() {
            return countSequence[t];
        }

        @Override
        public boolean known(int slot) {
            return sources[t][slot].identity.exists != null;
        }

        @Override
        public boolean exists(int slot) {
            return sources[t][slot].device.exists().get();
        }

        @Override
        public boolean ready(int slot) {
            return sources[t][slot].ready()
                    && !sources[t][slot].device.name().get().isEmpty();
        }

        @Override
        public String name(int slot) {
            return sources[t][slot].device.name().get();
        }

        @Override
        public String type(int slot) {
            return sources[t][slot].device.deviceType().get();
        }

        @Override
        public long nameSequence(int slot) {
            return sources[t][slot].identity.nameSequence;
        }
    }

    /** A layer's child window: two observed slots, with the layer's own total count. */
    private static final class LayerView implements DeviceChainEdit.View {
        final DeviceSources.Layer layer;

        LayerView(DeviceSources.Layer layer) {
            this.layer = layer;
        }

        private DeviceSources.Source child(int slot) {
            return layer.children[slot];
        }

        @Override
        public int count() {
            return layer.window.count == null ? -1 : layer.window.count;
        }

        @Override
        public int window() {
            return DeviceSources.CHILDREN;
        }

        @Override
        public long countSequence() {
            return layer.window.countSequence;
        }

        @Override
        public boolean known(int slot) {
            return child(slot).exists != null;
        }

        @Override
        public boolean exists(int slot) {
            return Boolean.TRUE.equals(child(slot).exists);
        }

        @Override
        public boolean ready(int slot) {
            return child(slot).metadataReady() && !child(slot).name.isEmpty();
        }

        @Override
        public String name(int slot) {
            return child(slot).name == null ? "" : child(slot).name;
        }

        @Override
        public String type(int slot) {
            return child(slot).type == null ? "" : child(slot).type;
        }

        @Override
        public long nameSequence(int slot) {
            return child(slot).nameSequence;
        }
    }

    private static final class TopState {
        final int count;
        final long[] revisions;
        final DeviceChainEdit.Before before;

        TopState(int count, long[] revisions, DeviceChainEdit.Before before) {
            this.count = count;
            this.revisions = revisions;
            this.before = before;
        }
    }

    private long observationEnd(HostOperation op) {
        return scheduler.observeUntil(op);
    }

    private static void uncertain(HostOperation op, Reason reason, String message) {
        op.result.complete(Errors.unknownResult(Errors.unknown(reason, message)));
    }

    private static JsonObject stamp(JsonObject result, Outcome outcome, Reason reason) {
        result.addProperty("outcome", outcome.wire());
        if (reason != null) result.addProperty("reason", reason.wire());
        return result;
    }

    private static JsonObject observedOrNot(JsonObject result, boolean observed) {
        return observed ? stamp(result, Outcome.APPLIED, null) : stamp(result, Outcome.UNKNOWN, Reason.NOT_OBSERVED);
    }

    private static JsonObject catalog() {
        return catalog(System.getProperty("installationRoot"));
    }

    /** Every factory device file under the installation, with category and description read from its localization resources at runtime. */
    static JsonObject catalog(String installation) {
        JsonObject result = new JsonObject();
        result.addProperty("scope", "installed_bitwig_factory_devices");
        result.addProperty("completePluginCatalog", false);
        result.addProperty("verification", "local_file_presence_not_insertion_or_audio_proof");
        result.addProperty("bitwigIdAvailable", false);
        JsonArray devices = new JsonArray();
        result.add("devices", devices);
        if (installation == null || installation.isEmpty()) {
            result.addProperty("supported", false);
            result.addProperty("reason", "Host did not provide its installationRoot; no paths were guessed.");
            return result;
        }
        try {
            Path root = Path.of(installation);
            if (!root.isAbsolute()) throw new InvalidPathException(installation, "Installation root is not absolute");
            Path directory = root.resolve("Library").resolve("devices").normalize();
            DeviceTexts texts = DeviceTexts.read(root.resolve("resources").resolve("localization"));
            java.util.List<Path> files = new java.util.ArrayList<>();
            try (java.util.stream.Stream<Path> stream =
                    Files.isDirectory(directory) ? Files.list(directory) : java.util.stream.Stream.<Path>empty()) {
                stream.filter(f -> f.getFileName().toString().endsWith(".bwdevice") && Files.isRegularFile(f))
                        .forEach(files::add);
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
            files.sort(java.util.Comparator.comparing(
                    f -> f.getFileName().toString().toLowerCase(java.util.Locale.ROOT)));
            for (Path path : files) {
                String file = path.getFileName().toString();
                String name = file.substring(0, file.length() - ".bwdevice".length());
                JsonObject item = new JsonObject();
                item.addProperty("name", name);
                item.addProperty("kind", "file");
                item.addProperty("path", path.toString());
                item.addProperty("sourceKind", "installed_bitwig_factory_device");
                String category = texts.category(name), description = texts.description(name);
                if (category != null) item.addProperty("category", category);
                if (description != null) item.addProperty("description", description);
                devices.add(item);
            }
            result.addProperty("supported", true);
            result.addProperty("textsAvailable", texts.available());
        } catch (InvalidPathException error) {
            result.addProperty("supported", false);
            result.addProperty("reason", "Host installationRoot is not an absolute filesystem path.");
        } catch (java.io.UncheckedIOException | SecurityException error) {
            result.addProperty("supported", false);
            result.addProperty("reason", "Device directory could not be listed.");
        }
        return result;
    }

    /** Name, category and description read from Device-descriptions/Categories resources; absent files only drop the text. */
    static final class DeviceTexts {
        private final java.util.Map<String, String> descriptions = new java.util.HashMap<>(),
                categories = new java.util.HashMap<>();
        private final java.util.Map<String, String> displayCategories = new java.util.HashMap<>();
        private boolean available;

        boolean available() {
            return available;
        }

        String description(String deviceName) {
            return descriptions.get(key(deviceName));
        }

        String category(String deviceName) {
            return categories.get(key(deviceName));
        }
        /** Word-order-insensitive: the file "v9 Hat Open" is resource "v9_open_hat". */
        private static String key(String name) {
            String[] words = name.toLowerCase(java.util.Locale.ROOT).split("[\\s_]+");
            java.util.Arrays.sort(words);
            return String.join(" ", words);
        }

        private static String squash(String text) {
            return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        }

        static DeviceTexts read(Path localization) {
            DeviceTexts texts = new DeviceTexts();
            try {
                Path file = localization.resolve("Device-descriptions-resources.properties");
                if (!Files.isRegularFile(file)) return texts;
                String text = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
                if (text.startsWith("\uFEFF")) text = text.substring(1);
                java.util.Properties values = new java.util.Properties();
                values.load(new java.io.StringReader(text));
                Path categoryFile = localization.resolve("Categories-resources.properties");
                if (Files.isRegularFile(categoryFile)) {
                    java.util.Properties display = new java.util.Properties();
                    display.load(new java.io.StringReader(
                            new String(Files.readAllBytes(categoryFile), java.nio.charset.StandardCharsets.UTF_8)));
                    for (String key : display.stringPropertyNames())
                        texts.displayCategories.put(squash(key), display.getProperty(key));
                }
                String header = null;
                for (String line : text.split("\r?\n")) {
                    java.util.regex.Matcher heading = java.util.regex.Pattern.compile("^#([a-z_-]+)\\.\\s*$")
                            .matcher(line);
                    if (heading.matches()) {
                        header = heading.group(1);
                        continue;
                    }
                    java.util.regex.Matcher entry = java.util.regex.Pattern.compile(
                                    "^device\\.([^=:\\s]+)\\.desc\\s*[=:]")
                            .matcher(line);
                    if (!entry.find()) continue;
                    String id = entry.group(1), description = values.getProperty("device." + id + ".desc");
                    String k = key(id);
                    if (description != null) texts.descriptions.put(k, description.trim());
                    if (header != null) texts.categories.put(k, texts.displayCategory(header));
                }
                texts.available = !texts.descriptions.isEmpty();
            } catch (java.io.IOException | RuntimeException error) {
                texts.descriptions.clear();
                texts.categories.clear();
            }
            return texts;
        }

        private String displayCategory(String header) {
            String flat = squash(header);
            String exact = displayCategories.get(flat);
            if (exact != null) return exact;
            for (java.util.Map.Entry<String, String> entry : displayCategories.entrySet())
                if (entry.getKey().startsWith(flat)) return entry.getValue();
            StringBuilder out = new StringBuilder();
            for (String word : header.split("[_-]+")) {
                if (word.isEmpty()) continue;
                if (out.length() > 0) out.append(' ');
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return out.toString();
        }
    }

    private static final class Source {
        final DeviceSources.Source identity;
        final Track track;
        final Device device;
        final int deviceIndex;
        CursorRemoteControlsPage page;
        final RemoteControl[] controls = new RemoteControl[GridRemotePageWriter.SLOTS];
        final boolean[] controlExistsObserved = new boolean[8],
                controlNamesObserved = new boolean[8],
                controlMappingObserved = new boolean[8],
                controlValuesObserved = new boolean[8];
        final boolean[] controlRebindPending = new boolean[8], controlValuesFresh = new boolean[8];
        final boolean[] controlPresenceEstablished = new boolean[8], lastControlExists = new boolean[8];
        final long[] controlSequences = new long[8];
        boolean pageNamesObserved, pageIndexObserved, pageNameObserved, pageCountObserved;
        boolean remoteRebindPending, remoteFlushPending, pageIndexFresh;
        long remoteGeneration, pageSequence;
        int lastPageIndex;
        long enabledSequence;
        boolean enabledObserved;

        Source(DeviceSources.Source identity, int deviceIndex) {
            this.identity = identity;
            this.track = identity.track.proxy;
            this.device = identity.device;
            this.deviceIndex = deviceIndex;
        }

        boolean ready() {
            return identity.metadataReady() && enabledObserved;
        }
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
