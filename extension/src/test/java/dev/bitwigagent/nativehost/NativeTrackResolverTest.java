package dev.bitwigagent.nativehost;

import static dev.bitwigagent.RpcAssert.assertRpcError;
import static dev.bitwigagent.nativehost.NativeTrackResolver.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.PortHandle;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.SharedData;
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
 * Binds every port of {@link NativePorts}, the bindings they resolve on first use, and the access classes built on the
 * track resolver against the classpath host jar. With the inspected jar of a host.json entry each exact descriptor must
 * resolve offline, and the types the code binds each member with are compared here with the name table's desc (the
 * compat layer's check that every port constructs on every shipped entry, and its offline descriptor comparison; run
 * once per entry jar with {@code pixi run test-java --host-jar}); with any other jar the bindings must fail closed with
 * the host_mismatch reason. No native host runs and no bound method is invoked. The kind and identity checks every
 * access class shares are exercised directly.
 */
public final class NativeTrackResolverTest {
    // A long-named class present in every inspected host jar, so the classpath jar can be found before its version.
    private static final String HOST_CLASS = "com.bitwig.flt.control_surface.proxy.ProjectProxy";

    @FunctionalInterface
    private interface Binding {
        Object bind(NativeHost.Access access) throws ReflectiveOperationException;
    }

    @Test
    void bindingsResolveOnTheInspectedJarAndFailClosedOnAnyOther() throws Exception {
        Path jar;
        try {
            jar = NativeHost.source(Class.forName(HOST_CLASS));
        } catch (ClassNotFoundException absent) {
            jar = null;
        }
        String hash = jar == null ? null : NativeHost.hash(jar, () -> {});
        JsonObject inspected = null;
        for (JsonElement entry : SharedData.readArray("host"))
            if (entry.getAsJsonObject().get("jarSha256").getAsString().equals(hash))
                inspected = entry.getAsJsonObject();
        if (inspected == null) {
            deniedBindingsFailClosed(new NativeHost(new Object(), NativeHost.SUPPORTED, () -> {}));
            return;
        }
        String version = inspected.get("version").getAsString();
        String names = inspected.get("names").getAsString();
        NativeHost runtime = new NativeHost(isolatedHost(jar, names), version, () -> {});
        assertTrue(runtime.access().gateError() == null, "inspected jar passes the exact version and SHA-256 gate");
        JsonObject members = SharedData.read(names).getAsJsonObject("members");
        List<String> differ = new ArrayList<>(), compared = new ArrayList<>();
        runtime.onBound((key, descriptor) -> {
            compared.add(key);
            if (!members.getAsJsonObject(key).get("desc").getAsString().equals(descriptor))
                differ.add(key + ": code " + descriptor + ", table "
                        + members.getAsJsonObject(key).get("desc"));
        });
        NativePorts ports = new NativePorts(op -> runtime);
        HostOperation op = new HostOperation(Long.MAX_VALUE);
        for (PortHandle<?> port : ports.all()) assertNotNull(port.get(op), "every port binds");
        assertNotNull(((NativeAutomationAccess) ports.automation().get(op)).text(), "display text binds on first use");
        assertNotNull(((NativeChainAccess) ports.chains().get(op)).layers(), "layer bindings bind on first use");
        assertTrue(
                !compared.isEmpty() && differ.isEmpty(), "the code binds each member with its table desc: " + differ);
        bind(runtime, NativeTrackResolver::new);
        bind(runtime, NativeDeviceTargetAccess::new);
        bind(runtime, NativeClipTiming::new);
        bind(runtime, NativeGridBooleanPanelAccess::new);
        bind(runtime, NativeGridConnectionAccess::new);
        bind(runtime, NativeGridModuleAccess::new);
        bind(runtime, NativeGridModuleNameAccess::new);
        bind(runtime, NativeGridParameterWriter::new);
        bind(runtime, NativeGridTopologyContext::new);
        bind(runtime, NativeLauncherClip::new);
        bind(runtime, NativeReadContextAccess::new);
        bind(runtime, NativeStepArrayAccess::new);
        bind(runtime, NativeStringPanelAccess::new);
        bind(runtime, NativeVoicingAccess::new);
    }

    @Test
    void identityMismatchOrAbsenceIsTheOneStaleError() {
        String expected = "Track " + HostFixture.A + " no longer resolves to its native track; re-read tracks.list.";
        for (UUID identity : new UUID[] {UUID.fromString(HostFixture.B), null}) {
            RpcException error = assertRpcError(
                    (identity == null ? Reason.TARGET_NOT_FOUND : Reason.TARGET_CHANGED).wire(),
                    () -> NativeTrackResolver.requireIdentity(HostFixture.A, identity));
            assertEquals(expected, error.getMessage());
            assertEquals("tracks.list", error.next);
        }
        assertEquals(expected, NativeTrackResolver.stale(HostFixture.A, null).getMessage());
        assertDoesNotThrow(
                () -> NativeTrackResolver.requireIdentity(HostFixture.A.toUpperCase(), UUID.fromString(HostFixture.A)));
    }

    @Test
    void disallowedKindsShareOneUnsupportedWording() {
        RpcException group = assertRpcError(
                Reason.UNSUPPORTED_SCOPE.wire(), () -> NativeTrackResolver.requireKind(HostFixture.A, GROUP, CHANNEL));
        assertEquals(
                "Track " + HostFixture.A
                        + " is a group track; this method supports audio/instrument/hybrid/effect/master tracks only.",
                group.getMessage());
        RpcException transport = assertRpcError(
                Reason.UNSUPPORTED_SCOPE.wire(),
                () -> NativeTrackResolver.requireKind(HostFixture.B, OTHER, CHANNEL, GROUP));
        assertEquals(
                "Track " + HostFixture.B
                        + " is a transport track; this method supports audio/instrument/hybrid/effect/master/group"
                        + " tracks only.",
                transport.getMessage());
        assertDoesNotThrow(() -> NativeTrackResolver.requireKind(HostFixture.A, GROUP, CHANNEL, GROUP));
        assertDoesNotThrow(() -> NativeTrackResolver.requireKind(HostFixture.A, CHANNEL, CHANNEL));
    }

    /** A host object from its own loader over the host jar and its sibling jars, which descriptor linking needs. */
    private static Object isolatedHost(Path jar, String names) throws Exception {
        List<URL> urls = new ArrayList<>();
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(jar.getParent(), "*.jar")) {
            for (Path sibling : jars) urls.add(sibling.toUri().toURL());
        }
        ClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        // Any host class works as the runtime's host object: only its loader and CodeSource are read.
        String type = SharedData.read(names)
                .getAsJsonObject("classes")
                .get("clip.copyMode")
                .getAsString();
        return Class.forName(type, true, loader).getEnumConstants()[0];
    }

    private static void bind(NativeHost runtime, Binding binding) throws ReflectiveOperationException {
        assertTrue(binding.bind(runtime.access()) != null, "exact descriptors bind");
    }

    private static void deniedBindingsFailClosed(NativeHost runtime) {
        NativeHost.Access access = runtime.access();
        assertTrue(access.gateError() != null, "a jar other than the inspected one closes the gate");
        RpcException error = assertThrows(RpcException.class, () -> new NativeTrackResolver(access));
        assertTrue(error.is(Reason.HOST_MISMATCH), "a closed gate is an incompatible host");
    }
}
