package dev.bitwigagent.nativehost;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.project.ProjectModule;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.Set;

/**
 * Exact bindings for the Save Project pipeline without its file dialog: app.saveProject(window, File, boolean, boolean,
 * task) is what the dialog's fileDialogClosed callback calls with the chosen file. Both flags false = do not collect
 * external samples (the dialog's default). The task callback reports refusal (async.task.refused(String)), failure
 * (async.task.failed(Exception)) or completion (async.task.completed() or async.task.succeeded(Object)).
 */
public final class NativeProjectSaveAsAccess implements ProjectModule.SaveAsAccess {
    private final NativeHost.Access a;
    private final String completed, failed, refused, succeeded;
    private final Class<?> projectType, projectProxyType, callbackType;
    private final Method application, target, window, save, projectsRoot, saving;

    /** The exact bindings, or native_unavailable when a descriptor does not match the loaded host. */
    public static NativeProjectSaveAsAccess bind(NativeHost.Access a) {
        try {
            return new NativeProjectSaveAsAccess(a);
        } catch (ReflectiveOperationException | LinkageError error) {
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Exact project save bindings unavailable: " + NativeHost.diagnostic(error));
        }
    }

    NativeProjectSaveAsAccess(NativeHost.Access a) throws ReflectiveOperationException {
        this.a = a;
        Class<?> app = a.type("app");
        application = a.exactMethod("app.singleton", app, true);
        projectType = a.type("project");
        projectProxyType = a.type("proxy.project");
        target = a.exactMethod("proxy.controlSurface.target", Object.class, false);
        window = a.exactMethod("app.documentWindow", a.type("app.projectWindow"), false, projectType);
        callbackType = a.type("async.task");
        if (!callbackType.isInterface() || !Modifier.isPublic(callbackType.getModifiers()))
            throw new NoSuchMethodException("Save callback type is not a public interface");
        completed = a.name("async.task.completed") + "()";
        failed = a.name("async.task.failed") + "(java.lang.Exception)";
        refused = a.name("async.task.refused") + "(java.lang.String)";
        succeeded = a.name("async.task.succeeded") + "(java.lang.Object)";
        Set<String> callbackMethods = Set.of(completed, failed, refused, succeeded);
        for (Method method : callbackType.getMethods()) {
            if (!callbackMethods.contains(signature(method)))
                throw new NoSuchMethodException("Unexpected save callback method: " + signature(method));
        }
        callbackType.getMethod(a.name("async.task.completed"));
        callbackType.getMethod(a.name("async.task.failed"), Exception.class);
        callbackType.getMethod(a.name("async.task.refused"), String.class);
        save = a.exactMethod(
                "app.saveProject",
                void.class,
                false,
                a.type("ui.window"),
                File.class,
                boolean.class,
                boolean.class,
                callbackType);
        projectsRoot = a.exactMethod("app.projectsRoot", File.class, false);
        saving = a.exactMethod("document.saving", boolean.class, false);
    }

    private static String signature(Method method) {
        StringBuilder text = new StringBuilder(method.getName()).append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) text.append(i == 0 ? "" : ",").append(types[i].getName());
        return text.append(')').toString();
    }

    @Override
    public void execute(Runnable work) {
        a.execute(work);
    }

    @Override
    public String nativeJarSha256() {
        return a.actualHash();
    }

    @Override
    public String nativeVersion() {
        return a.hostVersion();
    }

    private Object app() {
        Object app = a.invoke(application, null);
        if (app == null) throw Errors.notApplied(Reason.NOT_READY, "Native application is not ready.");
        return app;
    }

    @Override
    public Path projectsRoot() {
        a.documentThread();
        File root = (File) a.invoke(projectsRoot, app());
        return root == null ? null : root.toPath();
    }

    @Override
    public void saveAs(Object projectProxy, Path file, Runnable beforeDispatch, ProjectModule.Completion done) {
        a.documentThread();
        if (!projectProxyType.isInstance(projectProxy))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Controller Project is not the native project proxy.");
        Object project = a.invoke(target, projectProxy);
        if (!projectType.isInstance(project))
            throw Errors.notApplied(Reason.NOT_READY, "Controller Project proxy is not bound to an open project.");
        Object app = app(), documentWindow = a.invoke(window, app, project);
        if (documentWindow == null)
            throw Errors.notApplied(Reason.NOT_READY, "The current project has no document window.");
        if (Boolean.TRUE.equals(a.invoke(saving, project)))
            throw Errors.notApplied(Reason.BUSY, "Bitwig is already saving this project.");
        Object callback = Proxy.newProxyInstance(
                callbackType.getClassLoader(), new Class<?>[] {callbackType}, (proxy, method, args) -> {
                    String signature = signature(method);
                    if (signature.equals(completed) || signature.equals(succeeded)) {
                        done.completed();
                        return null;
                    }
                    if (signature.equals(refused)) {
                        done.failed(String.valueOf(args[0]), true);
                        return null;
                    }
                    if (signature.equals(failed)) {
                        Throwable error = (Throwable) args[0];
                        done.failed(error == null ? "unknown error" : NativeHost.diagnostic(error), false);
                        return null;
                    }
                    switch (signature) {
                        case "hashCode()":
                            return System.identityHashCode(proxy);
                        case "equals(java.lang.Object)":
                            return proxy == args[0];
                        case "toString()":
                            return "bitwig-agent project.saveAs callback";
                        default:
                            throw new UnsupportedOperationException(method.toString());
                    }
                });
        beforeDispatch.run();
        a.invoke(save, app, documentWindow, file.toFile(), false, false, callback);
    }
}
