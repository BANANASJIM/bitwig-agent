package dev.bitwigagent;

import static dev.bitwigagent.HostServices.failure;

import com.google.gson.*;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Poly Grid voicing: xlG.Mb2() returns the jBG (SOn) voice model, null on Note Grid and FX Grid. Getters and the public
 * final recorded setters are bound per field by exact descriptor; each getter/setter pair was matched by the model field
 * it reads and writes. A write-binding failure never takes the read bindings down.
 */
final class Bitwig614VoicingAccess implements GridVoicingWriter.Access {
    private static final class Field {
        final String name;
        final Method getter;
        Method setter;
        final Class<?> enumType;

        Field(String name, Method getter, Class<?> enumType) {
            this.name = name;
            this.getter = getter;
            this.enumType = enumType;
        }
    }

    private final Bitwig614Runtime.Access bindings;
    private final Class<?> voicing;
    private final Method model, property;
    private final Map<String, Field> fields = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> enums = new HashMap<>();
    private String setterError;

    Bitwig614VoicingAccess(Bitwig614Runtime.Access bindings) throws ReflectiveOperationException {
        this.bindings = bindings;
        Class<?> device = bindings.type("com.bitwig.flt.document.core.master.device.xlG");
        voicing = bindings.type("jBG");
        Class<?> base = bindings.type("SOn");
        if (voicing.getSuperclass() != base
                || !bindings.type("com.bitwig.ramona.core.AVL").isAssignableFrom(base))
            throw new NoSuchMethodException("Inspected jBG/SOn ancestry differs.");
        model = bindings.exactMethod(device, "Mb2", voicing, false);
        property = bindings.exactMethod(voicing, "IwV", bindings.type("com.bitwig.ramona.core.SYo"), false);
        Class<?> mono = bindings.type("Ybi"), retrigger = bindings.type("GFA"), priority = bindings.type("uZS");
        add("voices", base, "QNk", int.class, null);
        add("voiceStacking", base, "hUR", int.class, null);
        add("monoMode", base, "fFM", mono, mono);
        add("retriggerMode", base, "TD2", retrigger, retrigger);
        add("notePriority", base, "MJs", priority, priority);
        add("legatoGlide", base, "Guv", boolean.class, null);
        add("stealSameKey", base, "jG", boolean.class, null);
        add("allowOverlappingNotes", base, "wZs", boolean.class, null);
        enums.put("monoMode", constants(mono, Map.of("TRUE_MONO", "true_mono", "ALTERNATING_VOICES", "digi_mono")));
        enums.put(
                "retriggerMode",
                constants(retrigger, Map.of("NEVER", "never", "ON_NOTE_ON", "on_note_on", "ALWAYS", "always")));
        enums.put("notePriority", constants(priority, Map.of("LAST", "last", "HIGH", "high", "LOW", "low")));
        try {
            bindSetter("voices", base, "iHb", int.class);
            bindSetter("voiceStacking", base, "dnz", int.class);
            bindSetter("monoMode", base, "FzB", mono);
            bindSetter("retriggerMode", base, "Hnt", retrigger);
            bindSetter("notePriority", base, "Hnt", priority);
            bindSetter("legatoGlide", base, "TMF", boolean.class);
            bindSetter("stealSameKey", base, "TtY", boolean.class);
            bindSetter("allowOverlappingNotes", base, "qoU", boolean.class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            setterError = Bitwig614Runtime.diagnostic(error);
        }
    }

    private void add(String name, Class<?> base, String getter, Class<?> returns, Class<?> enumType)
            throws ReflectiveOperationException {
        Method method = bindings.exactMethod(voicing, getter, returns, false);
        if (method.getDeclaringClass() != base)
            throw new NoSuchMethodException("Expected the inspected SOn voicing getter " + name);
        fields.put(name, new Field(name, method, enumType));
    }

    private void bindSetter(String name, Class<?> base, String setter, Class<?> argument)
            throws ReflectiveOperationException {
        Method method = bindings.exactMethod(voicing, setter, void.class, false, argument);
        if (method.getDeclaringClass() != base || !Modifier.isFinal(method.getModifiers()))
            throw new NoSuchMethodException("Expected the inspected final SOn voicing setter " + name);
        fields.get(name).setter = method;
    }

    private static Map<String, Object> constants(Class<?> type, Map<String, String> names)
            throws NoSuchMethodException {
        Map<String, Object> result = new HashMap<>();
        Object[] all = type.getEnumConstants();
        if (all == null) throw new NoSuchMethodException("Voicing enum has no constants: " + type.getName());
        for (Object constant : all) {
            String key = names.get(((Enum<?>) constant).name());
            if (key != null) result.put(key, constant);
        }
        if (result.size() != names.size())
            throw new NoSuchMethodException("Inspected voicing enum constants differ: " + type.getName());
        return result;
    }

    /** Null when every write operation is bound; otherwise why not. */
    String setterError() {
        return setterError;
    }

    Object property(Object voicingModel) {
        requireModel(voicingModel);
        return bindings.invoke(property, voicingModel);
    }

    @Override
    public Object model(Object device) {
        Object value = bindings.invoke(model, device);
        if (value != null && value.getClass() != voicing)
            throw failure(ErrorCode.GRID_UNAVAILABLE, "Native voicing model is not the exact inspected jBG.");
        return value;
    }

    @Override
    public JsonObject read(Object voicingModel) {
        requireModel(voicingModel);
        JsonObject result = new JsonObject();
        for (Field field : fields.values()) {
            Object value = bindings.invoke(field.getter, voicingModel);
            if (field.enumType != null) {
                String text = null;
                for (Map.Entry<String, Object> entry : enums.get(field.name).entrySet())
                    if (entry.getValue() == value) text = entry.getKey();
                if (text == null)
                    throw failure(
                            ErrorCode.GRID_UNAVAILABLE,
                            "Native voicing " + field.name + " is outside the inspected enum values.");
                result.addProperty(field.name, text);
            } else if (value instanceof Boolean) result.addProperty(field.name, (Boolean) value);
            else if (value instanceof Integer) result.addProperty(field.name, (Integer) value);
            else throw failure(ErrorCode.GRID_UNAVAILABLE, "Native voicing " + field.name + " has an unexpected type.");
        }
        return result;
    }

    @Override
    public void set(Object voicingModel, String name, JsonElement value) {
        requireModel(voicingModel);
        Field field = fields.get(name);
        if (field == null) throw failure(ErrorCode.INVALID_PARAMS, "Unknown voicing field: " + name);
        if (field.setter == null)
            throw failure(bindings.unavailableCode(), "Voicing write operation is not bound: " + setterError);
        Object argument;
        if (field.enumType != null) argument = enums.get(name).get(value.getAsString());
        else if (field.getter.getReturnType() == boolean.class) argument = value.getAsBoolean();
        else argument = value.getAsInt();
        if (argument == null) throw failure(ErrorCode.INVALID_PARAMS, "Unknown voicing value for " + name + ".");
        bindings.invoke(field.setter, voicingModel, argument);
    }

    private void requireModel(Object object) {
        if (object == null || object.getClass() != voicing)
            throw failure(bindings.unavailableCode(), "Voicing requires the exact inspected jBG model.");
    }
}
