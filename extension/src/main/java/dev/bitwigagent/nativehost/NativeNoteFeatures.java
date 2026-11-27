package dev.bitwigagent.nativehost;

import com.bitwig.extension.controller.api.NoteOccurrence;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.launcher.Note;
import dev.bitwigagent.launcher.NoteFeatures;
import dev.bitwigagent.launcher.NoteFeatures.Expression;
import java.lang.reflect.Method;
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
 * transaction.
 */
final class NativeNoteFeatures {
    private final NativeHost.Access a;
    private final Class<?> occurrenceType, curve, point;
    private final Method chance, setChance, chanceOn, setChanceOn, spread, setSpread, release, setRelease;
    private final Method repeatOn, setRepeatOn, count, setCount, curveOf, setCurve, end, setEnd, endCurve, setEndCurve;
    private final Method recurrenceOn, setRecurrenceOn, recurrence, setRecurrence;
    private final Method occurrenceOn, setOccurrenceOn, occurrence, setOccurrence, muted, setMuted;
    private final Method expressions, lane, points, value, setExpression;
    private final Map<Expression, Object> kinds = new EnumMap<>(Expression.class);
    private final Class<?> clipContent;

    @SuppressWarnings({"unchecked", "rawtypes"})
    NativeNoteFeatures(NativeHost.Access a) throws ReflectiveOperationException {
        this.a = a;
        Class<?> note = a.type("note");
        occurrenceType = a.type("note.occurrence");
        Class<?> kind = a.type("note.expression");
        curve = a.type("note.expressionCurve");
        point = a.type("note.expressionPoint");
        clipContent = a.type("note.clipContent");
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
        curveOf = getter("note.repeatCurve", double.class);
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
        expressions = getter("note.expressions", a.type("note.expressions"));
        lane = a.exactMethod("note.expressions.lane", a.type("note.expressionLane"), false, kind, boolean.class);
        points = a.exactMethod("note.expressionCurve.points", List.class, false);
        value = a.exactMethod("note.expressionPoint.value", double.class, false);
        setExpression = a.exactMethod(
                "note.clipContent.setExpression",
                void.class,
                false,
                a.type("note.expressionOwner"),
                kind,
                double.class,
                double.class,
                a.type("note.editorSelection"));
        if (!a.type("note.expressionOwner").isAssignableFrom(note))
            throw new NoSuchMethodException("The native note is not an expression owner.");
        // Kinds by constant name: Bitwig's enum keeps its names (PRESSURE, TIMBRE, ...) in both supported hosts.
        for (Expression e : Expression.values()) kinds.put(e, Enum.valueOf((Class) kind, e.name()));
        for (NoteOccurrence o : NoteOccurrence.values()) Enum.valueOf((Class) occurrenceType, o.name());
    }

    private Method getter(String key, Class<?> returns) throws ReflectiveOperationException {
        return a.exactMethod(key, returns, false);
    }

    private Method setter(String key, Class<?> argument) throws ReflectiveOperationException {
        return a.exactMethod(key, void.class, false, argument);
    }

    /** The note's operators and expressions; an operator that is off reads as off whatever value it keeps. */
    NoteFeatures read(Object note) {
        NoteFeatures.Builder b = new NoteFeatures.Builder();
        if (on(chanceOn, note)) b.chance = number(chance, note);
        b.velocitySpread = number(spread, note);
        b.releaseVelocity = Note.releaseMidi(number(release, note));
        if (on(repeatOn, note))
            b.repeat = new NoteFeatures.Repeat(
                    (Integer) a.invoke(count, note), number(curveOf, note), number(end, note), number(endCurve, note));
        if (on(recurrenceOn, note)) b.recurrence = NoteFeatures.Recurrence.unpack((Integer) a.invoke(recurrence, note));
        if (on(occurrenceOn, note))
            b.occurrence = NoteOccurrence.valueOf(((Enum<?>) a.invoke(occurrence, note)).name());
        b.muted = on(muted, note);
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
            b.expression(e, e.fromStored(number(value, first)));
        }
        return b.build();
    }

    /** Refuses, before anything is written, expressions for a clip without the clip-level expression setter. */
    void requireExpressions(Object clip, Iterable<Note> notes) {
        if (clipContent.isInstance(clip)) return;
        for (Note n : notes)
            if (n.features.hasExpression())
                throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "The clip does not take note expressions.");
    }

    /**
     * Writes the features that differ from a new note's ({@link NoteFeatures#NONE}) onto a note just added to
     * {@code clip} ({@link #requireExpressions} first); the readback shows when Bitwig's defaults for a new note
     * differ from that.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void writeNew(Object clip, Object note, NoteFeatures f) {
        if (f.equals(NoteFeatures.NONE)) return;
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
        if (!f.hasExpression()) return;
        for (Expression e : Expression.values())
            if (f.expression(e) != e.neutral)
                a.invoke(setExpression, clip, note, kinds.get(e), 0.0, e.toStored(f.expression(e)), null);
    }

    private boolean on(Method getter, Object note) {
        return (Boolean) a.invoke(getter, note);
    }

    private double number(Method getter, Object target) {
        return (Double) a.invoke(getter, target);
    }
}
