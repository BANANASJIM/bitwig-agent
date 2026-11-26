package dev.bitwigagent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Stream;

/**
 * Exact 6.1.4 Remote Controls page model. Device xlG.AgD() is the pnr host; LQR.BNq() its sZK "preset_pages" list
 * (the list the UI's "Add New Page" and the controller API's createPresetPage use, and the only one whose eligibility
 * vNq.cfM(v3U) admits Grid module parameters) and BQF.fFs() its riz "device_pages" list (read only here); pages are
 * vNq (faW) holding iKw slots. The writes are the native operations the UI uses, each opening its own named undoable
 * change: riz.OzW() "Create Page" (inherited by sZK), vNq.cfM(int,String,SYo) "Map Remote Control Slot" (what
 * vNq.cfM(int,v3U) and bDH's map-mode path call), vNq.MRY(int) "Delete Remote Control", riz.cfM(FyM) "Delete Page",
 * and the public final recorded name setter faW.cfM(String).
 */
final class Bitwig614RemotePageAccess implements GridRemotePageWriter.Access {
    private static final String D = "com.bitwig.flt.document.core.master.device.";
    private final Bitwig614Runtime.Access bindings;
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

    Bitwig614RemotePageAccess(Bitwig614Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        deviceType = bindings.type(D + "xlG");
        Class<?> hostType = bindings.type(D + "pnr");
        devicePageList = bindings.type(D + "riz");
        presetPageList = bindings.type(D + "sZK");
        pageType = bindings.type(D + "vNq");
        slotType = bindings.type(D + "iKw");
        parameterType = bindings.type("bDH");
        Class<?> model = bindings.type("com.bitwig.ramona.core.AVL"), pageInterface = bindings.type("FyM");
        Class<?> referenceType = bindings.type("com.bitwig.flt.document.core.master.iok");
        Class<?> slotTargetInterface = bindings.type("com.bitwig.flt.document.core.iface.SYo");
        if (presetPageList.getSuperclass() != bindings.type(D + "XF")
                || presetPageList.getSuperclass().getSuperclass() != devicePageList
                || pageType.getSuperclass() != bindings.type(D + "faW")
                || slotType.getSuperclass() != bindings.type(D + "Nxu")
                || !pageInterface.isAssignableFrom(pageType)
                || !slotTargetInterface.isAssignableFrom(referenceType)
                || !bindings.type("v3U").isAssignableFrom(parameterType))
            throw new NoSuchMethodException("Inspected Remote Controls page model ancestry differs.");
        host = method(deviceType, "AgD", hostType, D + "FDA");
        devicePages = method(hostType, "fFs", devicePageList, D + "BQF");
        presetPages = method(hostType, "BNq", presetPageList, D + "LQR");
        allPages = method(hostType, "cR_", Stream.class, D + "pnr");
        visiblePages = method(hostType, "uWE", Stream.class, D + "pnr");
        listPages = method(devicePageList, "IwV", List.class, D + "GPn");
        pageList = method(pageType, "MIO", devicePageList, D + "vNq");
        pageOwner = method(pageType, "vpw", bindings.type(D + "hgq"), D + "vNq");
        pageName = method(pageType, "E3", String.class, D + "faW");
        licensed = method(deviceType, "TgI", boolean.class, D + "Dgc");
        nearest = method(model, "hUR", Object.class, "com.bitwig.ramona.core.AVL", Class.class);
        mappableFlag = method(parameterType, "zpW", boolean.class, "bDH");
        eligible = method(pageType, "cfM", boolean.class, D + "vNq", bindings.type("v3U"));
        slot = method(pageType, "Hnt", slotType, D + "vNq", int.class);
        slotTarget = method(slotType, "LaE", parameterType, D + "iKw");
        slotLabel = method(slotType, "UNj", String.class, D + "Nxu");
        create = method(devicePageList, "OzW", pageType, D + "riz");
        rename = method(pageType, "cfM", void.class, D + "faW", String.class);
        if (!Modifier.isFinal(rename.getModifiers()))
            throw new NoSuchMethodException("Expected the final faW.cfM(String) name setter.");
        reference = bindings.exactMethod(referenceType, "TMF", referenceType, true, parameterType);
        map = method(pageType, "cfM", void.class, D + "vNq", int.class, String.class, slotTargetInterface);
        clear = method(pageType, "MRY", void.class, D + "vNq", int.class);
        delete = method(devicePageList, "cfM", void.class, D + "riz", pageInterface);
    }

    private Method method(Class<?> owner, String name, Class<?> returns, String declaring, Class<?>... arguments)
            throws ReflectiveOperationException {
        Method method = bindings.exactMethod(owner, name, returns, false, arguments);
        if (method.getDeclaringClass() != bindings.type(declaring))
            throw new NoSuchMethodException(
                    "Expected the inspected " + declaring + "." + name + " Remote Controls operation.");
        return method;
    }

    @Override
    public Object host(Object device) {
        if (!deviceType.isInstance(device))
            throw Errors.notApplied(ErrorCode.GRID_UNAVAILABLE, "Remote Controls pages require a native xlG device.");
        return bindings.invoke(host, device);
    }

    @Override
    public Object devicePages(Object host) {
        Object list = bindings.invoke(devicePages, host);
        if (list != null && list.getClass() != devicePageList)
            throw Errors.notApplied(
                    ErrorCode.GRID_UNAVAILABLE, "Native device-page list is not the exact inspected riz.");
        return list;
    }

    @Override
    public Object presetPages(Object host) {
        Object list = bindings.invoke(presetPages, host);
        if (list != null && list.getClass() != presetPageList)
            throw Errors.notApplied(
                    ErrorCode.GRID_UNAVAILABLE, "Native preset-page list is not the exact inspected sZK.");
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
                    ErrorCode.GRID_UNAVAILABLE, "Native page list is not the exact inspected sZK or riz.");
        Object value = bindings.invoke(listPages, list);
        if (!(value instanceof List<?>))
            throw Errors.notApplied(ErrorCode.GRID_UNAVAILABLE, "Native page list is unavailable.");
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
                    ErrorCode.INVALID_PARAMS, "Remote control slot, label and native parameter are required.");
        Object target = bindings.invoke(reference, null, parameter);
        if (target == null)
            throw Errors.notApplied(ErrorCode.GRID_UNAVAILABLE, "Native parameter reference is unavailable.");
        bindings.invoke(map, page, index, label, target);
    }

    @Override
    public void clearSlot(Object page, int index) {
        requireType(pageType, page, "page");
        if (index < 0 || index >= GridRemotePageWriter.SLOTS)
            throw Errors.notApplied(ErrorCode.INVALID_PARAMS, "Remote control slot is out of range.");
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
            throw Errors.notApplied(ErrorCode.GRID_UNAVAILABLE, "Native page stream is unavailable.");
        List<Object> result = new ArrayList<>();
        try (Stream<?> pages = (Stream<?>) value) {
            Iterator<?> iterator = pages.iterator();
            while (iterator.hasNext()) {
                Object page = iterator.next();
                requireType(pageType, page, "page");
                result.add(page);
                if (result.size() > 4096)
                    throw Errors.notApplied(
                            ErrorCode.GRID_BOUNDS_EXCEEDED, "Native Remote Controls page list exceeds 4096 pages.");
            }
        }
        return result;
    }

    private static void requireType(Class<?> type, Object value, String label) {
        if (value == null || value.getClass() != type)
            throw Errors.notApplied(
                    ErrorCode.GRID_UNAVAILABLE,
                    "Native Remote Controls " + label + " is not the exact inspected " + type.getSimpleName() + ".");
    }
}
