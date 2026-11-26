package dev.bitwigagent;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.Refs;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Resolves a parameter ref to the host object behind it, one branch per owner kind. */
final class ParameterResolver {
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

    private final DeviceSources devices;
    private final TrackRefs tracks;
    private final TrackWindow window;
    private final DirectParameters direct;
    private final RemotePages remotes;

    ParameterResolver(
            DeviceSources devices, TrackRefs tracks, TrackWindow window, DirectParameters direct, RemotePages remotes) {
        this.devices = devices;
        this.tracks = tracks;
        this.window = window;
        this.direct = direct;
        this.remotes = remotes;
    }

    /** The parameter a ref names, refused as stale when it is gone or another parameter is there now. */
    Resolved resolve(JsonObject ref) {
        JsonObject owner = ref.getAsJsonObject("owner");
        String id = ref.get("id").getAsString();
        switch (ParameterRefs.owner(ref)) {
            case ParameterRefs.DEVICE:
                return direct(ref, devices.locate(owner), id);
            case ParameterRefs.REMOTE_PAGE:
                return remote(ref, devices.locate(owner.getAsJsonObject("device")));
            case ParameterRefs.TRACK:
                int slot = tracks.locate(owner);
                require(ref, Refs.parameter(tracks.ref(slot), id, null));
                return new TrackParameter(slot, id);
            default:
                return new ModuleParameter(ref, devices.locate(owner.getAsJsonObject("device")));
        }
    }

    private Direct direct(JsonObject ref, DeviceSources.Source device, String id) {
        if (!device.directReady())
            throw Errors.notApplied(Reason.NOT_READY, "The device's direct parameters are not observed yet.");
        Set<String> ids = direct.ids(device);
        if (ids == null) throw Errors.notApplied(Reason.NOT_READY, "The device's parameter ids are not observed yet.");
        if (!ids.contains(id))
            throw Errors.notApplied(
                    Reason.TARGET_NOT_FOUND, "The device has no such parameter now; read its parameters again.");
        require(ref, Refs.parameter(device.ref(), id, direct.name(device, id)));
        return new Direct(device, id);
    }

    private Remote remote(JsonObject ref, DeviceSources.Source device) {
        JsonObject owner = ref.getAsJsonObject("owner");
        int page = owner.get("index").getAsInt(), slot = ParameterRefs.control(ref);
        JsonObject current = page(device, page);
        if (!Refs.matches(owner, current, false))
            throw Errors.targetChanged(
                    "Another Remote Controls page is at this index than the one read.",
                    Refs.parameter(current, ref.get("id").getAsString(), null));
        return new Remote(ref, device, page, slot);
    }

    private JsonObject page(DeviceSources.Source device, int page) {
        String[] pages = remotes.pages(device);
        if (pages == null) throw Errors.notApplied(Reason.NOT_READY, "Remote Controls pages are not observed yet.");
        if (page >= pages.length)
            throw Errors.notApplied(
                    Reason.TARGET_NOT_FOUND, "The device has no such Remote Controls page now; read its pages again.");
        return Refs.remotePage(device.ref(), page, pages[page]);
    }

    private static void require(JsonObject ref, JsonObject current) {
        if (!Refs.matches(ref, current, true))
            throw Errors.targetChanged("Another parameter is at this location than the one read.", current);
    }

    /** One resolved parameter; its snapshot is taken right before dispatch. */
    abstract static class Resolved {
        abstract RequestSnapshot snapshot();
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
        RequestSnapshot snapshot() {
            return RequestSnapshot.of(device).with(() -> {
                Set<String> ids = direct.ids(device);
                return ids == null ? null : new LinkedHashSet<>(ids);
            });
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
                throw Errors.notApplied(Reason.TARGET_NOT_FOUND, "The page has no control in this slot now.");
            if (control.exists == null || !control.ready || control.name == null)
                throw Errors.notApplied(
                        Reason.NOT_READY, "The Remote Control is unobserved, being mapped or awaiting a fresh value.");
            require(ref, Refs.parameter(page(device, page), ref.get("id").getAsString(), control.name));
        }

        /** Also the remote generation, taken after the page switch settled so the switch itself does not count. */
        @Override
        RequestSnapshot snapshot() {
            return RequestSnapshot.of(device).with(() -> remotes.generation(device));
        }
    }

    /** A modulator or Grid module parameter: its device here, the module and parameter checked natively. */
    static final class ModuleParameter extends Resolved {
        final String owner, id;
        final UUID ownerId;
        final DeviceSources.Source device;

        ModuleParameter(JsonObject ref, DeviceSources.Source device) {
            this.owner = ParameterRefs.owner(ref);
            this.ownerId = ParameterRefs.ownerId(ref);
            this.id = ref.get("id").getAsString();
            this.device = device;
        }

        @Override
        RequestSnapshot snapshot() {
            return RequestSnapshot.of(device);
        }
    }

    /** A track's volume or pan. */
    final class TrackParameter extends Resolved {
        final int slot;
        final String id;

        TrackParameter(int slot, String id) {
            this.slot = slot;
            this.id = id;
        }

        @Override
        RequestSnapshot snapshot() {
            return new RequestSnapshot()
                    .with(() -> window.at(slot).channelId().get())
                    .with(() -> window.at(slot).position().get());
        }
    }
}
