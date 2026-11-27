package dev.bitwigagent.nativehost;

import dev.bitwigagent.launcher.ClipCommands;
import java.lang.reflect.Method;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;

/**
 * Exact bindings for Bitwig's clip commands (H1 route A): the kind of a launcher clip, and the main window's keyboard
 * focus: read, moved to the Clip Launcher, and moved back.
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
        return instrumentClip.isInstance(clip)
                ? ClipCommands.Kind.NOTES
                : audioClip.isInstance(clip) ? ClipCommands.Kind.AUDIO : null;
    }

    @Override
    public Object focused(Runnable check) {
        a.documentThread();
        check.run();
        return follow(window(), w -> a.invoke(focusedWidget, w));
    }

    @Override
    public boolean focusLauncher(Runnable check) {
        a.documentThread();
        check.run();
        Object window = window();
        // Every link can be null: no Arrange panel in the layout, or (6.1.4) a launcher field not bound yet.
        Object clipLauncher = follow(
                window,
                w -> a.invoke(view, w),
                v -> a.invoke(panel, v, arrangerKind),
                p -> arrangerPanel.isInstance(p) ? a.invoke(arrangerWidget, p) : null,
                w -> a.invoke(launcherWidget, w));
        return clipLauncher != null && focus(clipLauncher, window);
    }

    @Override
    public boolean refocus(Object widget, Runnable check) {
        a.documentThread();
        check.run();
        return widget != null && focus(widget, window());
    }

    private Object window() {
        return follow(a.invoke(application, null), app -> a.invoke(mainWindow, app));
    }

    /** Requests focus for {@code widget} and reads it back. */
    private boolean focus(Object widget, Object window) {
        a.invoke(requestFocus, widget);
        Object focused = follow(window, w -> a.invoke(focusedWidget, w));
        return holds(
                widget, a.invoke(focusState, widget), focused, (f, w) -> Boolean.TRUE.equals(a.invoke(isInside, f, w)));
    }

    /** {@code start} through each link in turn; null as soon as a link gives null, without calling the rest. */
    @SafeVarargs
    static Object follow(Object start, UnaryOperator<Object>... links) {
        Object at = start;
        for (UnaryOperator<Object> link : links) {
            if (at == null) return null;
            at = link.apply(at);
        }
        return at;
    }

    /**
     * Whether {@code widget} holds keyboard focus: its own focus state (an enum whose constant names are kept) is
     * FOCUSED and the window's deepest focused widget is it or inside it.
     */
    static boolean holds(Object widget, Object state, Object focused, BiPredicate<Object, Object> inside) {
        return state instanceof Enum
                && FOCUSED.equals(((Enum<?>) state).name())
                && (focused == widget || focused != null && inside.test(focused, widget));
    }
}
