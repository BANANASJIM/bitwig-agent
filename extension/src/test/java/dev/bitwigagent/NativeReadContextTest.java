package dev.bitwigagent;

import static dev.bitwigagent.NativeReadContext.Field.*;
import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import org.junit.jupiter.api.Test;

/** Host-free common read policy tests; no fake Grid graph or native compatibility claim. */
public final class NativeReadContextTest {

    @Test
    void readObservationsAreNotWriteGates() {
        Fixture f = new Fixture();
        f.values.put(INITIALIZED, false);
        Object foreignRoot = new Object(), foreignDefaultContext = new Object();
        f.values.put(ROOT, foreignRoot);
        f.values.put(CURRENT_DT, null);
        f.override(f.defaultDt, DT_CONTEXT, foreignDefaultContext);
        NativeReadContext.Capture captured = f.capture(true);
        assertTrue(
                captured.root == foreignRoot && !captured.initialized && captured.current == null,
                "read capture preserves uninitialized, non-project root and absent current transaction observations");
        assertTrue(
                !captured.attachedContext.get("rootIsOwningProject").getAsBoolean()
                        && captured.attachedContext
                                .get("currentDtContextMatches")
                                .isJsonNull()
                        && !captured.attachedContext
                                .get("defaultDtContextMatches")
                                .getAsBoolean(),
                "optional read facts are not silently promoted to write readiness gates");
        f.contexts.guard(captured, f.models, f.labels, () -> {});
        f.defaultSupported = false;
        f.forbidDefault = true;
        NativeReadContext.Capture unsupported = f.capture(true);
        assertTrue(
                !unsupported.hasDefault
                        && unsupported.defaultDt == null
                        && unsupported.attachedContext.get("defaultDtPresent").isJsonNull(),
                "unsupported default accessor is not invoked and remains unknown rather than false readiness");
        NativeReadContext.Capture lean = f.capture(false);
        assertTrue(
                lean.lifecycle == null && lean.attachedContext == null && lean.context == f.context,
                "guard captures retain actual native identities without constructing response JSON");
    }

    @Test
    void projectLifecycleIsCheckedWithoutProjectAncestorLookup() {
        Fixture f = new Fixture();
        NativeReadContext.Capture captured = f.capture(true);
        assertTrue(
                captured.lifecycle.size() == 3
                        && !captured.lifecycle.get("projectDeleted").getAsBoolean(),
                "project is an independently checked lifecycle participant, not an ancestor of itself");
        f.override(f.project, DELETED, true);
        assertNotApplied("stale_context", () -> f.capture(false));
        f.override(f.project, DELETED, false);
        f.override(f.project, CONTEXT, new Object());
        assertNotApplied("stale_context", () -> f.capture(false));
        Fixture moved = new Fixture();
        moved.override(moved.models[0], PROJECT, new Object());
        assertNotApplied("stale_context", () -> moved.capture(false));
        Fixture detached = new Fixture();
        detached.override(detached.models[1], CONTEXT, null);
        assertNotApplied("stale_context", () -> detached.capture(false));
    }

    @Test
    void guardsRetainOriginalContextIdentities() {
        for (NativeReadContext.Field field : new NativeReadContext.Field[] {ROOT, CURRENT_DT, DEFAULT_DT}) {
            Fixture f = new Fixture();
            NativeReadContext.Capture before = f.capture(false);
            f.values.put(field, new Object());
            assertNotApplied("stale_context", () -> f.contexts.guard(before, f.models, f.labels, () -> {}));
        }
        Fixture rebound = new Fixture();
        NativeReadContext.Capture before = rebound.capture(false);
        Object other = new Object();
        rebound.values.put(CONTEXT, other);
        rebound.values.put(DT_CONTEXT, other);
        assertNotApplied(
                "stale_context", () -> rebound.contexts.guard(before, rebound.models, rebound.labels, () -> {}));
        Fixture defaultOwner = new Fixture();
        NativeReadContext.Capture oldDefault = defaultOwner.capture(false);
        defaultOwner.override(defaultOwner.defaultDt, DT_CONTEXT, new Object());
        assertNotApplied(
                "stale_context",
                () -> defaultOwner.contexts.guard(oldDefault, defaultOwner.models, defaultOwner.labels, () -> {}));
        Fixture currentOwner = new Fixture();
        currentOwner.override(currentOwner.current, DT_CONTEXT, new Object());
        assertNotApplied("stale_context", () -> currentOwner.capture(false));
        Fixture cancelled = new Fixture();
        assertNotApplied(
                "cancelled",
                () -> cancelled.contexts.capture(
                        cancelled.project,
                        cancelled.models,
                        cancelled.labels,
                        () -> {
                            throw Errors.notApplied(ErrorCode.CANCELLED, "request ended");
                        },
                        false));
    }

    @Test
    void unavailablePredicatesRetainTheCallingDomain() {
        Fixture f = new Fixture();
        f.values.put(CONTEXT_THREAD, null);
        assertNotApplied("modulator_unavailable", () -> f.capture(false));
        f.values.put(CONTEXT_THREAD, false);
        assertNotApplied("modulator_unavailable", () -> f.capture(false));
        f.values.put(CONTEXT_THREAD, true);
        f.values.put(DISPOSED, true);
        assertNotApplied("stale_context", () -> f.capture(false));
    }

    private static final class Fixture implements NativeReadContext.Access {
        final Object project = new Object(), context = new Object(), current = new Object(), defaultDt = new Object();
        final Object[] models = {new Object(), new Object()};
        final String[] labels = {"deviceDeleted", "parameterDeleted", "projectDeleted"};
        final EnumMap<NativeReadContext.Field, Object> values = new EnumMap<>(NativeReadContext.Field.class);
        final IdentityHashMap<Object, EnumMap<NativeReadContext.Field, Object>> overrides = new IdentityHashMap<>();
        final NativeReadContext contexts = new NativeReadContext(this);
        boolean defaultSupported = true, forbidDefault;

        Fixture() {
            values.put(DELETED, false);
            values.put(CONTEXT, context);
            values.put(PROJECT, project);
            values.put(ROOT, project);
            values.put(INITIALIZED, true);
            values.put(DISPOSED, false);
            values.put(CONTEXT_THREAD, true);
            values.put(CURRENT_DT, current);
            values.put(DEFAULT_DT, defaultDt);
            values.put(DT_CONTEXT, context);
        }

        NativeReadContext.Capture capture(boolean serialize) {
            return contexts.capture(project, models, labels, () -> {}, serialize);
        }

        void override(Object model, NativeReadContext.Field field, Object value) {
            overrides
                    .computeIfAbsent(model, ignored -> new EnumMap<>(NativeReadContext.Field.class))
                    .put(field, value);
        }

        @Override
        public Object read(NativeReadContext.Field field, Object model) {
            if (field == PROJECT && model == project)
                throw new AssertionError("Project must not be queried for its own ancestor");
            if (field == DEFAULT_DT && forbidDefault)
                throw new AssertionError("Unsupported default accessor was invoked");
            EnumMap<NativeReadContext.Field, Object> specific = overrides.get(model);
            return specific != null && specific.containsKey(field) ? specific.get(field) : values.get(field);
        }

        @Override
        public boolean defaultContext(Object model) {
            return defaultSupported;
        }

        @Override
        public ErrorCode unavailableCode() {
            return ErrorCode.MODULATOR_UNAVAILABLE;
        }
    }
}
