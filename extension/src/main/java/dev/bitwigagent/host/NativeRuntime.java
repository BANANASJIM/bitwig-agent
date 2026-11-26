package dev.bitwigagent.host;

import com.bitwig.extension.controller.api.ControllerHost;
import dev.bitwigagent.bitwig614.Bitwig614Runtime;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Scheduler;

/** Controller-thread composition: domains share the gate, never their native bindings. */
public final class NativeRuntime {
    private final ControllerHost host;
    private final Scheduler scheduler;
    private Bitwig614Runtime runtime;

    public NativeRuntime(ControllerHost host, Scheduler scheduler) {
        this.host = host;
        this.scheduler = scheduler;
    }

    public Bitwig614Runtime get(HostOperation operation) {
        scheduler.check(operation);
        if (runtime == null)
            runtime = new Bitwig614Runtime(host, host.getHostVersion(), () -> scheduler.check(operation));
        return runtime;
    }
}
