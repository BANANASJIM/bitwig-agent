package dev.bitwigagent.device;

import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Positions;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.Verification;
import java.util.ArrayList;
import java.util.List;

/**
 * chains.list and the edits of a chain's devices (insert, delete, move), done and verified through the native document
 * model: an edit is proven by the chain's device objects before and after it, so devices with the same name and type
 * are told apart and no window limits the chain.
 */
final class NativeChainEdits {
    private final NativeChains chains;

    NativeChainEdits(NativeChains chains) {
        this.chains = chains;
    }

    /** chains.list: every chain of the device with its ref, name, channel index and device count. */
    void list(HostOperation op, JsonObject parent) {
        chains.run(op, parent, walk -> {
            List<NativeChains.Chain> found = walk.chains(parent);
            JsonArray rows = new JsonArray();
            JsonObject parentRef = null;
            for (int i = 0; i < Math.min(found.size(), NativeChains.MAX_ITEMS); i++) {
                NativeChains.Chain chain = found.get(i);
                parentRef = chain.ref.getAsJsonObject("device");
                JsonObject row = new JsonObject();
                row.add("ref", chain.ref);
                row.addProperty("name", chain.ref.get("name").getAsString());
                row.addProperty("channelIndex", walk.a.chainIndex(chain.chain));
                row.addProperty("readiness", "ready");
                row.addProperty("deviceCount", chain.devices.size());
                row.addProperty("devicesAvailable", true);
                row.add("window", DeviceListModule.nativeWindow(chain.devices.size()));
                rows.add(row);
            }
            JsonObject result = new JsonObject();
            result.add("target", parentRef != null ? parentRef : current(walk, parent));
            result.addProperty("scope", "one_chain_hop");
            result.add("window", DeviceListModule.nativeWindow(found.size()));
            result.add("chains", rows);
            return result;
        });
    }

    /** The parent device's ref as read now, for a device without chains to list. */
    private static JsonObject current(NativeChains.Walk walk, JsonObject device) {
        return NativeChains.nested(device) ? walk.device(device, false).ref : device;
    }

    // ---------------------------------------------------------------- insert

    void insert(HostOperation op, JsonObject p) {
        JsonObject parent = p.getAsJsonObject("to");
        JsonObject insert = p.getAsJsonObject("insert");
        String kind = insert.get("kind").getAsString();
        String value = insert.get(kind.equals("file") ? "path" : "id").getAsString();
        chains.edit(op, parent, walk -> {
            NativeChains.Chain chain = walk.chain(parent, false);
            List<Object> before = chain.devices;
            int index = Positions.index(p.get("position"), before.size(), anchor -> anchorIn(walk, chain, anchor));
            Object insertion = walk.a.insertion(kind, value);
            if (insertion == null)
                throw Errors.notApplied(
                        Reason.INVALID_TARGET, "The host knows no device, plug-in or file for this " + kind + ".");
            JsonArray listed = summaries(walk, before);
            walk.dispatch();
            walk.a.insert(chain.chain, index, insertion);
            return new NativeChains.Verify() {
                @Override
                public JsonObject done(NativeChains.Walk now) {
                    NativeChains.Chain found = now.chain(parent, false);
                    List<Object> after = now.now(found);
                    if (sameOrder(after, before)) return null;
                    boolean inserted = after.size() == before.size() + 1
                            && without(before, after.get(index)).size() == before.size()
                            && sameOrder(without(after, after.get(index)), before);
                    return insertResult(now, found, listed, after, index, inserted, true);
                }

                @Override
                public JsonObject timeout(NativeChains.Walk now) {
                    NativeChains.Chain found = now.chain(parent, false);
                    List<Object> after = now.now(found);
                    return insertResult(now, found, listed, after, index, false, !sameOrder(after, before));
                }
            };
        });
    }

    private static JsonObject insertResult(
            NativeChains.Walk walk,
            NativeChains.Chain chain,
            JsonArray before,
            List<Object> after,
            int index,
            boolean ok,
            boolean changed) {
        JsonObject result = conclusion(walk, ok ? Outcome.APPLIED : null, changed, before, after);
        result.addProperty("requestedIdentifierVerified", false);
        result.add("parent", chain.ref);
        if (ok) {
            NativeChains.Device inserted = walk.at(new NativeChains.Chain(chain.chain, chain.ref, after), index);
            result.add("ref", inserted.ref);
            result.add("inserted", DeviceChainEdit.summary(index, inserted.name, walk.a.deviceType(inserted.device)));
        } else result.add("inserted", JsonNull.INSTANCE);
        return result;
    }

    // ---------------------------------------------------------------- delete

    void delete(HostOperation op, JsonObject target, Runnable safety) {
        chains.edit(op, target, walk -> {
            NativeChains.Device device = walk.device(target, true);
            List<Object> before = device.chain.devices;
            JsonArray listed = summaries(walk, before);
            JsonObject deleted = listed.get(device.index).getAsJsonObject();
            JsonObject parent = device.chain.ref;
            walk.dispatch(safety);
            walk.a.delete(device.device);
            return new NativeChains.Verify() {
                @Override
                public JsonObject done(NativeChains.Walk now) {
                    List<Object> after = now.now(now.chain(parent, false));
                    if (sameOrder(after, before)) return null;
                    boolean removed = sameOrder(after, without(before, device.device));
                    JsonObject result = conclusion(now, removed ? Outcome.APPLIED : null, true, listed, after);
                    result.add("deleted", deleted);
                    return result;
                }

                @Override
                public JsonObject timeout(NativeChains.Walk now) {
                    JsonObject result = conclusion(now, null, false, listed, now.now(now.chain(parent, false)));
                    result.add("deleted", deleted);
                    return result;
                }
            };
        });
    }

