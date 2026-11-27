package dev.bitwigagent.nativehost;

import static dev.bitwigagent.grid.GridTopologyContext.Field.*;

import dev.bitwigagent.grid.GridTopologyContext;

/** Shared public context observations, independent of move geometry and connection setter bindings. */
final class NativeGridTopologyContext extends NativeContextReaders<GridTopologyContext.Field>
        implements GridTopologyContext.Access {

    NativeGridTopologyContext(NativeHost.Access bindings) throws ReflectiveOperationException {
        super(GridTopologyContext.Field.class, bindings);
        Class<?> model = bindings.type("model.object");
        Class<?> dt = bindings.type("model.thread"), undo = bindings.type("document.undo");
        Class<?> lock = bindings.type("model.lock");
        bind(GRAPH_PROJECT, "model.element.project", bindings.type("project"));
        bind(CIU, "model.element.skipped", boolean.class);
        bind(PROJECT_ADM, "project.state.closing", boolean.class); // is_being_closed (4701)
        bind(PROJECT_OOK, "project.state.saving", boolean.class); // is_being_saved (4702)
        bindUndo(UNDO, "document.undo.of", undo, model);
        bind(UNDO_EXECUTING, "document.undo.executing", boolean.class);
        bind(UNDO_ACTIVE, "document.undo.active", boolean.class);
        bind(UNDO_ICL, "document.undo.recording", boolean.class);
        bind(LOCK, "device.element.lock", lock);
        bind(LOCK_OWNER, "model.lock.owner", dt);
    }

    @Override
    public Object read(GridTopologyContext.Field field, Object object) {
        return invoke(field, UNDO, object, "topology context");
    }
}
