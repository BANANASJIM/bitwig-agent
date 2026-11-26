package dev.bitwigagent.bitwig614;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.project.ProjectModule;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.Set;

/**
 * Exact 6.1.4 bindings for the Save Project pipeline without its file dialog: TL.cfM(aBF,File,boolean,boolean,lBQ)
 * is what the dialog's fileDialogClosed callback calls with the chosen file. Both flags false = do not collect
 * external samples (the dialog's default). The lBQ callback reports refusal (cfM(String)), failure (cfM(Exception))
 * or completion (taskCompleted()).
 */
public final class Bitwig614ProjectSaveAsAccess implements ProjectModule.SaveAsAccess {
    private static final Set<String> CALLBACK_METHODS =
            Set.of("taskCompleted()", "cfM(java.lang.Exception)", "cfM(java.lang.String)", "cfM(java.lang.Object)");
    private final Bitwig614Runtime.Access a;
    private final Class<?> projectType, projectProxyType, callbackType;
    private final Method application, target, window, save, projectsRoot, saving;

    /** The exact bindings, or native_unavailable when a descriptor does not match the loaded host. */
    public static Bitwig614ProjectSaveAsAccess bind(Bitwig614Runtime.Access a) {
        try {
            return new Bitwig614ProjectSaveAsAccess(a);
        } catch (ReflectiveOperationException | LinkageError error) {
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Exact project save bindings unavailable: " + Bitwig614Runtime.diagnostic(error));
        }
    }

    Bitwig614ProjectSaveAsAccess(Bitwig614Runtime.Access a) throws ReflectiveOperationException {
        this.a = a;
        Class<?> app = a.type("com.bitwig.flt.app.TL");
        application = a.exactMethod(app, "hIO", app, true);
        projectType = a.type("com.bitwig.flt.document.core.master.g2L");
        projectProxyType = a.type("com.bitwig.flt.control_surface.proxy.ProjectProxy");
        target = a.exactMethod(
                a.type("com.bitwig.flt.control_surface.proxy.ControlSurfaceProxy"), "getTarget", Object.class, false);
        window = a.exactMethod(app, "hUR", a.type("com.bitwig.flt.app.eD"), false, projectType);
        callbackType = a.type("com.bitwig.base.async.lBQ");
        if (!callbackType.isInterface() || !Modifier.isPublic(callbackType.getModifiers()))
            throw new NoSuchMethodException("Save callback type is not a public interface");
        for (Method method : callbackType.getMethods()) {
            if (!CALLBACK_METHODS.contains(signature(method)))
                throw new NoSuchMethodException("Unexpected save callback method: " + signature(method));
        }
        callbackType.getMethod("taskCompleted");
        callbackType.getMethod("cfM", Exception.class);
        callbackType.getMethod("cfM", String.class);
        save = a.exactMethod(
                app, "cfM", void.class, false, a.type("aBF"), File.class, boolean.class, boolean.class, callbackType);
        projectsRoot = a.exactMethod(app, "iW2", File.class, false);
        saving = a.exactMethod(a.type("com.bitwig.flt.document.core.iface.pHd"), "zpW", boolean.class, false);
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
                    switch (signature(method)) {
                        case "taskCompleted()":
                        case "cfM(java.lang.Object)":
                            done.completed();
                            return null;
                        case "cfM(java.lang.String)":
                            done.failed(String.valueOf(args[0]), true);
                            return null;
                        case "cfM(java.lang.Exception)":
                            Throwable error = (Throwable) args[0];
                            done.failed(error == null ? "unknown error" : Bitwig614Runtime.diagnostic(error), false);
                            return null;
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
