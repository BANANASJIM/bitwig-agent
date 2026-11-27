package dev.bitwigagent.nativehost;

import com.google.gson.*;
import dev.bitwigagent.BitwigAgentExtension;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.grid.GridInsertionBridge;
import dev.bitwigagent.grid.GridInsertionWriter;
import java.io.File;
import java.lang.reflect.*;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;

/** Only evidenced public native APIs. Binding inspection never constructs an adapter or resolves a file. */
final class NativeGridInsertionAccess implements GridInsertionWriter.Access {
    private final NativeHost.Access bindings;
    private final Constructor<?> constructor;
    private final Method singleton, resource, modules, parent, deleted, owner, listSize, place, success, rejection;
    private final Class<?> convertedType, completionType;
    private final JsonObject evidence = new JsonObject();

    NativeGridInsertionAccess(NativeHost.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        ClassLoader extension = BitwigAgentExtension.class.getClassLoader();
        if (NativeGridAdapter.class.getClassLoader() != extension
                || GridInsertionBridge.class.getClassLoader() != extension
                || GridInsertionBridge.Callback.class.getClassLoader() != extension)
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Insertion public boundary differs from the running extension defining loader.");
        Class<?> base = identical("grid.insertion", extension), policy = identical("grid.loadPolicy", extension);
        identical("project", extension);
        Class<?> conversionContext = identical("grid.conversionContext", extension);
        Class<?> module = identical("grid.module", extension);
        convertedType = identical("grid.loadResult", extension);
        completionType = identical("async.completion", extension);
        Class<?> adapter = Class.forName(
                "BitwigAgentGridInsertion_" + bindings.hostVersion().replace('.', '_'), false, extension);
        if (adapter.getClassLoader() != extension
                || adapter.getSuperclass() != base
                || base.getSuperclass() != policy
                || !GridInsertionBridge.class.isAssignableFrom(adapter)
                || !Modifier.isPublic(adapter.getModifiers())
                || Modifier.isAbstract(adapter.getModifiers()))
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Native insertion adapter superclass/interface/defining-loader identity mismatch.");
        String extensionSource = origin(GridInsertionBridge.class);
        if (!origin(adapter).equals(extensionSource)
                || !origin(GridInsertionBridge.Callback.class).equals(extensionSource)
                || !origin(BitwigAgentExtension.class).equals(extensionSource)
                || !origin(NativeGridAdapter.class).equals(extensionSource))
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Native insertion adapter and public boundary do not share the loaded extension origin.");
        constructor = adapter.getConstructor(GridInsertionBridge.Callback.class);
        bindings.exactConstructor("grid.insertion", completionType);
        Method callback =
                adapter.getDeclaredMethod(bindings.name("grid.insertion.deliver"), convertedType, completionType);
        if (!Modifier.isPublic(callback.getModifiers())
                || Modifier.isStatic(callback.getModifiers())
                || callback.getReturnType() != void.class
                || callback.isBridge())
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Native insertion delivered-callback descriptor mismatch.");
        bindings.exactMethod("project.load", void.class, false, String[].class, policy);
        requireConversionBridge(adapter, bindings.name("grid.insertion.convertBridge"), conversionContext);
        requireConversionBridge(adapter, bindings.name("grid.insertion.convertAlternateBridge"), conversionContext);
        bindings.exactMethod("grid.insertion.convert", module, false, Object.class, conversionContext);
        bindings.exactMethod("grid.insertion.convertAlternate", module, false, Object.class, conversionContext);
        evidence.addProperty("adapterClass", adapter.getName());
        evidence.addProperty("adapterCodeSource", origin(adapter));
        evidence.addProperty("interfaceCodeSource", extensionSource);
        evidence.addProperty("superclassIdentity", true);
        evidence.addProperty("interfaceIdentity", true);
        evidence.addProperty("constructorIdentity", true);
        evidence.addProperty("callbackIdentity", true);
        evidence.addProperty("conversionBridgeIdentity", true);
        evidence.addProperty("runtimeConversionVerified", false);
        evidence.addProperty("extensionLoader", loaderId(extension));
        evidence.addProperty("extensionParentLoader", loaderId(extension.getParent()));
        evidence.addProperty(
                "inspection",
                "Class.forName_false_public_signatures_and_actual_Class_identity_no_construction_or_native_calls");
        evidence.addProperty("constructorInvokedDuringInspection", false);
        evidence.addProperty("nativeLoadInvokedDuringInspection", false);
        evidence.addProperty("runtimeInsertionVerified", false);
        singleton = bindings.exactMethod("app.singleton", bindings.type("app"), true);
        resource = bindings.exactMethod("app.resource", File.class, false, UUID.class);
        modules = bindings.exactMethod("grid.loadResult.modules", List.class, false);
        parent = bindings.exactMethod("model.node.parent", bindings.type("model.node"), false);
        deleted = bindings.exactMethod("model.node.deleted", boolean.class, false);
        owner = bindings.exactMethod("device.ownedComponent.owner", bindings.type("device.componentList"), false);
        listSize = bindings.exactMethod("device.listBase.pendingSize", int.class, false);
        place = bindings.exactMethod(
                "grid.patchGraph.place",
                void.class,
                false,
                Collection.class,
                int.class,
                int.class,
                boolean.class,
                completionType);
        success = bindings.exactMethod("async.completion.succeed", void.class, false, Object.class);
        rejection = bindings.exactMethod("async.completion.fail", void.class, false, Exception.class);
    }