    // ---------------------------------------------------------------- move

    void move(HostOperation op, JsonObject p, Runnable safety) {
        JsonObject target = p.getAsJsonObject("target");
        chains.edit(op, target, walk -> {
            NativeChains.Device device = walk.device(target, true);
            NativeChains.Chain chain = device.chain;
            if (p.has("to") && !Refs.matches(p.getAsJsonObject("to"), chain.ref, false))
                throw Errors.notApplied(
                        Reason.UNSUPPORTED_SCOPE, "Moving a device to another chain or track is not supported yet.");
            List<Object> before = chain.devices;
            int from = device.index;
            int insertion = Positions.index(p.get("position"), before.size(), anchor -> anchorIn(walk, chain, anchor));
            int dest = insertion > from ? insertion - 1 : insertion;
            if (dest == from) {
                JsonObject result = Results.outcome(Outcome.ALREADY_MATCHED);
                result.add("ref", device.ref);
                return already(result);
            }
            JsonArray listed = summaries(walk, before);
            JsonObject moved = listed.get(from).getAsJsonObject().deepCopy();
            moved.addProperty("toIndex", dest);
            walk.dispatch(safety);
            walk.a.move(device.device, chain.chain, insertion);
            return new NativeChains.Verify() {
                @Override
                public JsonObject done(NativeChains.Walk now) {
                    NativeChains.Chain found = now.chain(chain.ref, false);
                    List<Object> after = now.now(found);
                    if (sameOrder(after, before)) return null;
                    boolean arranged = after.size() == before.size()
                            && after.get(dest) == device.device
                            && sameOrder(without(after, device.device), without(before, device.device));
                    JsonObject result = conclusion(now, arranged ? Outcome.APPLIED : null, true, listed, after);
                    result.add("moved", moved);
                    if (arranged)
                        result.add("ref", now.at(new NativeChains.Chain(found.chain, found.ref, after), dest).ref);
                    return result;
                }

                @Override
                public JsonObject timeout(NativeChains.Walk now) {
                    JsonObject result = conclusion(now, null, false, listed, now.now(now.chain(chain.ref, false)));
                    result.add("moved", moved);
                    return result;
                }
            };
        });
    }

    /** A verification that concludes at once with {@code result}: nothing was dispatched. */
    private static NativeChains.Verify already(JsonObject result) {
        return new NativeChains.Verify() {
            @Override
            public JsonObject done(NativeChains.Walk walk) {
                return result;
            }

            @Override
            public JsonObject timeout(NativeChains.Walk walk) {
                return result;
            }
        };
    }

    // ---------------------------------------------------------------- helpers

    /** The index of an anchor device, which must be a device of {@code chain}. */
    private static int anchorIn(NativeChains.Walk walk, NativeChains.Chain chain, JsonObject anchor) {
        if (!NativeChains.nested(anchor)) throw Errors.invalid("The anchor device is not in this parent.");
        NativeChains.Device found;
        try {
            found = walk.device(anchor, false);
        } catch (RpcException error) {
            if (error.is(Reason.INVALID_PARAMS)) throw Errors.invalid("The anchor device is not in this parent.");
            throw error;
        }
        if (found.chain.chain != chain.chain) throw Errors.invalid("The anchor device is not in this parent.");
        return found.index;
    }

    /**
     * The result of a dispatched edit: applied with the native verification when {@code ok}; otherwise unknown, as
     * readback_mismatch when the chain changed in another way and not_observed when it did not change in time.
     */
    private static JsonObject conclusion(
            NativeChains.Walk walk, Outcome ok, boolean changed, JsonArray before, List<Object> after) {
        JsonObject result = Results.outcome(ok != null ? ok : Outcome.UNKNOWN);
        if (ok == null) result.addProperty("reason", (changed ? Reason.READBACK_MISMATCH : Reason.NOT_OBSERVED).wire());
        else Verification.NATIVE_READBACK.put(result);
        result.addProperty("beforeCount", before.size());
        result.addProperty("afterCount", after.size());
        result.add("before", before);
        result.add("after", summaries(walk, after));
        return result;
    }

    private static JsonArray summaries(NativeChains.Walk walk, List<Object> devices) {
        JsonArray result = new JsonArray();
        for (int i = 0; i < devices.size(); i++) {
            Object device = devices.get(i);
            result.add(DeviceChainEdit.summary(i, walk.a.deviceName(device), walk.a.deviceType(device)));
        }
        return result;
    }

    /** The same objects in the same order. */
    static boolean sameOrder(List<Object> left, List<Object> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (left.get(i) != right.get(i)) return false;
        return true;
    }

    static List<Object> without(List<Object> devices, Object device) {
        List<Object> result = new ArrayList<>(devices.size());
        for (Object each : devices) if (each != device) result.add(each);
        return result;
    }
}
