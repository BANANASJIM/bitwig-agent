package dev.bitwigagent;

import java.io.InputStream;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import static dev.bitwigagent.HostServices.failure;

/** One loaded-binary gate and document dispatcher; domain bindings remain independent. */
final class Bitwig614Runtime {
    static final String VERSION = "6.1.4";
    static final String SHA256 = "77a50264156a9f79c925fa85e0456720ce157bfa10e2503d6049e2f733af4ba6";
    private final ClassLoader loader;
    private final String hostVersion;
    private Path hostJar;
    private String actualHash, gateError, modelThreadError;
    private Method singleton, async, isDocumentThread, modelThread, executor;

    Bitwig614Runtime(Object host, String version, Runnable check) {
        hostVersion = version; loader = host.getClass().getClassLoader();
        Access binding = access("native_unavailable");
        try {
            check.run();
            if (!VERSION.equals(version)) throw failure("native_unavailable", "Native access requires exactly Bitwig " + VERSION + "; running " + version + ".");
            hostJar = source(host.getClass());
            if (!Files.isRegularFile(hostJar)) throw failure("native_unavailable", "Loaded host CodeSource is not a regular JAR file.");
            actualHash = hash(hostJar, check);
            if (!SHA256.equals(actualHash)) throw failure("native_unavailable", "Loaded host JAR SHA-256 is not the inspected " + VERSION + " binary; obtain a matching adapter, do not bypass this gate.");
            Class<?> app = binding.type("com.bitwig.flt.app.TL");
            singleton = binding.exactMethod(app, "hIO", app, true);
            async = binding.exactMethod(app, "asyncExec", void.class, false, Runnable.class);
            isDocumentThread = binding.exactMethod(app, "isEventDispatchThread", boolean.class, false);
            Class<?> scopedExecutor = binding.type("QYb");
            modelThread = binding.exactMethod(scopedExecutor, "isEventDispatchThread", boolean.class, false);
            check.run();
        } catch (RuntimeException | ReflectiveOperationException | java.io.IOException error) {
            if (error instanceof RpcException && !"native_unavailable".equals(((RpcException) error).code)) throw (RpcException) error;
            gateError = diagnostic(error);
        } catch (LinkageError error) { gateError = diagnostic(error); }
        if (gateError != null) return;
        try {
            executor = binding.exactMethod(binding.type("com.bitwig.ramona.core.AVL"), "Q_", binding.type("QYb"), false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            modelThreadError = diagnostic(error);
        }
        check.run();
    }

    Access access(String unavailableCode) { return new Access(unavailableCode); }

    final class Access {
        private final String unavailableCode;
        private Access(String unavailableCode) { this.unavailableCode = unavailableCode; }
        String unavailableCode() { return unavailableCode; }
        String hostVersion() { return hostVersion; }
        String actualHash() { return actualHash; }
        String gateError() { return gateError; }
        void requireAvailable() {
            if (gateError != null) throw failure(unavailableCode, "Native runtime unavailable: " + gateError);
        }
        Class<?> type(String name) throws ClassNotFoundException {
            requireAvailable();
            Class<?> type = Class.forName(name, false, loader);
            try {
                if (!source(type).equals(hostJar)) throw failure(unavailableCode, "Native binding class is not from the hashed loaded host JAR: " + name);
            } catch (java.io.IOException error) { throw failure(unavailableCode, "Cannot establish native class CodeSource: " + name); }
            return type;
        }
        Method exactMethod(Class<?> owner, String name, Class<?> returns, boolean isStatic, Class<?>... args)
                throws ReflectiveOperationException {
            requireAvailable();
            Method method = owner.getMethod(name, args);
            if (method.getReturnType() != returns || Modifier.isStatic(method.getModifiers()) != isStatic || method.isBridge()
                    || !Modifier.isPublic(method.getDeclaringClass().getModifiers()))
                throw new NoSuchMethodException("Exact public descriptor mismatch: " + owner.getName() + "." + name);
            if (type(owner.getName()) != owner || type(method.getDeclaringClass().getName()) != method.getDeclaringClass())
                throw new NoSuchMethodException("Native descriptor class differs from the loaded host binding.");
            return method;
        }
        Object invoke(Method method, Object object, Object... args) {
            requireAvailable();
            return Bitwig614Runtime.invoke(unavailableCode, method, object, args);
        }
        void execute(Runnable work) {
            requireAvailable();
            Object app = invoke(singleton, null);
            if (app == null) throw failure(unavailableCode, "Native application singleton is not ready; wait for host startup.");
            invoke(async, app, work);
        }
        void documentThread() {
            requireAvailable();
            Object app = invoke(singleton, null);
            if (app == null || !Boolean.TRUE.equals(invoke(isDocumentThread, app)))
                throw failure(unavailableCode, "Native read was not dispatched on the native document thread.");
        }
        void requireModelThreadAvailable() {
            requireAvailable();
            if (modelThreadError != null)
                throw failure(unavailableCode, "Native model executor binding unavailable: " + modelThreadError);
        }
        void requireModelThread(Object object) {
            requireModelThreadAvailable();
            Object current = invoke(executor, object);
            // AVL.Q_ is a scoped executor predicate, not proof of model ancestry.
            if (current == null || !Boolean.TRUE.equals(invoke(modelThread, current)))
                throw failure(unavailableCode, "Native model thread predicate failed: "
                        + (current == null ? "null" : current.getClass().getName()));
        }
    }

    static Object invoke(String errorCode, Method method, Object object, Object... args) {
        try { return method.invoke(object, args); }
        catch (ReflectiveOperationException | IllegalArgumentException error) {
            Throwable cause = error instanceof InvocationTargetException ? ((InvocationTargetException) error).getCause() : error;
            RpcException translated = failure(errorCode, "Native call failed at " + method.getDeclaringClass().getName() + "." + method.getName() + ": " + diagnostic(cause));
            translated.initCause(error);
            throw translated;
        }
    }
    static boolean isInvocationFailure(Throwable error) {
        if (!(error instanceof RpcException)) return false;
        Throwable boundary = error.getCause();
        return boundary instanceof ReflectiveOperationException || boundary instanceof IllegalArgumentException;
    }
    static Path source(Class<?> type) throws java.io.IOException {
        try {
            if (type.getProtectionDomain() == null || type.getProtectionDomain().getCodeSource() == null)
                throw new java.io.IOException("Loaded class has no CodeSource.");
            java.net.URI uri = type.getProtectionDomain().getCodeSource().getLocation().toURI();
            if (!"file".equals(uri.getScheme())) throw new java.io.IOException("Loaded class CodeSource is not a file URI: " + uri);
            return Path.of(uri).toRealPath();
        } catch (java.net.URISyntaxException | SecurityException error) { throw new java.io.IOException("Cannot resolve loaded host CodeSource.", error); }
    }
    static String hash(Path file, Runnable check) throws java.io.IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536];
            try (InputStream stream = Files.newInputStream(file)) {
                int size;
                while ((size = stream.read(buffer)) != -1) { check.run(); digest.update(buffer, 0, size); }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) { result.append(Character.forDigit((value >>> 4) & 15, 16)); result.append(Character.forDigit(value & 15, 16)); }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    static String diagnostic(Throwable error) {
        String message = error.getMessage();
        if (message != null && message.length() > 512) message = message.substring(0, 512);
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
