package dev.bitwigagent.launcher;

import com.bitwig.extension.controller.api.NoteOccurrence;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The operators and expressions of one note, canonical: floats are kept as float32 (the comparison the design uses for
 * them), velocities as MIDI steps, a recurrence mask only up to its length. An operator that is off is null whatever
 * value Bitwig keeps behind its switch, so two notes that play alike compare equal. Values at their default and
 * operators that are off are left out of the record, so a plain note keeps its four fields.
 */
public final class NoteFeatures {
    /** Bitwig's release velocity of a new note: 100/127 ({@code note.create} with no release velocity). */
    public static final int DEFAULT_RELEASE_VELOCITY = 100;

    /** The record's feature keys, in the order notes.get lists them. */
    static final List<String> KEYS = List.of(
            "chance",
            "velocitySpread",
            "releaseVelocity",
            "repeat",
            "recurrence",
            "occurrence",
            "muted",
            "timbre",
            "pressure",
            "pan",
            "gain",
            "transpose",
            "formant");

    /** Whether a note record names any operator or expression, a value or null. */
    public static boolean named(JsonObject note) {
        for (String key : KEYS) if (note.has(key)) return true;
        return false;
    }

    /**
     * A note expression: the value at the note's start, in the units of the public API setters. Bitwig stores each as
     * a curve of 0..1 points; the stored value is {@code (value - min) / (max - min)} (the API's conversion for timbre,
     * pan and transpose, the identity for pressure and gain). Formant has no API setter: its range and conversion are
     * assumed like timbre's until a native check confirms them.
     */
    public enum Expression {
        TIMBRE("timbre", -1, 1, 0),
        PRESSURE("pressure", 0, 1, 0),
        PAN("pan", -1, 1, 0),
        GAIN("gain", 0, 1, 0.5),
        TRANSPOSE("transpose", -96, 96, 0),
        FORMANT("formant", -1, 1, 0);

        public final String wire;
        final double min, max;
        /** The value of a note without a curve for this expression. */
        public final float neutral;

        Expression(String wire, double min, double max, double neutral) {
            this.wire = wire;
            this.min = min;
            this.max = max;
            this.neutral = (float) neutral;
        }

        /** The 0..1 point value Bitwig stores for {@code value}. */
        public double toStored(double value) {
            return (value - min) / (max - min);
        }

        public double fromStored(double stored) {
            return min + stored * (max - min);
        }
    }

    /** Repeats: count (negative counts are Bitwig's rate-based repeats), curve, velocity end and velocity curve. */
    public static final class Repeat {
        public final int count;
        public final float curve, velocityEnd, velocityCurve;

        public Repeat(int count, double curve, double velocityEnd, double velocityCurve) {
            this.count = count;
            this.curve = f32(curve);
            this.velocityEnd = f32(velocityEnd);
            this.velocityCurve = f32(velocityCurve);
        }