    private static void requireConversionBridge(Class<?> adapter, String name, Class<?> context)
            throws NoSuchMethodException {
        Method bridge = adapter.getDeclaredMethod(name, Object.class, context);
        if (!Modifier.isPublic(bridge.getModifiers())
                || Modifier.isStatic(bridge.getModifiers())
                || bridge.getReturnType() != Object.class
                || bridge.isBridge())
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Native insertion conversion bridge descriptor mismatch: " + name);
    }

    private Class<?> identical(String key, ClassLoader extension) throws ClassNotFoundException {
        Class<?> host = bindings.type(key), child = Class.forName(host.getName(), false, extension);
        if (host != child
                || !Modifier.isPublic(host.getModifiers())
                || !host.getModule().isExported(host.getPackageName(), GridInsertionBridge.class.getModule()))
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Native insertion Class identity/public access mismatch: " + key);
        JsonObject row = new JsonObject();
        row.addProperty("sameClass", true);
        row.addProperty("codeSource", origin(host));
        row.addProperty("definingLoader", loaderId(host.getClassLoader()));
        evidence.add(key, row);
        return host;
    }

    private static String origin(Class<?> type) {
        try {
            ProtectionDomain domain = type.getProtectionDomain();
            CodeSource source = domain == null ? null : domain.getCodeSource();
            URL location = source == null ? null : source.getLocation();
            if (location == null)
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "Loaded insertion class has no CodeSource location: " + type.getName());
            // Compare the loaded archive origin, not a filesystem path; URL locations need not be URI-escaped.
            return location.toExternalForm();
        } catch (SecurityException error) {
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Insertion binding CodeSource access denied: " + type.getName());
        }
    }

    private static String loaderId(ClassLoader loader) {
        return loader == null
                ? "bootstrap"
                : loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    JsonObject evidence() {
        return evidence.deepCopy();
    }

    @Override
    public String resolve(UUID typeId) {
        Object app = bindings.invoke(singleton, null);
        if (app == null)
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native application singleton is unavailable.");
        Object file = bindings.invoke(resource, app, typeId);
        if (!(file instanceof File) || !((File) file).isFile())
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Known MODULE catalog resource is unavailable through the native resolver.");
        return ((File) file).getAbsolutePath();
    }

    @Override
    public Runnable prepareLoad(Object project, String path, GridInsertionBridge.Callback callback) {
        try {
            GridInsertionBridge bridge = (GridInsertionBridge) constructor.newInstance(callback);
            return () -> bridge.load(project, path);
        } catch (ReflectiveOperationException error) {
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Native insertion adapter construction failed: "
                            + error.getClass().getSimpleName());
        }
    }

    @Override
    public List<?> converted(Object result) {
        if (!convertedType.isInstance(result))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native loader delivered an unexpected result type.");
        return (List<?>) bindings.invoke(modules, result);
    }

    @Override
    public void requireDetached(Object module) {
        if (Boolean.TRUE.equals(bindings.invoke(deleted, module))
                || bindings.invoke(parent, module) != null
                || bindings.invoke(owner, module) != null)
            throw Errors.notApplied(
                    Reason.TARGET_CHANGED, "Loaded module is deleted, parented or owned; refusing placement.");
    }

    @Override
    public void requirePlacementReady(Object graph) {
        if (!Integer.valueOf(0).equals(bindings.invoke(listSize, graph)))
            throw Errors.notApplied(
                    Reason.UNSAFE_STATE,
                    "Native graph pending list is nonempty; collection placement prerequisite failed.");
    }

    @Override
    public void place(Object graph, List<?> loaded, int x, int y) {
        bindings.invoke(place, graph, loaded, x, y, false, null);
    }

    @Override
    public void reject(Object completion, Exception error) {
        requireCompletion(completion);
        bindings.invoke(rejection, completion, error);
    }

    @Override
    public void succeed(Object completion, List<?> loaded) {
        requireCompletion(completion);
        bindings.invoke(success, completion, loaded);
    }

    private void requireCompletion(Object completion) {
        if (!completionType.isInstance(completion))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native loader did not deliver its cleanup completion.");
    }

    @Override
    public void execute(Runnable event) {
        bindings.execute(event);
    }
}
