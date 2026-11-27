package dev.bitwigagent.nativehost;

import com.bitwig.extension.controller.api.NoteOccurrence;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.LazyBinding;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.launcher.Note;
import dev.bitwigagent.launcher.NoteFeatures;
import dev.bitwigagent.launcher.NoteFeatures.Expression;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Exact bindings for a native note's operators and expressions, the calls the public {@code NoteStep} setters end in
 * ({@code ClipProxy.updateStep*} makes one native setter call each, so the names come from Bitwig's own code; offline
 * RE of 6.1.4 and 6.1.7). Operators are getter and setter pairs on the note ({@code note}); the API's units are kept,
 * except velocities (MIDI steps here, 0..1 there). An expression is a curve of 0..1 points per kind: it is read as
 * the first point of {@code note.expressions.lane(kind, false)} (no curve: neutral) and written with the clip's
 * {@code note.clipContent.setExpression(note, kind, 0, value, null)}, which sets the point at time 0 or, when there is
 * none, adds one there; later points stay (the editor selection argument is unused). Writes run inside the caller's
 * transaction. The two groups bind lazily and separately (keys added after a host entry shipped): a group that does
 * not bind is unread and refused, and plain notes stay native.
 */
final class NativeNoteFeatures {
    private final LazyBinding<Operators> operators;
    private final LazyBinding<Expressions> expressions;

    NativeNoteFeatures(NativeHost.Access a) {
        operators = new LazyBinding<>(() -> new Operators(a));
        expressions = new LazyBinding<>(() -> new Expressions(a));
    }

    /** The record keys of the groups that do not bind on this host. */
    List<String> unread() {
        List<String> out = new ArrayList<>();
        if (operators.unavailable() != null) out.addAll(NoteFeatures.OPERATOR_KEYS);
        if (expressions.unavailable() != null) out.addAll(NoteFeatures.EXPRESSION_KEYS);
        return out;
    }

    /** The note's operators and expressions; a group that does not bind reads as default (listed by unread()). */
    NoteFeatures read(Object note) {
        NoteFeatures.Builder b = new NoteFeatures.Builder();
        if (operators.unavailable() == null) operators.get().read(note, b);
        if (expressions.unavailable() == null) expressions.get().read(note, b);
        return b.build();
    }

    /** Refuses, before anything is written, expressions for a clip without the clip-level expression setter. */
    void requireExpressions(Object clip, Iterable<Note> notes) {
        for (Note n : notes)
            if (n.features.hasExpression()) {
                expressions.get().requireClip(clip);
                return;
            }
    }

    /**
     * Writes the features that differ from a new note's ({@link NoteFeatures#NONE}) onto a note just added to
     * {@code clip} ({@link #requireExpressions} first); the readback shows when Bitwig's defaults for a new note
     * differ from that.
     */
    void writeNew(Object clip, Object note, NoteFeatures f) {
        if (f.hasOperators()) operators.get().write(note, f);
        if (f.hasExpression()) expressions.get().write(clip, note, f);
    }

    private static boolean on(NativeHost.Access a, Method getter, Object note) {
        return (Boolean) a.invoke(getter, note);
    }

    private static double number(NativeHost.Access a, Method getter, Object target) {
        return (Double) a.invoke(getter, target);
    }

    /** Chance, velocity spread, release velocity, repeats, recurrence, occurrence and mute. */
    private static final class Operators {
        private final NativeHost.Access a;
        private final Class<?> occurrenceType;
        private final Method chance, setChance, chanceOn, setChanceOn, spread, setSpread, release, setRelease;
        private final Method repeatOn,
                setRepeatOn,
                count,
                setCount,
                curve,
                setCurve,
                end,
                setEnd,
                endCurve,
                setEndCurve;
        private final Method recurrenceOn, setRecurrenceOn, recurrence, setRecurrence;
        private final Method occurrenceOn, setOccurrenceOn, occurrence, setOccurrence, muted, setMuted;

        @SuppressWarnings({"unchecked", "rawtypes"})
        Operators(NativeHost.Access a) throws ReflectiveOperationException {
            this.a = a;
            occurrenceType = a.type("note.occurrence");
            chance = getter("note.chance", double.class);
            setChance = setter("note.setChance", double.class);
            chanceOn = getter("note.chanceEnabled", boolean.class);
            setChanceOn = setter("note.setChanceEnabled", boolean.class);
            spread = getter("note.velocitySpread", double.class);
            setSpread = setter("note.setVelocitySpread", double.class);
            release = getter("note.releaseVelocity", double.class);
            setRelease = setter("note.setReleaseVelocity", double.class);
            repeatOn = getter("note.repeatEnabled", boolean.class);
            setRepeatOn = setter("note.setRepeatEnabled", boolean.class);
            count = getter("note.repeatCount", int.class);
            setCount = setter("note.setRepeatCount", int.class);
            curve = getter("note.repeatCurve", double.class);
            setCurve = setter("note.setRepeatCurve", double.class);
            end = getter("note.repeatVelocityEnd", double.class);
            setEnd = setter("note.setRepeatVelocityEnd", double.class);
            endCurve = getter("note.repeatVelocityCurve", double.class);
            setEndCurve = setter("note.setRepeatVelocityCurve", double.class);
            recurrenceOn = getter("note.recurrenceEnabled", boolean.class);
            setRecurrenceOn = setter("note.setRecurrenceEnabled", boolean.class);
            recurrence = getter("note.recurrence", int.class);
            setRecurrence = setter("note.setRecurrence", int.class);
            occurrenceOn = getter("note.occurrenceEnabled", boolean.class);
            setOccurrenceOn = setter("note.setOccurrenceEnabled", boolean.class);
            occurrence = getter("note.occurrence", occurrenceType);
            setOccurrence = setter("note.setOccurrence", occurrenceType);
            muted = getter("note.muted", boolean.class);
            setMuted = setter("note.setMuted", boolean.class);
            // Occurrences by constant name: Bitwig's enum keeps the API's names in both supported hosts.
            for (NoteOccurrence o : NoteOccurrence.values()) Enum.valueOf((Class) occurrenceType, o.name());
        }

