package dev.bitwigagent.launcher;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bitwigagent.core.RangeEdit;
import dev.bitwigagent.core.RpcException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Scale;
import net.jqwik.api.statistics.Statistics;

/**
 * The convergence properties of the range rule (high-frequency representation design §4.1): a read written back
 * changes nothing, a replay changes nothing, nothing outside the range changes, an upsert removes nothing, the range
 * becomes the listed notes, and canonical() is idempotent. On the pure functions and on the fake native port.
 */
final class RangeEditPropertyTest {
    private static final long TICKS = Note.TICKS;

    /** A small grid of onsets (1/4 beat, plus off-grid ticks) keeps collisions between notes likely. */
    @Provide
    Arbitrary<Note> notes() {
        Arbitrary<Long> beat = Combinators.combine(Arbitraries.longs().between(0, 31), Arbitraries.of(0L, 0L, 0L, 3L))
                .as((quarter, offset) -> quarter * TICKS / 4 + offset);
        return Combinators.combine(
                        Arbitraries.integers().between(0, 2),
                        Arbitraries.integers().between(60, 63),
                        beat,
                        Arbitraries.longs().between(1, 3 * TICKS),
                        Arbitraries.integers().between(1, 127))
                .as(Note::ofTicks);
    }

    /** Notes with distinct identities, as a clip holds them and a request lists them. */
    @Provide
    Arbitrary<List<Note>> clip() {
        return notes().list().ofMaxSize(24).map(RangeEditPropertyTest::distinct);
    }

