package dev.bitwigagent.host;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * What one request saw of its target, taken once any internal remote-page switch or scene scroll has settled and
 * before dispatch. It lives only in the request, never in a ref.
 */
public final class RequestSnapshot {
    private final List<Supplier<Object>> readers = new ArrayList<>();
    private final List<Object> seen = new ArrayList<>();

    /** A root device proxy's revision, which structure changes and renames move. */
    public static RequestSnapshot of(DeviceSources.Source device) {
        return new RequestSnapshot().with(() -> device.revision);
    }

    /** {@code reader} returns a value compared by equality, so it copies anything mutable. */
    public RequestSnapshot with(Supplier<Object> reader) {
        readers.add(reader);
        seen.add(reader.get());
        return this;
    }

    /** A change before dispatch refuses the request as stale; after dispatch its outcome is unknown. */
    public void require(HostOperation operation) {
        for (int i = 0; i < readers.size(); i++) {
            if (Objects.equals(seen.get(i), readers.get(i).get())) continue;
            if (operation.dispatched())
                throw Errors.unknown(
                        Reason.TARGET_CHANGED, "The target changed while the request ran; outcome unknown.");
            throw Errors.notApplied(Reason.TARGET_CHANGED, "The target changed before dispatch; nothing was sent.");
        }
    }
}
