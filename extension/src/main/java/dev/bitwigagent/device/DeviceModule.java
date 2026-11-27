package dev.bitwigagent.device;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.host.DeviceSources;
import dev.bitwigagent.host.RequestSnapshot;
import dev.bitwigagent.host.SelectionLease;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * devices.get, devices.set, devices.select and devices.catalog: one device by ref. A root device is read and written
 * through its observed proxy, a device inside a chain natively through {@link NativeChains}.
 */
public final class DeviceModule implements HostModule {
    public static final Set<String> METHODS = Set.of("devices.get", "devices.set", "devices.select", "devices.catalog");
    private final Scheduler scheduler;
    private final DeviceSources deviceSources;
    private final NativeChains chains;
    private final SelectionLease selection;
    private final Map<DeviceSources.Source, Enabled> enabled = new IdentityHashMap<>();
    private HostOperation mutation;
    private final JsonObject deviceCatalog = catalog();

    /** What the device's enabled state was last observed to be. */
    private static final class Enabled {
        boolean observed;
        long sequence;
    }

    public DeviceModule(
            Scheduler scheduler, DeviceSources deviceSources, NativeChains chains, SelectionLease selection) {
        this.scheduler = scheduler;
        this.deviceSources = deviceSources;
        this.chains = chains;
        this.selection = selection;
        for (DeviceSources.Source source : deviceSources.all) {
            Enabled state = new Enabled();
            enabled.put(source, state);
            source.device.isEnabled().addValueObserver((BooleanValueChangedCallback) value -> {
                state.observed = true;
                state.sequence++;
            });
        }
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject params) {}

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        if (method.equals("devices.catalog")) {
            op.result.complete(filtered(deviceCatalog, p));
            return;
        }
        JsonObject target = p.getAsJsonObject("target");
        if (NativeChains.nested(target)) {
            nested(op, method, target, p);
            return;
        }
        DeviceSources.Source device = deviceSources.device(target, true);
        if (!enabled.get(device).observed)
            throw Errors.notApplied(Reason.NOT_READY, "The device's enabled state has not been observed.");
        if (method.equals("devices.get")) {
            op.result.complete(metadata(device));
            return;
        }
        if (mutation != null && !mutation.result.isDone())
            throw Errors.notApplied(Reason.BUSY, "Another device change is awaiting observations.");
        mutation = op;
        if (method.equals("devices.select")) {
            scheduler.check(op);
            selection.requireFree();
            op.markDispatched();
            device.device.selectInEditor();
            JsonObject result = Results.sent();
            result.add("ref", device.ref());
            op.result.complete(result);
            return;
        }
        setEnabled(op, device, p.get("enabled").getAsBoolean());
    }

    /** A device inside a chain: read, enabled, or selected natively in one document-thread pass. */
    private void nested(HostOperation op, String method, JsonObject target, JsonObject p) {
        if (method.equals("devices.get")) {
            chains.run(op, target, walk -> {
                NativeChains.Device device = walk.device(target, true);
                JsonObject result = new JsonObject();
                result.add("ref", device.ref);
                result.addProperty("name", device.name);
                result.addProperty("type", walk.a.deviceType(device.device));
                result.addProperty("enabled", on(walk, device));
                result.addProperty("scope", "chain_device");
                result.addProperty("remoteControlsAvailable", false);
                result.addProperty("nameWritable", false);
                return result;
            });
            return;
        }
        if (mutation != null && !mutation.result.isDone())
            throw Errors.notApplied(Reason.BUSY, "Another device change is awaiting observations.");
        mutation = op;
        if (method.equals("devices.select")) {
            selection.requireFree();
            chains.run(op, target, walk -> {
                NativeChains.Device device = walk.device(target, true);
                walk.dispatch(selection::requireFree);
                walk.a.select(device.device);
                JsonObject result = Results.sent();
                result.add("ref", device.ref);
                return result;
            });
            return;
        }
        boolean wanted = p.get("enabled").getAsBoolean();
        JsonElement asked = new JsonPrimitive(wanted);
        chains.run(op, target, walk -> {
            NativeChains.Device device = walk.device(target, true);
            if (on(walk, device) == wanted)
                return result(device.ref, asked, wanted, Outcome.ALREADY_MATCHED, null, Verification.NATIVE_READBACK);
            Object onOff = walk.a.enabled(device.device);
            if (!walk.a.writable(onOff))
                throw Errors.notApplied(Reason.INVALID_TARGET, "The host does not let this device be bypassed.");
            walk.dispatch();
            walk.a.write(onOff, wanted ? 1 : 0);
            boolean observed = on(walk, device);
            return observed == wanted
                    ? result(device.ref, asked, wanted, Outcome.APPLIED, null, Verification.NATIVE_READBACK)
                    : result(
                            device.ref,
                            asked,
                            observed,
                            Outcome.UNKNOWN,
                            Reason.READBACK_MISMATCH,
                            Verification.NATIVE_READBACK);
        });
    }

    /** Whether a device inside a chain is on: its On/Off parameter at its upper half. */
    private static boolean on(NativeChains.Walk walk, NativeChains.Device device) {
        return walk.a.value(walk.a.enabled(device.device)) >= .5;
    }

    private void setEnabled(HostOperation op, DeviceSources.Source device, boolean wanted) {
        JsonElement asked = new JsonPrimitive(wanted);
        if (device.device.isEnabled().get() == wanted) {
            op.result.complete(
                    result(device.ref(), asked, wanted, Outcome.ALREADY_MATCHED, null, Verification.API_READBACK));
            return;
        }
        Enabled state = enabled.get(device);
        long sequence = state.sequence;
        RequestSnapshot snapshot = RequestSnapshot.of(device);
        scheduler.check(op);
        snapshot.require(op);
        op.markDispatched();
        device.device.isEnabled().set(wanted);
        scheduler.conclude(
                op,
                scheduler.observeUntil(op),
                () -> {
                    snapshot.require(op);
                    if (state.sequence <= sequence || device.device.isEnabled().get() != wanted) return null;
                    return () -> op.result.complete(
                            result(device.ref(), asked, wanted, Outcome.APPLIED, null, Verification.API_READBACK));
                },
                () -> op.result.complete(result(
                        device.ref(),
                        asked,
                        device.device.isEnabled().get(),
                        Outcome.UNKNOWN,
                        Reason.NOT_OBSERVED,
                        Verification.API_READBACK)));
    }

    private static JsonObject result(
            JsonObject ref, JsonElement requested, boolean observed, Outcome outcome, Reason reason, Verification how) {
        JsonObject result = Results.outcome(outcome, how);
        if (reason != null) result.addProperty("reason", reason.wire());
        result.add("ref", ref);
        JsonObject fields = new JsonObject();
        fields.add("enabled", Results.field(requested, new JsonPrimitive(observed), outcome, reason, how));
        result.add("fields", fields);
        return result;
    }

    private JsonObject metadata(DeviceSources.Source source) {
        JsonObject result = new JsonObject();
        result.add("ref", source.ref());
        Device device = source.device;
        result.addProperty("name", device.name().get());
        result.addProperty("type", device.deviceType().get());
        result.addProperty("enabled", device.isEnabled().get());
        result.addProperty("scope", "root_device");
        result.addProperty("remoteControlsAvailable", true);
        result.addProperty("nameWritable", false);
        return result;
    }

    /**
     * The catalog narrowed by the optional {@code query} (case-insensitive substring of name or category) and
     * {@code category} (case-insensitive exact); the unfiltered catalog is cached, so a filter only copies.
     */
    static JsonObject filtered(JsonObject catalog, JsonObject p) {
        String query = p.has("query") ? p.get("query").getAsString().toLowerCase(java.util.Locale.ROOT) : null;
        String category = p.has("category") ? p.get("category").getAsString().toLowerCase(java.util.Locale.ROOT) : null;
        if (query == null && category == null) return catalog;
        JsonObject result = catalog.deepCopy();
        JsonArray kept = new JsonArray();
        for (JsonElement element : catalog.getAsJsonArray("devices")) {
            JsonObject item = element.getAsJsonObject();
            String name = text(item, "name"), own = text(item, "category");
            if (query != null && !name.contains(query) && !own.contains(query)) continue;
            if (category != null && !own.equals(category)) continue;
            kept.add(item);
        }
        result.add("devices", kept);
        return result;
    }

    private static String text(JsonObject item, String key) {
        return item.has(key) ? item.get(key).getAsString().toLowerCase(java.util.Locale.ROOT) : "";
    }

    private static JsonObject catalog() {
        return catalog(System.getProperty("installationRoot"));
    }

    /** Every factory device file under the installation, with category and description read from its localization resources at runtime. */
    static JsonObject catalog(String installation) {
        JsonObject result = new JsonObject();
        result.addProperty("scope", "installed_bitwig_factory_devices");
        result.addProperty("completePluginCatalog", false);
        Results.readBy(result, Verification.FILE_READBACK);
        result.addProperty("bitwigIdAvailable", false);
        JsonArray devices = new JsonArray();
        result.add("devices", devices);
        if (installation == null || installation.isEmpty()) {
            result.addProperty("supported", false);
            result.addProperty("reason", "Host did not provide its installationRoot; no paths were guessed.");
            return result;
        }
        try {
            Path root = Path.of(installation);
            if (!root.isAbsolute()) throw new InvalidPathException(installation, "Installation root is not absolute");
            Path directory = root.resolve("Library").resolve("devices").normalize();
            DeviceTexts texts = DeviceTexts.read(root.resolve("resources").resolve("localization"));
            java.util.List<Path> files = new java.util.ArrayList<>();
            try (java.util.stream.Stream<Path> stream =
                    Files.isDirectory(directory) ? Files.list(directory) : java.util.stream.Stream.<Path>empty()) {
                stream.filter(f -> f.getFileName().toString().endsWith(".bwdevice") && Files.isRegularFile(f))
                        .forEach(files::add);
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
            files.sort(java.util.Comparator.comparing(
                    f -> f.getFileName().toString().toLowerCase(java.util.Locale.ROOT)));
            for (Path path : files) {
                String file = path.getFileName().toString();
                String name = file.substring(0, file.length() - ".bwdevice".length());
                JsonObject item = new JsonObject();
                item.addProperty("name", name);
                JsonObject insert = new JsonObject();
                insert.addProperty("kind", "file");
                insert.addProperty("path", path.toString());
                item.add("insert", insert);
                item.addProperty("sourceKind", "installed_bitwig_factory_device");
                String category = texts.category(name), description = texts.description(name);
                if (category != null) item.addProperty("category", category);
                if (description != null) item.addProperty("description", description);
                devices.add(item);
            }
            result.addProperty("supported", true);
            result.addProperty("textsAvailable", texts.available());
        } catch (InvalidPathException error) {
            result.addProperty("supported", false);
            result.addProperty("reason", "Host installationRoot is not an absolute filesystem path.");
        } catch (java.io.UncheckedIOException | SecurityException error) {
            result.addProperty("supported", false);
            result.addProperty("reason", "Device directory could not be listed.");
        }
        return result;
    }

    /** Name, category and description read from Device-descriptions/Categories resources; absent files only drop the text. */
    static final class DeviceTexts {
        private final java.util.Map<String, String> descriptions = new java.util.HashMap<>(),
                categories = new java.util.HashMap<>();
        private final java.util.Map<String, String> displayCategories = new java.util.HashMap<>();
        private boolean available;

        boolean available() {
            return available;
        }

        String description(String deviceName) {
            return descriptions.get(key(deviceName));
        }

        String category(String deviceName) {
            return categories.get(key(deviceName));
        }
        /** Word-order-insensitive: the file "v9 Hat Open" is resource "v9_open_hat". */
        private static String key(String name) {
            String[] words = name.toLowerCase(java.util.Locale.ROOT).split("[\\s_]+");
            java.util.Arrays.sort(words);
            return String.join(" ", words);
        }

        private static String squash(String text) {
            return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        }

        static DeviceTexts read(Path localization) {
            DeviceTexts texts = new DeviceTexts();
            try {
                Path file = localization.resolve("Device-descriptions-resources.properties");
                if (!Files.isRegularFile(file)) return texts;
                String text = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
                if (text.startsWith("\uFEFF")) text = text.substring(1);
                java.util.Properties values = new java.util.Properties();
                values.load(new java.io.StringReader(text));
                Path categoryFile = localization.resolve("Categories-resources.properties");
                if (Files.isRegularFile(categoryFile)) {
                    java.util.Properties display = new java.util.Properties();
                    display.load(new java.io.StringReader(
                            new String(Files.readAllBytes(categoryFile), java.nio.charset.StandardCharsets.UTF_8)));
                    for (String key : display.stringPropertyNames())
                        texts.displayCategories.put(squash(key), display.getProperty(key));
                }
                String header = null;
                for (String line : text.split("\r?\n")) {
                    java.util.regex.Matcher heading = java.util.regex.Pattern.compile("^#([a-z_-]+)\\.\\s*$")
                            .matcher(line);
                    if (heading.matches()) {
                        header = heading.group(1);
                        continue;
                    }
                    java.util.regex.Matcher entry = java.util.regex.Pattern.compile(
                                    "^device\\.([^=:\\s]+)\\.desc\\s*[=:]")
                            .matcher(line);
                    if (!entry.find()) continue;
                    String id = entry.group(1), description = values.getProperty("device." + id + ".desc");
                    String k = key(id);
                    if (description != null) texts.descriptions.put(k, description.trim());
                    if (header != null) texts.categories.put(k, texts.displayCategory(header));
                }
                texts.available = !texts.descriptions.isEmpty();
            } catch (java.io.IOException | RuntimeException error) {
                texts.descriptions.clear();
                texts.categories.clear();
            }
            return texts;
        }

        private String displayCategory(String header) {
            String flat = squash(header);
            String exact = displayCategories.get(flat);
            if (exact != null) return exact;
            for (java.util.Map.Entry<String, String> entry : displayCategories.entrySet())
                if (entry.getKey().startsWith(flat)) return entry.getValue();
            StringBuilder out = new StringBuilder();
            for (String word : header.split("[_-]+")) {
                if (word.isEmpty()) continue;
                if (out.length() > 0) out.append(' ');
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return out.toString();
        }
    }
}
