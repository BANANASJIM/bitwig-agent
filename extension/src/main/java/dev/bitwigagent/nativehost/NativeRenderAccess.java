package dev.bitwigagent.nativehost;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.render.RenderModule;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Exact bindings for the two render jobs, entered where Bitwig's own UI enters them:
 * <ul>
 * <li>Export Audio: {@code render.exporter.export(tracks, basePaths, wav, flac, flacDepth, mp3, mp3Q, vorbis, vorbisQ,
 * opus, opusQ, start, end, tap, wavDepth, dither, realtime, sampleRate)}, the call the export dialog makes after
 * building its paths. The flags map to the export_wav/flac/mp3/vorbis/opus, dither and real_time preferences;
 * sampleRate 0 = current.</li>
 * <li>Bounce in Place: static {@code render.bouncer.bounce(project, tracks, start, end, tap)}, which builds a
 * bounce job that replaces the range on each track's main lane with the bounced audio in one undoable action.</li>
 * </ul>
 * Both create a render job that registers itself as {@code project.renderJob()} synchronously when nothing is wrong,
 * and count down {@code render.job.latch()} when finished (also on failure). Refused first, as Bitwig would refuse
 * silently or ask: {@code project.renderBlocker()} reasons, {@code !project.engineActive()} (silent return), a job
 * already running, a remote-session project ({@code project.remote()}), and missing files/plugins (a confirmation
 * dialog in the render job).
 */
public final class NativeRenderAccess implements RenderModule.Access {
    private final NativeHost.Access a;
    private final NativeTrackResolver resolver;
    private final Class<?> projectType, projectProxyType, jobType, exportJob, bounceJob, hostedDevice;
    private final Method target, exportCall, bounceCall, current, engineActive, remote, blocked;
    private final Method missingFiles, missingCount, devices, chains, chainDevices, deviceState, root, latch;
    private final Object pluginMissing;
    private final Map<String, Object> taps = new HashMap<>(), wavDepths = new HashMap<>(), flacDepths = new HashMap<>();
    private final Object mp3, vorbis, opus;
    private NativeArrangerAccess arranger;

    /** The exact bindings, or native_unavailable when a descriptor does not match the loaded host. */
    public static NativeRenderAccess bind(NativeHost.Access a) {
        try {
            return new NativeRenderAccess(a);
        } catch (ReflectiveOperationException | LinkageError error) {
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Exact render bindings unavailable: " + NativeHost.diagnostic(error));
        }
    }

