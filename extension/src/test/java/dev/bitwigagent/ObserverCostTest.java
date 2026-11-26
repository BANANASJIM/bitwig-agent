package dev.bitwigagent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * What the display-text support costs at startup and at rest, counted as host registrations on the simulated window
 * (the extension's 1024-track window, as built). A registration count is not a measurement of time; it is the quantity
 * the cost scales with, and the native checklist times the real startup against the P2-0 baseline.
 */
public final class ObserverCostTest {
    private static void walk(FakeHost node, Set<FakeHost> seen, Map<String, Integer> registrations) {
        if (!seen.add(node)) return;
        node.calls.forEach((method, count) -> {
            boolean registers =
                    (method.startsWith("add") && method.endsWith("Observer")) || method.equals("markInterested");
            if (registers || method.equals("createLayerBank")) registrations.merge(method, count, Integer::sum);
        });
        for (FakeHost child : node.children.values()) walk(child, seen, registrations);
    }

    @Test
    void theDisplayObserverIsOneRegistrationPerDeviceProxyAndObservesNothingAtRest() {
        HostFixture f = new HostFixture();
        RemoteModule remotes = f.services.remote();
        f.services.parameters(remotes);
        f.services.device();
        f.services.deviceChain();
        Map<String, Integer> registrations = new TreeMap<>();
        walk(f.host, Collections.newSetFromMap(new IdentityHashMap<>()), registrations);
        int display = registrations.get("addDirectParameterValueDisplayObserver");
        int proxies = f.services.sources.all.size();
        assertEquals(proxies, display, "exactly one display observer per device proxy");
        assertEquals(HostLimits.TRACKS * HostLimits.DEVICES, proxies, "root devices only: chains are walked natively");
        assertNull(registrations.remove("createLayerBank"), "no nested proxy windows");
        long total =
                registrations.values().stream().mapToLong(Integer::longValue).sum();
        int direct = registrations.get("addDirectParameterNormalizedValueObserver");
        assertEquals(direct, display);
        int others = registrations.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("addDirectParameterValueDisplayObserver"))
                .mapToInt(Map.Entry::getValue)
                .sum();
        double share = 100.0 * display / others;
        System.out.println("display observer registrations: " + display + " on top of " + others + " others ("
                + String.format("%.2f", share) + "%), per proxy "
                + registrations.get("addDirectParameterIdObserver") / proxies
                + " direct-parameter observers of the 3 kinds plus the display one");
        assertTrue(share < 10, "the per-proxy display observer stays a small share of the startup registrations");
        assertEquals(
                0,
                f.tracks[0]
                        .child("createDeviceBank")
                        .item(0)
                        .child("directDisplayObserver")
                        .calls("setObservedParameterIds"),
                "nothing is observed until a request asks for a parameter");
    }
}
