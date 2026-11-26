package dev.bitwigagent.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Set;

/** Strict, host-free JSON validation shared by the domain boundaries. */
public final class Params {
    private Params() {}

    public static void fields(JsonObject p, Set<String> required, Set<String> optional) {
        if (p == null || !p.keySet().containsAll(required))
            throw Errors.invalid("Parameters must contain required fields " + required);
        for (String key : p.keySet()) {
            if (!required.contains(key) && !optional.contains(key)) throw Errors.invalid("Unknown parameter: " + key);
            if (p.get(key).isJsonNull()) throw Errors.invalid(key + " must not be null.");
        }
    }

    public static String string(JsonObject p, String key) {
        JsonElement value = value(p, key);
        if (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isEmpty()
                || value.getAsString().length() > 4096)
            throw Errors.invalid(key + " must be a nonempty string (maximum 4096 characters).");
        return value.getAsString();
    }

    public static Path absoluteFilePath(JsonObject p, String key) {
        String path = string(p, key);
        try {
            Path parsed = Path.of(path);
            if (!parsed.isAbsolute()
                    || !parsed.normalize().toString().equals(path)
                    || path.codePoints().anyMatch(Character::isISOControl))
                throw Errors.invalid(
                        key + " must be an absolute normalized path without control characters; no shell expansion.");
            return parsed;
        } catch (InvalidPathException error) {
            throw Errors.invalid("Invalid file path.");
        }
    }

    public static int integer(JsonObject p, String key, int min, int max) {
        JsonElement value = value(p, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw Errors.invalid(key + " must be an integer.");
        int number;
        try {
            number = value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw Errors.invalid(key + " must be an integer.");
        }
        if (number < min || number > max) throw Errors.invalid(key + " is outside its range.");
        return number;
    }

    static double number(JsonObject p, String key, double min, double max) {
        JsonElement value = value(p, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw Errors.invalid(key + " must be numeric.");
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number < min || number > max)
            throw Errors.invalid(key + " is outside its range.");
        return number;
    }

    public static boolean bool(JsonObject p, String key) {
        JsonElement value = value(p, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw Errors.invalid(key + " must be boolean.");
        return value.getAsBoolean();
    }

    private static JsonElement value(JsonObject p, String key) {
        JsonElement value = p == null ? null : p.get(key);
        if (value == null || value.isJsonNull()) throw Errors.invalid("Missing or null parameter: " + key);
        return value;
    }
}
