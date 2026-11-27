package dev.bitwigagent.nativehost;

import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.grid.GridRemotePageWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Stream;

/**
 * Exact Remote Controls page model. device.remoteHost() is the page host; remote.host.presetPages() its
 * "preset_pages" list (the list the UI's "Add New Page" and the controller API's createPresetPage use, and the only one
 * whose eligibility remote.page.eligible admits Grid module parameters) and remote.host.devicePages() its
 * "device_pages" list (read only here); pages hold remote slots. The writes are the native operations the UI uses, each
 * opening its own named undoable change: remote.devicePages.create "Create Page" (inherited by the preset-page list),
 * remote.page.map "Map Remote Control Slot" (what eligibility mapping and the parameter's map-mode path call),
 * remote.page.clear "Delete Remote Control", remote.devicePages.delete "Delete Page", and the public final recorded
 * name setter remote.page.rename.
 */
public final class NativeRemotePageAccess implements GridRemotePageWriter.Access {
    private final NativeHost.Access bindings;
    private final Class<?> pageType, slotType, parameterType, deviceType, devicePageList, presetPageList;
    private final Method host,
            devicePages,
            presetPages,
            allPages,
            visiblePages,
            listPages,
            pageList,
            pageOwner,
            pageName,
            licensed,
            nearest,
            mappableFlag,
            eligible,
            slot,
            slotTarget,
            slotLabel,
            create,
            rename,
            reference,
            map,
            clear,
            delete;

    public NativeRemotePageAccess(NativeHost.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        deviceType = bindings.type("device");
        Class<?> hostType = bindings.type("remote.host");
        devicePageList = bindings.type("remote.devicePages");
        presetPageList = bindings.type("remote.presetPages");
        pageType = bindings.type("remote.page");
        slotType = bindings.type("remote.slot");
        parameterType = bindings.type("parameter");
        Class<?> pageInterface = bindings.type("remote.pageInterface");
        Class<?> referenceType = bindings.type("parameter.reference");
        Class<?> slotTargetInterface = bindings.type("remote.slotTarget");
        if (presetPageList.getSuperclass() != bindings.type("remote.presetPagesBase")
                || presetPageList.getSuperclass().getSuperclass() != devicePageList
                || pageType.getSuperclass() != bindings.type("remote.pageBase")
                || slotType.getSuperclass() != bindings.type("remote.slotBase")
                || !pageInterface.isAssignableFrom(pageType)
                || !slotTargetInterface.isAssignableFrom(referenceType)
                || !bindings.type("parameter.target").isAssignableFrom(parameterType))
            throw new NoSuchMethodException("Inspected Remote Controls page model ancestry differs.");
        host = method("device.remoteHost", hostType, "device.element");
        devicePages = method("remote.host.devicePages", devicePageList, "remote.hostDevicePages");
        presetPages = method("remote.host.presetPages", presetPageList, "remote.hostPresetPages");
        allPages = method("remote.host.allPages", Stream.class, "remote.host");
        visiblePages = method("remote.host.visiblePages", Stream.class, "remote.host");
        listPages = method("remote.devicePages.pages", List.class, "remote.pageListBase");
        pageList = method("remote.page.list", devicePageList, "remote.page");
        pageOwner = method("remote.page.owner", bindings.type("device.component"), "remote.page");
        pageName = method("remote.page.name", String.class, "remote.pageBase");
        licensed = method("device.licensed", boolean.class, "device.named");
        nearest = method("model.object.nearest", Object.class, "model.object", Class.class);
        mappableFlag = method("parameter.remoteMappable", boolean.class, "parameter");
        eligible = method("remote.page.eligible", boolean.class, "remote.page", bindings.type("parameter.target"));
        slot = method("remote.page.slot", slotType, "remote.page", int.class);
        slotTarget = method("remote.slot.target", parameterType, "remote.slot");
        slotLabel = method("remote.slot.label", String.class, "remote.slotBase");
        create = method("remote.devicePages.create", pageType, "remote.devicePages");
        rename = method("remote.page.rename", void.class, "remote.pageBase", String.class);
        if (!Modifier.isFinal(rename.getModifiers()))
            throw new NoSuchMethodException("Expected the final remote.page.rename name setter.");
        reference = bindings.exactMethod("parameter.reference.of", referenceType, true, parameterType);
        map = method("remote.page.map", void.class, "remote.page", int.class, String.class, slotTargetInterface);
        clear = method("remote.page.clear", void.class, "remote.page", int.class);
        delete = method("remote.devicePages.delete", void.class, "remote.devicePages", pageInterface);
    }

    private Method method(String key, Class<?> returns, String declaring, Class<?>... arguments)
            throws ReflectiveOperationException {
        Method method = bindings.exactMethod(key, returns, false, arguments);
        if (method.getDeclaringClass() != bindings.type(declaring))
            throw new NoSuchMethodException("Expected the inspected " + key + " Remote Controls operation.");
        return method;
    }

    @Override
    public Object host(Object device) {
        if (!deviceType.isInstance(device))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Remote Controls pages require a native device.");
        return bindings.invoke(host, device);
    }

