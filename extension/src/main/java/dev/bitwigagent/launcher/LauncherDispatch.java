package dev.bitwigagent.launcher;

import com.bitwig.extension.controller.api.Application;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import java.util.List;

/**
 * How a clip command reaches Bitwig (H1 route A): keyboard focus is moved to the Clip Launcher on the native document
 * thread and read back, then each action is invoked; Bitwig runs an action on the selection of the focused panel, the
 * launcher cursor clip. Focus not observed on the launcher is {@code not_applied} with nothing sent. The readback and
 * the invoke are one controller-thread hop apart, so a focus change by the user in between is not seen (the action then
 * runs on the panel focused at that moment). Route B′ (dispatching from the launcher widget, focus unchanged) is not
 * probed on a host yet; it would replace this class.
 */
final class LauncherDispatch {
    private final LauncherState state;
    private final Application application;

    LauncherDispatch(LauncherState state, Application application) {
        this.state = state;
        this.application = application;
    }

    static RpcException notFocused() {
        return Errors.notApplied(
                Reason.NOT_READY,
                "Keyboard focus did not reach the Clip Launcher; nothing was sent. Show the Clip Launcher in the "
                        + "Arrange view and close any dialog, then retry.");
    }

    /** Focuses the launcher, then invokes {@code actions} on the target's selected clip and runs {@code dispatched}. */
    void dispatch(
            HostOperation op,
            LauncherState.Target target,
            ClipCommands.Access access,
            List<String> actions,
            Runnable dispatched) {
        Runnable check = state.nativeCheck(op, target);
        state.scheduler.afterNative(op, access::execute, () -> access.focusLauncher(check), focused -> {
            if (!focused) throw notFocused();
            state.cursorGuard(op, target);
            op.markDispatched();
            for (String action : actions) application.getAction(action).invoke();
            dispatched.run();
        });
    }
}