    NativeRenderAccess(NativeHost.Access a) throws ReflectiveOperationException {
        this.a = a;
        resolver = new NativeTrackResolver(a);
        projectType = a.type("project");
        projectProxyType = a.type("proxy.project");
        jobType = a.type("render.job");
        exportJob = a.type("render.exportJob");
        bounceJob = a.type("render.bounceJob");
        hostedDevice = a.type("device.hosted");
        Class<?> tap = a.type("render.tap"),
                wav = a.type("render.wavDepth"),
                flac = a.type("render.flacDepth"),
                mp3Type = a.type("render.mp3Quality"),
                vorbisType = a.type("render.vorbisQuality"),
                opusType = a.type("render.opusQuality");
        target = a.exactMethod("proxy.controlSurface.target", Object.class, false);
        exportCall = a.exactMethod(
                "render.exporter.export",
                void.class,
                false,
                List.class,
                String[].class,
                boolean.class,
                boolean.class,
                flac,
                boolean.class,
                mp3Type,
                boolean.class,
                vorbisType,
                boolean.class,
                opusType,
                double.class,
                double.class,
                tap,
                wav,
                boolean.class,
                boolean.class,
                int.class);
        bounceCall = a.exactMethod(
                "render.bouncer.bounce",
                void.class,
                true,
                projectType,
                Collection.class,
                double.class,
                double.class,
                tap);
        current = a.exactMethod("project.renderJob", jobType, false);
        engineActive = a.exactMethod("project.engineActive", boolean.class, false);
        remote = a.exactMethod("project.remote", boolean.class, false);
        blocked = a.exactMethod("project.renderBlocker", String.class, false);
        missingFiles = a.exactMethod("project.missingFiles", a.type("project.missingFileSet"), false);
        missingCount = a.exactMethod("project.missingFileSet.count", int.class, false);
        devices = a.exactMethod("project.devices", a.type("device.tree"), false);
        chains = a.exactMethod("device.tree.chains", List.class, false);
        chainDevices = a.exactMethod("device.chain.devices", List.class, false);
        Class<?> state = a.type("device.pluginState");
        deviceState = a.exactMethod("device.hosted.pluginState", state, false);
        Class<?> rootGroup = a.type("track.root");
        if (!resolver.groupType().isAssignableFrom(rootGroup))
            throw new NoSuchMethodException("Inspected root track is not a group: " + rootGroup.getName());
        root = a.exactMethod("project.rootTrack", rootGroup, false);
        latch = a.exactMethod("render.job.latch", CountDownLatch.class, false);
        pluginMissing = constant(state, "PLUGIN_MISSING");
        taps.put("pre_fx", constant(tap, "PRE_FX"));
        taps.put("pre_fader", constant(tap, "PRE_FADER"));
        taps.put("post_fader", constant(tap, "POST_FADER"));
        wavDepths.put("16", constant(wav, "INT16"));
        wavDepths.put("24", constant(wav, "INT24"));
        wavDepths.put("32", constant(wav, "FLOAT32"));
        flacDepths.put("16", constant(flac, "BIT16"));
        flacDepths.put("24", constant(flac, "BIT24"));
        mp3 = constant(mp3Type, "HIGH");
        vorbis = constant(vorbisType, "HIGH");
        opus = constant(opusType, "HIGH");
    }

    private static Object constant(Class<?> type, String name) throws NoSuchFieldException {
        if (type.getEnumConstants() != null)
            for (Object value : type.getEnumConstants())
                if (((Enum<?>) value).name().equals(name)) return value;
        throw new NoSuchFieldException(type.getName() + "." + name + " absent");
    }

    @Override
    public void execute(Runnable work) {
        a.execute(work);
    }

    @Override
    public void provenance(JsonObject out) {
        a.provenance(out);
    }

    private Object project(Object projectProxy) {
        if (!projectProxyType.isInstance(projectProxy))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Controller Project is not the native project proxy.");
        Object project = a.invoke(target, projectProxy);
        if (!projectType.isInstance(project))
            throw Errors.notApplied(Reason.NOT_READY, "Controller Project proxy is not bound to an open project.");
        a.requireModelThread(project);
        return project;
    }