    @Override
    public Object devicePages(Object host) {
        Object list = bindings.invoke(devicePages, host);
        if (list != null && list.getClass() != devicePageList)
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Native device-page list is not the exact inspected device-page list.");
        return list;
    }

    @Override
    public Object presetPages(Object host) {
        Object list = bindings.invoke(presetPages, host);
        if (list != null && list.getClass() != presetPageList)
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE, "Native preset-page list is not the exact inspected preset-page list.");
        return list;
    }

    @Override
    public List<Object> allPages(Object host) {
        return stream(allPages, host);
    }

    @Override
    public List<Object> visiblePages(Object host) {
        return stream(visiblePages, host);
    }

    @Override
    public List<Object> pages(Object list) {
        if (list == null || (list.getClass() != presetPageList && list.getClass() != devicePageList))
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Native page list is not the exact inspected preset-page or device-page list.");
        Object value = bindings.invoke(listPages, list);
        if (!(value instanceof List<?>))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native page list is unavailable.");
        List<Object> result = new ArrayList<>((List<?>) value);
        for (Object page : result) requireType(pageType, page, "page");
        return result;
    }

    @Override
    public Object pageList(Object page) {
        requireType(pageType, page, "page");
        return bindings.invoke(pageList, page);
    }

    @Override
    public Object pageOwner(Object page) {
        requireType(pageType, page, "page");
        return bindings.invoke(pageOwner, page);
    }

    @Override
    public String pageName(Object page) {
        requireType(pageType, page, "page");
        Object name = bindings.invoke(pageName, page);
        return name instanceof String ? (String) name : null;
    }

    @Override
    public boolean licensed(Object device) {
        return Boolean.TRUE.equals(bindings.invoke(licensed, device));
    }

    @Override
    public boolean mappable(Object parameter, Object device) {
        if (!parameterType.isInstance(parameter)) return false;
        return bindings.invoke(nearest, parameter, deviceType) == device
                && Boolean.TRUE.equals(bindings.invoke(mappableFlag, parameter));
    }

    @Override
    public boolean eligible(Object page, Object parameter) {
        requireType(pageType, page, "page");
        return parameterType.isInstance(parameter) && Boolean.TRUE.equals(bindings.invoke(eligible, page, parameter));
    }

    @Override
    public Object slot(Object page, int index) {
        requireType(pageType, page, "page");
        Object value = bindings.invoke(slot, page, index);
        if (value != null) requireType(slotType, value, "slot");
        return value;
    }

    @Override
    public Object slotTarget(Object slot) {
        requireType(slotType, slot, "slot");
        return bindings.invoke(slotTarget, slot);
    }

    @Override
    public String slotLabel(Object slot) {
        requireType(slotType, slot, "slot");
        Object label = bindings.invoke(slotLabel, slot);
        return label instanceof String ? (String) label : null;
    }

    @Override
    public Object createPage(Object list) {
        requireType(presetPageList, list, "preset-page list");
        Object page = bindings.invoke(create, list);
        if (page != null) requireType(pageType, page, "page");
        return page;
    }

    @Override
    public void renamePage(Object page, String name) {
        requireType(pageType, page, "page");
        bindings.invoke(rename, page, name);
    }

    @Override
    public void mapSlot(Object page, int index, String label, Object parameter) {
        requireType(pageType, page, "page");
        if (index < 0 || index >= GridRemotePageWriter.SLOTS || label == null || !parameterType.isInstance(parameter))
            throw Errors.notApplied(
                    Reason.INVALID_PARAMS, "Remote control slot, label and native parameter are required.");
        Object target = bindings.invoke(reference, null, parameter);
        if (target == null)
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native parameter reference is unavailable.");
        bindings.invoke(map, page, index, label, target);
    }

    @Override
    public void clearSlot(Object page, int index) {
        requireType(pageType, page, "page");
        if (index < 0 || index >= GridRemotePageWriter.SLOTS)
            throw Errors.notApplied(Reason.INVALID_PARAMS, "Remote control slot is out of range.");
        bindings.invoke(clear, page, index);
    }

    @Override
    public void deletePage(Object list, Object page) {
        requireType(presetPageList, list, "preset-page list");
        requireType(pageType, page, "page");
        bindings.invoke(delete, list, page);
    }

    private List<Object> stream(Method method, Object host) {
        Object value = bindings.invoke(method, host);
        if (!(value instanceof Stream<?>))
            throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Native page stream is unavailable.");
        List<Object> result = new ArrayList<>();
        try (Stream<?> pages = (Stream<?>) value) {
            Iterator<?> iterator = pages.iterator();
            while (iterator.hasNext()) {
                Object page = iterator.next();
                requireType(pageType, page, "page");
                result.add(page);
                if (result.size() > 4096)
                    throw Errors.notApplied(
                            Reason.OUT_OF_BOUNDS, "Native Remote Controls page list exceeds 4096 pages.");
            }
        }
        return result;
    }

    private static void requireType(Class<?> type, Object value, String label) {
        if (value == null || value.getClass() != type)
            throw Errors.notApplied(
                    Reason.NATIVE_UNAVAILABLE,
                    "Native Remote Controls " + label + " is not the exact inspected " + type.getSimpleName() + ".");
    }
}
