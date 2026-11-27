package dev.bitwigagent.device;

import static org.junit.jupiter.api.Assertions.*;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.PortHandle;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.grid.GridRemotePageWriter;
import dev.bitwigagent.host.DeviceSources;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/** How a Remote Controls slot is matched to its native parameter, against stand-ins for the document model. */
public final class NativeRemoteValuesTest {
    private static final Object DEVICE = new Object(), HOST = new Object(), PARAMETER = new Object();

    /** The page model: the visible pages by stored name, and what each slot is mapped to. */
    private static GridRemotePageWriter.Access pages(List<Object> visible, Map<Object, String> names, Object target) {
        return proxy(GridRemotePageWriter.Access.class, (method, args) -> {
            switch (method) {
                case "host":
                    return HOST;
                case "visiblePages":
                    return visible;
                case "pageName":
                    return names.get(args[0]);
                case "slot":
                    return target == null ? null : "slot";
                case "slotTarget":
                    return target;
                default:
                    throw new UnsupportedOperationException(method);
            }
        });
    }

    private static NativeChains.Access values(double value) {
        return proxy(NativeChains.Access.class, (method, args) -> {
            assertEquals("value", method);
            assertSame(PARAMETER, args[0]);
            return value;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> answer) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> answer.apply(m.getName(), a));
    }

    private static Double held(GridRemotePageWriter.Access pages, double value, int page, int count, int slot) {
        return NativeRemoteValues.held(pages, values(value), DEVICE, page, "Main", count, slot);
    }

    @Test
    void theMappedParametersValueIsReadForTheVisiblePageAtTheIndex() {
        Object main = new Object(), other = new Object();
        Map<Object, String> names = new HashMap<>(Map.of(main, "Main", other, "Other"));
        GridRemotePageWriter.Access access = pages(List.of(main, other), names, PARAMETER);
        assertEquals(.6, held(access, .6, 0, 2, 0));
        names.put(main, "");
        assertEquals(.6, held(access, .6, 0, 2, 0), "an unnamed page is matched by its place");
    }

    @Test
    void anythingThatDoesNotMatchTheControllerPagesIsNoAnswer() {
        Object main = new Object(), other = new Object();
        Map<Object, String> names = Map.of(main, "Main", other, "Other");
        GridRemotePageWriter.Access access = pages(List.of(main, other), names, PARAMETER);
        assertNull(held(access, .6, 0, 3, 0), "another page count");
        assertNull(held(access, .6, 2, 2, 0), "a page past the list");
        assertNull(held(access, .6, 1, 2, 0), "another stored name");
        assertNull(held(pages(List.of(main, other), names, null), .6, 0, 2, 0), "an empty slot");
        assertNull(held(access, Double.NaN, 0, 2, 0), "a value that is not finite");
        GridRemotePageWriter.Access noHost = proxy(GridRemotePageWriter.Access.class, (method, args) -> null);
        assertNull(held(noHost, .6, 0, 2, 0), "a device without a Remote Controls host");
    }

    @Test
    void theValueIsReadOnTheDocumentThreadFromTheVerifiedRootDevice() {
        DeviceFixture f = DeviceFixture.twins();
        Object main = new Object();
        FakeChains.Param mapped = new FakeChains.Param("Gain", .6);
        NativeRemoteValues model = new NativeRemoteValues(
                PortHandle.of(pages(List.of(main), Map.of(main, "Main"), mapped)), f.services.chains);
        DeviceSources.Source device = f.services.sources.device(f.twin(1), false);
        List<Double> held = new ArrayList<>();
        List<RuntimeException> failed = new ArrayList<>();
        model.read(new HostOperation(Long.MAX_VALUE), device, 0, "Main", 1, 0, held::add, failed::add);
        model.read(new HostOperation(Long.MAX_VALUE), device, 0, "Main", 2, 0, held::add, failed::add);
        assertEquals(Arrays.asList(.6, null), held);
        assertTrue(failed.isEmpty());
    }

    @Test
    void aBindingThatIsUnavailableIsNoAnswerAndAnyOtherFailureIsReported() {
        DeviceFixture f = DeviceFixture.twins();
        DeviceSources.Source device = f.services.sources.device(f.twin(1), false);
        List<Double> held = new ArrayList<>();
        List<RuntimeException> failed = new ArrayList<>();
        new NativeRemoteValues(
                        op -> {
                            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "no binding");
                        },
                        f.services.chains)
                .read(new HostOperation(Long.MAX_VALUE), device, 0, "Main", 1, 0, held::add, failed::add);
        assertEquals(Arrays.asList((Double) null), held);
        new NativeRemoteValues(
                        op -> {
                            throw Errors.notApplied(Reason.BUSY, "busy");
                        },
                        f.services.chains)
                .read(new HostOperation(Long.MAX_VALUE), device, 0, "Main", 1, 0, held::add, failed::add);
        assertEquals(1, held.size());
        assertEquals(1, failed.size());
    }

    @Test
    void onlyAnUnavailableBindingFallsBackAndAnyOtherFailureIsAnError() {
        List<Double> done = new ArrayList<>();
        List<RuntimeException> failed = new ArrayList<>();
        NativeRemoteValues.unavailable(Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "m"), done::add, failed::add);
        NativeRemoteValues.unavailable(Errors.notApplied(Reason.HOST_MISMATCH, "m"), done::add, failed::add);
        assertEquals(Arrays.asList(null, null), done);
        assertTrue(failed.isEmpty());
        NativeRemoteValues.unavailable(Errors.notApplied(Reason.NOT_READY, "m"), done::add, failed::add);
        NativeRemoteValues.unavailable(new IllegalStateException("x"), done::add, failed::add);
        assertEquals(2, done.size());
        assertEquals(2, failed.size());
    }
}
