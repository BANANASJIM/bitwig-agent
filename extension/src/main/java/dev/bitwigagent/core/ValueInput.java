package dev.bitwigagent.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Locale;

/** A write value: the bare value a read returned, {display: text} resolved by the host, or {label: text}. */
public final class ValueInput {
    public enum Form {
        BARE,
        DISPLAY,
        LABEL
    }

    public final Form form;
    /** The bare value, for {@link Form#BARE}. */
    public final JsonPrimitive bare;
    /** The display or label text, for the other forms. */
    public final String text;

    private ValueInput(Form form, JsonPrimitive bare, String text) {
        this.form = form;
        this.bare = bare;
        this.text = text;
    }

    public static ValueInput parse(String field, JsonElement value) {
        if (value != null && value.isJsonPrimitive())
            return new ValueInput(Form.BARE, value.getAsJsonPrimitive(), null);
        if (value != null && value.isJsonObject() && value.getAsJsonObject().size() == 1) {
            JsonObject object = value.getAsJsonObject();
            for (Form form : new Form[] {Form.DISPLAY, Form.LABEL}) {
                JsonElement text = object.get(form.name().toLowerCase(Locale.ROOT));
                if (text != null
                        && text.isJsonPrimitive()
                        && text.getAsJsonPrimitive().isString()
                        && !text.getAsString().isEmpty()) return new ValueInput(form, null, text.getAsString());
            }
        }
        throw Errors.invalid(field + " takes the bare value a read returned, {display: text} or {label: text}.");
    }

    /** The bare value as a normalized host parameter value; a display this field cannot resolve is unsupported_unit. */
    public double normalized(String field) {
        if (form == Form.DISPLAY)
            throw Errors.notApplied(
                    Reason.UNSUPPORTED_UNIT, field + " cannot resolve a display here; write the value you read.");
        if (form != Form.BARE || !bare.isNumber()) throw Errors.invalid(field + " must be a number from 0 to 1.");
        double value = bare.getAsDouble();
        if (!(value >= 0 && value <= 1)) throw Errors.invalid(field + " must be a number from 0 to 1.");
        return value;
    }
}
