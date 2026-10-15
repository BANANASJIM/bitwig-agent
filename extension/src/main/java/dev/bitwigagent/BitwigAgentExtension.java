package dev.bitwigagent;

import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.api.ControllerHost;
import java.io.IOException;

public final class BitwigAgentExtension extends ControllerExtension {
    private final ControllerHost host;
    private HostBridge bridge;
    private RpcServer server;

    BitwigAgentExtension(BitwigAgentExtensionDefinition definition, ControllerHost host) {
        super(definition, host);
        this.host = host;
    }

    @Override public void init() {
        bridge = new HostBridge(host);
        server = new RpcServer(61172, bridge);
        try {
            server.start();
            host.println("Bitwig Agent listening on 127.0.0.1:61172");
        } catch (IOException error) {
            bridge.close();
            throw new IllegalStateException("Cannot start Bitwig Agent loopback server", error);
        }
    }

    @Override public void exit() {
        if (bridge != null) bridge.close();
        if (server != null) server.close();
    }

    @Override public void flush() { if (bridge != null) bridge.flush(); }
}
