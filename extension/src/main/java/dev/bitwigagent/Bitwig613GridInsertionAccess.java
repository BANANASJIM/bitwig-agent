package dev.bitwigagent;

import com.google.gson.*;
import java.io.File;
import java.lang.reflect.*;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;
import static dev.bitwigagent.Bitwig613GridAdapter.invoke;
import static dev.bitwigagent.HostServices.failure;

/** Only evidenced public native APIs. Binding inspection never constructs an adapter or resolves a file. */
final class Bitwig613GridInsertionAccess implements GridInsertionWriter.Access {
    private static final String M = "com.bitwig.flt.document.core.master.", D = M + "device.", R = "com.bitwig.ramona.core.";
    private final Bitwig613GridAdapter bindings;
    private final Constructor<?> constructor;
    private final Method singleton, resource, modules, parent, deleted, owner, listSize, place, success, rejection;
    private final Class<?> convertedType, completionType;
    private final JsonObject evidence = new JsonObject();

    Bitwig613GridInsertionAccess(Bitwig613GridAdapter bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        ClassLoader extension = BitwigAgentExtension.class.getClassLoader();
        if (Bitwig613GridAdapter.class.getClassLoader() != extension || GridInsertionBridge.class.getClassLoader() != extension
                || GridInsertionBridge.Callback.class.getClassLoader() != extension)
            throw failure("grid_unavailable", "Insertion public boundary differs from the running extension defining loader.");
        Class<?> base = identical("jDf", extension), policy = identical("JTS", extension);
        Class<?> project = identical(M + "WN2", extension);
        Class<?> conversionContext = identical(M + "UhY", extension), module = identical(D + "gxR", extension);
        convertedType = identical(M + "CPg", extension);
        completionType = identical("com.bitwig.base.async.jJa", extension);
        Class<?> adapter = Class.forName("BitwigAgent613GridInsertion", false, extension);
        if (adapter.getClassLoader() != extension || adapter.getSuperclass() != base
                || base.getSuperclass() != policy || !GridInsertionBridge.class.isAssignableFrom(adapter)
                || !Modifier.isPublic(adapter.getModifiers()) || Modifier.isAbstract(adapter.getModifiers()))
            throw failure("grid_unavailable", "Native insertion adapter superclass/interface/defining-loader identity mismatch.");
        String extensionSource = origin(GridInsertionBridge.class);
        if (!origin(adapter).equals(extensionSource) || !origin(GridInsertionBridge.Callback.class).equals(extensionSource)
                || !origin(BitwigAgentExtension.class).equals(extensionSource) || !origin(Bitwig613GridAdapter.class).equals(extensionSource))
            throw failure("grid_unavailable", "Native insertion adapter and public boundary do not share the loaded extension origin.");
        constructor = adapter.getConstructor(GridInsertionBridge.Callback.class);
        base.getConstructor(completionType);
        Method callback = adapter.getDeclaredMethod("vhT", convertedType, completionType);
        if (!Modifier.isPublic(callback.getModifiers()) || Modifier.isStatic(callback.getModifiers())
                || callback.getReturnType() != void.class || callback.isBridge())
            throw failure("grid_unavailable", "Native insertion delivered-callback descriptor mismatch.");
        bindings.exactMethod(project, "vhT", void.class, false, String[].class, policy);
        requireConversionBridge(adapter, "vhT", conversionContext);
        requireConversionBridge(adapter, "HEY", conversionContext);
        bindings.exactMethod(base, "E3I", module, false, Object.class, conversionContext);
        bindings.exactMethod(base, "kpO", module, false, Object.class, conversionContext);
        evidence.addProperty("adapterClass", adapter.getName());
        evidence.addProperty("adapterCodeSource", origin(adapter));
        evidence.addProperty("interfaceCodeSource", extensionSource);
        evidence.addProperty("superclassIdentity", true); evidence.addProperty("interfaceIdentity", true);
        evidence.addProperty("constructorIdentity", true); evidence.addProperty("callbackIdentity", true);
        evidence.addProperty("conversionBridgeIdentity", true);
        evidence.addProperty("runtimeConversionVerified", false);
        evidence.addProperty("extensionLoader", loaderId(extension));
        evidence.addProperty("extensionParentLoader", loaderId(extension.getParent()));
        evidence.addProperty("inspection", "Class.forName_false_public_signatures_and_actual_Class_identity_no_construction_or_native_calls");
        evidence.addProperty("constructorInvokedDuringInspection", false);
        evidence.addProperty("nativeLoadInvokedDuringInspection", false);
        evidence.addProperty("runtimeInsertionVerified", false);
        Class<?> app = bindings.type("com.bitwig.flt.app.tcc");
        singleton = bindings.exactMethod(app, "Kvg", app, true);
        resource = bindings.exactMethod(app, "vhT", File.class, false, UUID.class);
        modules = bindings.exactMethod(convertedType, "kpO", List.class, false);
        parent = bindings.exactMethod(bindings.type(R + "xKS"), "M_", bindings.type(R + "xKS"), false);
        deleted = bindings.exactMethod(bindings.type(R + "xKS"), "N_", boolean.class, false);
        owner = bindings.exactMethod(bindings.type(D + "urR"), "OOK", bindings.type(D + "sF"), false);
        listSize = bindings.exactMethod(bindings.type(D + "jhe"), "XEl", int.class, false);
        place = bindings.exactMethod(bindings.type(D + "zXc"), "vhT", void.class, false,
                Collection.class, int.class, int.class, boolean.class, completionType);
        success = bindings.exactMethod(completionType, "vhT", void.class, false, Object.class);
        rejection = bindings.exactMethod(completionType, "vhT", void.class, false, Exception.class);
    }
    private static void requireConversionBridge(Class<?> adapter, String name, Class<?> context) throws NoSuchMethodException {
        Method bridge = adapter.getDeclaredMethod(name, Object.class, context);
        if (!Modifier.isPublic(bridge.getModifiers()) || Modifier.isStatic(bridge.getModifiers())
                || bridge.getReturnType() != Object.class || bridge.isBridge())
            throw failure("grid_unavailable", "Native insertion conversion bridge descriptor mismatch: " + name);
    }
    private Class<?> identical(String name, ClassLoader extension) throws ClassNotFoundException {
        Class<?> host = bindings.type(name), child = Class.forName(name, false, extension);
        if (host != child || !Modifier.isPublic(host.getModifiers())
                || !host.getModule().isExported(host.getPackageName(), GridInsertionBridge.class.getModule()))
            throw failure("grid_unavailable", "Native insertion Class identity/public access mismatch: " + name);
        JsonObject row = new JsonObject(); row.addProperty("sameClass", true);
        row.addProperty("codeSource", origin(host)); row.addProperty("definingLoader", loaderId(host.getClassLoader()));
        evidence.add(name, row); return host;
    }
    private static String origin(Class<?> type) {
        try {
            ProtectionDomain domain = type.getProtectionDomain();
            CodeSource source = domain == null ? null : domain.getCodeSource();
            URL location = source == null ? null : source.getLocation();
            if (location == null) throw failure("grid_unavailable", "Loaded insertion class has no CodeSource location: " + type.getName());
            // Compare the loaded archive origin, not a filesystem path; URL locations need not be URI-escaped.
            return location.toExternalForm();
        } catch (SecurityException error) {
            throw failure("grid_unavailable", "Insertion binding CodeSource access denied: " + type.getName());
        }
    }
    private static String loaderId(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
    }
    JsonObject evidence() { return evidence.deepCopy(); }
    @Override public String resolve(UUID typeId) {
        Object app = invoke(singleton, null);
        if (app == null) throw failure("grid_unavailable", "Native application singleton is unavailable.");
        Object file = invoke(resource, app, typeId);
        if (!(file instanceof File) || !((File) file).isFile())
            throw failure("grid_unavailable", "Known MODULE catalog resource is unavailable through the native resolver.");
        return ((File) file).getAbsolutePath();
    }
    @Override public Runnable prepareLoad(Object project, String path, GridInsertionBridge.Callback callback) {
        try {
            GridInsertionBridge bridge = (GridInsertionBridge) constructor.newInstance(callback);
            return () -> bridge.load(project, path);
        } catch (ReflectiveOperationException error) {
            throw failure("grid_unavailable", "Native insertion adapter construction failed: " + error.getClass().getSimpleName());
        }
    }
    @Override public List<?> converted(Object result) {
        if (!convertedType.isInstance(result)) throw failure("grid_unavailable", "Native loader delivered an unexpected result type.");
        return (List<?>) invoke(modules, result);
    }
    @Override public void requireDetached(Object module) {
        if (Boolean.TRUE.equals(invoke(deleted, module)) || invoke(parent, module) != null || invoke(owner, module) != null)
            throw failure("stale_context", "Loaded module is deleted, parented or owned; refusing placement.");
    }
    @Override public void requirePlacementReady(Object graph) {
        if (!Integer.valueOf(0).equals(invoke(listSize, graph)))
            throw failure("unsafe_state", "Native graph XEl list is nonempty; collection placement prerequisite failed.");
    }
    @Override public void place(Object graph, List<?> loaded, int x, int y) { invoke(place, graph, loaded, x, y, false, null); }
    @Override public void reject(Object completion, Exception error) {
        requireCompletion(completion); invoke(rejection, completion, error);
    }
    @Override public void succeed(Object completion, List<?> loaded) {
        requireCompletion(completion); invoke(success, completion, loaded);
    }
    private void requireCompletion(Object completion) {
        if (!completionType.isInstance(completion)) throw failure("grid_unavailable", "Native loader did not deliver its cleanup completion.");
    }
    @Override public void execute(Runnable event) { bindings.execute(event); }
}
