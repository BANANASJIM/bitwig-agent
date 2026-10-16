package dev.bitwigagent;

import java.util.List;
import static dev.bitwigagent.HostServices.failure;

/** Conservative topology prerequisites, deliberately independent of scalar licensing/touch wrappers. */
final class GridTopologyContext {
    static final int MAX_MODULES = 128;
    enum Field {
        GRAPH_PROJECT, CIU, PROJECT_ADM, PROJECT_OOK,
        UNDO, UNDO_EXECUTING, UNDO_ACTIVE, UNDO_ICL, LOCK, LOCK_OWNER
    }
    interface Access extends NativeReadContext.Access {
        Object read(Field field, Object object);
        boolean defaultContext(Object context);
    }
    private final Access a;
    GridTopologyContext(Access access) { a = access; }

    Capture capture(Object device, Object graph, List<?> members, Capture before, Runnable check) {
        check.run();
        Object project = required(a.read(Field.GRAPH_PROJECT, graph), "Graph owning project");
        Object context = required(a.read(NativeReadContext.Field.CONTEXT, graph), "Attached document context");
        requireModel(device, project, context, check);
        requireModel(graph, project, context, check);
        requireModel(project, project, context, check);
        for (Object member : members) requireModel(member, project, context, check);
        Object root = required(a.read(NativeReadContext.Field.ROOT, context), "Attached context root");
        if (root != project || bool(NativeReadContext.Field.DISPOSED, context))
            throw failure("stale_context", "Native topology context root changed or was disposed.");
        if (!bool(NativeReadContext.Field.INITIALIZED, context) || !bool(NativeReadContext.Field.CONTEXT_THREAD, context))
            throw failure("unsafe_state", "Topology editing requires an initialized document event context.");
        if (!a.defaultContext(context)) throw failure("grid_unavailable", "Native default Dt accessor is unavailable.");
        Object current = required(a.read(NativeReadContext.Field.CURRENT_DT, context), "Current Dt");
        Object defaultDt = required(a.read(NativeReadContext.Field.DEFAULT_DT, context), "Default Dt");
        if (current != defaultDt || a.read(NativeReadContext.Field.DT_CONTEXT, current) != context)
            throw failure("unsafe_state", "Topology editing requires the current/default document Dt.");
        if (bool(Field.CIU, graph) || bool(Field.PROJECT_ADM, project) || bool(Field.PROJECT_OOK, project))
            throw failure("unsafe_state", "Native graph CIu or project closing/saving precondition blocks topology editing.");
        Object undo = required(a.read(Field.UNDO, graph), "Ancestor undo manager");
        if (bool(Field.UNDO_EXECUTING, undo) || bool(Field.UNDO_ACTIVE, undo) || bool(Field.UNDO_ICL, undo))
            throw failure("unsafe_state", "Native undo manager is outside the bounded idle state.");
        Object lock = required(a.read(Field.LOCK, device), "Device lock");
        Object owner = required(a.read(Field.LOCK_OWNER, lock), "Device lock owner");
        if (owner != current || a.read(NativeReadContext.Field.DT_CONTEXT, owner) != context)
            throw failure("unsafe_state", "Native device lock owner differs from the current/default context.");
        Capture captured = new Capture(new Object[]{project, context, root, current, defaultDt, undo, lock, owner});
        if (before != null) {
            for (int i = 0; i < captured.identities.length; i++)
                if (captured.identities[i] != before.identities[i])
                    throw failure("stale_context", "Native topology context identity changed during the operation.");
        }
        check.run();
        return captured;
    }
    private void requireModel(Object model, Object project, Object context, Runnable check) {
        check.run();
        if (bool(NativeReadContext.Field.DELETED, model) || a.read(NativeReadContext.Field.CONTEXT, model) != context)
            throw failure("stale_context", "Native topology target or member is deleted, detached or in a different context.");
        if (model != project && a.read(NativeReadContext.Field.PROJECT, model) != project)
            throw failure("stale_context", "Native topology target or member belongs to a different project.");
    }
    private boolean bool(Field field, Object object) {
        Object value = a.read(field, object);
        if (!(value instanceof Boolean)) throw failure("grid_unavailable", "Native topology predicate unavailable: " + field);
        return (Boolean) value;
    }
    private boolean bool(NativeReadContext.Field field, Object object) {
        Object value = a.read(field, object);
        if (!(value instanceof Boolean)) throw failure("grid_unavailable", "Native topology predicate unavailable: " + field);
        return (Boolean) value;
    }
    private static Object required(Object value, String label) {
        if (value == null) throw failure("grid_unavailable", label + " is unavailable.");
        return value;
    }
    static final class Capture {
        private final Object[] identities;
        Capture(Object[] identities) { this.identities = identities; }
        Object undo() { return identities[5]; }
        Object project() { return identities[0]; }
    }
}
