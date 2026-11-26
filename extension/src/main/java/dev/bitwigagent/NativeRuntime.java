package dev.bitwigagent;

import com.bitwig.extension.controller.api.ControllerHost;

/** Controller-thread composition: domains share the gate, never their native bindings. */
final class NativeRuntime {
    private final ControllerHost host;
    private final Scheduler scheduler;
    private Bitwig614Runtime runtime;

    NativeRuntime(ControllerHost host, Scheduler scheduler) {
        this.host = host;
        this.scheduler = scheduler;
    }

    Bitwig614Runtime get(HostOperation operation) {
        scheduler.check(operation);
        if (runtime == null)
            runtime = new Bitwig614Runtime(host, host.getHostVersion(), () -> scheduler.check(operation));
        return runtime;
    }
}
