package dev.bitwigagent.bitwig614;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.host.NativeReadContext;
import java.lang.reflect.Method;

/** Exact 6.1.4 resolution of a launcher slot proxy to its native slot, clip and track; no cursor or GUI selection. */
final class Bitwig614LauncherClip {
    private static final String[] CLIP_LABELS = {"clipDeleted", "slotDeleted", "trackDeleted", "projectDeleted"},
            SLOT_LABELS = {"slotDeleted", "trackDeleted", "projectDeleted"};
    private final Bitwig614Runtime.Access a;
    private final NativeReadContext context;
    final Bitwig614TrackResolver resolver;
    final Class<?> event, slotType, projectType;
    final Method target, clip, slot, name;

    Bitwig614LauncherClip(Bitwig614Runtime.Access a) throws ReflectiveOperationException {
        this.a = a;
        context = new NativeReadContext(new Bitwig614ReadContextAccess(a));
        resolver = new Bitwig614TrackResolver(a);
        event = a.type("zZi");
        slotType = a.type("Us");
        projectType = a.type("com.bitwig.flt.document.core.master.g2L");
        Class<?> proxy = a.type("com.bitwig.flt.control_surface.proxy.ControlSurfaceProxy");
        target = a.exactMethod(proxy, "getTarget", Object.class, false);
        clip = a.exactMethod(
                a.type("com.bitwig.flt.control_surface.proxy.ClipLauncherSlotProxy"), "getClip", a.type("Ob"), false);
        slot = a.exactMethod(a.type("Ob"), "kFU", slotType, false);
        name = a.exactMethod(a.type("Ob"), "dJ_", String.class, false);
    }

    /** A resolved slot (and its clip, or null for an empty slot); {@link #guard} re-checks it against the proxy. */
    final class Bound {
        final Object proxy, slot, clip, track, project;
        private final Object[] models;
        private final String[] labels;
        private final NativeReadContext.Capture capture;
        private final Runnable check;

        private Bound(Object proxy, Object slot, Object clip, Object track, Runnable check) {
            this.proxy = proxy;
            this.slot = slot;
            this.clip = clip;
            this.track = track;
            this.check = check;
            project = resolver.owner(clip != null ? clip : slot, projectType);
            models = clip != null ? new Object[] {clip, slot, track} : new Object[] {slot, track};
            labels = clip != null ? CLIP_LABELS : SLOT_LABELS;
            capture = context.capture(project, models, labels, check, false);
            a.requireModelThread(clip != null ? clip : slot);
        }

        void guard() {
            check.run();
            a.requireModelThread(clip != null ? clip : slot);
            context.guard(capture, models, labels, check);
            Object current = a.invoke(Bitwig614LauncherClip.this.clip, proxy);
            if (a.invoke(target, proxy) != slot
                    || current != clip
                    || clip != null && a.invoke(Bitwig614LauncherClip.this.slot, clip) != slot
                    || resolver.slotTrack(slot) != track)
                throw Errors.notApplied(Reason.TARGET_CHANGED, "Launcher target changed during native operation.");
        }
    }

    /** The slot's clip, required to be named {@code expectedName}, on the channel track {@code trackId}. */
    Bound clip(Object proxy, String trackId, String expectedName, Runnable check) {
        a.documentThread();
        check.run();
        Object nativeSlot = a.invoke(target, proxy), value = a.invoke(clip, proxy);
        if (!slotType.isInstance(nativeSlot) || !event.isInstance(value) || a.invoke(slot, value) != nativeSlot)
            throw Errors.notApplied(
                    Reason.TARGET_CHANGED, "Launcher proxy does not resolve to one attached native clip slot.");
        Object track = resolver.require(resolver.slotTrack(nativeSlot), trackId, Bitwig614TrackResolver.Kind.CHANNEL);
        if (!expectedName.equals(a.invoke(name, value)))
            throw Errors.notApplied(Reason.TARGET_CHANGED, "Native clip name differs from expectedClipName.");
        Bound bound = new Bound(proxy, nativeSlot, value, track, check);
        bound.guard();
        return bound;
    }

    /** The empty slot behind the proxy, on the channel track {@code trackId}. */
    Bound empty(Object proxy, String trackId, Runnable check) {
        a.documentThread();
        check.run();
        Object nativeSlot = a.invoke(target, proxy);
        if (!slotType.isInstance(nativeSlot))
            throw Errors.notApplied(Reason.TARGET_CHANGED, "Launcher proxy does not resolve to a native clip slot.");
        if (a.invoke(clip, proxy) != null)
            throw Errors.notApplied(Reason.TARGET_EXISTS, "The native clip slot is not empty.");
        Object track = resolver.require(resolver.slotTrack(nativeSlot), trackId, Bitwig614TrackResolver.Kind.CHANNEL);
        Bound bound = new Bound(proxy, nativeSlot, null, track, check);
        bound.guard();
        return bound;
    }
}
