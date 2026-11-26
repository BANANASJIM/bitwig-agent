package dev.bitwigagent.launcher;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.FakeHost;
import dev.bitwigagent.HostBridge;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.ScheduledTasks;
import dev.bitwigagent.TestServices;
import dev.bitwigagent.arranger.ArrangerModule;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.host.ProjectState;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * One launcher world for the clip, note, launcher and scene module tests: a single track over a scrollable scene
 * window, a cursor clip with a note grid, and the native settings and arranger accesses as in-memory stand-ins.
 * Scenes and slots are addressed by absolute scene; the 16-position window shows {@link #bankOffset}.. onwards. API
 * shaped consumer fixture, never native-host evidence.
 */
public final class LauncherFake {
    static final String ID = "11111111-1111-1111-1111-111111111111";
    static final int SCENES = 64;

    public final HostFixture base = HostFixture.withoutServices();
    public final ScheduledTasks tasks = base.tasks;
    final Map<String, Note> notes = new HashMap<>();
    /** The slot of the track, by absolute scene. */
    public final SlotData[] slots = new SlotData[SCENES];

    public final String[] sceneNames = new String[SCENES];
    final Track[] tracks = new Track[HostLimits.TRACKS];
    public final TestServices services;
    final LauncherModule launcher;
    final NoteModule noteModule;
    public final ClipModule clipModule;
    final SceneModule sceneModule;
    public final Map<String, HostModule> modules = new HashMap<>();
    public final SettingsAccess settings = new SettingsAccess();
    final LaneAccess lane = new LaneAccess();
    NoteStepChangedCallback observer;
    public int scenes = 16;
    /** Tracks that exist; the ones past the first only report content from {@link #extraContent}. */
    int trackCount = 1;

    final boolean[] extraContent = new boolean[SCENES];
    boolean transportPlaying;
    /** The window position the scene bank scrolled to, and how often it was scrolled. */
    public int bankOffset, scrolls;
    /** When above 0 the scene bank echoes a scroll only after this many milliseconds of host time. */
    long scrollEchoMs;

    public int selected, start, writes, creates, failWriteAt, nameWrites, playbackDispatches, stopWrites;
    double size = .25, loopStart;
    public String trackId = ID;
    String trackName = "Track";
    boolean emitNotes = true, launchPlaying, stopped = true;
    boolean emitPlaybackState = true, queueStop, stopQueued;
    boolean deferGridChanges;
    boolean applyStop = true;
    boolean deferCreate, failCreate, failImport, emitCreateContent = true, applyName = true;
    Double pendingSize;
    Integer pendingStart;
    Double noteStorageStepSize;
    long gridRefreshDelayMillis, gridReadyAt;
    int imports, importedSlot = -1, sceneAppends;
    int deletes, copies, transposes, quantizes, duplicates, colorSets, loopSets;
    int sceneRenames, sceneDuplicates, sceneDeletes, sceneLaunches;
    String copyName;
    boolean applyDelete = true, applyCopy = true, applyClipOp = true, quantizeMoves = true, applyColor = true;
    boolean applyLoop = true;
    float[] color = {0f, 0f, 0f};
    String launchQuant = "default", launchMode = "from_group";
    boolean useLoopStart, applyLaunch = true, emitLaunch = true;
    int launchSets;
    boolean applyScene = true, initialised;
    String importedPath;
    Runnable beforeInsertion = () -> {}, onContentRead = () -> {};
    Runnable afterPlayStopRead = () -> {}, onSetName = () -> {}, onSelect = () -> {};
    int unreadableFromStep = -1;
    FakeHost recordNode;
    FakeHost trackIdentity;
    private final FakeHost[] contentNodes = new FakeHost[HostLimits.SCENES];
    private final FakeHost[] nameNodes = new FakeHost[HostLimits.SCENES];
    private final FakeHost[] sceneNodes = new FakeHost[HostLimits.SCENES];

    public LauncherFake() {
        this(true, false, false, false);
    }

    LauncherFake(boolean safetyObserved, boolean record, boolean arrangerAutomation, boolean launcherAutomation) {
        this(safetyObserved, record, arrangerAutomation, launcherAutomation, true);
    }

    public LauncherFake(
            boolean safetyObserved,
            boolean record,
            boolean arrangerAutomation,
            boolean launcherAutomation,
            boolean sceneCountObserved) {
        for (int i = 0; i < SCENES; i++) {
            slots[i] = new SlotData();
            sceneNames[i] = "";
        }
        for (int t = 0; t < HostFixture.DETAILED_TRACKS; t++) {
            final int trackIndex = t;
            FakeHost track = base.tracks[t];
            track.live("exists", () -> trackIndex < trackCount);
            FakeHost identity = track.attach("channelId", FakeHost.of(StringValue.class, "channelId"))
                    .reading(() -> trackIndex == 0 ? trackId : "");
            if (trackIndex == 0) trackIdentity = identity;
            track.live("name", () -> trackName);
            track.live("position", () -> trackIndex);
            track.live("isStopped", () -> stopped);
            track.live("isQueuedForStop", () -> stopQueued);
            FakeHost bank = FakeHost.of(ClipLauncherSlotBank.class, "bank");
            bank.live("scrollPosition", () -> bankOffset);
            bank.live("itemCount", () -> scenes);
            ClipLauncherSlot[] slotApis = new ClipLauncherSlot[HostLimits.SCENES];
            for (int j = 0; j < HostLimits.SCENES; j++) {
                final int position = j;
                FakeHost slot = FakeHost.of(ClipLauncherSlot.class, "slot");
                FakeHost content = slot.attach("hasContent", FakeHost.of(BooleanValue.class, "hasContent"))
                        .reading(() -> {
                            onContentRead.run();
                            return trackIndex == 0
                                    ? data(position).content
                                    : trackIndex < trackCount && extraContent[bankOffset + position];
                        });
                if (trackIndex == 0) contentNodes[j] = content;
                slot.live("sceneIndex", () -> bankOffset + position);
                slot.live("name", () -> data(position).name);
                slot.live("isPlaying", () -> data(position).playing);
                slot.live("isPlaybackQueued", () -> data(position).queued);
                slot.live("isRecording", () -> false);
                slot.on("select", a -> {
                    selected = bankOffset + position;
                    onSelect.run();
                    return null;
                });
                slot.on("createEmptyClip", a -> {
                    creates++;
                    if (failCreate) throw Errors.notApplied(Reason.HOST_REFUSED, "Fixture native creation failure");
                    data(position).length = (Integer) a[0];
                    if (!deferCreate) {
                        if (emitCreateContent) observeContent(bankOffset + position, true);
                        else data(position).content = true;
                    }
                    return null;
                });
                slot.on("deleteObject", a -> {
                    deletes++;
                    if (applyDelete && trackIndex == 0) observeContent(bankOffset + position, false);
                    return null;
                });
                slot.on("launch", a -> {
                    playbackDispatches++;
                    if (emitPlaybackState) {
                        data(position).playing = launchPlaying;
                        data(position).queued = !launchPlaying;
                        stopped = false;
                    }
                    return null;
                });
                FakeHost insertion = FakeHost.of(InsertionPoint.class, "insertion");
                insertion.on("copySlotsOrScenes", a -> {
                    copies++;
                    if (applyCopy && trackIndex == 0) {
                        ClipLauncherSlotOrScene[] sources = (ClipLauncherSlotOrScene[]) a[0];
                        for (int src = 0; src < HostLimits.SCENES; src++)
                            if (slotApis[src] == sources[0]) {
                                data(position).name = copyName != null ? copyName : data(src).name;
                                data(position).length = data(src).length;
                            }
                        observeContent(bankOffset + position, true);
                    }
                    return null;
                });
                insertion.on("insertFile", a -> {
                    imports++;
                    importedPath = (String) a[0];
                    importedSlot = bankOffset + position;
                    if (failImport) throw Errors.notApplied(Reason.HOST_REFUSED, "Fixture native insertion failure");
                    return null;
                });
                slot.on("replaceInsertionPoint", a -> {
                    beforeInsertion.run();
                    return insertion.proxy();
                });
                slotApis[j] = slot.proxy(ClipLauncherSlot.class);
            }
            bank.on("getItemAt", a -> slotApis[(Integer) a[0]]);
            bank.on("stop", a -> {
                playbackDispatches++;
                if (emitPlaybackState) {
                    stopQueued = queueStop;
                    if (!queueStop) {
                        stopped = true;
                        for (SlotData data : slots) {
                            data.playing = false;
                            data.queued = false;
                        }
                    }
                }
                return null;
            });
            track.attach("clipLauncherSlotBank", bank);
            FakeHost devices = FakeHost.of(DeviceBank.class, "devices");
            devices.live("exists", () -> true);
            devices.live("scrollPosition", () -> 0);
            devices.live("itemCount", () -> 0);
            FakeHost[] roots = new FakeHost[16];
            for (int d = 0; d < roots.length; d++) roots[d] = emptyDevice(d);
            devices.on("getItemAt", a -> roots[(Integer) a[0]].proxy());
            track.attach("createDeviceBank", devices);
            tracks[t] = track.proxy(Track.class);
        }
        for (int t = HostFixture.DETAILED_TRACKS; t < tracks.length; t++)
            tracks[t] = tracks[HostFixture.DETAILED_TRACKS - 1];
        FakeHost clip = FakeHost.of(Clip.class, "clip");
        clip.on("getTrack", a -> tracks[0]);
        clip.live("exists", () -> slots[selected].content);
        FakeHost loopLength = FakeHost.of(SettableBeatTimeValue.class, "loopLength");
        loopLength.on("get", a -> slots[selected].length);
        loopLength.on("addValueObserver", a -> {
            ((DoubleValueChangedCallback) a[a.length - 1]).valueChanged(slots[selected].length);
            return null;
        });
        loopLength.on("set", a -> {
            loopSets++;
            if (applyLoop) slots[selected].length = (Double) a[0];
            return null;
        });
        clip.on("getLoopLength", a -> loopLength.proxy());
        FakeHost colorNode = FakeHost.of(SettableColorValue.class, "colorNode");
        colorNode.on("red", a -> color[0]);
        colorNode.on("green", a -> color[1]);
        colorNode.on("blue", a -> color[2]);
        colorNode.on("set", a -> {
            colorSets++;
            if (applyColor) color = new float[] {(Float) a[0], (Float) a[1], (Float) a[2]};
            return null;
        });
        clip.on("color", a -> colorNode.proxy());
        Object quantNode = enumValue(() -> launchQuant, v -> launchQuant = v, new String[] {"default", "1", "1/4"});
        Object modeNode =
                enumValue(() -> launchMode, v -> launchMode = v, new String[] {"from_group", "trigger", "gate"});
        clip.on("launchQuantization", a -> quantNode);
        clip.on("launchMode", a -> modeNode);
        FakeHost useLoop = FakeHost.of(SettableBooleanValue.class, "useLoop");
        final List<BooleanValueChangedCallback> useLoopObservers = new ArrayList<>();
        useLoop.on("get", a -> useLoopStart);
        useLoop.on("addValueObserver", a -> {
            useLoopObservers.add((BooleanValueChangedCallback) a[a.length - 1]);
            return null;
        });
        useLoop.on("set", a -> {
            launchSets++;
            if (applyLaunch) {
                useLoopStart = (Boolean) a[0];
                if (emitLaunch) for (BooleanValueChangedCallback o : useLoopObservers) o.valueChanged(useLoopStart);
            }
            return null;
        });
        clip.on("useLoopStartAsQuantizationReference", a -> useLoop.proxy());
        clip.on("transpose", a -> {
            transposes++;
            if (applyClipOp) remap(k -> new int[] {k[0], k[1], k[2] + (Integer) a[0]});
            return null;
        });
        clip.on("quantize", a -> {
            quantizes++;
            if (applyClipOp && quantizeMoves) remap(k -> new int[] {k[0], k[1] % 2 == 0 ? k[1] : k[1] + 1, k[2]});
            return null;
        });
        clip.on("duplicateContent", a -> {
            duplicates++;
            if (applyClipOp) slots[selected].length *= 2;
            return null;
        });
        clip.live("getLoopStart", () -> loopStart);
        clip.live("getPlayStart", () -> 0.0);
        FakeHost playStop = FakeHost.of(SettableBeatTimeValue.class, "playStop");
        playStop.on("get", a -> {
            double value = slots[selected].playStop;
            afterPlayStopRead.run();
            return value;
        });
        playStop.on("set", a -> {
            stopWrites++;
            if (applyStop) slots[selected].playStop = (Double) a[0];
            return null;
        });
        clip.on("getPlayStop", a -> playStop.proxy());
        clip.live("isLoopEnabled", () -> true);
        FakeHost cursorSlot = FakeHost.of(ClipLauncherSlot.class, "cursorSlot");
        cursorSlot.on("createEqualsValue", a -> {
            Object target = a[0];
            return value(
                    BooleanValue.class,
                    () -> selected >= bankOffset
                            && selected < bankOffset + HostLimits.SCENES
                            && tracks[0].clipLauncherSlotBank().getItemAt(selected - bankOffset) == target);
        });
        cursorSlot.live("sceneIndex", () -> cursorSceneUnknown ? -1 : selected);
        clip.on("clipLauncherSlot", a -> cursorSlot.proxy());
        clip.on("setName", a -> {
            nameWrites++;
            if (applyName) slots[selected].name = (String) a[0];
            onSetName.run();
            return null;
        });
        clip.on("setStepSize", a -> {
            if (deferGridChanges || gridRefreshDelayMillis > 0) {
                pendingSize = (Double) a[0];
                gridReadyAt = tasks.after(gridRefreshDelayMillis);
            } else size = (Double) a[0];
            return null;
        });
        clip.on("scrollToStep", a -> {
            if (deferGridChanges || gridRefreshDelayMillis > 0) pendingStart = (Integer) a[0];
            else start = (Integer) a[0];
            return null;
        });
        clip.on("scrollToKey", a -> null);
        clip.on("addNoteStepObserver", a -> {
            observer = (NoteStepChangedCallback) a[0];
            return null;
        });
        clip.on(
                "getStep",
                a -> unreadableFromStep >= 0 && (Integer) a[1] >= unreadableFromStep
                        ? null
                        : note((Integer) a[0], (Integer) a[1], (Integer) a[2]));
        clip.on("setStep", a -> {
            writes++;
            if (writes == failWriteAt) throw Errors.notApplied(Reason.HOST_REFUSED, "fixture interrupted batch");
            int c = (Integer) a[0], x = (Integer) a[1], y = (Integer) a[2];
            put(selected, c, start + x, y, (Integer) a[3], (Double) a[4]);
            if (emitNotes) observer.noteStepChanged(note(c, x, y));
            return null;
        });
        clip.on("clearStep", a -> {
            writes++;
            int c = (Integer) a[0], x = (Integer) a[1], y = (Integer) a[2];
            notes.remove(key(selected, c, start + x, y));
            if (emitNotes) observer.noteStepChanged(note(c, x, y));
            return null;
        });
        FakeHost trackBank = base.bank;
        trackBank.live("scrollPosition", () -> 0);
        trackBank.live("itemCount", () -> 1);
        FakeHost application = base.application;
        application.live("projectName", () -> "Fixture");
        application.live("hasActiveEngine", () -> true);
        FakeHost transport = base.transport;
        transport.live("isPlaying", () -> transportPlaying);
        recordNode = transport.put("isArrangerRecordEnabled", record);
        transport.live("isArrangerAutomationWriteEnabled", () -> arrangerAutomation);
        transport.live("isClipLauncherAutomationWriteEnabled", () -> launcherAutomation);
        for (String key : List.of(
                "isArrangerRecordEnabled", "isArrangerAutomationWriteEnabled", "isClipLauncherAutomationWriteEnabled"))
            transport.child(key).notifyInitial = safetyObserved;
        trackBank.child("sceneBank").child("itemCount").notifyInitial = sceneCountObserved;
        scenesBank(trackBank.child("sceneBank"));
        FakeHost host = base.host;
        FakeHost project = base.project;
        project.on("createScene", a -> {
            sceneAppends++;
            if (applyScene) {
                scenes++;
                publishScenes();
            }
            return null;
        });
        host.on("getProject", a -> {
            // Mirrors Bitwig: the Project is only available while the extension initialises.
            if (initialised) throw new IllegalStateException("getProject can only be called during init");
            return project.proxy(Project.class);
        });
        host.on("createLauncherCursorClip", a -> clip.proxy());
        host.on("scheduleTask", a -> {
            Runnable action = (Runnable) a[0];
            long delay = ((Number) a[1]).longValue();
            tasks.schedule(
                    () -> {
                        if (gridRefreshDelayMillis > 0 && tasks.now() >= gridReadyAt) applyPendingGrid();
                        action.run();
                    },
                    delay);
            return null;
        });
        services = new TestServices(host.proxy(ControllerHost.class), tasks::now);
        launcher = services.launcher();
        noteModule = services.notes();
        clipModule = services.clips(settings, lane);
        sceneModule = services.scene();
        for (HostModule module : List.of(launcher, noteModule, clipModule, sceneModule))
            for (String method : module.methods()) modules.put(method, module);
        tasks.advance(ProjectState.SETTLE_NANOS);
        initialised = true;
    }

    // ---- the scene bank ----

    private void scenesBank(FakeHost bank) {
        bank.items(Scene.class);
        bank.live("itemCount", () -> scenes);
        FakeHost scroll = bank.child("scrollPosition").reading(() -> bankOffset);
        scroll.on("set", a -> {
            int offset = (Integer) a[0];
            if (scrollStuck) return null;
            if (scrollEchoMs > 0) tasks.schedule(() -> scroll(offset), scrollEchoMs);
            else scroll(offset);
            return null;
        });
        for (int j = 0; j < HostLimits.SCENES; j++) {
            final int position = j;
            FakeHost scene = sceneNodes[j] = bank.item(j);
            scene.live("exists", () -> bankOffset + position < scenes);
            scene.live("sceneIndex", () -> bankOffset + position);
            FakeHost name = nameNodes[j] = scene.child("name").reading(() -> sceneNames[bankOffset + position]);
            name.on("set", a -> {
                sceneRenames++;
                if (applyRename) {
                    sceneNames[bankOffset + position] = (String) a[0];
                    publishScenes();
                }
                return null;
            });
            scene.on("launch", a -> {
                sceneLaunches++;
                SlotData data = data(position);
                if (data.content && applySceneLaunch) {
                    data.playing = launchPlaying;
                    data.queued = !launchPlaying;
                }
                return null;
            });
            scene.on("duplicateObject", a -> {
                sceneDuplicates++;
                if (applyDuplicate) {
                    int abs = bankOffset + position;
                    for (int s = scenes; s > abs + 1; s--) {
                        sceneNames[s] = sceneNames[s - 1];
                        slots[s] = slots[s - 1];
                    }
                    sceneNames[abs + 1] = sceneNames[abs];
                    slots[abs + 1] = slots[abs].copy();
                    scenes++;
                    relabel();
                    publishScenes();
                }
                return null;
            });
            scene.on("deleteObject", a -> {
                sceneDeletes++;
                if (applyDelete) {
                    int abs = bankOffset + position;
                    for (int s = abs; s < scenes - 1; s++) {
                        sceneNames[s] = sceneNames[s + 1];
                        slots[s] = slots[s + 1];
                    }
                    sceneNames[scenes - 1] = "";
                    slots[scenes - 1] = new SlotData();
                    scenes--;
                    relabel();
                    publishScenes();
                }
                return null;
            });
        }
    }

    /** The scene bank ignores scroll requests, as if the host never settled. */
    boolean scrollStuck;
    /** The cursor slot reports no usable scene index of its own. */
    public boolean cursorSceneUnknown;

    boolean applyDuplicate = true, applyRename = true, applySceneLaunch = true, positionalLabels;

    /** Bitwig labels an unnamed scene by its position, so every default label shifts after a duplicate or delete. */
    private void relabel() {
        if (!positionalLabels) return;
        for (int i = 0; i < scenes; i++) if (sceneNames[i].startsWith("Scene ")) sceneNames[i] = "Scene " + (i + 1);
    }

    /** The scene bank scrolls: every window proxy now shows other scenes, and its observers say so. */
    public void scroll(int offset) {
        bankOffset = offset;
        scrolls++;
        publishScenes();
        for (FakeHost node : contentNodes) node.emit(node.data);
    }

    private void publishScenes() {
        base.bank.child("sceneBank").child("itemCount").emit(scenes);
        for (FakeHost name : nameNodes) name.emit(name.data);
    }

    /** The slot proxy at a window position shows this absolute scene's data. */
    private SlotData data(int position) {
        return slots[bankOffset + position];
    }

    // ---- refs the module hands out, as a test builds them from this world ----

    public JsonObject track() {
        return Refs.track(trackId, trackName);
    }

    String sceneName(int scene) {
        return LauncherTargets.sceneName(scene, sceneNames[scene]) == null ? null : sceneNames[scene];
    }

    JsonObject scene(int scene) {
        return Refs.scene(scene, sceneName(scene));
    }

    JsonObject slot(int scene) {
        return Refs.slot(track(), scene(scene));
    }

    /** The clip ref of the clip currently named in the slot. */
    public JsonObject clip(int scene) {
        return Refs.launcherClip(track(), scene(scene), slots[scene].name);
    }

    // ---- the settings and arranger stand-ins ----

    /** A native settings access over the slot data: reads timing, writes the Next Action fields. */
    final class SettingsAccess implements LauncherSettingsModule.Access {
        int calls, writes;
        boolean failWrite;

        @Override
        public void execute(Runnable work) {
            work.run();
        }

        @Override
        public JsonObject apply(
                Object slot, String id, String expectedName, JsonObject changes, Runnable check, Runnable beforeWrite) {
            calls++;
            SlotData data = slots[selectedSlotOf(slot)];
            check.run();
            JsonObject fields = new JsonObject();
            for (String key : LauncherSettingsModule.WRITE_ORDER) {
                if (!changes.has(key)) continue;
                JsonElement before = data.nextAction.get(key), requested = changes.get(key);
                boolean already = LauncherSettingsModule.matches(before, requested);
                if (!already) {
                    beforeWrite.run();
                    writes++;
                    if (failWrite) throw Errors.unknown(Reason.INTERNAL, "fixture native write failure");
                    data.nextAction.add(key, requested);
                }
                fields.add(
                        key,
                        Results.field(
                                requested,
                                data.nextAction.get(key),
                                already ? Outcome.ALREADY_MATCHED : Outcome.APPLIED,
                                null));
            }
            JsonObject out = data.nextAction.deepCopy();
            out.addProperty("loopLengthBeats", data.length);
            out.addProperty("loopStartBeats", loopStart);
            out.addProperty("playStartBeats", 0.0);
            out.addProperty("playStopBeats", data.playStop);
            out.addProperty("loopEnabled", true);
            out.addProperty("name", data.name);
            out.add("fields", fields);
            return out;
        }

        private int selectedSlotOf(Object slotProxy) {
            ClipLauncherSlotBank bank = tracks[0].clipLauncherSlotBank();
            for (int j = 0; j < HostLimits.SCENES; j++) if (bank.getItemAt(j) == slotProxy) return bankOffset + j;
            throw new AssertionError("The settings access got a slot that is not in the window");
        }
    }

    /** A lane of arranger clips, in memory. */
    final class LaneAccess implements ArrangerModule.Access {
        final List<double[]> clips = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        int created, copied, edited;
        List<double[]> notes = List.of();

        @Override
        public void execute(Runnable work) {
            work.run();
        }

        private JsonObject row(int index) {
            JsonObject row = new JsonObject();
            row.addProperty("clipIndex", index);
            row.addProperty("startBeats", clips.get(index)[0]);
            row.addProperty("lengthBeats", clips.get(index)[1]);
            row.addProperty("name", names.get(index));
            return row;
        }

        private JsonObject result(Outcome outcome, Integer clip) {
            JsonObject out = Results.outcome(outcome);
            JsonArray rows = new JsonArray();
            for (int i = 0; i < clips.size(); i++) rows.add(row(i));
            out.add("clips", rows);
            out.add("clip", clip == null ? JsonNull.INSTANCE : row(clip));
            return out;
        }

        @Override
        public JsonObject list(Object trackProxy, String trackId, Runnable check) {
            return result(Outcome.APPLIED, null);
        }

        @Override
        public JsonObject create(
                Object trackProxy,
                String trackId,
                double startBeats,
                double lengthBeats,
                String name,
                List<double[]> noteList,
                int channel,
                Runnable check,
                Runnable beforeWrite) {
            beforeWrite.run();
            created++;
            notes = noteList;
            clips.add(new double[] {startBeats, lengthBeats});
            names.add(name);
            return result(Outcome.APPLIED, clips.size() - 1);
        }

        @Override
        public JsonObject copyFromLauncher(
                Object trackProxy,
                Object slotProxy,
                String trackId,
                String expectedClipName,
                double startBeats,
                Runnable check,
                Runnable beforeWrite) {
            beforeWrite.run();
            copied++;
            clips.add(new double[] {startBeats, 4});
            names.add(expectedClipName);
            return result(Outcome.APPLIED, clips.size() - 1);
        }

        @Override
        public JsonObject edit(
                Object trackProxy, JsonObject ref, String op, JsonObject args, Runnable check, Runnable beforeWrite) {
            double at = ref.get("startBeats").getAsDouble();
            String name = ref.get("name").getAsString();
            for (int i = 0; i < clips.size(); i++) {
                if (clips.get(i)[0] != at) continue;
                if (!names.get(i).equals(name))
                    throw Errors.targetChanged(
                            "fixture", Refs.arrangerClip(ref.getAsJsonObject("track"), at, names.get(i)));
                beforeWrite.run();
                edited++;
                switch (op) {
                    case "move":
                        clips.get(i)[0] = args.get("startBeats").getAsDouble();
                        break;
                    case "resize":
                        clips.get(i)[1] = args.get("lengthBeats").getAsDouble();
                        break;
                    case "rename":
                        names.set(i, args.get("name").getAsString());
                        break;
                    default:
                        clips.remove(i);
                        names.remove(i);
                        return result(Outcome.APPLIED, null);
                }
                return result(Outcome.APPLIED, i);
            }
            throw Errors.targetNotFound(ref, "fixture");
        }
    }

    // ---- fixture plumbing ----

    Object enumValue(Supplier<String> get, Consumer<String> apply, String[] options) {
        FakeHost value = FakeHost.of(SettableEnumValue.class, "value");
        value.on("get", a -> get.get());
        final List<EnumValueChangedCallback> observers = new ArrayList<>();
        value.on("addValueObserver", a -> {
            observers.add((EnumValueChangedCallback) a[a.length - 1]);
            return null;
        });
        value.on("set", a -> {
            launchSets++;
            if (applyLaunch) {
                apply.accept((String) a[0]);
                if (emitLaunch) for (EnumValueChangedCallback o : observers) o.valueChanged((String) a[0]);
            }
            return null;
        });
        FakeHost definition = FakeHost.of(EnumDefinition.class, "definition");
        definition.on("getValueCount", a -> options.length);
        definition.on("valueDefinitionAt", a -> {
            FakeHost option = FakeHost.of(EnumValueDefinition.class, "option");
            option.on("getId", b -> options[(Integer) a[0]]);
            return option.proxy();
        });
        value.on("enumDefinition", a -> definition.proxy());
        return value.proxy();
    }

    public void observeTrackId(String id) {
        trackId = id;
        trackIdentity.emit(id);
    }

    void observeContent(int scene, boolean content) {
        slots[scene].content = content;
        int position = scene - bankOffset;
        if (position >= 0 && position < HostLimits.SCENES) contentNodes[position].emit(content);
    }

    void applyPendingGrid() {
        if (pendingSize != null) {
            size = pendingSize;
            pendingSize = null;
        }
        if (pendingStart != null) {
            start = pendingStart;
            pendingStart = null;
        }
    }

    void remap(UnaryOperator<int[]> move) {
        Map<String, Note> moved = new HashMap<>();
        for (Iterator<Map.Entry<String, Note>> it = notes.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Note> e = it.next();
            String[] k = e.getKey().split(":");
            if (Integer.parseInt(k[0]) != selected) continue;
            int[] to = move.apply(new int[] {Integer.parseInt(k[1]), Integer.parseInt(k[2]), Integer.parseInt(k[3])});
            it.remove();
            moved.put(key(selected, to[0], to[1], to[2]), e.getValue());
        }
        notes.putAll(moved);
    }

    void put(int scene, int channel, int step, int pitch, int velocity, double duration) {
        notes.put(key(scene, channel, step, pitch), new Note(velocity, duration));
    }

    static String key(int scene, int channel, int step, int pitch) {
        return scene + ":" + channel + ":" + step + ":" + pitch;
    }

    static Object stateConstant(String name) {
        try {
            for (Object constant :
                    NoteStep.class.getMethod("state").getReturnType().getEnumConstants())
                if (((Enum<?>) constant).name().equals(name)) return constant;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("No NoteStep state " + name);
    }

    NoteStep note(int channel, int x, int pitch) {
        double storageSize = noteStorageStepSize == null ? size : noteStorageStepSize;
        double storagePosition = (start + x) * size / storageSize;
        int storedStep = (int) storagePosition;
        Note on = storagePosition == storedStep ? notes.get(key(selected, channel, storedStep, pitch)) : null;
        Object state = stateConstant(on == null ? "Empty" : "NoteOn");
        if (on == null)
            for (int step = 0; step < storagePosition; step++) {
                Note earlier = notes.get(key(selected, channel, step, pitch));
                if (earlier != null && step * storageSize + earlier.duration > (start + x) * size) {
                    on = earlier;
                    state = stateConstant("NoteSustain");
                    break;
                }
            }
        final Note found = on;
        final Object actual = state;
        FakeHost n = FakeHost.of(NoteStep.class, "n");
        n.on("channel", a -> channel);
        n.on("x", a -> x);
        n.on("y", a -> pitch);
        n.on("state", a -> actual);
        n.on("velocity", a -> found == null ? 0.0 : found.exact != null ? found.exact : found.velocity / 127.0);
        n.on("duration", a -> found == null ? 0.0 : found.duration);
        return n.proxy(NoteStep.class);
    }

    /** What HostBridge does for a request: schema, the module's own rules, then a queued dispatch. */
    public HostOperation start(String method, JsonObject p) {
        return start(new HostOperation(tasks.after(2000)), method, p);
    }

    public HostOperation start(HostOperation op, String method, JsonObject p) {
        HostModule module = modules.get(method);
        try {
            JsonObject filled = HostBridge.checkSchema(HostFixture.SPECS, method, p);
            module.validate(method, filled);
            services.enqueue(op, () -> module.dispatch(op, method, filled));
            tasks.take().run();
        } catch (RuntimeException error) {
            op.fail(error);
        }
        return op;
    }

    public HostOperation startAndDrain(String method, JsonObject p) {
        HostOperation op = start(method, p);
        drain(op);
        return op;
    }

    HostOperation startAndDrain(HostOperation operation, String method, JsonObject p) {
        HostOperation op = start(operation, method, p);
        drain(op);
        return op;
    }

    public JsonObject call(String method, JsonObject p) {
        return startAndDrain(method, p).result.join();
    }

    public void next() {
        if (!tasks.isEmpty()) tasks.take().run();
    }

    public void drain(HostOperation op) {
        int iterations = 0;
        while (!op.result.isDone()) {
            if (tasks.isEmpty() || ++iterations > 3000) throw new AssertionError("fixture operation did not settle");
            next();
        }
    }

    public static final class SlotData {
        public boolean content, playing, queued;
        public String name = "";
        double length = 4, playStop = 4;
        final JsonObject nextAction = new JsonObject();

        SlotData copy() {
            SlotData out = new SlotData();
            out.content = content;
            out.name = name;
            out.length = length;
            out.playStop = playStop;
            return out;
        }
    }

    static final class Note {
        final int velocity;
        final double duration;
        Double exact; // host velocity that is not a whole MIDI step, e.g. a GUI drag

        Note(int velocity, double duration) {
            this.velocity = velocity;
            this.duration = duration;
        }
    }

    private static FakeHost emptyDevice(int position) {
        FakeHost device = FakeHost.of(Device.class, "device");
        device.live("exists", () -> false);
        device.live("name", () -> "");
        device.live("position", () -> position);
        device.live("deviceType", () -> "");
        device.on("addDirectParameterIdObserver", a -> {
            ((StringArrayValueChangedCallback) a[0]).valueChanged(new String[0]);
            return null;
        });
        return device;
    }

    private static Object value(Class<?> type, Supplier<Object> supplier) {
        FakeHost node = FakeHost.of(type, "value").reading(supplier);
        return node.proxy();
    }
}
