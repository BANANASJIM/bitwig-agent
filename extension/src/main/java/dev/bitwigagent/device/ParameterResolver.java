package dev.bitwigagent.device;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.host.DeviceSources;
import dev.bitwigagent.host.RequestSnapshot;
import dev.bitwigagent.host.TrackRefs;
import dev.bitwigagent.host.TrackWindow;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Resolves a parameter ref to the host object behind it, one branch per owner kind. */
public final class ParameterResolver {
    /** The direct-parameter observations of a device. */
    interface DirectParameters {
        /** The observed parameter ids, or null before the device's first accepted id callback. */
        Set<String> ids(DeviceSources.Source device);

        String name(DeviceSources.Source device, String id);
    }

    /** The extension's own Remote Controls page of a device; it does not follow the page selected in the GUI. */
    interface RemotePages {
        /** The page names, or null while page metadata is unobserved or a rebind awaits a flush. */
        String[] pages(DeviceSources.Source device);

        int selectedPage(DeviceSources.Source device);

        /** Moves whenever a page name, the page count or selection, or a control's existence, name or mapping does. */
        long generation(DeviceSources.Source device);

        Control control(DeviceSources.Source device, int slot);
    }

    /** A control of the selected page: exists is null while unobserved; ready when it can be written now. */
    static final class Control {
        final Boolean exists;
        final String name;
        final boolean ready;

        Control(Boolean exists, String name, boolean ready) {
            this.exists = exists;
            this.name = name;
            this.ready = ready;
        }
    }

    /** Document-thread lookups for one owner kind (Grid module or modulator) on a captured native device. */
    public interface ModuleOwners {
        /** The owner with this UUID on the device, or null when the device has none. */
        Object owner(DeviceSources.Native.Capture device, UUID id, Runnable check);

        String ownerName(Object owner);

        /** The owner's parameter with this key, or null when it has none. */
        Object parameter(Object owner, String key, Runnable check);

        /** The parameter's name, or null when it cannot be read. */
        String parameterName(Object parameter);
    }

    private final DeviceSources devices;
    private final TrackRefs tracks;
    private final TrackWindow window;
    private final DirectParameters direct;
    private final RemotePages remotes;

    public ParameterResolver(
            DeviceSources devices, TrackRefs tracks, TrackWindow window, DirectParameters direct, RemotePages remotes) {
        this.devices = devices;
        this.tracks = tracks;
        this.window = window;
        this.direct = direct;
        this.remotes = remotes;
    }

    /**
     * The parameter a ref names. Its owning device is compared before anything below it is looked at, so a replaced
     * device is target_changed with the device now there, never a parameter of the other device.
     */
    public Resolved resolve(JsonObject ref) {
        JsonObject owner = ref.getAsJsonObject("owner");
        String id = ref.get("id").getAsString();
        switch (ParameterRefs.owner(ref)) {
            case ParameterRefs.DEVICE:
                return NativeChains.nested(owner) ? new Nested() : direct(ref, devices.device(owner, false), id);
            case ParameterRefs.REMOTE_PAGE:
                return remote(ref, devices.device(owner.getAsJsonObject("device"), false));
            case ParameterRefs.TRACK:
                int slot = tracks.locate(owner);
                require(ref, Refs.parameter(tracks.ref(slot), id, null));
                return new TrackParameter(slot, id);
            default:
                return new ModuleParameter(ref, devices.device(owner.getAsJsonObject("device"), false));
        }
    }

    private Direct direct(JsonObject ref, DeviceSources.Source device, String id) {
        if (!device.directReady())
            throw Errors.notApplied(Reason.NOT_READY, "The device's direct parameters are not observed yet.");
        Set<String> ids = direct.ids(device);
        if (ids == null) throw Errors.notApplied(Reason.NOT_READY, "The device's parameter ids are not observed yet.");
        if (!ids.contains(id)) throw Errors.targetNotFound(ref, "The device has no such parameter now.");
        String name = direct.name(device, id);
        if (ref.has("name") && name == null)
            throw Errors.notApplied(Reason.NOT_READY, "The parameter's name is not observed yet.");
        require(ref, Refs.parameter(device.ref(), id, name));
        return new Direct(device, id);
    }

