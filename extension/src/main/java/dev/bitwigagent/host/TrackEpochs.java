package dev.bitwigagent.host;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import dev.bitwigagent.core.HostLimits;
import java.util.concurrent.atomic.AtomicLongArray;

/** Per bank slot, bumped by controller observers; document-thread checks compare these instead of calling getters. */
public final class TrackEpochs {
    private final AtomicLongArray epochs = new AtomicLongArray(HostLimits.TRACKS);

    public TrackEpochs(TrackWindow window) {
        for (int i = 0; i < HostLimits.TRACKS; i++) {
            final int index = i;
            Track track = window.at(i);
            track.channelId().addValueObserver((StringValueChangedCallback) v -> epochs.incrementAndGet(index));
            track.exists().addValueObserver((BooleanValueChangedCallback) v -> epochs.incrementAndGet(index));
        }
    }

    public long get(int index) {
        return epochs.get(index);
    }
}
