package dev.bitwigagent.nativehost;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.launcher.ClipCommands;
import java.lang.reflect.Method;

/**
 * Exact bindings for Bitwig's clip commands (H1 route A): the kind of a launcher clip, and the main window's keyboard
 * focus.
 *
 * <p>An Action runs on the selection of the panel holding keyboard focus: {@code ActionImpl.doInvoke} starts from the
 * main window's deepest focused widget ({@code ui.windowRoot.focusedWidget}) and walks its parents to a handler. The
 * Clip Launcher is a sub-widget of the Arrange panel ({@code ui.mainView.panel} of the arranger panel kind, then
 * {@code ui.arrangerPanel.widget} and {@code ui.arrangerWidget.launcher}); {@code ui.widget.requestFocus} moves focus
 * to it synchronously without changing selection, showing a panel or adding an Undo step (H1 probe, 6.1.7, 20/20). It
 * is silently ineffective while the widget is not shown, so focus is read back afterwards: the launcher's own focus
 * state ({@code ui.focusable.focusState}, an enum whose constant names are kept) and the deepest focused widget, which
 * a modal dialog keeps.
 */
public final class NativeClipCommandAccess implements ClipCommands.Access {
    private static final String FOCUSED = "FOCUSED";
    private final NativeHost.Access a;
    private final NativeLauncherClip launcher;
    private final Class<?> instrumentClip, audioClip, arrangerPanel, arrangerKind;
    private final Method application, mainWindow, view, panel, arrangerWidget, launcherWidget;
    private final Method requestFocus, focusState, focusedWidget, isInside;

    public NativeClipCommandAccess(NativeHost.Access a) throws ReflectiveOperationException {
        this.a = a;
        launcher = new NativeLauncherClip(a);
        instrumentClip = a.type("clip.instrument");
        audioClip = a.type("clip.audio");
        arrangerPanel = a.type("ui.arrangerPanel");
        arrangerKind = a.type("ui.arrangerPanelKind");
        Class<?> widget = a.type("ui.widget");
        application = a.exactMethod("application.singleton", a.type("application"), true);
        mainWindow = a.exactMethod("application.mainWindow", a.type("ui.mainWindow"), false);
        view = a.exactMethod("ui.mainWindow.view", a.type("ui.mainView"), false);
        panel = a.exactMethod("ui.mainView.panel", a.type("ui.panel"), false, Class.class);
        arrangerWidget = a.exactMethod("ui.arrangerPanel.widget", a.type("ui.arrangerWidget"), false);
        launcherWidget = a.exactMethod("ui.arrangerWidget.launcher", a.type("ui.launcherWidget"), false);
        requestFocus = a.exactMethod("ui.widget.requestFocus", void.class, false);
        focusState = a.exactMethod("ui.focusable.focusState", a.type("ui.focusState"), false);
        focusedWidget = a.exactMethod("ui.windowRoot.focusedWidget", widget, false);
        isInside = a.exactMethod("ui.widget.isInside", boolean.class, false, widget);
    }

    @Override
    public void execute(Runnable work) {
        a.execute(work);
    }

    @Override
    public ClipCommands.Kind kind(Object slot, String trackId, String expectedName, Runnable check) {
        Object clip = launcher.clip(slot, trackId, expectedName, check).clip;
        if (instrumentClip.isInstance(clip)) return ClipCommands.Kind.NOTES;
        if (audioClip.isInstance(clip)) return ClipCommands.Kind.AUDIO;
        throw Errors.notApplied(Reason.UNSUPPORTED_SCOPE, "The launcher clip is neither a note nor an audio clip.");
    }

    @Override
    public boolean focusLauncher(Runnable check) {
        a.documentThread();
        check.run();
        Object window = a.invoke(mainWindow, a.invoke(application, null));
        // Null while the main window shows no Arrange panel (another panel layout).
        Object arranger = a.invoke(panel, a.invoke(view, window), arrangerKind);
        if (!arrangerPanel.isInstance(arranger)) return false;
        Object clipLauncher = a.invoke(launcherWidget, a.invoke(arrangerWidget, arranger));
        a.invoke(requestFocus, clipLauncher);
        Object focused = a.invoke(focusedWidget, window);
        return FOCUSED.equals(((Enum<?>) a.invoke(focusState, clipLauncher)).name())
                && (focused == clipLauncher
                        || focused != null && Boolean.TRUE.equals(a.invoke(isInside, focused, clipLauncher)));
    }
}
