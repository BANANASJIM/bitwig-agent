package dev.bitwigagent.device;

import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.PortHandle;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.grid.GridRemotePageWriter;
import dev.bitwigagent.host.DeviceSources;
import java.util.List;
import java.util.function.Consumer;

/**
 * What a Remote Controls slot holds, read from the native document: the page shown at an index of the device's visible
 * pages, its slot's mapped parameter and that parameter's value, the same read a native chain parameter write checks
 * against. Anything that cannot be matched to the page the controller API shows (another page count, another stored
 * name, an empty slot, an unavailable binding) is no answer rather than a guess.
 */
public final class NativeRemoteValues implements RemoteModule.NativeValues {
    private final PortHandle<GridRemotePageWriter.Access> pages;
    private final NativeChains chains;

    /** {@code pages}: the native Remote Controls page model, bound on first use. */
    public NativeRemoteValues(PortHandle<GridRemotePageWriter.Access> pages, NativeChains chains) {
        this.pages = pages;
        this.chains = chains;
    }

    @Override
    public void read(
            HostOperation op,
            DeviceSources.Source device,
            int page,
            String pageName,
            int pageCount,
            int slot,
            Consumer<Double> done,
            Consumer<RuntimeException> failed) {
        try {
            GridRemotePageWriter.Access access = pages.get(op);
            chains.async(
                    op,
                    device.ref(),
                    walk -> done.accept(
                            held(access, walk.a, walk.owner(device.ref()), page, pageName, pageCount, slot)),
                    error -> unavailable(error, done, failed));
        } catch (RpcException error) {
            unavailable(error, done, failed);
        }
    }

    static void unavailable(RuntimeException error, Consumer<Double> done, Consumer<RuntimeException> failed) {
        if (error instanceof RpcException
                && (((RpcException) error).is(Reason.NATIVE_UNAVAILABLE)
                        || ((RpcException) error).is(Reason.HOST_MISMATCH))) done.accept(null);
        else failed.accept(error);
    }

    static Double held(
            GridRemotePageWriter.Access access,
            NativeChains.Access chains,
            Object device,
            int page,
            String pageName,
            int pageCount,
            int slot) {
        Object host = access.host(device);
        if (host == null) return null;
        List<Object> visible = access.visiblePages(host);
        if (visible.size() != pageCount || page >= visible.size()) return null;
        Object shown = visible.get(page);
        String stored = access.pageName(shown);
        if (stored != null && !stored.isEmpty() && !stored.equals(pageName)) return null;
        Object mapped = access.slot(shown, slot);
        Object parameter = mapped == null ? null : access.slotTarget(mapped);
        if (parameter == null) return null;
        double value = chains.value(parameter);
        return Double.isFinite(value) ? value : null;
    }
}
