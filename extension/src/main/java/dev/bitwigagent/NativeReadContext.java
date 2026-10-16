package dev.bitwigagent;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import static dev.bitwigagent.HostServices.failure;

/** Owning-project/context read observations, not active-project identity or write permission. */
final class NativeReadContext {
    enum Field { DELETED, CONTEXT, PROJECT, ROOT, INITIALIZED, DISPOSED, CONTEXT_THREAD, CURRENT_DT, DEFAULT_DT, DT_CONTEXT }
    interface Access {
        Object read(Field field, Object object);
        boolean defaultContext(Object context);
        String unavailableCode();
    }
    private final Access a;
    NativeReadContext(Access access) { a = access; }

    Capture capture(Object project, Object[] models, String[] lifecycleLabels, Runnable check, boolean serialize) {
        if (models.length == 0 || lifecycleLabels.length != models.length + 1)
            throw new IllegalArgumentException("Read context requires models and one lifecycle label per model plus project.");
        check.run(); required(project, "Owning project");
        Object context = attached(models[models.length - 1]);
        JsonObject lifecycle = serialize ? new JsonObject() : null;
        for (int i = 0; i <= models.length; i++) {
            check.run();
            Object model = i < models.length ? models[i] : project;
            boolean deleted = bool(Field.DELETED, model);
            if (serialize) lifecycle.addProperty(lifecycleLabels[i], deleted);
            if (deleted) throw failure("stale_context", "Native inspection target was deleted.");
            if (attached(model) != context)
                throw failure("stale_context", "Native target no longer shares its owning project's attached context.");
            if (i < models.length && a.read(Field.PROJECT, model) != project)
                throw failure("stale_context", "Native target belongs to a different owning project.");
        }
        Object root = required(a.read(Field.ROOT, context), "Attached context root");
        boolean initialized = bool(Field.INITIALIZED, context), disposed = bool(Field.DISPOSED, context);
        if (disposed) throw failure("stale_context", "Native target context was disposed.");
        boolean contextThread = bool(Field.CONTEXT_THREAD, context);
        if (!contextThread) throw failure(a.unavailableCode(), "Attached context thread predicate failed.");
        Object current = a.read(Field.CURRENT_DT, context);
        Object currentContext = current == null ? null : a.read(Field.DT_CONTEXT, current);
        if (current != null && currentContext != context)
            throw failure("stale_context", "Current native Dt belongs to a different context.");
        boolean hasDefault = a.defaultContext(context);
        Object defaultDt = hasDefault ? a.read(Field.DEFAULT_DT, context) : null;
        Object defaultDtContext = defaultDt == null ? null : a.read(Field.DT_CONTEXT, defaultDt);
        JsonObject attached = serialize ? new JsonObject() : null;
        if (serialize) {
            attached.addProperty("modelClass", context.getClass().getName());
            attached.addProperty("rootPresent", true); attached.addProperty("rootIsOwningProject", root == project);
            attached.addProperty("initialized", initialized); attached.addProperty("disposed", disposed);
            attached.addProperty("eventDispatchThread", contextThread);
            attached.addProperty("currentDtPresent", current != null);
            nullable(attached, "currentDtContextMatches", current == null ? null : currentContext == context);
            attached.addProperty("defaultDtAccessorSupported", hasDefault);
            nullable(attached, "defaultDtPresent", hasDefault ? defaultDt != null : null);
            nullable(attached, "currentDtIsDefault", current == null || defaultDt == null ? null : current == defaultDt);
            nullable(attached, "defaultDtContextMatches", defaultDt == null ? null : defaultDtContext == context);
        }
        check.run();
        return new Capture(project, context, root, current, currentContext, defaultDt, defaultDtContext,
                initialized, disposed, contextThread, hasDefault, lifecycle, attached);
    }
    void guard(Capture before, Object[] models, String[] labels, Runnable check) {
        Capture after = capture(before.project, models, labels, check, false);
        if (before.project != after.project || before.context != after.context || before.root != after.root
                || before.current != after.current || before.currentContext != after.currentContext
                || before.defaultDt != after.defaultDt || before.defaultDtContext != after.defaultDtContext)
            throw failure("stale_context", "Native inspection context identity changed during capture.");
    }
    private Object attached(Object model) {
        Object context = a.read(Field.CONTEXT, model);
        if (context == null) throw failure("stale_context", "Native target is detached from its document context.");
        return context;
    }
    private boolean bool(Field field, Object object) {
        Object value = a.read(field, object);
        if (!(value instanceof Boolean)) throw failure(a.unavailableCode(), "Native context predicate unavailable: " + field);
        return (Boolean) value;
    }
    private Object required(Object value, String label) {
        if (value == null) throw failure(a.unavailableCode(), label + " is unavailable.");
        return value;
    }
    private static void nullable(JsonObject object, String key, Boolean value) {
        if (value == null) object.add(key, JsonNull.INSTANCE); else object.addProperty(key, value);
    }
    static final class Capture {
        final Object project, context, root, current, currentContext, defaultDt, defaultDtContext;
        final boolean initialized, disposed, contextThread, hasDefault;
        final JsonObject lifecycle, attachedContext;
        Capture(Object project, Object context, Object root, Object current, Object currentContext,
                Object defaultDt, Object defaultDtContext, boolean initialized, boolean disposed,
                boolean contextThread, boolean hasDefault, JsonObject lifecycle, JsonObject attachedContext) {
            this.project = project; this.context = context; this.root = root; this.current = current;
            this.currentContext = currentContext; this.defaultDt = defaultDt; this.defaultDtContext = defaultDtContext;
            this.initialized = initialized; this.disposed = disposed; this.contextThread = contextThread;
            this.hasDefault = hasDefault; this.lifecycle = lifecycle; this.attachedContext = attachedContext;
        }
    }
}
