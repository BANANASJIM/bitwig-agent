package dev.bitwigagent;

import static dev.bitwigagent.GridTopologyContext.Field.*;

/** Shared public context observations, independent of move geometry and connection setter bindings. */
final class Bitwig614GridTopologyContext extends Bitwig614ContextReaders<GridTopologyContext.Field>
        implements GridTopologyContext.Access {
    private static final String R = "com.bitwig.ramona.core.", D = "com.bitwig.flt.document.core.master.device.";

    Bitwig614GridTopologyContext(Bitwig614Runtime.Access bindings) throws ReflectiveOperationException {
        super(GridTopologyContext.Field.class, bindings);
        Class<?> model = bindings.type(R + "AVL");
        Class<?> dt = bindings.type(R + "XKG"), undo = bindings.type("dfI"), lock = bindings.type(R + "IOJ");
        bind(GRAPH_PROJECT, bindings.type("K2G"), "ce_", bindings.type("com.bitwig.flt.document.core.master.g2L"));
        bind(CIU, bindings.type("K2G"), "qZj", boolean.class);
        Class<?> projectFlags = bindings.type("com.bitwig.flt.document.core.master.AJH");
        bind(PROJECT_ADM, projectFlags, "jG", boolean.class); // is_being_closed (4701)
        bind(PROJECT_OOK, projectFlags, "zpW", boolean.class); // is_being_saved (4702)
        bindUndo(UNDO, undo, model);
        bind(UNDO_EXECUTING, undo, "iCS", boolean.class);
        bind(UNDO_ACTIVE, undo, "iHb", boolean.class);
        bind(UNDO_ICL, undo, "nVd", boolean.class);
        bind(LOCK, bindings.type(D + "FDA"), "cm_", lock);
        bind(LOCK_OWNER, lock, "IwV", dt);
    }

    @Override
    public Object read(GridTopologyContext.Field field, Object object) {
        return invoke(field, UNDO, object, "topology context");
    }
}
