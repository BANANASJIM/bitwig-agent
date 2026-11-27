package dev.bitwigagent.host;

import com.bitwig.extension.controller.api.ControllerHost;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.nativehost.NativeHost;

/** Controller-thread composition: domains share the gate, never their native bindings. */
public final class NativeRuntime {
    private final ControllerHost host;
    private final Scheduler scheduler;
    private NativeHost runtime;

    public NativeRuntime(ControllerHost host, Scheduler scheduler) {
        this.host = host;
        this.scheduler = scheduler;
    }

    public NativeHost get(HostOperation operation) {
        scheduler.check(operation);
        if (runtime == null) runtime = new NativeHost(host, host.getHostVersion(), () -> scheduler.check(operation));
        return runtime;
    }
}