    private Remote remote(JsonObject ref, DeviceSources.Source device) {
        JsonObject owner = ref.getAsJsonObject("owner");
        int page = owner.get("index").getAsInt(), slot = ParameterRefs.control(ref);
        JsonObject current = page(owner, device, page);
        if (!Refs.matches(owner, current, false))
            throw Errors.targetChanged("Another Remote Controls page is at this index than the one read.", current);
        return new Remote(ref, device, page, slot);
    }

    private JsonObject page(JsonObject owner, DeviceSources.Source device, int page) {
        String[] pages = remotes.pages(device);
        if (pages == null) throw Errors.notApplied(Reason.NOT_READY, "Remote Controls pages are not observed yet.");
        if (page >= pages.length)
            throw Errors.targetNotFound(owner, "The device has no such Remote Controls page now.");
        return Refs.remotePage(device.ref(), page, pages[page]);
    }

    /** A native device that changed under a parameter is target_changed like any other change of it. */
    private static RpcException changed(RpcException error, HostOperation operation) {
        if (!error.is(Reason.TARGET_CHANGED)) return error;
        if (operation != null && operation.dispatched())
            return Errors.unknown(Reason.TARGET_CHANGED, error.getMessage() + " Outcome unknown.");
        return Errors.notApplied(Reason.TARGET_CHANGED, error.getMessage());
    }

    private static void require(JsonObject ref, JsonObject current) {
        if (!Refs.matches(ref, current, true)) throw Refs.parameterChanged(ref, current);
    }

    /** One resolved parameter; its snapshot is taken right before dispatch. */
    public abstract static class Resolved {
        public abstract RequestSnapshot snapshot();
    }

    final class Direct extends Resolved {
        final DeviceSources.Source device;
        final String id;

        Direct(DeviceSources.Source device, String id) {
            this.device = device;
            this.id = id;
        }

        /** Also the id set: a parameter appearing or vanishing on the device changes the target under the request. */
        @Override
        public RequestSnapshot snapshot() {
            return RequestSnapshot.of(device).with(() -> {
                Set<String> ids = direct.ids(device);
                return ids == null ? null : new LinkedHashSet<>(ids);
            });
        }
    }

    /**
     * A direct parameter of a device inside a chain. Nothing about it is observed: the caller finds the device and the
     * parameter natively and compares the ref there, in the same document-thread pass as the write.
     */
    static final class Nested extends Resolved {
        @Override
        public RequestSnapshot snapshot() {
            return new RequestSnapshot();
        }
    }

    /** A Remote Control; the caller selects its page, then checks the control before taking the snapshot. */
    final class Remote extends Resolved {
        private final JsonObject ref;
        final DeviceSources.Source device;
        final int page, slot;

        Remote(JsonObject ref, DeviceSources.Source device, int page, int slot) {
            this.ref = ref;
            this.device = device;
            this.page = page;
            this.slot = slot;
        }

        /** Once the page is selected: the control exists, is ready to write and still has the name the ref read. */
        void requireControl() {
            if (remotes.selectedPage(device) != page)
                throw Errors.notApplied(Reason.NOT_READY, "The Remote Controls page is not selected yet.");
            Control control = remotes.control(device, slot);
            if (Boolean.FALSE.equals(control.exists))
                throw Errors.targetNotFound(ref, "The page has no control in this slot now.");
            if (control.exists == null || !control.ready || control.name == null)
                throw Errors.notApplied(
                        Reason.NOT_READY, "The Remote Control is unobserved, being mapped or awaiting a fresh value.");
            JsonObject owner = ref.getAsJsonObject("owner");
            require(ref, Refs.parameter(page(owner, device, page), ref.get("id").getAsString(), control.name));
        }

