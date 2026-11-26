package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * One node of a simulated Bitwig host API tree, served through a dynamic proxy.
 *
 * <p>Strict by default: a call that the test did not declare throws, so a module can never lean on a silently
 * invented default. Declare a child with {@link #child}, a value with {@link #put}, behaviour with {@link #on}.
 */
final class FakeHost implements InvocationHandler {
    private static final Object[] NO_ARGS = {};
    private static final Object NO_MATCH = new Object();
    private static final Set<String> VALUE_READS = Set.of("get", "getRaw", "getAsDouble");
    private static final Set<String> VALUE_WRITES = Set.of("set", "setRaw", "setImmediately");
    private static final Set<String> VALUE_OBSERVERS = Set.of("addValueObserver", "addRawValueObserver");

    final String path;
    final Map<String, FakeHost> children = new LinkedHashMap<>();
    final Map<String, Integer> calls = new HashMap<>();
    final List<Object> observers = new ArrayList<>();
    private final FakeHost parent;
    private final Map<Class<?>, Object> proxies = new HashMap<>();
    private final Map<String, Object> constants = new HashMap<>();
    private final Map<String, Function<Object[], Object>> handlers = new HashMap<>();
    private Map<IntegerValueChangedCallback, Integer> unassignedObservers;
    private Runnable writeGuard = () -> {};
    private Class<?> itemType;
    private Class<?> type;
    private Supplier<Object> source;

    Object data;
    BiConsumer<String, Object[]> onCall = (method, args) -> {};
    Runnable beforeWrite = () -> {};
    Runnable afterWrite = () -> {};
    boolean notifyInitial = true;
    boolean notifyWrites = true;
    boolean updateWrites = true;
    boolean takeoverPending;
    int writes;
    int actions;

    FakeHost(String name) {
        this(null, name);
    }

    /** A standalone node whose {@link #proxy()} implements {@code type}. */
    static FakeHost of(Class<?> type, String name) {
        FakeHost node = new FakeHost(name);
        node.type = type;
        return node;
    }

    private FakeHost(FakeHost parent, String name) {
        this.parent = parent;
        this.path = parent == null ? name : parent.path + "." + name;
    }

    /** Declares (or returns) the named child: a sub-object, a value, or both. */
    FakeHost child(String name) {
        return children.computeIfAbsent(name, key -> new FakeHost(this, key));
    }

    /** Declares a method whose behaviour (and result, null for void) is {@code handler}. */
    FakeHost on(String method, Function<Object[], Object> handler) {
        handlers.put(method, handler);
        return this;
    }

    /** Declares {@code handler} for every method of {@code api} whose name passes {@code selected}. */
    FakeHost onMethods(Class<?> api, Predicate<String> selected, BiFunction<String, Object[], Object> handler) {
        for (Method method : api.getMethods())
            if (selected.test(method.getName())) on(method.getName(), args -> handler.apply(method.getName(), args));
        return this;
    }

    /** Declares a method that always answers {@code value}, including null. */
    FakeHost returns(String method, Object value) {
        constants.put(method, value);
        return this;
    }

    /**
     * An integer value like the host's assignable ones: observers registered with a value-when-unassigned are kept
     * apart from ordinary ones, and re-emitting an equal value is not a change.
     */
    static FakeHost assignmentInteger(String name) {
        FakeHost node = new FakeHost(name);
        node.data = 0;
        node.unassignedObservers = new IdentityHashMap<>();
        return node;
    }

    void unassign() {
        boolean changed = !Objects.equals(data, 0);
        data = 0;
        if (changed) for (Object observer : observers) notifyObserver(observer);
        unassignedObservers.forEach(IntegerValueChangedCallback::valueChanged);
    }

    /** Declares a separately built subtree as the named child. */
    FakeHost attach(String name, FakeHost node) {
        children.put(name, node);
        return node;
    }

    /** The API type of this bank's items, which {@code getItemAt} reports erased. */
    FakeHost items(Class<?> type) {
        itemType = type;
        return this;
    }

    FakeHost item(int index) {
        return child("item" + index);
    }

    /** This value reads {@code supplier} afresh each time, for state owned by the surrounding fixture. */
    FakeHost reading(Supplier<Object> supplier) {
        source = supplier;
        return this;
    }

    FakeHost live(String name, Supplier<Object> supplier) {
        return child(name).reading(supplier);
    }

    Object proxy() {
        return proxyOf(type);
    }

    /** Declares a value child holding {@code value}. */
    FakeHost put(String name, Object value) {
        FakeHost node = child(name);
        node.data = value;
        return node;
    }

    /** Every host write anywhere under this root runs {@code guard} first. */
    void guardWrites(Runnable guard) {
        writeGuard = guard;
    }

    <T> T proxy(Class<T> type) {
        return type.cast(proxyOf(type));
    }

    private Object proxyOf(Class<?> type) {
        return proxies.computeIfAbsent(
                type, key -> Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, this));
    }

    int calls(String method) {
        return calls.getOrDefault(method, 0);
    }

    void listen(Object observer) {
        observers.add(observer);
        if (notifyInitial) notifyObserver(observer);
    }

    void emit(Object value) {
        boolean changed = !Objects.equals(data, value);
        data = value;
        if (unassignedObservers != null && !changed) return;
        for (Object observer : observers) notifyObserver(observer);
        if (unassignedObservers != null)
            for (IntegerValueChangedCallback observer : unassignedObservers.keySet())
                observer.valueChanged(((Number) data).intValue());
    }

    float[] rgb() {
        return data instanceof float[] ? (float[]) data : new float[] {0f, 0f, 0f};
    }

    @Override
    public Object invoke(Object self, Method method, Object[] supplied) {
        String name = method.getName();
        Object[] args = supplied == null ? NO_ARGS : supplied;
        if (name.equals("toString")) return path;
        if (name.equals("hashCode")) return System.identityHashCode(self);
        if (name.equals("equals")) return self == args[0];
        calls.merge(name, 1, Integer::sum);
        onCall.accept(name, args);
        refresh();
        if (name.equals("markInterested")) return null;
        Function<Object[], Object> handler = handlers.get(name);
        if (handler != null) return handler.apply(args);
        if (constants.containsKey(name)) return constants.get(name);
        if (data != null) {
            Object result = valueCall(self, method, args);
            if (result != NO_MATCH) return result;
        }
        FakeHost declared = children.get(name.equals("getItemAt") ? "item" + args[0] : name);
        if (declared != null && method.getReturnType().isInterface())
            return declared.proxyOf(name.equals("getItemAt") && itemType != null ? itemType : method.getReturnType());
        throw new AssertionError("Unexpected host API call: " + path + "." + name + "(" + args.length + " args)");
    }

    private Object valueCall(Object self, Method method, Object[] args) {
        String name = method.getName();
        Class<?> type = method.getReturnType();
        if (VALUE_OBSERVERS.contains(name)) {
            if (unassignedObservers != null
                    && args.length == 2
                    && args[0] instanceof IntegerValueChangedCallback
                    && (Integer) args[1] != 0) {
                unassignedObservers.put((IntegerValueChangedCallback) args[0], (Integer) args[1]);
                return null;
            }
            Object callback = args[0] instanceof IntegerValueChangedCallback ? args[0] : args[args.length - 1];
            // Native EnumValue rejects a StringValue callback despite the erased API signature.
            if (self instanceof EnumValue) EnumValueChangedCallback.class.cast(callback);
            listen(callback);
            return null;
        }
        if (type == float.class && data instanceof float[])
            return rgb()[name.equals("red") ? 0 : name.equals("green") ? 1 : 2];
        if (VALUE_WRITES.contains(name)) {
            writeGuard().run();
            beforeWrite.run();
            writes++;
            if (takeoverPending && name.equals("set")) return null;
            if (updateWrites)
                data = args.length == 3 && args[0] instanceof Float
                        ? new float[] {(Float) args[0], (Float) args[1], (Float) args[2]}
                        : args[0];
            if (notifyWrites) for (Object observer : observers) notifyObserver(observer);
            afterWrite.run();
            return null;
        }
        if (VALUE_READS.contains(name)) return data;
        return NO_MATCH;
    }

    Runnable writeGuard() {
        return parent == null ? writeGuard : parent.writeGuard();
    }

    private void refresh() {
        if (source != null) data = source.get();
    }

    private void notifyObserver(Object observer) {
        refresh();
        if (observer instanceof BooleanValueChangedCallback)
            ((BooleanValueChangedCallback) observer).valueChanged(flag());
        else if (observer instanceof IntegerValueChangedCallback)
            ((IntegerValueChangedCallback) observer).valueChanged(number().intValue());
        else if (observer instanceof DoubleValueChangedCallback)
            ((DoubleValueChangedCallback) observer).valueChanged(number().doubleValue());
        else if (observer instanceof StringValueChangedCallback)
            ((StringValueChangedCallback) observer).valueChanged(text());
        else if (observer instanceof EnumValueChangedCallback)
            ((EnumValueChangedCallback) observer).valueChanged(text());
        else if (observer instanceof ColorValueChangedCallback) {
            float[] c = rgb();
            ((ColorValueChangedCallback) observer).valueChanged(c[0], c[1], c[2]);
        } else if (observer instanceof StringArrayValueChangedCallback)
            ((StringArrayValueChangedCallback) observer)
                    .valueChanged(data instanceof String[] ? (String[]) data : new String[0]);
        else if (observer instanceof ObjectValueChangedCallback) {
            @SuppressWarnings("unchecked")
            ObjectValueChangedCallback<Object> callback = (ObjectValueChangedCallback<Object>) observer;
            callback.valueChanged(data);
        } else throw new AssertionError("Unexpected observer " + observer.getClass());
    }

    private boolean flag() {
        if (data instanceof Boolean) return (Boolean) data;
        throw new AssertionError("Boolean observer on non-boolean value " + path + ": " + data);
    }

    private Number number() {
        if (data instanceof Number) return (Number) data;
        throw new AssertionError("Numeric observer on non-numeric value " + path + ": " + data);
    }

    private String text() {
        if (data instanceof String) return (String) data;
        throw new AssertionError("String observer on non-string value " + path + ": " + data);
    }
}
