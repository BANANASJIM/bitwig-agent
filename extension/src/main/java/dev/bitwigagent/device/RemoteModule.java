package dev.bitwigagent.device;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.MethodSpecs;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.ValueInput;
import dev.bitwigagent.core.ValueMatch;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.grid.GridRemotePageWriter;
import dev.bitwigagent.host.DeviceSources;
import dev.bitwigagent.host.RequestSnapshot;
import dev.bitwigagent.host.TrackWindow;
import dev.bitwigagent.host.TransportSafety;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * devices.remote.get and the extension's own Remote Controls page of each root device, which never follows the page
 * selected in the GUI: a page or control is addressed by its ref and the page is switched here, internally.
 */
public final class RemoteModule implements HostModule, ParameterResolver.RemotePages {
    static final Set<String> METHODS = Set.of("devices.remote.get");
    private final ControllerHost host;
    private final Scheduler scheduler;
    private final TrackWindow window;
    private final DeviceSources deviceSources;
    private final TransportSafety transportSafety;
    private final Remote[][] sources = new Remote[HostLimits.TRACKS][HostLimits.DEVICES];
    private final Map<DeviceSources.Source, Remote> remoteSources = new IdentityHashMap<>();
    /** How a write that finds its cache not fresh learns what the control holds; null when there is no native model. */
    public NativeValues nativeValues;

    /** Reads a control's value from the native model on the document thread. */
    interface NativeValues {
        /**
         * The value the control of {@code slot} on the page now shown at {@code page} (named {@code pageName} of
         * {@code pageCount} pages) holds, or null when the model cannot say; a binding that is unavailable is also
         * null, so the write goes on as it would without a model. Other failures go to {@code failed}.
         */
        void read(
                HostOperation op,
                DeviceSources.Source device,
                int page,
                String pageName,
                int pageCount,
                int slot,
                java.util.function.Consumer<Double> done,
                java.util.function.Consumer<RuntimeException> failed);
    }

    public RemoteModule(
            ControllerHost host,
            Scheduler scheduler,
            TrackWindow window,
            DeviceSources deviceSources,
            TransportSafety transportSafety) {
        this.host = host;
        this.scheduler = scheduler;
        this.window = window;
        this.deviceSources = deviceSources;
        this.transportSafety = transportSafety;
        for (int t = 0; t < HostLimits.TRACKS; t++) {
            for (int d = 0; d < HostLimits.DEVICES; d++) {
                Remote source = new Remote(deviceSources.roots[t][d], d);
                sources[t][d] = source;
                remoteSources.put(source.identity, source);
                source.identity.listeners.add((structural, name) -> {
                    if (structural) invalidateRemotes(source);
                    if (source.remoteFlushPending) requestRemoteFlush(source);
                });
                initRemotes(source, t, d);
            }
        }
    }

    /** The observed Remote Controls page names of a root device slot, joined, or null while unobserved or absent. */
    public String pageNames(int track, int device) {
        Remote source = sources[track][device];
        String[] names = source.page.pageNames().get();
        if (!source.pageNamesObserved || names == null || !remoteSourcePresent(source)) return null;
        return String.join("\n", names);
    }