    /** A range ({@code null}: none, i.e. an upsert) over part of the clip. */
    @Provide
    Arbitrary<NoteRange> range() {
        Arbitrary<NoteRange> some = Combinators.combine(
                        Arbitraries.longs().between(0, 7),
                        Arbitraries.longs().between(1, 8),
                        Arbitraries.integers().between(60, 63),
                        Arbitraries.integers().between(0, 3))
                .as((start, span, low, width) ->
                        new NoteRange(start * TICKS, (start + span) * TICKS, low, Math.min(127, low + width)));
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(2, Arbitraries.just((NoteRange) null)),
                net.jqwik.api.Tuple.of(1, Arbitraries.just(NoteRange.ALL)),
                net.jqwik.api.Tuple.of(4, some));
    }

    private static List<Note> distinct(List<Note> notes) {
        Map<Long, Note> byKey = new LinkedHashMap<>();
        for (Note n : notes) byKey.putIfAbsent(n.key(), n);
        return new ArrayList<>(byKey.values());
    }

    private static List<Note> inside(List<Note> listed, NoteRange range) {
        return range == null ? listed : listed.stream().filter(range).collect(Collectors.toList());
    }

    private static Set<Note> set(List<Note> notes) {
        return new HashSet<>(notes);
    }

    @Property
    void aReadWrittenBackOverTheWholeClipChangesNothing(@ForAll("clip") List<Note> observed) {
        RangeEdit.Change<Note> change = RangeEdit.plan(observed, observed, NoteRange.ALL, Note.KIND);
        assertTrue(change.isEmpty());
        assertEquals(observed.size(), change.unchanged);
    }

    @Property
    void aReplayChangesNothing(
            @ForAll("clip") List<Note> observed, @ForAll("clip") List<Note> request, @ForAll("range") NoteRange range) {
        List<Note> listed = inside(request, range);
        List<Note> after = RangeEdit.apply(observed, RangeEdit.plan(observed, listed, range, Note.KIND));
        assertTrue(RangeEdit.plan(after, listed, range, Note.KIND).isEmpty());
    }

    @Property
    void nothingOutsideTheRangeChangesAndAnUpsertRemovesNothing(
            @ForAll("clip") List<Note> observed, @ForAll("clip") List<Note> request, @ForAll("range") NoteRange range) {
        List<Note> listed = inside(request, range);
        RangeEdit.Change<Note> change = RangeEdit.plan(observed, listed, range, Note.KIND);
        if (range == null) assertTrue(change.remove.isEmpty(), "an upsert removes nothing");
        else {
            for (Note n : change.remove) assertTrue(range.test(n), "removed inside the range: " + n);
            for (Note n : change.written()) assertTrue(range.test(n), "written inside the range: " + n);
        }
        List<Note> after = RangeEdit.apply(observed, change);
        Set<Long> keys = after.stream().map(Note::key).collect(Collectors.toSet());
        for (Note n : observed) {
            boolean outside = range != null && !range.test(n);
            if (outside
                    || range == null && !listed.stream().anyMatch(l -> l.key().equals(n.key())))
                assertTrue(after.contains(n), "kept unchanged: " + n);
            if (range == null) assertTrue(keys.contains(n.key()), "an upsert keeps every note: " + n);
        }
    }

    @Property
    void theRangeBecomesExactlyTheListedNotes(
            @ForAll("clip") List<Note> observed, @ForAll("clip") List<Note> request, @ForAll("range") NoteRange range) {
        if (range == null) return;
        List<Note> listed = inside(request, range);
        List<Note> after = RangeEdit.apply(observed, RangeEdit.plan(observed, listed, range, Note.KIND));
        assertEquals(set(listed), set(inside(after, range)));
    }

    @Property
    void canonicalIsIdempotent(
            @ForAll @IntRange(min = 0, max = 15) int channel,
            @ForAll @IntRange(min = 0, max = 127) int pitch,
            @ForAll @DoubleRange(min = 0, max = 4194304) @Scale(9) double beat,
            @ForAll @DoubleRange(min = 0.00000095367431640625, max = 4096) @Scale(20) double duration,
            @ForAll @IntRange(min = 1, max = 127) int velocity) {
        Note once = Note.canonical(channel, pitch, beat, duration, velocity);
        assertEquals(once, Note.canonical(channel, pitch, once.beatValue(), once.durationValue(), once.velocity));
        // What the native write stores: the public setter's float factor 1/127 times MIDI velocity.
        float stored = once.velocity * 0.007874016f;
        assertEquals(once, Note.read(channel, pitch, once.beatValue(), once.durationValue(), stored));
    }

    /** Through notes.get and notes.set on the fake native port: whatever a clip holds reads back unchanged. */
    @Property(tries = 40)
    void getAllThenSetAllIsAlreadyMatchedOnTheFakePort(@ForAll("clip") List<Note> clip) {
        LauncherFake f = LauncherFake.withNativeNotes();
        NoteModuleTest.clipAt(f, 0);
        f.slots[0].length = 16;
        for (Note n : withoutOverlaps(clip)) f.put(0, n);
        JsonObject read = f.call("notes.get", NoteModuleTest.target(f, 0));
        JsonObject p = NoteModuleTest.target(f, 0);
        p.addProperty("range", "all");
        p.add("notes", read.getAsJsonArray("notes"));
        JsonObject echo = f.call("notes.set", p);
        assertEquals("already_matched", echo.get("outcome").getAsString(), echo.toString());
        assertEquals(read.get("count"), echo.getAsJsonObject("summary").get("unchanged"));
        assertEquals(0, f.nativeNotes.transactions);
    }

    /** The plan through the fake port moves only the range: notes outside it are the same before and after. */
    @Property(tries = 40)
    void aRangeWriteOnTheFakePortLeavesTheRestOfTheClip(
            @ForAll("clip") List<Note> clip, @ForAll("clip") List<Note> request) {
        LauncherFake f = LauncherFake.withNativeNotes();
        NoteModuleTest.clipAt(f, 0);
        f.slots[0].length = 16;
        for (Note n : withoutOverlaps(clip)) f.put(0, n);
        NoteRange range = new NoteRange(2 * TICKS, 4 * TICKS, 0, 127);
        List<Note> before = f.clipNotes(0);
        JsonObject p = NoteModuleTest.target(f, 0);
        p.add("range", NoteModuleTest.range(2, 4));
        JsonArray listed = new JsonArray();
        for (Note n : withoutOverlaps(inside(request, range)))
            listed.add(Note.ofTicks(n.channel, n.pitch, n.beat, Math.min(n.duration, range.end - n.beat), n.velocity)
                    .json());
        p.add("notes", listed);
        dev.bitwigagent.core.HostOperation op = f.startAndDrain("notes.set", p);
        boolean refused = op.result.isCompletedExceptionally(); // a clip rule refused it: nothing was written
        Statistics.label("refused").collect(refused);
        Statistics.label("refused").coverage(c -> c.check(false).percentage(share -> share >= 50));
        if (refused) {
            assertEquals(set(before), set(f.clipNotes(0)));
            return;
        }
        List<Note> outside = inside(before, n -> !range.test(n));
        assertEquals(set(outside), set(inside(f.clipNotes(0), n -> !range.test(n))));
    }

    /** Listed notes without velocity or duration update only the given field, remove nothing, and need a note. */
    @Property
    void partialNotesUpdateOnlyTheGivenFieldAndNeedAnExistingNote(
            @ForAll("clip") List<Note> clip, @ForAll @IntRange(min = 1, max = 127) int velocity) {
        List<Note> observed = withoutOverlaps(clip);
        JsonArray patches = new JsonArray();
        for (int i = 0; i < observed.size(); i += 2) {
            Note n = observed.get(i);
            JsonObject patch = new JsonObject();
            patch.addProperty("beat", n.beatValue());
            patch.addProperty("pitch", n.pitch);
            patch.addProperty("channel", n.channel);
            patch.addProperty("velocity", velocity);
            patches.add(patch);
        }
        RangeEdit.Change<Note> change =
                new NotePlan(NotePlan.listed(patches), null, true).plan(observed, Long.MAX_VALUE);
        assertTrue(change.remove.isEmpty() && change.insert.isEmpty());
        assertEquals(patches.size(), change.update.size() + change.unchanged);
        for (RangeEdit.Update<Note> u : change.update) {
            assertEquals(u.before.duration, u.after.duration, "an omitted duration is kept");
            assertEquals(velocity, u.after.velocity);
        }
        JsonObject fresh = new JsonObject();
        fresh.addProperty("beat", 100);
        fresh.addProperty("pitch", 60);
        fresh.addProperty("velocity", velocity);
        patches.add(fresh);
        RpcException error = assertThrows(
                RpcException.class,
                () -> new NotePlan(NotePlan.listed(patches), null, true).plan(observed, Long.MAX_VALUE));
        assertTrue(error.getMessage().contains("is a new note"), error.getMessage());
    }

    private static List<Note> inside(List<Note> notes, java.util.function.Predicate<Note> keep) {
        return notes.stream().filter(keep).collect(Collectors.toList());
    }

    /** The first notes of each channel and pitch that do not run into the next, as a real clip holds them. */
    private static List<Note> withoutOverlaps(List<Note> notes) {
        List<Note> sorted = new ArrayList<>(notes);
        sorted.sort(Note.ORDER);
        Map<Integer, Long> ends = new java.util.HashMap<>();
        List<Note> out = new ArrayList<>();
        for (Note n : sorted) {
            int row = n.channel * 128 + n.pitch;
            if (ends.getOrDefault(row, -1L) > n.beat) continue;
            out.add(n);
            ends.put(row, n.end());
        }
        return out;
    }
}