        JsonObject json() {
            JsonObject out = new JsonObject();
            out.addProperty("count", count);
            out.add("curve", number(curve));
            out.add("velocityEnd", number(velocityEnd));
            out.add("velocityCurve", number(velocityCurve));
            return out;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Repeat)) return false;
            Repeat r = (Repeat) other;
            return count == r.count
                    && curve == r.curve
                    && velocityEnd == r.velocityEnd
                    && velocityCurve == r.velocityCurve;
        }

        @Override
        public int hashCode() {
            return Objects.hash(count, curve, velocityEnd, velocityCurve);
        }
    }

    /**
     * Recurrence: a cycle of {@code length} (1..8) loop passes and which of them play. Bitwig keeps both in one int,
     * {@code (length - 1) | (mask << 3)}, with an 8-bit mask. Assumed and not yet checked natively: mask bit i (least
     * significant first) is pass i + 1 of the cycle. Only {@link #pack} and {@link #unpack} depend on that order.
     */
    public static final class Recurrence {
        public final int length, mask;

        /** {@code mask} bits at or above {@code length} are dropped: they play no pass. */
        public Recurrence(int length, int mask) {
            this.length = length;
            this.mask = mask & ((1 << length) - 1);
        }

        public static Recurrence unpack(int packed) {
            return new Recurrence((packed & 7) + 1, (packed >> 3) & 0xff);
        }

        public int pack() {
            return (length - 1) | (mask << 3);
        }

        public boolean plays(int pass) {
            return (mask >> pass & 1) != 0;
        }

        static Recurrence of(JsonObject json) {
            int length = json.get("length").getAsInt(), mask = 0;
            JsonArray passes = json.getAsJsonArray("mask");
            for (int i = 0; i < Math.min(length, passes.size()); i++)
                if (passes.get(i).getAsBoolean()) mask |= 1 << i;
            return new Recurrence(length, mask);
        }

        JsonObject json() {
            JsonObject out = new JsonObject();
            out.addProperty("length", length);
            JsonArray passes = new JsonArray();
            for (int i = 0; i < length; i++) passes.add(plays(i));
            out.add("mask", passes);
            return out;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Recurrence
                    && length == ((Recurrence) other).length
                    && mask == ((Recurrence) other).mask;
        }

        @Override
        public int hashCode() {
            return Objects.hash(length, mask);
        }
    }

    /** The values a reader collects; {@link #build} makes them canonical. */
    public static final class Builder {
        public Double chance;
        public double velocitySpread;
        public int releaseVelocity = DEFAULT_RELEASE_VELOCITY;
        public Repeat repeat;
        public Recurrence recurrence;
        public NoteOccurrence occurrence;
        public boolean muted;
        private final double[] expression = new double[Expression.values().length];

        public Builder() {
            for (Expression e : Expression.values()) expression[e.ordinal()] = e.neutral;
        }

        public Builder expression(Expression e, double value) {
            expression[e.ordinal()] = value;
            return this;
        }

        public NoteFeatures build() {
            return new NoteFeatures(this);
        }
    }

    public static final NoteFeatures NONE = new Builder().build();

    public final Float chance;
    public final float velocitySpread;
    public final int releaseVelocity;
    public final Repeat repeat;
    public final Recurrence recurrence;
    public final NoteOccurrence occurrence;
    public final boolean muted;
    private final float[] expression;

    private NoteFeatures(Builder b) {
        chance = b.chance == null ? null : f32(b.chance);
        velocitySpread = f32(b.velocitySpread);
        releaseVelocity = b.releaseVelocity;
        repeat = b.repeat;
        recurrence = b.recurrence;
        occurrence = b.occurrence;
        muted = b.muted;
        expression = new float[b.expression.length];
        for (int i = 0; i < expression.length; i++) expression[i] = f32(b.expression[i]);
    }

    /** The float32 a value is compared as; -0 becomes 0, so equal values hash alike. */
    static float f32(double value) {
        return (float) value + 0.0f;
    }

    public float expression(Expression e) {
        return expression[e.ordinal()];
    }

    public boolean hasExpression() {
        for (Expression e : Expression.values()) if (expression(e) != e.neutral) return true;
        return false;
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.chance = chance == null ? null : (double) chance;
        b.velocitySpread = velocitySpread;
        b.releaseVelocity = releaseVelocity;
        b.repeat = repeat;
        b.recurrence = recurrence;
        b.occurrence = occurrence;
        b.muted = muted;
        for (Expression e : Expression.values()) b.expression(e, expression(e));
        return b;
    }

    /** The occurrence condition as the record names it: the API constant in lower case ("not_prev_key"). */
    static String wire(NoteOccurrence occurrence) {
        return occurrence.name().toLowerCase(Locale.ROOT);
    }

    /** Adds every value that is not its default to a note's record. */
    void addTo(JsonObject out) {
        if (chance != null) out.add("chance", number(chance));
        if (velocitySpread != 0) out.add("velocitySpread", number(velocitySpread));
        if (releaseVelocity != DEFAULT_RELEASE_VELOCITY) out.addProperty("releaseVelocity", releaseVelocity);
        if (repeat != null) out.add("repeat", repeat.json());
        if (recurrence != null) out.add("recurrence", recurrence.json());
        if (occurrence != null) out.addProperty("occurrence", wire(occurrence));
        if (muted) out.addProperty("muted", true);
        for (Expression e : Expression.values()) if (expression(e) != e.neutral) out.add(e.wire, number(expression(e)));
    }

    /** A float32 as its shortest decimal, so 0.85 reads back as 0.85 and writes back as the same float. */
    private static JsonPrimitive number(float value) {
        return new JsonPrimitive(new BigDecimal(Float.toString(value)));
    }

    /**
     * The feature fields of one listed note: an omitted field keeps the note's value, null turns an operator off (an
     * expression back to neutral), a value sets it. A repeat object's omitted members keep an enabled repeat's values.
     */
    static final class Patch {
        static final Patch NONE = new Patch(new JsonObject());

        private final JsonObject fields;

        private Patch(JsonObject fields) {
            this.fields = fields;
        }

        static Patch of(JsonObject note) {
            if (!named(note)) return NONE;
            JsonObject fields = new JsonObject();
            for (String key : KEYS) if (note.has(key)) fields.add(key, note.get(key));
            return new Patch(fields);
        }

        /** Why the recurrence it names is malformed on its own (a mask entry per pass); null when it is not. */
        String shape() {
            JsonElement r = fields.get("recurrence");
            if (r == null || r.isJsonNull()) return null;
            int length = r.getAsJsonObject().get("length").getAsInt(),
                    entries = r.getAsJsonObject().getAsJsonArray("mask").size();
            return entries == length
                    ? null
                    : "recurrence.mask has " + entries + " entries for length " + length + ": give one per pass";
        }

        /** Whether it sets an expression, which only the native path reads back. */
        boolean setsExpression() {
            for (Expression e : Expression.values()) if (fields.has(e.wire)) return true;
            return false;
        }

        /** Why the patch cannot apply to {@code base}; null when it can. */
        String problem(NoteFeatures base) {
            JsonElement repeat = fields.get("repeat");
            if (repeat != null
                    && !repeat.isJsonNull()
                    && base.repeat == null
                    && !repeat.getAsJsonObject().has("count"))
                return "repeat is off on this note: give repeat.count to turn it on";
            return null;
        }

        NoteFeatures apply(NoteFeatures base) {
            if (fields.size() == 0) return base;
            Builder b = base.toBuilder();
            JsonElement v;
            if ((v = fields.get("chance")) != null) b.chance = v.isJsonNull() ? null : v.getAsDouble();
            if ((v = fields.get("velocitySpread")) != null) b.velocitySpread = v.isJsonNull() ? 0 : v.getAsDouble();
            if ((v = fields.get("releaseVelocity")) != null)
                b.releaseVelocity = v.isJsonNull() ? DEFAULT_RELEASE_VELOCITY : v.getAsInt();
            if ((v = fields.get("repeat")) != null) b.repeat = v.isJsonNull() ? null : repeat(base.repeat, v);
            if ((v = fields.get("recurrence")) != null)
                b.recurrence = v.isJsonNull() ? null : Recurrence.of(v.getAsJsonObject());
            if ((v = fields.get("occurrence")) != null)
                b.occurrence = v.isJsonNull()
                        ? null
                        : NoteOccurrence.valueOf(v.getAsString().toUpperCase(Locale.ROOT));
            if ((v = fields.get("muted")) != null) b.muted = !v.isJsonNull() && v.getAsBoolean();
            for (Expression e : Expression.values())
                if ((v = fields.get(e.wire)) != null) b.expression(e, v.isJsonNull() ? e.neutral : v.getAsDouble());
            return b.build();
        }

        /** An enabled repeat with the given members over the note's (zero for a repeat that was off). */
        private static Repeat repeat(Repeat base, JsonElement value) {
            JsonObject r = value.getAsJsonObject();
            Repeat from = base != null ? base : new Repeat(0, 0, 0, 0);
            return new Repeat(
                    r.has("count") ? r.get("count").getAsInt() : from.count,
                    r.has("curve") ? r.get("curve").getAsDouble() : from.curve,
                    r.has("velocityEnd") ? r.get("velocityEnd").getAsDouble() : from.velocityEnd,
                    r.has("velocityCurve") ? r.get("velocityCurve").getAsDouble() : from.velocityCurve);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof NoteFeatures)) return false;
        NoteFeatures f = (NoteFeatures) other;
        return Objects.equals(chance, f.chance)
                && velocitySpread == f.velocitySpread
                && releaseVelocity == f.releaseVelocity
                && Objects.equals(repeat, f.repeat)
                && Objects.equals(recurrence, f.recurrence)
                && occurrence == f.occurrence
                && muted == f.muted
                && Arrays.equals(expression, f.expression);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                chance,
                velocitySpread,
                releaseVelocity,
                repeat,
                recurrence,
                occurrence,
                muted,
                Arrays.hashCode(expression));
    }
}