        private Method getter(String key, Class<?> returns) throws ReflectiveOperationException {
            return a.exactMethod(key, returns, false);
        }

        private Method setter(String key, Class<?> argument) throws ReflectiveOperationException {
            return a.exactMethod(key, void.class, false, argument);
        }

        /** An operator that is off reads as off whatever value it keeps. */
        void read(Object note, NoteFeatures.Builder b) {
            if (on(a, chanceOn, note)) b.chance = number(a, chance, note);
            b.velocitySpread = number(a, spread, note);
            b.releaseVelocity = Note.releaseMidi(number(a, release, note));
            if (on(a, repeatOn, note))
                b.repeat = new NoteFeatures.Repeat(
                        (Integer) a.invoke(count, note),
                        number(a, curve, note),
                        number(a, end, note),
                        number(a, endCurve, note));
            if (on(a, recurrenceOn, note))
                b.recurrence = NoteFeatures.Recurrence.unpack((Integer) a.invoke(recurrence, note));
            if (on(a, occurrenceOn, note))
                b.occurrence = NoteOccurrence.valueOf(((Enum<?>) a.invoke(occurrence, note)).name());
            b.muted = on(a, muted, note);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void write(Object note, NoteFeatures f) {
            if (f.chance != null) {
                a.invoke(setChance, note, (double) f.chance);
                a.invoke(setChanceOn, note, true);
            }
            if (f.velocitySpread != 0) a.invoke(setSpread, note, (double) f.velocitySpread);
            if (f.releaseVelocity != NoteFeatures.DEFAULT_RELEASE_VELOCITY)
                a.invoke(setRelease, note, f.releaseVelocity / 127.0);
            if (f.repeat != null) {
                a.invoke(setCount, note, f.repeat.count);
                a.invoke(setCurve, note, (double) f.repeat.curve);
                a.invoke(setEnd, note, (double) f.repeat.velocityEnd);
                a.invoke(setEndCurve, note, (double) f.repeat.velocityCurve);
                a.invoke(setRepeatOn, note, true);
            }
            if (f.recurrence != null) {
                a.invoke(setRecurrence, note, f.recurrence.pack());
                a.invoke(setRecurrenceOn, note, true);
            }
            if (f.occurrence != null) {
                a.invoke(setOccurrence, note, Enum.valueOf((Class) occurrenceType, f.occurrence.name()));
                a.invoke(setOccurrenceOn, note, true);
            }
            if (f.muted) a.invoke(setMuted, note, true);
        }
    }

    /** Timbre, pressure, pan, gain and transpose: the start value of each curve. */
    private static final class Expressions {
        private final NativeHost.Access a;
        private final Class<?> curve, point, clipContent;
        private final Method expressions, lane, points, value, setExpression;
        private final Map<Expression, Object> kinds = new EnumMap<>(Expression.class);

        @SuppressWarnings({"unchecked", "rawtypes"})
        Expressions(NativeHost.Access a) throws ReflectiveOperationException {
            this.a = a;
            Class<?> kind = a.type("note.expression"), owner = a.type("note.expressionOwner");
            curve = a.type("note.expressionCurve");
            point = a.type("note.expressionPoint");
            clipContent = a.type("note.clipContent");
            expressions = a.exactMethod("note.expressions", a.type("note.expressions"), false);
            lane = a.exactMethod("note.expressions.lane", a.type("note.expressionLane"), false, kind, boolean.class);
            points = a.exactMethod("note.expressionCurve.points", List.class, false);
            value = a.exactMethod("note.expressionPoint.value", double.class, false);
            setExpression = a.exactMethod(
                    "note.clipContent.setExpression",
                    void.class,
                    false,
                    owner,
                    kind,
                    double.class,
                    double.class,
                    a.type("note.editorSelection"));
            if (!owner.isAssignableFrom(a.type("note")))
                throw new NoSuchMethodException("The native note is not an expression owner.");
            // Kinds by constant name: Bitwig's enum keeps its names (PRESSURE, TIMBRE, ...) in both supported hosts.
            for (Expression e : Expression.values()) kinds.put(e, Enum.valueOf((Class) kind, e.name()));
        }

        void read(Object note, NoteFeatures.Builder b) {
            Object set = a.invoke(expressions, note);
            for (Expression e : Expression.values()) {
                Object curveOfKind = a.invoke(lane, set, kinds.get(e), false);
                if (curveOfKind == null) continue;
                if (!curve.isInstance(curveOfKind))
                    throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "A note's " + e.wire + " is not a point curve.");
                List<?> list = (List<?>) a.invoke(points, curveOfKind);
                if (list.isEmpty()) continue;
                Object first = list.get(0);
                if (!point.isInstance(first))
                    throw Errors.notApplied(
                            Reason.UNSUPPORTED_SCOPE, "A note's " + e.wire + " point is not a value point.");
                b.expression(e, e.fromPoint(number(a, value, first)));
            }
        }

        void requireClip(Object clip) {
            if (!clipContent.isInstance(clip))
                throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "The clip does not take note expressions.");
        }

        void write(Object clip, Object note, NoteFeatures f) {
            for (Expression e : Expression.values())
                if (f.expression(e) != e.neutral)
                    a.invoke(setExpression, clip, note, kinds.get(e), 0.0, e.toStored(f.expression(e)), null);
        }
    }
}
