package dev.bitwigagent.nativehost;

import static dev.bitwigagent.RpcAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Shared-domain error translation tests, not a substitute for native binding acceptance. */
public final class NativeHostTest {

    @Test
    void aClosedGateIsAnIncompatibleHostForEveryAccessor() {
        NativeHost runtime = new NativeHost(new Object(), "unsupported-version", () -> {});
        NativeHost.Access grid = runtime.access(), modulators = runtime.access();
        assertTrue(
                grid.gateError() != null && grid.gateError().equals(modulators.gateError()),
                "one failed runtime gate is reported consistently to independent domain accessors");
        assertEquals(
                "incompatible",
                assertRpcError("host_mismatch", grid::requireAvailable).code());
        assertRpcError("host_mismatch", modulators::requireAvailable);
        assertRpcError(
                "host_mismatch",
                () -> grid.execute(() -> {
                    throw new AssertionError("Denied runtime dispatched work");
                }));
        assertRpcError("host_mismatch", modulators::documentThread);
        assertRpcError("host_mismatch", () -> modulators.requireModelThread(new Object()));
        assertRpcError(
                "cancelled",
                () -> new NativeHost(new Object(), "unsupported-version", () -> {
                    throw Errors.notApplied(Reason.CANCELLED, "stop before binding");
                }));
    }

    @Test
    void accessResolvesSemanticKeysThroughTheSelectedVersionTable() throws Exception {
        Path jar = Files.createTempFile("bwa-host", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Class<?> type : new Class<?>[] {App.class, Executor.class}) {
                String entry = type.getName().replace('.', '/') + ".class";
                out.putNextEntry(new JarEntry(entry));
                try (InputStream in = NativeHostTest.class.getResourceAsStream("/" + entry)) {
                    in.transferTo(out);
                }
            }
        }
        String hash = NativeHost.hash(jar.toRealPath(), () -> {});
        JsonArray hosts = JsonParser.parseString("[{\"version\":\"0.0.1\",\"jarSha256\":\"0\",\"names\":\"none\"},"
                        + "{\"version\":\"9.9.9\",\"jarSha256\":\"" + hash + "\",\"names\":\"names_9_9_9\"}]")
                .getAsJsonArray();
        String app = App.class.getName(), executor = Executor.class.getName();
        JsonObject table = JsonParser.parseString("{\"classes\":{\"app\":\"" + app + "\",\"model.executor\":\""
                        + executor + "\",\"model.object\":\"" + executor + "\"},\"members\":{"
                        + "\"app.singleton\":{\"owner\":\"app\",\"name\":\"q1\"},"
                        + "\"app.asyncExec\":{\"owner\":\"app\",\"name\":\"q2\"},"
                        + "\"app.isDocumentThread\":{\"owner\":\"app\",\"name\":\"q3\"},"
                        + "\"app.none\":{\"owner\":\"app\",\"name\":\"q4\"},"
                        + "\"model.executor.isModelThread\":{\"owner\":\"model.executor\",\"name\":\"q5\"},"
                        + "\"model.object.executor\":{\"owner\":\"model.object\",\"name\":\"q6\"}}}")
                .getAsJsonObject();
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> host = loader.loadClass(app);
            NativeHost runtime = new NativeHost(host.getConstructor().newInstance(), "9.9.9", () -> {}, hosts, name -> {
                assertEquals("names_9_9_9", name, "the table named by the matching version entry loads");
                return table;
            });
            NativeHost.Access access = runtime.access();
            assertNull(access.gateError(), "the version entry's hash and table open the gate");
            assertEquals(hash, access.expectedHash());
            assertSame(host, access.type("app"));
            assertNotSame(App.class, access.type("app"), "classes come from the hashed host jar, not the caller");
            assertEquals("q4", access.name("app.none"));
            assertEquals(
                    "q3",
                    access.exactMethod("app.isDocumentThread", boolean.class, false)
                            .getName());
            assertSame(host, access.exactConstant("app.none", host).getClass());
            assertSame(host, access.exactConstructor("app").getDeclaringClass());
            assertThrows(
                    NoSuchMethodException.class, () -> access.exactMethod("app.isDocumentThread", int.class, false));
            boolean[] ran = {false};
            access.execute(() -> ran[0] = true);
            assertTrue(ran[0], "execute dispatches through the table's asyncExec binding");
            access.documentThread();
            access.requireModelThread(executor(loader));
            // A key absent from this version's table fails only the feature that uses it.
            for (Executable missing : List.<Executable>of(
                    () -> access.type("app.missing"),
                    () -> access.name("app.missing"),
                    () -> access.exactMethod("app.missing", void.class, false)))
                assertEquals(
                        "native_unavailable",
                        assertThrows(RpcException.class, missing).reason.wire());
            NativeHost other = new NativeHost(host.getConstructor().newInstance(), "0.0.1", () -> {}, hosts, name -> {
                throw new AssertionError("a hash mismatch must close the gate before any binding");
            });
            String gate = other.access().gateError();
            assertTrue(
                    gate != null && gate.startsWith("Loaded host JAR SHA-256"),
                    "a gate refusal carries its own message, not the exception class: " + gate);
            // Lookups through a member's owner class must hit the gate before the (unloaded) name table.
            for (Executable closed : List.<Executable>of(
                    () -> other.access().exactMethod("app.isDocumentThread", boolean.class, false),
                    () -> other.access().exactConstant("app.none", host)))
                assertEquals(
                        "host_mismatch",
                        assertThrows(RpcException.class, closed).reason.wire(),
                        "a closed gate is the gate error, not a NullPointerException");
        } finally {
            Files.delete(jar);
        }
    }

    private static Object executor(ClassLoader loader) throws Exception {
        return loader.loadClass(Executor.class.getName()).getConstructor().newInstance();
    }

    public static final class App {
        public static final App q4 = new App();

        public static App q1() {
            return q4;
        }

        public void q2(Runnable work) {
            work.run();
        }

        public boolean q3() {
            return true;
        }
    }

    public static final class Executor {
        public Executor q6() {
            return this;
        }

        public boolean q5() {
            return true;
        }
    }

    @Test
    void nativeRejectionsRetainTheirInvocationBoundary() throws Exception {
        Method reader = ThrowingRead.class.getMethod("read");
        {
            RpcException nativeRejection = Errors.notApplied(Reason.CANCELLED, "native code threw an RPC-shaped error");
            {
                RpcException translated = assertThrows(
                        RpcException.class, () -> NativeHost.invoke(reader, new ThrowingRead(nativeRejection)));
                assertTrue(
                        translated.is(Reason.NATIVE_UNAVAILABLE) && NativeHost.isInvocationFailure(translated),
                        "native rejection is classified as a failed invocation");
                assertTrue(
                        translated.getCause() instanceof InvocationTargetException
                                && translated.getCause().getCause() == nativeRejection,
                        "translation preserves the actual invocation boundary and native throwable identity");
            }
            assertTrue(
                    !NativeHost.isInvocationFailure(nativeRejection),
                    "ordinary controller cancellation remains distinct from reflected invocation failure");
        }
    }

    public static final class ThrowingRead {
        private final RpcException error;

        ThrowingRead(RpcException error) {
            this.error = error;
        }

        public Object read() {
            throw error;
        }
    }
}
