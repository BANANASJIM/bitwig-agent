package dev.bitwigagent.routing;

import dev.bitwigagent.core.NativeProvenance;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.host.DeviceSources;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The native routing choosers in domain records: what a track's output/input chooser or a device's sidechain
 * component holds, what it offers, and an atomic edit whose plan the domain decides ({@link RoutingModel}). The
 * adapter resolves entries to host objects, keeps Bitwig's own accept check (a refused target comes back as
 * {@link Edit#rejected}, nothing written) and reads every chooser back by native identity.
 */
public interface RoutingPort {
    void execute(Runnable work);

    NativeProvenance provenance();

    TrackRouting readTrack(TrackRef self, List<TrackRef> bank, boolean choices, Runnable check);

    /**
     * Read the track's choosers, ask {@code plan} for the change, and when it is not empty check Bitwig's accept
     * check, run {@code beforeWrite}, write every target in one project transaction and read the choosers back.
     */
    Edit<TrackRouting> editTrack(
            TrackRef self,
            List<TrackRef> bank,
            Function<TrackRouting, Change> plan,
            Runnable check,
            Runnable beforeWrite);

    Sidechains readSidechains(DeviceSources.Source device, List<TrackRef> bank, boolean choices, Runnable check);

    /** As {@link #editTrack} for the component at {@code index}; {@code plan} receives null when there is none. */
    Edit<Component> editSidechain(
            DeviceSources.Source device,
            List<TrackRef> bank,
            int index,
            Function<Component, Change> plan,
            Runnable check,
            Runnable beforeWrite);

    /** One observed bank track: public UUID, name, type and its proxy (the native side re-resolves and re-checks it). */
    final class TrackRef {
        public final String id, name, type;
        public final Object proxy;

        public TrackRef(String id, String name, String type, Object proxy) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.proxy = proxy;
        }
    }

    /** An entry a chooser offers, its display name and whether Bitwig's own check accepts it. */
    final class Choice {
        public final RoutingEntry entry;
        public final String name;
        public final boolean accepted;

        public Choice(RoutingEntry entry, String name, boolean accepted) {
            this.entry = entry;
            this.name = name;
            this.accepted = accepted;
        }
    }

    /** A chooser's current entry and display name (null for none), its native source groups and, on request, choices. */
    final class Chooser {
        public final RoutingEntry current;
        public final String currentName;
        public final List<String> groups;
        public final List<Choice> choices;

        public Chooser(RoutingEntry current, String currentName, List<String> groups, List<Choice> choices) {
            this.current = current;
            this.currentName = currentName;
            this.groups = groups;
            this.choices = choices;
        }
    }

    /** Why the hardware inputs were not listed (null each when they were, or when choices were not asked for). */
    final class Hardware {
        public final String audio, notes;

        public Hardware(String audio, String notes) {
            this.audio = audio;
            this.notes = notes;
        }
    }

    /** A bank track the native side could not resolve: reported, never guessed. */
    final class Unresolved {
        public final TrackRef track;
        public final Reason reason;
        public final String message;

        public Unresolved(TrackRef track, Reason reason, String message) {
            this.track = track;
            this.reason = reason;
            this.message = message;
        }
    }

    /** One track's choosers: input is null with inputUnavailable where the track has no input chooser. */
    final class TrackRouting {
        public final String trackType, nativeKind, nativeRole, inputUnavailable;
        public final Chooser output, input;
        public final List<Unresolved> unresolved;
        public final Hardware hardware;

        public TrackRouting(
                String trackType,
                String nativeKind,
                String nativeRole,
                Chooser output,
                Chooser input,
                String inputUnavailable,
                List<Unresolved> unresolved,
                Hardware hardware) {
            this.trackType = trackType;
            this.nativeKind = nativeKind;
            this.nativeRole = nativeRole;
            this.output = output;
            this.input = input;
            this.inputUnavailable = inputUnavailable;
            this.unresolved = unresolved;
            this.hardware = hardware;
        }
    }

    /**
     * One sidechain component of a device: its host-side identity, the modulator or Grid module that owns it
     * (ownerId), its native source groups, and its chooser (null when it has no source selector).
     */
    final class Component {
        public final int index;
        public final String id, label, kind, host, ownerId;
        public final List<String> groups;
        public final Chooser chooser;

        public Component(
                int index,
                String id,
                String label,
                String kind,
                String host,
                String ownerId,
                List<String> groups,
                Chooser chooser) {
            this.index = index;
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.host = host;
            this.ownerId = ownerId;
            this.groups = groups;
            this.chooser = chooser;
        }
    }

    final class Sidechains {
        public final List<Component> components;
        public final List<Unresolved> unresolved;

        public Sidechains(List<Component> components, List<Unresolved> unresolved) {
            this.components = components;
            this.unresolved = unresolved;
        }
    }

    /** The entries to write, by field (output, input or source), in write order; empty writes nothing. */
    final class Change {
        public final Map<String, RoutingEntry> targets;

        public Change(Map<String, RoutingEntry> targets) {
            this.targets = Collections.unmodifiableMap(new LinkedHashMap<>(targets));
        }

        public boolean isEmpty() {
            return targets.isEmpty();
        }
    }

    /**
     * What an edit saw and left: before, the change the plan chose, and after (the readback, or before when nothing
     * was written). rejected names the field whose target Bitwig's accept check refused; nothing was written then.
     */
    final class Edit<T> {
        public final T before, after;
        public final Change change;
        public final String rejected;

        public Edit(T before, Change change, T after, String rejected) {
            this.before = before;
            this.change = change;
            this.after = after;
            this.rejected = rejected;
        }
    }
}
