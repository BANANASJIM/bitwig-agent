package dev.bitwigagent.bitwig614;

import static dev.bitwigagent.RpcAssert.assertRpcError;
import static dev.bitwigagent.bitwig614.Bitwig614TrackResolver.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.bitwigagent.HostFixture;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Binds the track resolver and every access class built on it against the classpath host jar. With the inspected
 * 6.1.4 jar each exact descriptor must resolve offline; with any other jar the bindings must fail closed with the
 * host_mismatch reason. No native host runs and no bound method is invoked. The kind and identity checks every
 * access class shares are exercised directly.
 */
public final class Bitwig614TrackResolverTest {
    // Any host class works as the runtime's host object: only its loader and CodeSource are read.
    private static final String HOST_ENUM = "i3D";

    @FunctionalInterface
    private interface Binding {
        Object bind(Bitwig614Runtime.Access access) throws ReflectiveOperationException;
    }

    @Test
    void bindingsResolveOnTheInspectedJarAndFailClosedOnAnyOther() throws Exception {
        Object host = isolatedHost();
        boolean inspected = host != null
                && Bitwig614Runtime.SHA256.equals(
                        Bitwig614Runtime.hash(Bitwig614Runtime.source(host.getClass()), () -> {}));
        Bitwig614Runtime runtime =
                new Bitwig614Runtime(inspected ? host : new Object(), Bitwig614Runtime.VERSION, () -> {});
        if (!inspected) {
            deniedBindingsFailClosed(runtime);
            return;
        }
        assertTrue(runtime.access().gateError() == null, "inspected jar passes the exact version and SHA-256 gate");
        bind(runtime, Bitwig614TrackResolver::new);
        bind(runtime, Bitwig614DeviceTargetAccess::new);
        bind(runtime, Bitwig614ArrangerAccess::new);
        bind(runtime, Bitwig614AutomationAccess::new);
        bind(runtime, Bitwig614TrackGroupAccess::new);
        bind(runtime, Bitwig614RoutingAccess::new);
        bind(runtime, Bitwig614RenderAccess::new);
        bind(runtime, Bitwig614LauncherSettingsAccess::new);
    }

    @Test
    void identityMismatchOrAbsenceIsTheOneStaleError() {
        String expected = "Track " + HostFixture.A + " no longer resolves to its native track; re-read tracks.list.";
        for (UUID identity : new UUID[] {UUID.fromString(HostFixture.B), null}) {
            RpcException error = assertRpcError(
                    (identity == null ? Reason.TARGET_NOT_FOUND : Reason.TARGET_CHANGED).wire(),
                    () -> Bitwig614TrackResolver.requireIdentity(HostFixture.A, identity));
            assertEquals(expected, error.getMessage());
            assertEquals("tracks.list", error.next);
        }
        assertEquals(expected, Bitwig614TrackResolver.stale(HostFixture.A, null).getMessage());
        assertDoesNotThrow(() ->
                Bitwig614TrackResolver.requireIdentity(HostFixture.A.toUpperCase(), UUID.fromString(HostFixture.A)));
    }

    @Test
    void disallowedKindsShareOneUnsupportedWording() {
        RpcException group = assertRpcError(
                Reason.UNSUPPORTED_SCOPE.wire(),
                () -> Bitwig614TrackResolver.requireKind(HostFixture.A, GROUP, CHANNEL));
        assertEquals(
                "Track " + HostFixture.A
                        + " is a group track; this method supports audio/instrument/hybrid/effect/master tracks only.",
                group.getMessage());
        RpcException transport = assertRpcError(
                Reason.UNSUPPORTED_SCOPE.wire(),
                () -> Bitwig614TrackResolver.requireKind(HostFixture.B, OTHER, CHANNEL, GROUP));
        assertEquals(
                "Track " + HostFixture.B
                        + " is a transport track; this method supports audio/instrument/hybrid/effect/master/group"
                        + " tracks only.",
                transport.getMessage());
        assertDoesNotThrow(() -> Bitwig614TrackResolver.requireKind(HostFixture.A, GROUP, CHANNEL, GROUP));
        assertDoesNotThrow(() -> Bitwig614TrackResolver.requireKind(HostFixture.A, CHANNEL, CHANNEL));
    }

    /** A host object from its own loader over the host jar and its sibling jars, which descriptor linking needs. */
    private static Object isolatedHost() throws Exception {
        Path jar;
        try {
            jar = Bitwig614Runtime.source(Class.forName(HOST_ENUM));
        } catch (ClassNotFoundException absent) {
            return null;
        }
        List<URL> urls = new ArrayList<>();
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(jar.getParent(), "*.jar")) {
            for (Path sibling : jars) urls.add(sibling.toUri().toURL());
        }
        ClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        return Class.forName(HOST_ENUM, true, loader).getEnumConstants()[0];
    }

    private static void bind(Bitwig614Runtime runtime, Binding binding) throws ReflectiveOperationException {
        assertTrue(binding.bind(runtime.access()) != null, "exact descriptors bind");
    }

    private static void deniedBindingsFailClosed(Bitwig614Runtime runtime) {
        Bitwig614Runtime.Access access = runtime.access();
        assertTrue(access.gateError() != null, "a jar other than the inspected one closes the gate");
        RpcException error = assertThrows(RpcException.class, () -> new Bitwig614TrackResolver(access));
        assertTrue(error.is(Reason.HOST_MISMATCH), "a closed gate is an incompatible host");
    }
}
