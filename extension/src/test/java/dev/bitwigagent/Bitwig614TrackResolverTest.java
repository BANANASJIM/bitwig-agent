package dev.bitwigagent;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Binds the track resolver and every access class built on it against the classpath host jar. With the inspected
 * 6.1.4 jar each exact descriptor must resolve offline; with any other jar the bindings must fail closed with the
 * calling domain code. No native host runs and no bound method is invoked.
 */
public final class Bitwig614TrackResolverTest {
    // Any host class works as the runtime's host object: only its loader and CodeSource are read.
    private static final String HOST_ENUM = "i3D";
    private static int assertions;

    @FunctionalInterface
    private interface Binding {
        Object bind(Bitwig614Runtime.Access access) throws ReflectiveOperationException;
    }

    public static void main(String[] args) throws Exception {
        Object host = isolatedHost();
        boolean inspected = host != null
                && Bitwig614Runtime.SHA256.equals(
                        Bitwig614Runtime.hash(Bitwig614Runtime.source(host.getClass()), () -> {}));
        Bitwig614Runtime runtime =
                new Bitwig614Runtime(inspected ? host : new Object(), Bitwig614Runtime.VERSION, () -> {});
        if (!inspected) {
            deniedBindingsFailClosed(runtime);
            System.out.println("Bitwig614TrackResolverTest: " + assertions + " assertions (not the inspected jar)");
            return;
        }
        check(
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
        System.out.println("Bitwig614TrackResolverTest: " + assertions + " assertions (inspected host jar)");
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
        check(binding.bind(runtime.access(code)) != null, "exact descriptors bind for " + code.wire());
    }

    private static void deniedBindingsFailClosed(Bitwig614Runtime runtime) {
        Bitwig614Runtime.Access access = runtime.access(ErrorCode.ROUTING_UNAVAILABLE);
        check(access.gateError() != null, "a jar other than the inspected one closes the gate");
        try {
            new Bitwig614TrackResolver(access);
            throw new AssertionError("Track resolver bound without the inspected host jar");
        } catch (RpcException error) {
            check(error.is(ErrorCode.ROUTING_UNAVAILABLE), "denied resolver keeps the calling domain code");
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("A closed gate must refuse before descriptor lookup", error);
        }
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
