package dev.bitwigagent;

import static dev.bitwigagent.HostLimits.*;

import com.bitwig.extension.callback.IntegerValueChangedCallback;
import com.bitwig.extension.controller.api.*;

/** The fixed track window with its device banks and parent-track proxies, and slot lookup by track id. */
final class TrackWindow {
    private final TrackBank bank;
    private final DeviceBank[] devices = new DeviceBank[TRACKS];
    private final Track[] parents = new Track[TRACKS];
    private volatile boolean observed;

    TrackWindow(ControllerHost host) {
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

    TrackBank bank() {
        return bank;
    }

    Track at(int slot) {
        return (Track) bank.getItemAt(slot);
    }

    DeviceBank devices(int slot) {
        return devices[slot];
    }

    /** Public createParentTrack proxy of a bank slot, for TrackTree. */
    Track parent(int slot) {
        return parents[slot];
    }

    int offset() {
        return bank.scrollPosition().get();
    }

    int total() {
        return bank.itemCount().get();
    }

    boolean observed() {
        return observed;
    }

    Track track(String trackId) {
        return at(trackSlot(trackId));
    }

    int trackSlot(String trackId) {
        if (!observed) throw Errors.notApplied(ErrorCode.NOT_READY, "Track bank has not reported its size yet.");
        for (int i = 0; i < TRACKS; i++) {
            if (trackMatches(trackId, at(i))) return i;
        }
        throw Errors.notApplied(
                ErrorCode.OUTSIDE_WINDOW, "Track UUID is absent from the current " + TRACKS + "-track window.");
    }

    boolean trackMatches(String trackId, Track track) {
        return track.exists().get() && trackId.equals(track.channelId().get());
    }
}
