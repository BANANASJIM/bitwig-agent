package dev.bitwigagent.launcher;

import static dev.bitwigagent.TestJson.obj;
import static org.junit.jupiter.api.Assertions.*;

import com.bitwig.extension.controller.api.NoteOccurrence;
import com.google.gson.JsonObject;
import dev.bitwigagent.launcher.NoteFeatures.Expression;
import dev.bitwigagent.launcher.NoteFeatures.Recurrence;
import dev.bitwigagent.launcher.NoteFeatures.Repeat;
import org.junit.jupiter.api.Test;

/** The note record's operators and expressions: canonical values, the patch rule, packing and conversions. */
final class NoteFeaturesTest {
    private static final String ALL = "{\"chance\":0.85,\"velocitySpread\":0.2,\"releaseVelocity\":0,"
            + "\"repeat\":{\"count\":-3,\"curve\":0.3,\"velocityEnd\":-0.4,\"velocityCurve\":1.0},"
            + "\"recurrence\":{\"length\":4,\"mask\":[false,false,false,true]},\"occurrence\":\"not_prev_key\","
            + "\"muted\":true,\"timbre\":-1.0,\"pressure\":0.6,\"pan\":0.5,\"gain\":0.0,\"transpose\":12.5}";

    private static NoteFeatures patch(NoteFeatures base, String json) {
        return NoteFeatures.Patch.of(obj(json)).apply(base);
    }

    private static JsonObject record(NoteFeatures f) {
        JsonObject out = new JsonObject();
        f.addTo(out);
        return out;
    }

    @Test
    void aPlainNoteListsNoFeatureAndEveryFeatureListsAsWritten() {
        assertEquals(new JsonObject(), record(NoteFeatures.NONE));
        NoteFeatures all = patch(NoteFeatures.NONE, ALL);
        assertEquals(obj(ALL), record(all), "floats list as their shortest decimal");
        assertEquals(all, patch(NoteFeatures.NONE, record(all).toString()), "a read written back is equal");
        assertEquals(
                all.hashCode(), patch(NoteFeatures.NONE, record(all).toString()).hashCode());
        assertTrue(all.hasExpression());
        assertFalse(NoteFeatures.NONE.hasExpression());
    }

    @Test
    void omittedFieldsKeepNullTurnsOffAndAValueTurnsOn() {
        NoteFeatures all = patch(NoteFeatures.NONE, ALL);
        assertSame(all, NoteFeatures.Patch.of(obj("{\"beat\":0,\"pitch\":1}")).apply(all), "nothing given");
        NoteFeatures chance = patch(all, "{\"chance\":0.5}");
        assertEquals(Float.valueOf(0.5f), chance.chance);
        assertEquals(all.repeat, chance.repeat, "an omitted field keeps its value");
        JsonObject nulls = new JsonObject();
        for (String key : NoteFeatures.KEYS) nulls.add(key, com.google.gson.JsonNull.INSTANCE);
        assertEquals(NoteFeatures.NONE, patch(all, nulls.toString()), "null turns everything off or neutral");
        assertEquals(NoteFeatures.NONE, patch(NoteFeatures.NONE, "{\"chance\":null,\"timbre\":null}"));
    }

    @Test
    void aRepeatObjectMergesIntoAnEnabledRepeatAndNeedsACountToTurnOn() {
        NoteFeatures on = patch(NoteFeatures.NONE, "{\"repeat\":{\"count\":3}}");
        assertEquals(new Repeat(3, 0, 0, 0), on.repeat, "the other members start at 0");
        assertEquals(new Repeat(3, 0.5, 0, 0), patch(on, "{\"repeat\":{\"curve\":0.5}}").repeat);
        NoteFeatures.Patch curveOnly = NoteFeatures.Patch.of(obj("{\"repeat\":{\"curve\":0.5}}"));
        assertNull(curveOnly.problem(on));
        assertEquals(
                "repeat is off on this note: give repeat.count to turn it on", curveOnly.problem(NoteFeatures.NONE));
        assertNull(NoteFeatures.Patch.of(obj("{\"repeat\":null}")).problem(NoteFeatures.NONE));
        assertNull(NoteFeatures.Patch.NONE.problem(NoteFeatures.NONE));
        assertNotEquals(new Repeat(3, 0, 0, 0), new Repeat(3, 0, 0, 0.1));
        assertNotEquals(new Repeat(3, 0, 0, 0), "repeat");
    }

