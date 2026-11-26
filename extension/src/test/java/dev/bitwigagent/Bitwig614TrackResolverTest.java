package dev.bitwigagent;

import static dev.bitwigagent.Bitwig614TrackResolver.Kind.*;
import static dev.bitwigagent.RpcAssert.assertNotApplied;
import static org.junit.jupiter.api.Assertions.*;

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
 * calling domain code. No native host runs and no bound method is invoked. The kind and identity checks every
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
        assertTrue(
                runtime.access(ErrorCode.NATIVE_UNAVAILABLE).gateError() == null,
                "inspected jar passes the exact version and SHA-256 gate");
        bind(runtime, ErrorCode.NATIVE_UNAVAILABLE, Bitwig614TrackResolver::new);
        bind(runtime, ErrorCode.NATIVE_UNAVAILABLE, Bitwig614DeviceTargetAccess::new);
        bind(runtime, ErrorCode.ARRANGER_UNAVAILABLE, Bitwig614ArrangerAccess::new);
        bind(runtime, ErrorCode.AUTOMATION_UNAVAILABLE, Bitwig614AutomationAccess::new);
        bind(runtime, ErrorCode.TRACK_GROUP_UNAVAILABLE, Bitwig614TrackGroupAccess::new);
        bind(runtime, ErrorCode.ROUTING_UNAVAILABLE, Bitwig614RoutingAccess::new);
        bind(runtime, RenderModule.UNAVAILABLE, Bitwig614RenderAccess::new);
        bind(runtime, ErrorCode.LAUNCHER_SETTINGS_UNAVAILABLE, Bitwig614LauncherSettingsAccess::new);
    }

    @Test
    void identityMismatchOrAbsenceIsTheOneStaleError() {
        String expected = "Track " + HostFixture.A + " no longer resolves to its native track; re-read tracks.list.";
        for (UUID identity : new UUID[] {UUID.fromString(HostFixture.B), null}) {
            RpcException error = assertNotApplied(
                    ErrorCode.STALE_CONTEXT.wire(),
                    () -> Bitwig614TrackResolver.requireIdentity(HostFixture.A, identity));
            assertEquals(expected, error.getMessage());
        }
        assertEquals(expected, Bitwig614TrackResolver.stale(HostFixture.A).getMessage());
        assertDoesNotThrow(() ->
                Bitwig614TrackResolver.requireIdentity(HostFixture.A.toUpperCase(), UUID.fromString(HostFixture.A)));
    }

    @Test
    void disallowedKindsShareOneUnsupportedWording() {
        RpcException group = assertNotApplied(
                ErrorCode.UNSUPPORTED_SCOPE.wire(),
                () -> Bitwig614TrackResolver.requireKind(HostFixture.A, GROUP, CHANNEL));
        assertEquals(
                "Track " + HostFixture.A
                        + " is a group track; this method supports audio/instrument/hybrid/effect/master tracks only.",
                group.getMessage());
        RpcException transport = assertNotApplied(
                ErrorCode.UNSUPPORTED_SCOPE.wire(),
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

    private static void bind(Bitwig614Runtime runtime, ErrorCode code, Binding binding)
            throws ReflectiveOperationException {
        assertTrue(binding.bind(runtime.access(code)) != null, "exact descriptors bind for " + code.wire());
    }

    private static void deniedBindingsFailClosed(Bitwig614Runtime runtime) {
        Bitwig614Runtime.Access access = runtime.access(ErrorCode.ROUTING_UNAVAILABLE);
        assertTrue(access.gateError() != null, "a jar other than the inspected one closes the gate");
        RpcException error = assertThrows(RpcException.class, () -> new Bitwig614TrackResolver(access));
        assertTrue(error.is(ErrorCode.ROUTING_UNAVAILABLE), "denied resolver keeps the calling domain code");
    }
}