        /** Also the remote generation, taken after the page switch settled so the switch itself does not count. */
        @Override
        public RequestSnapshot snapshot() {
            return RequestSnapshot.of(device).with(() -> remotes.generation(device));
        }
    }

    /**
     * A modulator or Grid module parameter. Its device is resolved here; {@link #bind} finds the owner by UUID and the
     * parameter by key on the document thread.
     */
    public static final class ModuleParameter extends Resolved {
        public final String owner, id;
        public final UUID ownerId;
        public final DeviceSources.Source device;
        private final JsonObject ref, deviceRef;

        ModuleParameter(JsonObject ref, DeviceSources.Source device) {
            this.ref = ref;
            this.owner = ParameterRefs.owner(ref);
            this.ownerId = ParameterRefs.ownerId(ref);
            this.id = ref.get("id").getAsString();
            this.device = device;
            this.deviceRef = device.ref();
        }

        @Override
        public RequestSnapshot snapshot() {
            return RequestSnapshot.of(device);
        }

        /** On the document thread: the owner and parameter now, refused as stale when either is gone or changed. */
        NativeParameter bind(ModuleOwners owners, DeviceSources.Native natives, Runnable check) {
            DeviceSources.Native.Capture captured;
            try {
                captured = natives.capture(device, check);
            } catch (RpcException error) {
                throw changed(error, null);
            }
            Object found = owners.owner(captured, ownerId, check);
            if (found == null)
                throw Errors.targetNotFound(ref.getAsJsonObject("owner"), "The device has no such " + owner + " now.");
            Object parameter = owners.parameter(found, id, check);
            if (parameter == null) throw Errors.targetNotFound(ref, "The " + owner + " has no such parameter now.");
            String name = owners.parameterName(parameter);
            if (ref.has("name") && name == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "The parameter's name cannot be read here.");
            String ownerName = owners.ownerName(found);
            JsonObject ownerRef = owner.equals(ParameterRefs.GRID_MODULE)
                    ? Refs.gridModule(deviceRef, ownerId.toString(), ownerName)
                    : Refs.modulator(deviceRef, ownerId.toString(), ownerName);
            require(ref, Refs.parameter(ownerRef, id, name));
            RequestSnapshot snapshot = new RequestSnapshot()
                    .with(() -> owners.owner(captured, ownerId, check))
                    .with(() -> owners.parameter(found, id, check));
            return new NativeParameter(captured, found, parameter, snapshot, natives, check);
        }
    }

    /** A bound modulator or Grid module parameter: the native device, owner and parameter objects. */
    static final class NativeParameter {
        final DeviceSources.Native.Capture device;
        final Object owner, parameter;
        private final RequestSnapshot snapshot;
        private final DeviceSources.Native natives;
        private final Runnable check;

        NativeParameter(
                DeviceSources.Native.Capture device,
                Object owner,
                Object parameter,
                RequestSnapshot snapshot,
                DeviceSources.Native natives,
                Runnable check) {
            this.device = device;
            this.owner = owner;
            this.parameter = parameter;
            this.snapshot = snapshot;
            this.natives = natives;
            this.check = check;
        }

        /** On the document thread: the same device, owner and parameter as at bind; else the target changed. */
        void require(HostOperation operation) {
            try {
                natives.guard(device, check);
            } catch (RpcException error) {
                throw changed(error, operation);
            }
            snapshot.require(operation);
        }
    }

    /** A track's volume or pan. */
    public final class TrackParameter extends Resolved {
        public final int slot;
        public final String id;

        TrackParameter(int slot, String id) {
            this.slot = slot;
            this.id = id;
        }

        @Override
        public RequestSnapshot snapshot() {
            return new RequestSnapshot()
                    .with(() -> window.at(slot).channelId().get())
                    .with(() -> window.at(slot).position().get());
        }
    }
}