    @Test
    void recurrencePacksLengthMinusOneAndTheMaskAboveBitThree() {
        Recurrence fourth = new Recurrence(4, 0b1000);
        assertEquals(3 | 0b1000 << 3, fourth.pack(), "pass i is mask bit i (assumed order, native check)");
        assertEquals(fourth, Recurrence.unpack(fourth.pack()));
        assertEquals(new Recurrence(8, 0xff), Recurrence.unpack(7 | 0xff << 3));
        assertEquals(new Recurrence(2, 0b01), new Recurrence(2, 0b101), "bits past the length play no pass");
        assertEquals(new Recurrence(2, 0b01), Recurrence.unpack(1 | 0b101 << 3));
        assertTrue(fourth.plays(3));
        assertFalse(fourth.plays(0));
        assertEquals(
                fourth,
                patch(NoteFeatures.NONE, "{\"recurrence\":{\"length\":4,\"mask\":[false,false,false,true]}}")
                        .recurrence);
        assertNotEquals(fourth, new Recurrence(3, 0b100));
        assertNotEquals(fourth, "recurrence");
        assertEquals(fourth.hashCode(), Recurrence.unpack(fourth.pack()).hashCode());
    }

    @Test
    void aRecurrenceMaskNeedsOneEntryPerPass() {
        assertEquals(
                "recurrence.mask has 1 entries for length 4: give one per pass",
                NoteFeatures.Patch.of(obj("{\"recurrence\":{\"length\":4,\"mask\":[true]}}"))
                        .shape());
        assertNull(NoteFeatures.Patch.of(obj("{\"recurrence\":{\"length\":1,\"mask\":[true]}}"))
                .shape());
        assertNull(NoteFeatures.Patch.of(obj("{\"recurrence\":null}")).shape());
        assertNull(NoteFeatures.Patch.NONE.shape());
        assertFalse(NoteFeatures.named(obj("{\"beat\":0,\"pitch\":60}")));
        assertTrue(NoteFeatures.named(obj("{\"beat\":0,\"pitch\":60,\"muted\":null}")));
        String message = assertThrows(
                        dev.bitwigagent.core.RpcException.class,
                        () -> NotePlan.rules(
                                NotePlan.listed(NoteModuleTest.array(obj("{\"beat\":0,\"pitch\":60,"
                                        + "\"recurrence\":{\"length\":3,\"mask\":[true,false]}}"))),
                                null,
                                null))
                .getMessage();
        assertTrue(message.contains("notes[0] recurrence.mask has 2 entries for length 3: give one per pass"), message);
    }

    @Test
    void occurrenceUsesTheApiNamesInLowerCase() {
        for (NoteOccurrence o : NoteOccurrence.values()) {
            NoteFeatures f = patch(NoteFeatures.NONE, "{\"occurrence\":\"" + NoteFeatures.wire(o) + "\"}");
            if (o == NoteOccurrence.ALWAYS) {
                assertEquals(NoteFeatures.NONE, f, "always is the neutral condition: off");
                continue;
            }
            assertEquals(o, f.occurrence);
            assertEquals(NoteFeatures.wire(o), record(f).get("occurrence").getAsString());
        }
        assertEquals(11, NoteOccurrence.values().length);
        assertEquals("not_prev_channel", NoteFeatures.wire(NoteOccurrence.NOT_PREV_CHANNEL));
    }

