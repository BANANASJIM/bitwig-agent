package dev.bitwigagent.host;

import static dev.bitwigagent.core.HostLimits.*;

import com.bitwig.extension.callback.IntegerValueChangedCallback;
import com.bitwig.extension.controller.api.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;

/** The fixed track window with its device banks and parent-track proxies, and slot lookup by track id. */
public final class TrackWindow {
    private final TrackBank bank;
    private final DeviceBank[] devices = new DeviceBank[TRACKS];
    private final Track[] parents = new Track[TRACKS];
    private SceneBank scenes;
    private int scrollTarget = -1;
    private volatile boolean observed, scenesObserved;

    public TrackWindow(ControllerHost host) {
        bank = host.createTrackBank(TRACKS, SENDS, SCENES, true);
        bank.scrollPosition().markInterested();
        bank.itemCount().addValueObserver((IntegerValueChangedCallback) count -> observed = true);
        for (int t = 0; t < TRACKS; t++) {
            Track track = (Track) bank.getItemAt(t);
            track.exists().markInterested();
            track.channelId().markInterested();
            track.name().markInterested();
            track.position().markInterested();
            track.trackType().markInterested();
            track.isGroup().markInterested();
            track.isGroupExpanded().markInterested();
            parents[t] = track.createParentTrack(0, 0);
            parents[t].exists().markInterested();
            parents[t].channelId().markInterested();
            devices[t] = track.createDeviceBank(DEVICES);
            devices[t].scrollPosition().markInterested();
            devices[t].itemCount().markInterested();
            for (int d = 0; d < DEVICES; d++) {
                Device device = (Device) devices[t].getItemAt(d);
                device.exists().markInterested();
                device.name().markInterested();
                device.position().markInterested();
                device.deviceType().markInterested();
            }
        }
    }

    public TrackBank bank() {
        return bank;
    }

    public Track at(int slot) {
        return (Track) bank.getItemAt(slot);
    }

    public DeviceBank devices(int slot) {
        return devices[slot];
    }

    /** Public createParentTrack proxy of a bank slot, for TrackTree. */
    public Track parent(int slot) {
        return parents[slot];
    }

    /** Marks what an addressed launcher slot is compared against (name, content, scene index) so its getters are valid. */
    public void markSlotIdentityInterested(Track track) {
        ClipLauncherSlotBank slots = track.clipLauncherSlotBank();
        slots.itemCount().markInterested();
        for (int j = 0; j < SCENES; j++) {
            ClipLauncherSlot slot = (ClipLauncherSlot) slots.getItemAt(j);
            slot.name().markInterested();
            slot.hasContent().markInterested();
            slot.sceneIndex().markInterested();
        }
    }

    public int offset() {
        return bank.scrollPosition().get();
    }

    /** Registers the scene bank's observers; the scene window lease does it once while the extension initialises. */
    public void observeScenes() {
        scenes = bank.sceneBank();
        scenes.scrollPosition().markInterested();
        scenes.itemCount().addValueObserver((IntegerValueChangedCallback) count -> scenesObserved = true);
        for (int j = 0; j < SCENES; j++) {
            Scene scene = (Scene) scenes.getItemAt(j);
            scene.exists().markInterested();
            scene.name().markInterested();
            scene.sceneIndex().markInterested();
        }
    }

    public Scene scene(int position) {
        return (Scene) scenes.getItemAt(position);
    }

    public boolean scenesObserved() {
        return scenesObserved;
    }

    public int sceneCount() {
        return scenes.itemCount().get();
    }

    public int sceneOffset() {
        return scenes.scrollPosition().get();
    }

    /** Where the window was last asked to scroll; the observed position may still lag behind it. */
    public int scrollTarget() {
        return scrollTarget >= 0 ? scrollTarget : sceneOffset();
    }

    public void scrollScenes(int offset) {
        scrollTarget = offset;
        scenes.scrollPosition().set(offset);
    }

    /** The first scene of the window that shows scene {@code index}, kept inside the scene list. */
    public int sceneWindowFor(int index) {
        return Math.min(index - index % SCENES, Math.max(0, sceneCount() - SCENES));
    }

    /** Whether the scroll position, the scene proxies and every existing track's slot proxies show {@code offset}. */
    public boolean scenesShown(int offset) {
        if (sceneOffset() != offset) return false;
        int visible = Math.min(SCENES, sceneCount() - offset);
        for (int j = 0; j < visible; j++) if (scene(j).sceneIndex().get() != offset + j) return false;
        if (visible <= 0) return true;
        for (int t = 0; t < TRACKS; t++) {
            if (!at(t).exists().get()) continue;
            ClipLauncherSlotBank slots = at(t).clipLauncherSlotBank();
            if (slotScene(slots, 0) != offset || slotScene(slots, visible - 1) != offset + visible - 1) return false;
        }
        return true;
    }

    private static int slotScene(ClipLauncherSlotBank slots, int position) {
        return ((ClipLauncherSlot) slots.getItemAt(position)).sceneIndex().get();
    }

    public int total() {
        return bank.itemCount().get();
    }

    public boolean observed() {
        return observed;
    }

    Track track(String trackId) {
        return at(trackSlot(trackId));
    }

    public int trackSlot(String trackId) {
        if (!observed) throw Errors.notApplied(Reason.NOT_READY, "Track bank has not reported its size yet.");
        for (int i = 0; i < TRACKS; i++) {
            if (trackMatches(trackId, at(i))) return i;
        }
        throw Errors.notApplied(
                Reason.OUTSIDE_WINDOW, "Track UUID is absent from the current " + TRACKS + "-track window.");
    }

    public boolean trackMatches(String trackId, Track track) {
        return track.exists().get() && trackId.equals(track.channelId().get());
    }
}