    /** What the job would refuse silently, through a notification, or with a confirmation dialog. */
    private void preflight(Object project) {
        if (Boolean.TRUE.equals(a.invoke(remote, project)))
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_SCOPE,
                    "This project is driven by a remote session; its render job would run elsewhere.");
        if (a.invoke(current, project) != null)
            throw Errors.notApplied(Reason.BUSY, "Bitwig is already exporting or bouncing (possibly from the GUI).");
        String reason = (String) a.invoke(blocked, project);
        if (reason != null) throw Errors.notApplied(Reason.HOST_REFUSED, "Bitwig refuses to render: " + reason + ".");
        if (!Boolean.TRUE.equals(a.invoke(engineActive, project)))
            throw Errors.notApplied(
                    Reason.HOST_REFUSED, "The project's audio engine is not active; Bitwig would ignore the request.");
        int files = (Integer) a.invoke(missingCount, a.invoke(missingFiles, project)), plugins = 0;
        for (Object chain : (List<?>) a.invoke(chains, a.invoke(devices, project)))
            for (Object device : (List<?>) a.invoke(chainDevices, chain))
                if (hostedDevice.isInstance(device) && a.invoke(deviceState, device) == pluginMissing) plugins++;
        if (files > 0 || plugins > 0)
            throw Errors.notApplied(
                    Reason.NEEDS_CONFIRMATION,
                    "Project has " + files + " missing file(s) and " + plugins
                            + " missing plugin(s); Bitwig would ask for confirmation in a dialog. Fix them first.");
    }

    /** Proxy to the rendered channel: a channel track itself, a group's master channel, or the project master (null). */
    private Object channel(Object project, Object proxy, String id, NativeTrackResolver.Kind... allowed) {
        Object channel;
        if (proxy == null) channel = resolver.alias(a.invoke(root, project));
        else {
            Object track = resolver.resolve(proxy, id, allowed);
            channel = resolver.kind(track) == NativeTrackResolver.Kind.GROUP ? resolver.alias(track) : track;
        }
        if (resolver.kind(channel) != NativeTrackResolver.Kind.CHANNEL)
            throw Errors.notApplied(
                    proxy == null ? Reason.NATIVE_UNAVAILABLE : Reason.TARGET_NOT_FOUND,
                    "No native channel for " + (proxy == null ? "the master" : id) + ".");
        a.requireModelThread(channel);
        if (resolver.owner(channel, projectType) != project)
            throw Errors.notApplied(Reason.TARGET_CHANGED, "Source " + id + " belongs to another project.");
        return channel;
    }

    private RenderModule.Handle started(Object project, Class<?> kind) {
        Object job = a.invoke(current, project);
        if (!kind.isInstance(job))
            throw Errors.unknown(
                    Reason.JOB_FAILED,
                    "Bitwig accepted the call but no " + (kind == exportJob ? "export" : "bounce")
                            + " job was registered; inspect the output before another attempt.");
        CountDownLatch done = (CountDownLatch) a.invoke(latch, job);
        return () -> done.getCount() == 0;
    }

    @Override
    public RenderModule.Handle export(
            Object projectProxy,
            List<Object> sources,
            List<String> ids,
            String[] basePaths,
            RenderModule.Export o,
            Runnable check,
            Runnable beforeDispatch) {
        a.documentThread();
        check.run();
        Object project = project(projectProxy);
        preflight(project);
        List<Object> channels = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            Object channel = channel(
                    project,
                    sources.get(i),
                    ids.get(i),
                    NativeTrackResolver.Kind.CHANNEL,
                    NativeTrackResolver.Kind.GROUP);
            if (channels.contains(channel))
                throw Errors.notApplied(Reason.INVALID_PARAMS, "Two sources resolve to the same native channel.");
            channels.add(channel);
        }
        List<String> types = o.fileTypes;
        Object flacDepth = flacDepths.getOrDefault(String.valueOf(o.bitDepth), flacDepths.get("24"));
        Object wavDepth = wavDepths.get(
                String.valueOf(o.bitDepth)); // without wav: the intermediate WAV is rendered at this depth
        beforeDispatch.run();
        a.invoke(
                exportCall,
                project,
                channels,
                basePaths,
                types.contains("wav"),
                types.contains("flac"),
                flacDepth,
                types.contains("mp3"),
                mp3,
                types.contains("ogg"),
                vorbis,
                types.contains("opus"),
                opus,
                o.start,
                o.end,
                taps.get(o.tap),
                wavDepth,
                o.dither,
                false,
                o.sampleRate);
        return started(project, exportJob);
    }

    @Override
    public RenderModule.Handle bounce(
            Object projectProxy,
            List<Object> tracks,
            List<String> ids,
            double start,
            double end,
            String tap,
            Runnable check,
            Runnable beforeDispatch) {
        a.documentThread();
        check.run();
        Object project = project(projectProxy);
        preflight(project);
        List<Object> channels = new ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) {
            channels.add(channel(project, tracks.get(i), ids.get(i), NativeTrackResolver.Kind.CHANNEL));
        }
        beforeDispatch.run();
        a.invoke(bounceCall, null, project, channels, start, end, taps.get(tap));
        return started(project, bounceJob);
    }

    @Override
    public RenderModule.Clips clips(Object trackProxy, String trackId, Runnable check) {
        if (arranger == null) arranger = NativeArrangerAccess.bind(a);
        return arranger.snapshot(trackProxy, trackId, check);
    }
}