    @Test
    void expressionsConvertToTheStoredZeroToOneAndBack() {
        assertEquals(0.75, Expression.TIMBRE.toStored(0.5), 1e-12, "(v + 1) / 2");
        assertEquals(0.25, Expression.PAN.toStored(-0.5), 1e-12);
        assertEquals((12 + 96) / 192.0, Expression.TRANSPOSE.toStored(12), 1e-12, "(v + 96) / 192");
        assertEquals(0.6, Expression.PRESSURE.toStored(0.6), 1e-12, "unipolar: as is");
        assertEquals(0.3, Expression.GAIN.toStored(0.3), 1e-12);
        // A curve point reads in natural units (6.1.7): the record's units, gain as twice the stored value.
        assertEquals(-0.5, Expression.PAN.fromPoint(-0.5), 1e-12, "pan -0.5 reads -0.5, not -2");
        assertEquals(12, Expression.TRANSPOSE.fromPoint(12), 1e-12, "transpose 12 reads 12, not 2208");
        assertEquals(0.75, Expression.GAIN.fromPoint(1.5), 1e-12, "gain stored 0.75 reads 1.5 (+10.6 dB)");
        assertEquals(0.5f, Expression.GAIN.neutral, "stored 0.5 reads 1.0 = 0 dB");
        for (Expression e : Expression.values())
            for (double v = e.min; v <= e.max; v += (e.max - e.min) / 7) {
                NoteFeatures written =
                        new NoteFeatures.Builder().expression(e, v).build();
                double point = e == Expression.GAIN ? 2 * e.toStored(written.expression(e)) : written.expression(e);
                NoteFeatures read = new NoteFeatures.Builder()
                        .expression(e, e.fromPoint(point))
                        .build();
                assertEquals(written, read, e.wire + " " + v + " round-trips through Bitwig's setter and point");
            }
    }

    @Test
    void anOperatorAtItsNeutralValueIsOffWhateverItsSwitchSays() {
        NoteFeatures.Builder fresh = new NoteFeatures.Builder();
        fresh.chance = 1.0;
        fresh.repeat = new Repeat(0, 0.3, 0, 0);
        fresh.recurrence = Recurrence.ALWAYS;
        fresh.occurrence = NoteOccurrence.ALWAYS;
        assertEquals(NoteFeatures.NONE, fresh.build(), "Bitwig's new note: switches on, neutral values");
        assertEquals(new JsonObject(), record(fresh.build()));
        assertEquals(NoteFeatures.NONE, patch(NoteFeatures.NONE, "{\"chance\":1,\"repeat\":{\"count\":0}}"));
        assertNotEquals(
                NoteFeatures.NONE, patch(NoteFeatures.NONE, "{\"recurrence\":{\"length\":1,\"mask\":[false]}}"));
        assertNotEquals(
                NoteFeatures.NONE, patch(NoteFeatures.NONE, "{\"recurrence\":{\"length\":2,\"mask\":[true,true]}}"));
    }

    @Test
    void floatsCompareAsFloat32AndMinusZeroIsZero() {
        NoteFeatures a =
                new NoteFeatures.Builder().expression(Expression.PAN, -0.0).build();
        assertEquals(NoteFeatures.NONE, a);
        assertEquals(NoteFeatures.NONE.hashCode(), a.hashCode());
        NoteFeatures.Builder b = new NoteFeatures.Builder();
        b.chance = 0.85;
        NoteFeatures c = new NoteFeatures.Builder().build().toBuilder().build();
        assertEquals(NoteFeatures.NONE, c);
        NoteFeatures.Builder close = new NoteFeatures.Builder();
        close.chance = 0.85 + 1e-12;
        assertEquals(b.build(), close.build(), "equal as float32");
        assertNotEquals(b.build(), NoteFeatures.NONE);
        assertNotEquals(NoteFeatures.NONE, "features");
    }

    @Test
    void releaseVelocityIsAMidiStepAndTheNoteRecordCarriesTheFeatures() {
        assertEquals(0, Note.releaseMidi(0));
        assertEquals(100, Note.releaseMidi(100 / 127.0));
        assertEquals(1, Note.midi(0), "a note-on velocity is at least 1");
        Note plain = Note.canonical(0, 60, 1, 0.5, 90);
        Note chance = plain.with(patch(NoteFeatures.NONE, "{\"chance\":0.5}"));
        assertNotEquals(plain, chance);
        assertFalse(Note.KIND.same(plain, chance), "a feature change is an update");
        assertEquals(Note.KIND.key(plain), Note.KIND.key(chance));
        assertEquals(obj("{\"beat\":1.0,\"pitch\":60,\"velocity\":90,\"duration\":0.5,\"chance\":0.5}"), chance.json());
        assertEquals(chance, Note.read(0, 60, 1, 0.5, 90 / 127.0, chance.features));
    }
}