    private void initRemotes(Remote source, int track, int device) {
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

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject params) {}

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        DeviceSources.Source device = deviceSources.device(p.getAsJsonObject("target"), false);
        Remote source = remoteSource(device);
        requireRemotePage(source);
        if (!p.has("page")) {
            op.result.complete(metadata(device, source));
            return;
        }
        selectPage(op, device, p.get("page").getAsInt(), -1, () -> op.result.complete(metadata(device, source)));
    }

    /**
     * Switches this extension's own page (never the GUI's) and runs {@code then} once the host reports it selected.
     * {@code slot} is the control a write is about to use, whose value must have arrived too; -1 for a read.
     */
    void selectPage(HostOperation op, DeviceSources.Source device, int page, int slot, Runnable then) {
        Remote source = remoteSource(device);
        requireRemotePage(source);
        String[] names = source.page.pageNames().get();
        int count = Math.min(source.page.pageCount().get(), names.length);
        if (page >= count)
            throw Errors.targetNotFound(
                    Refs.remotePage(device.ref(), page, ""),
                    "The device has " + count + " Remote Controls page(s), so page " + page + " does not exist.");
        lease(op, source);
        if (source.pageIndexFresh && source.page.selectedPageIndex().get() == page) {
            then.run();
            return;
        }
        long sequence = source.pageSequence;
        RequestSnapshot unchanged = RequestSnapshot.of(device);
        scheduler.check(op);
        source.page.selectedPageIndex().set(page);
        scheduler.poll(
                op,
                scheduler.observeUntil(op),
                () -> {
                    unchanged.require(op);
                    return source.pageSequence > sequence && settled(source, page, slot);
                },
                observed -> {
                    if (!observed)
                        throw Errors.notApplied(Reason.NOT_OBSERVED, "The host did not report the page switch.");
                    then.run();
                });
    }

    /**
     * One request at a time moves a device's page and uses what it moved to, from the switch to its verified write: a
     * second one meets pending/busy instead of finding the page under another request's control.
     */
    private static void lease(HostOperation op, Remote source) {
        HostOperation owner = source.pageOwner;
        if (owner != null && owner != op && !owner.result.isDone())
            throw Errors.notApplied(
                    Reason.BUSY, "Another request is using this device's Remote Controls page; try again after it.");
        source.pageOwner = op;
    }

    /**
     * The host shows the page and its name, and every existing control has its rebind cleared and its mapping observed.
     * A value the host does not report again reads as unobserved (null); only the control about to be written needs one.
     */
    private static boolean settled(Remote source, int page, int slot) {
        String[] names = source.page.pageNames().get();
        if (source.page.selectedPageIndex().get() != page
                || page >= names.length
                || !names[page].equals(source.page.getName().get())) return false;
        for (int i = 0; i < source.controls.length; i++) {
            if (!source.controlExistsObserved[i] || !source.controls[i].exists().get()) continue;
            if (source.controlRebindPending[i] || !source.controlMappingObserved[i]) return false;
            if (i == slot && !source.controlValuesObserved[i]) return false;
        }
        return true;
    }

    /** The device's Remote Controls: its pages and the controls of the selected page, each with the ref to write it. */
    private JsonObject metadata(DeviceSources.Source device, Remote source) {
        requireRemotePage(source);
        JsonObject deviceRef = device.ref();
        JsonObject result = new JsonObject();
        result.add("ref", deviceRef);
        result.addProperty("scope", "independent_device_remote_page");
        result.addProperty("followsUiPageSelection", false);
        int selected = source.page.selectedPageIndex().get();
        String[] pageNames = source.page.pageNames().get();
        JsonObject pageRef = Refs.remotePage(
                deviceRef,
                selected,
                selected < pageNames.length
                        ? pageNames[selected]
                        : source.page.getName().get());
        result.add("page", pageRef);
        result.addProperty("pageCount", source.page.pageCount().get());
        JsonArray pages = new JsonArray();
        String[] names = source.page.pageNames().get();
        for (int i = 0; i < Math.min(names.length, 4096); i++) pages.add(Refs.remotePage(deviceRef, i, names[i]));
        result.add("pages", pages);
        result.add("pageWindow", Results.window(0, 4096, source.page.pageCount().get()));
        JsonArray controls = new JsonArray();
        boolean complete = true;
        for (int i = 0; i < 8; i++) {
            RemoteControl control = source.controls[i];
            JsonObject item = new JsonObject();
            item.addProperty("index", i);
            boolean present =
                    source.controlExistsObserved[i] && control.exists().get();
            boolean readable = present && !source.controlRebindPending[i];
            item.addProperty(
                    "exists", source.controlExistsObserved[i] ? control.exists().get() : null);
            String name =
                    readable && source.controlNamesObserved[i] ? control.name().get() : null;
            item.addProperty("name", name);
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
            item.addProperty("value", valueKnown ? control.value().get() : null);
            if (name != null && !name.isEmpty()) item.add("ref", Refs.parameter(pageRef, Integer.toString(i), name));
            complete &= source.controlExistsObserved[i]
                    && (!control.exists().get()
                            || (source.controlNamesObserved[i] && source.controlMappingObserved[i] && valueKnown));
            controls.add(item);
        }
        result.add("controls", controls);
        result.addProperty("complete", complete);
        return result;
    }

    /**
     * Writes one control of the extension's own page, switching to its page first. The control and its snapshot are
     * taken once the switch has settled, so the switch itself never counts as a change of the target.
     */
    void setControl(
            HostOperation op, ParameterResolver.Remote target, JsonObject ref, ValueInput input, JsonElement expect) {
        double requested = input.normalized("value");
        op.inspect = Refs.read(ref);
        selectPage(op, target.device, target.page, target.slot, () -> write(op, target, ref, requested, expect));
    }

    private void write(
            HostOperation op, ParameterResolver.Remote target, JsonObject ref, double requested, JsonElement expect) {
        target.requireControl();
        Remote source = remoteSource(target.device);
        int slot = target.slot;
        RemoteControl control = source.controls[slot];
        RequestSnapshot snapshot = target.snapshot();
        double current = control.value().get();
        if (expect != null) {
            JsonObject observed = new JsonObject();
            observed.addProperty("value", current);
            ValueMatch.requireExpected(
                    expect.getAsJsonObject(), observed, java.util.Map.of("value", ValueMatch.NORMALIZED));
        }
        JsonElement asked = new JsonPrimitive(requested);
        if (source.controlValuesFresh[slot] && Math.abs(current - requested) <= HostLimits.TOLERANCE) {
            op.result.complete(result(ref, asked, current, Outcome.ALREADY_MATCHED, null, Verification.API_READBACK));
            return;
        }
        if (nativeValues == null || source.controlValuesFresh[slot]) {
            dispatch(op, source, slot, ref, requested, snapshot);
            return;
        }
        // The cache is not fresh (after a rebind or page switch) and the host does not echo an unchanged value, so
        // the model itself is asked what the control holds before anything is sent.
        nativeValues.read(
                op,
                target.device,
                target.page,
                source.page.pageNames().get()[target.page],
                Math.min(source.page.pageCount().get(), source.page.pageNames().get().length),
                slot,
                held -> scheduler.schedule(
                        op, () -> afterNativeRead(op, source, slot, ref, requested, snapshot, held), 0),
                error -> scheduler.schedule(op, () -> op.fail(error), 0));
    }

    private void afterNativeRead(
            HostOperation op,
            Remote source,
            int slot,
            JsonObject ref,
            double requested,
            RequestSnapshot snapshot,
            Double held) {
        scheduler.check(op);
        snapshot.require(op);
        if (held != null && Math.abs(held - requested) <= HostLimits.TOLERANCE) {
            JsonObject result = result(
                    ref,
                    new JsonPrimitive(requested),
                    held,
                    Outcome.ALREADY_MATCHED,
                    null,
                    Verification.NATIVE_READBACK);
            op.result.complete(result);
            return;
        }
        dispatch(op, source, slot, ref, requested, snapshot);
    }

    private void dispatch(
            HostOperation op, Remote source, int slot, JsonObject ref, double requested, RequestSnapshot snapshot) {
        RemoteControl control = source.controls[slot];
        JsonElement asked = new JsonPrimitive(requested);
        transportSafety.atLeast(op, MethodSpecs.Transport.NOT_RECORDING);
        scheduler.check(op);
        snapshot.require(op);
        long sequence = source.controlSequences[slot];
        op.markDispatched();
        // Explicit absolute RPC writes must not wait for physical-controller takeover.
        control.value().setImmediately(requested);
        scheduler.poll(
                op,
                scheduler.observeUntil(op),
                () -> {
                    snapshot.require(op);
                    return source.controlValuesFresh[slot]
                            && source.controlSequences[slot] > sequence
                            && Math.abs(control.value().get() - requested) <= HostLimits.TOLERANCE;
                },
                observed -> {
                    snapshot.require(op);
                    boolean fresh = source.controlValuesFresh[slot] && source.controlSequences[slot] > sequence;
                    double seen = control.value().get();
                    op.result.complete(Errors.withInspect(
                            observed
                                    ? result(ref, asked, seen, Outcome.APPLIED, null, Verification.API_READBACK)
                                    : result(
                                            ref,
                                            asked,
                                            fresh ? seen : null,
                                            Outcome.UNKNOWN,
                                            fresh ? Reason.READBACK_MISMATCH : Reason.NOT_OBSERVED,
                                            Verification.API_READBACK),
                            op.inspect));
                });
    }

    private static JsonObject result(
            JsonObject ref, JsonElement requested, Double observed, Outcome outcome, Reason reason, Verification how) {
        JsonObject result = Results.outcome(outcome, how);
        if (reason != null) result.addProperty("reason", reason.wire());
        result.add("ref", ref);
        JsonObject fields = new JsonObject();
        fields.add(
                "value",
                Results.field(requested, observed == null ? null : new JsonPrimitive(observed), outcome, reason, how));
        result.add("fields", fields);
        return result;
    }

    private static void requireRemotePage(Remote source) {
        if (source.remoteRebindPending)
            throw Errors.notApplied(Reason.NOT_READY, "Remote source rebind awaits a host flush.");
        if (!pageMetadataObserved(source))
            throw Errors.notApplied(Reason.NOT_READY, "Remote page metadata has not been observed.");
    }

    private static boolean pageMetadataObserved(Remote source) {
        return source.pageNamesObserved
                && source.pageCountObserved
                && source.pageIndexObserved
                && source.pageNameObserved;
    }

    private Remote remoteSource(DeviceSources.Source device) {
        Remote source = remoteSources.get(device);
        if (source == null)
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE, "Remote Controls pages of a device inside a chain are not observed.");
        return source;
    }

    @Override
    public String[] pages(DeviceSources.Source device) {
        Remote source = remoteSource(device);
        return !source.remoteRebindPending && pageMetadataObserved(source)
                ? source.page.pageNames().get()
                : null;
    }

    @Override
    public int selectedPage(DeviceSources.Source device) {
        return remoteSource(device).page.selectedPageIndex().get();
    }

    @Override
    public long generation(DeviceSources.Source device) {
        return remoteSource(device).remoteGeneration;
    }

    @Override
    public ParameterResolver.Control control(DeviceSources.Source device, int slot) {
        Remote source = remoteSource(device);
        RemoteControl control = source.controls[slot];
        Boolean exists = source.controlExistsObserved[slot] ? control.exists().get() : null;
        boolean readable = Boolean.TRUE.equals(exists) && !source.controlRebindPending[slot];
        boolean ready = readable
                && source.controlMappingObserved[slot]
                && source.controlValuesObserved[slot]
                && !control.isBeingMapped().get()
                && Double.isFinite(control.value().get());
        return new ParameterResolver.Control(
                exists,
                readable && source.controlNamesObserved[slot] ? control.name().get() : null,
                ready);
    }

    private static boolean remoteSourcePresent(Remote source) {
        // Retain first-population observations even if existence/UUID callbacks have not arrived yet.
        // Later absent callbacks cannot establish freshness; history is reconciled only at flush.
        return !source.identity.established
                || (source.track.exists().get() && source.device.exists().get());
    }

    private static boolean remoteControlPresent(Remote source, int index) {
        // Initial absent placeholders may precede the first real control's metadata/value callbacks.
        // After that first presence, callbacks during absence cannot acknowledge a replacement binding.
        return remoteSourcePresent(source)
                && (!source.controlPresenceEstablished[index] || source.lastControlExists[index]);
    }

    /** Observation history belongs to the continuous proxy; a rebind invalidates readiness and write freshness. */
    private void invalidateRemotes(Remote source) {
        source.remoteRebindPending = true;
        source.pageIndexFresh = false;
        invalidateControls(source);
        source.remoteGeneration++;
    }

    private void invalidateControls(Remote source) {
        java.util.Arrays.fill(source.controlRebindPending, true);
        java.util.Arrays.fill(source.controlValuesFresh, false);
        requestRemoteFlush(source);
    }

    private void invalidateControl(Remote source, int index) {
        source.controlRebindPending[index] = true;
        source.controlValuesFresh[index] = false;
        requestRemoteFlush(source);
    }

    private void requestRemoteFlush(Remote source) {
        source.remoteFlushPending = true;
        host.requestFlush();
    }

    @Override
    public void flush() {
        for (int t = 0; t < sources.length; t++) {
            for (Remote source : sources[t]) {
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

    private static final class Remote {
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
        HostOperation pageOwner;
        long remoteGeneration, pageSequence;
        int lastPageIndex;

        Remote(DeviceSources.Source identity, int deviceIndex) {
            this.identity = identity;
            this.track = identity.track.proxy;
            this.device = identity.device;
            this.deviceIndex = deviceIndex;
        }
    }
}
