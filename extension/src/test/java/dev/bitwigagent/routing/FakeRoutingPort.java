package dev.bitwigagent.routing;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.NativeProvenance;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.host.DeviceSources;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * An in-memory routing model behind the port: each bank track's output and input entry, one sidechain component,
 * the listed hardware inputs and Bitwig's accept check. Edits run the domain's own plan. Each behaviour has a native
 * E2E step (the PR's fake-to-E2E table): a refused target writes nothing (self-cycle write), an unlisted hardware id is
 * target_not_found (stale id), a write is read back (identity readback), a failed readback stays unknown.
 */
public final class FakeRoutingPort implements RoutingPort {
    public static final RoutingEntry BUS =
            RoutingEntry.hardware("hardware_audio_input", "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0", "Stereo 1");
    public static final RoutingEntry KEYS =
            RoutingEntry.hardware("hardware_note_input", "a1b2c3d4-e5f6-4789-abcd-ef0123456789/0/0", "Keys");

    final Deque<Runnable> tasks = new ArrayDeque<>();
    final Map<String, RoutingEntry> outputs = new HashMap<>(), inputs = new HashMap<>();
    final List<RoutingEntry> hardware = new ArrayList<>(List.of(BUS, KEYS));
    int calls, reads, writes;
    boolean unknown, failAfterWrite, lastChoices;
    /** The field whose target Bitwig's accept check refuses. */
    String reject;

    TrackRef lastSelf;
    List<TrackRef> lastBank;
    Object lastDevice;
    RoutingEntry source = RoutingEntry.none();
    public boolean component;
    /** Run native work at once instead of queueing it for the test to drain. */
    public boolean inline;

    @Override
    public void execute(Runnable work) {
        if (inline) work.run();
        else tasks.add(work);
    }

    @Override
    public NativeProvenance provenance() {
        return new NativeProvenance("6.1.7", "fake");
    }

    private static List<String> groups(String type) {
        return type.equals("Audio")
                ? List.of("HARDWARE_AUDIO_SOURCES", "TRACK_AUDIO_SOURCES")
                : List.of("HARDWARE_NOTE_SOURCES", "TRACK_NOTE_SOURCES");
    }

    private TrackRouting routing(TrackRef self, List<TrackRef> bank, boolean choices) {
        List<Choice> offered = new ArrayList<>(List.of(new Choice(RoutingEntry.none(), null, true)));
        if (choices) {
            for (TrackRef track : bank)
                if (!track.id.equals(self.id))
                    offered.add(new Choice(RoutingEntry.track("post_fader", track.id), "Post", true));
            for (RoutingEntry entry : hardware) offered.add(new Choice(entry, entry.name, true));
        }
        RoutingEntry output = outputs.getOrDefault(self.id, RoutingEntry.none());
        RoutingEntry input = inputs.getOrDefault(self.id, RoutingEntry.none());
        boolean inputChooser = !self.type.equals("Group");
        return new TrackRouting(
                self.type,
                self.type.toUpperCase(),
                "track",
                new Chooser(output, output.kind.equals("none") ? null : "Out", List.of(), List.of()),
                inputChooser ? new Chooser(input, input.name, groups(self.type), offered) : null,
                inputChooser ? null : "no_input_chooser_for_track_type_Group",
                List.of(),
                new Hardware(null, null));
    }

    @Override
    public TrackRouting readTrack(TrackRef self, List<TrackRef> bank, boolean choices, Runnable check) {
        calls++;
        reads++;
        check.run();
        lastSelf = self;
        lastBank = bank;
        lastChoices = choices;
        return routing(self, bank, choices);
    }

    private void listed(RoutingEntry target) {
        if (RoutingEntry.HARDWARE_KINDS.contains(target.kind)
                && hardware.stream().noneMatch(target::matches))
            throw Errors.notApplied(Reason.TARGET_NOT_FOUND, "No hardware input has this id now.");
    }

    @Override
    public Edit<TrackRouting> editTrack(
            TrackRef self,
            List<TrackRef> bank,
            Function<TrackRouting, Change> plan,
            Runnable check,
            Runnable beforeWrite) {
        calls++;
        check.run();
        lastSelf = self;
        lastBank = bank;
        TrackRouting before = routing(self, bank, false);
        Change change = plan.apply(before);
        if (change.isEmpty()) return new Edit<>(before, change, before, null);
        for (Map.Entry<String, RoutingEntry> target : change.targets.entrySet()) {
            listed(target.getValue());
            if (target.getKey().equals(reject)) return new Edit<>(before, change, before, reject);
        }
        written(beforeWrite);
        if (!unknown)
            for (Map.Entry<String, RoutingEntry> target : change.targets.entrySet())
                (target.getKey().equals("output") ? outputs : inputs).put(self.id, target.getValue());
        return new Edit<>(before, change, routing(self, bank, false), null);
    }

    private void written(Runnable beforeWrite) {
        beforeWrite.run();
        writes++;
        if (failAfterWrite) throw Errors.unknown(Reason.NATIVE_UNAVAILABLE, "simulated native failure");
    }

    private Component part(boolean choices) {
        if (!component) return null;
        List<Choice> offered = new ArrayList<>(List.of(new Choice(RoutingEntry.none(), null, true)));
        return new Component(
                0,
                "SIDECHAIN",
                "Sidechain",
                "audio",
                "device",
                null,
                List.of("TRACK_AUDIO_SOURCES"),
                new Chooser(source, null, List.of("TRACK_AUDIO_SOURCES"), choices ? offered : List.of()));
    }

    @Override
    public Sidechains readSidechains(
            DeviceSources.Source device, List<TrackRef> bank, boolean choices, Runnable check) {
        calls++;
        reads++;
        check.run();
        lastDevice = device;
        Component c = part(choices);
        return new Sidechains(c == null ? List.of() : List.of(c), List.of());
    }

    @Override
    public Edit<Component> editSidechain(
            DeviceSources.Source device,
            List<TrackRef> bank,
            int index,
            Function<Component, Change> plan,
            Runnable check,
            Runnable beforeWrite) {
        calls++;
        check.run();
        lastDevice = device;
        Component before = index == 0 ? part(false) : null;
        Change change = plan.apply(before);
        if (change.isEmpty()) return new Edit<>(before, change, before, null);
        if ("source".equals(reject)) return new Edit<>(before, change, before, reject);
        written(beforeWrite);
        if (!unknown) source = change.targets.get("source");
        return new Edit<>(before, change, part(false), null);
    }
}
