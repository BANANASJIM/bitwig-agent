package dev.bitwigagent.device;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bitwigagent.core.DisplaySolver;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Scheduler;
import java.util.function.Function;

/**
 * A native parameter under a chain as the display solver sees it: each probe is one document-thread task that finds
 * the parameter again from {@code root}, writes it and reads it back.
 */
final class NativeParameterPort implements DisplaySolver.Port {
    private final NativeChains chains;
    private final Scheduler scheduler;
    private final HostOperation op;
    private final JsonObject root;
    private final Function<NativeChains.Walk, Object> bind;
    private final Runnable safety;
    private volatile Object parameter;
    private volatile NativeChains.Access a;
    private volatile Double value;
    private volatile String display;
    private volatile long valueSeq, displaySeq;
    private volatile RuntimeException failure;

    NativeParameterPort(
            NativeChains chains,
            Scheduler scheduler,
            HostOperation op,
            JsonObject root,
            Function<NativeChains.Walk, Object> bind,
            Runnable safety) {
        this.chains = chains;
        this.scheduler = scheduler;
        this.op = op;
        this.root = root;
        this.bind = bind;
        this.safety = safety;
    }

    /** Binds the parameter in this walk and reads it; the walk that starts the solve calls it first. */
    Object bind(NativeChains.Walk walk) {
        parameter = bind.apply(walk);
        read(walk);
        return parameter;
    }

    private void read(NativeChains.Walk walk) {
        a = walk.a;
        double now = walk.a.value(parameter);
        value = Double.isFinite(now) ? now : null;
        display = walk.a.display(parameter);
        valueSeq++;
        displaySeq++;
    }

    /** The value last read, or null while unread or not finite. */
    Double observed() {
        return value;
    }

    void provenance(JsonObject out) {
        a.provenance(out);
    }

    @Override
    public long valueSeq() {
        return valueSeq;
    }

    @Override
    public JsonElement value() {
        return value == null ? JsonNull.INSTANCE : new JsonPrimitive(value);
    }

    @Override
    public JsonElement display() {
        return display == null ? JsonNull.INSTANCE : new JsonPrimitive(display);
    }

    @Override
    public long displaySeq() {
        return displaySeq;
    }

    @Override
    public void write(double normalized) {
        chains.async(
                op,
                root,
                walk -> {
                    if (bind.apply(walk) != parameter)
                        throw Errors.unknown(
                                Reason.TARGET_CHANGED, "The parameter changed while its display was solved.");
                    walk.a.write(parameter, normalized);
                    read(walk);
                },
                error -> failure = error);
    }

    @Override
    public void guard() {
        safety.run();
        scheduler.check(op);
        RuntimeException failed = failure;
        if (failed != null) throw failed;
    }
}
