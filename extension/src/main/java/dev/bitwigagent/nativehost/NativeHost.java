package dev.bitwigagent.nativehost;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.SharedData;
import java.io.InputStream;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** One loaded-binary gate and document dispatcher; domain bindings remain independent. */
public final class NativeHost {
    private static final JsonArray HOSTS = SharedData.readArray("host");
    public static final String SUPPORTED = SharedData.hostVersions();
    private final ClassLoader loader;
    private final String hostVersion;
    private JsonObject classes, members;
    private Path hostJar;
    private String expectedHash, actualHash, gateError, modelThreadError;
    private Method singleton, async, isDocumentThread, modelThread, executor;
    /** Each exact member binding with the descriptor of the code's types; the offline check against the table. */
    private BiConsumer<String, String> bound = (key, descriptor) -> {};

    public NativeHost(Object host, String version, Runnable check) {
        this(host, version, check, HOSTS, SharedData::read);
    }

    NativeHost(Object host, String version, Runnable check, JsonArray hosts, Function<String, JsonObject> tables) {
        hostVersion = version;
        loader = host.getClass().getClassLoader();
        Access binding = access();
        try {
            check.run();
            JsonObject target = null;
            for (JsonElement entry : hosts)
                if (entry.getAsJsonObject().get("version").getAsString().equals(version))
                    target = entry.getAsJsonObject();
            if (target == null)
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "Native access requires exactly Bitwig " + SUPPORTED + "; running " + version + ".");
            expectedHash = target.get("jarSha256").getAsString();
            hostJar = source(host.getClass());
            if (!Files.isRegularFile(hostJar))
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Loaded host CodeSource is not a regular JAR file.");
            actualHash = hash(hostJar, check);
            if (!expectedHash.equals(actualHash))
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "Loaded host JAR SHA-256 is not the inspected " + version
                                + " binary; obtain a matching adapter, do not bypass this gate.");
            JsonObject names = tables.apply(target.get("names").getAsString());
            classes = names.getAsJsonObject("classes");
            members = names.getAsJsonObject("members");
            singleton = binding.exactMethod("app.singleton", binding.type("app"), true);
            async = binding.exactMethod("app.asyncExec", void.class, false, Runnable.class);
            isDocumentThread = binding.exactMethod("app.isDocumentThread", boolean.class, false);
            modelThread = binding.exactMethod("model.executor.isModelThread", boolean.class, false);
            check.run();
        } catch (RuntimeException | ReflectiveOperationException | java.io.IOException error) {
            if (error instanceof RpcException && !((RpcException) error).is(Reason.NATIVE_UNAVAILABLE))
                throw (RpcException) error;
            // A gate refusal already explains itself; other failures need their type to mean anything.
            gateError = error instanceof RpcException ? error.getMessage() : Errors.diagnostic(error);
        } catch (LinkageError error) {
            gateError = Errors.diagnostic(error);
        }
        if (gateError != null) return;
        try {
            executor = binding.exactMethod("model.object.executor", binding.type("model.executor"), false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            modelThreadError = Errors.diagnostic(error);
        }
        check.run();
    }

    void onBound(BiConsumer<String, String> observer) {
        bound = observer;
    }

    public Access access() {
        return new Access();
    }

    public final class Access {
        private Access() {}

        String hostVersion() {
            return hostVersion;
        }

        String actualHash() {
            return actualHash;
        }

        /** Which host binary answered: the result's nativeVersion and nativeJarSha256. */
        public void provenance(JsonObject out) {
            out.addProperty("nativeVersion", hostVersion);
            out.addProperty("nativeJarSha256", actualHash);
        }

        public String gateError() {
            return gateError;
        }

        void requireAvailable() {
            if (gateError != null)
                throw Errors.notApplied(Reason.HOST_MISMATCH, "Native runtime unavailable: " + gateError);
        }

        String expectedHash() {
            return expectedHash;
        }

        /** The host class bound to {@code key} in this version's name table. */
        Class<?> type(String key) throws ClassNotFoundException {
            requireAvailable();
            return hostType(entry(classes, key).getAsString());
        }

        /** The host member name bound to {@code key}, for lookups the exact descriptors below do not cover. */
        String name(String key) {
            requireAvailable();
            return entry(members, key).getAsJsonObject().get("name").getAsString();
        }

        private Class<?> owner(String key) throws ClassNotFoundException {
            // Before entry(): a closed gate may never have loaded the members table.
            requireAvailable();
            return type(entry(members, key).getAsJsonObject().get("owner").getAsString());
        }

        /** A key this version's table lacks fails only the feature that uses it. */
        private JsonElement entry(JsonObject table, String key) {
            JsonElement entry = table.get(key);
            if (entry == null || entry.isJsonNull())
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "Bitwig " + hostVersion + " has no native binding for " + key + ".");
            return entry;
        }

        private Class<?> hostType(String name) throws ClassNotFoundException {
            Class<?> type = Class.forName(name, false, loader);
            try {
                if (!source(type).equals(hostJar))
                    throw Errors.notApplied(
                            Reason.NATIVE_UNAVAILABLE,
                            "Native binding class is not from the hashed loaded host JAR: " + name);
            } catch (java.io.IOException error) {
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Cannot establish native class CodeSource: " + name);
            }
            return type;
        }

        Method exactMethod(String key, Class<?> returns, boolean isStatic, Class<?>... args)
                throws ReflectiveOperationException {
            Class<?> owner = owner(key);
            String name = name(key);
            Method method = owner.getMethod(name, args);
            if (method.getReturnType() != returns
                    || Modifier.isStatic(method.getModifiers()) != isStatic
                    || method.isBridge()
                    || !Modifier.isPublic(method.getDeclaringClass().getModifiers()))
                throw new NoSuchMethodException("Exact public descriptor mismatch: " + key);
            if (hostType(method.getDeclaringClass().getName()) != method.getDeclaringClass())
                throw new NoSuchMethodException("Native descriptor class differs from the loaded host binding.");
            bound.accept(key, MethodType.methodType(returns, args).toMethodDescriptorString());
            return method;
        }

        /** A public constructor of the public host class {@code key} with exactly these parameter types. */
        Constructor<?> exactConstructor(String key, Class<?>... args) throws ReflectiveOperationException {
            Class<?> owner = type(key);
            Constructor<?> constructor = owner.getConstructor(args);
            if (!Modifier.isPublic(owner.getModifiers()))
                throw new NoSuchMethodException("Exact public constructor mismatch: " + key);
            return constructor;
        }

        /** The value of a public static final field of a public host class, of exactly this type. */
        Object exactConstant(String key, Class<?> type) throws ReflectiveOperationException {
            Class<?> owner = owner(key);
            Field field = owner.getField(name(key));
            int modifiers = field.getModifiers();
            if (field.getType() != type
                    || field.getDeclaringClass() != owner
                    || !Modifier.isStatic(modifiers)
                    || !Modifier.isFinal(modifiers)
                    || !Modifier.isPublic(owner.getModifiers()))
                throw new NoSuchFieldException("Exact public constant mismatch: " + key);
            bound.accept(
                    key, MethodType.methodType(type).toMethodDescriptorString().substring(2));
            return field.get(null);
        }

        Object invoke(Method method, Object object, Object... args) {
            requireAvailable();
            return NativeHost.invoke(method, object, args);
        }

        Object construct(Constructor<?> constructor, Object... args) {
            requireAvailable();
            try {
                return constructor.newInstance(args);
            } catch (ReflectiveOperationException | IllegalArgumentException error) {
                Throwable cause = error instanceof InvocationTargetException
                        ? ((InvocationTargetException) error).getCause()
                        : error;
                RpcException translated = Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "Native construction failed at "
                                + constructor.getDeclaringClass().getName() + ": " + Errors.diagnostic(cause));
                translated.initCause(error);
                throw translated;
            }
        }

        void execute(Runnable work) {
            requireAvailable();
            Object app = invoke(singleton, null);
            if (app == null)
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "Native application singleton is not ready; wait for host startup.");
            invoke(async, app, work);
        }

        void documentThread() {
            requireAvailable();
            Object app = invoke(singleton, null);
            if (app == null || !Boolean.TRUE.equals(invoke(isDocumentThread, app)))
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "Native read was not dispatched on the native document thread.");
        }

        void requireModelThreadAvailable() {
            requireAvailable();
            if (modelThreadError != null)
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE, "Native model executor binding unavailable: " + modelThreadError);
        }

        void requireModelThread(Object object) {
            requireModelThreadAvailable();
            Object current = invoke(executor, object);
            // model.object.executor is a scoped executor predicate, not proof of model ancestry.
            if (current == null || !Boolean.TRUE.equals(invoke(modelThread, current)))
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "Native model thread predicate failed: "
                                + (current == null ? "null" : current.getClass().getName()));
        }
    }

    public static Object invoke(Method method, Object object, Object... args) {
        try {
            return method.invoke(object, args);
        } catch (ReflectiveOperationException | IllegalArgumentException error) {
            Throwable cause =
                    error instanceof InvocationTargetException ? ((InvocationTargetException) error).getCause() : error;
            RpcException translated = Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Native call failed at " + method.getDeclaringClass().getName() + "." + method.getName() + ": "
                            + Errors.diagnostic(cause));
            translated.initCause(error);
            throw translated;
        }
    }

    static Path source(Class<?> type) throws java.io.IOException {
        try {
            if (type.getProtectionDomain() == null || type.getProtectionDomain().getCodeSource() == null)
                throw new java.io.IOException("Loaded class has no CodeSource.");
            java.net.URI uri =
                    type.getProtectionDomain().getCodeSource().getLocation().toURI();
            if (!"file".equals(uri.getScheme()))
                throw new java.io.IOException("Loaded class CodeSource is not a file URI: " + uri);
            return Path.of(uri).toRealPath();
        } catch (java.net.URISyntaxException | SecurityException error) {
            throw new java.io.IOException("Cannot resolve loaded host CodeSource.", error);
        }
    }

    public static String hash(Path file, Runnable check) throws java.io.IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            try (InputStream stream = Files.newInputStream(file)) {
                int size;
                while ((size = stream.read(buffer)) != -1) {
                    check.run();
                    digest.update(buffer, 0, size);
                }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
