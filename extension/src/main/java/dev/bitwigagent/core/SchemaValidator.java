package dev.bitwigagent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates a request against the JSON Schema pydantic exports for it, and fills schema defaults.
 *
 * <p>Only the keywords the protocol uses are implemented; a schema with any other keyword is refused at compile time.
 * Types are strict: an integer is a literal without fraction or exponent, and a number is never a boolean or string.
 */
public final class SchemaValidator {
    /** A request that does not satisfy its schema; the message names the offending field. */
    public static final class Violation extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private Violation(String message) {
            super(message, null, false, false);
        }
    }

    private static final Set<String> ANNOTATIONS = Set.of("title", "description", "examples");
    private static final Set<String> TYPES =
            Set.of("object", "array", "string", "integer", "number", "boolean", "null");
    private static final Pattern INTEGER_LITERAL = Pattern.compile("-?[0-9]+");
    private static final String PATTERN_TOKENS = "[A-Za-z0-9^$()|\\[\\]{}?*+:#_,-]";
    private static final Pattern PATTERN_SUBSET =
            Pattern.compile("(?:" + PATTERN_TOKENS + "|\\\\[$(){}\\[\\]*+?|^\\\\.-])*");

    private static final class Bound {
        final BigDecimal exact;
        final double approx;

        Bound(JsonElement value) {
            JsonPrimitive primitive = number(value);
            exact = new BigDecimal(primitive.getAsString());
            approx = primitive.getAsDouble();
        }

        /** Integers compare exactly; floats compare as doubles. */
        int compare(JsonPrimitive value) {
            if (isInteger(value)) return new BigDecimal(value.getAsString()).compareTo(exact);
            double v = value.getAsDouble();
            return v < approx ? -1 : v > approx ? 1 : 0;
        }
    }

    private static final class Node {
        String type;
        JsonElement defaultValue, constant;
        List<JsonElement> options;
        Bound minimum, maximum, exclusiveMinimum, exclusiveMaximum;
        Integer minLength, maxLength, minItems, maxItems;
        Pattern pattern;
        Set<String> required = Set.of();
        Map<String, Node> properties = Map.of();
        Node items, reference, additional;
        boolean closed;
        List<Node> anyOf = List.of(), oneOf = List.of();
    }

    private final Node root;

    private SchemaValidator(Node root) {
        this.root = root;
    }

    /** Compile a pydantic schema; any keyword outside the supported set is an error. */
    public static SchemaValidator compile(JsonObject schema) {
        Map<String, Node> definitions = new HashMap<>();
        JsonObject declared = schema.has("$defs") ? schema.getAsJsonObject("$defs") : new JsonObject();
        for (String name : declared.keySet()) definitions.put(name, new Node());
        for (String name : declared.keySet())
            build(declared.getAsJsonObject(name), definitions.get(name), definitions, "$defs." + name);
        JsonObject body = schema.deepCopy();
        body.remove("$defs");
        Node node = new Node();
        build(body, node, definitions, "schema");
        return new SchemaValidator(node);
    }

    /** The request with defaults filled; the argument is not modified. */
    public JsonObject apply(JsonObject params) {
        if (params == null) throw fail("params", "must be an object");
        return check(root, params, "params").getAsJsonObject();
    }

    private static void build(JsonObject schema, Node node, Map<String, Node> definitions, String where) {
        for (Map.Entry<String, JsonElement> entry : schema.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            switch (key) {
                case "type":
                    if (!value.isJsonPrimitive() || !TYPES.contains(value.getAsString()))
                        throw new IllegalStateException(where + ": unsupported type " + value);
                    node.type = value.getAsString();
                    break;
                case "default":
                    node.defaultValue = value;
                    break;
                case "const":
                    node.constant = value;
                    break;
                case "enum":
                    node.options = list(value, where);
                    break;
                case "minimum":
                    node.minimum = new Bound(value);
                    break;
                case "maximum":
                    node.maximum = new Bound(value);
                    break;
                case "exclusiveMinimum":
                    node.exclusiveMinimum = new Bound(value);
                    break;
                case "exclusiveMaximum":
                    node.exclusiveMaximum = new Bound(value);
                    break;
                case "minLength":
                    node.minLength = count(value, where);
                    break;
                case "maxLength":
                    node.maxLength = count(value, where);
                    break;
                case "minItems":
                    node.minItems = count(value, where);
                    break;
                case "maxItems":
                    node.maxItems = count(value, where);
                    break;
                case "pattern":
                    node.pattern = pattern(value.getAsString(), where);
                    break;
                case "required":
                    node.required = new LinkedHashSet<>();
                    for (JsonElement name : list(value, where)) node.required.add(name.getAsString());
                    break;
                case "properties":
                    node.properties = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> property :
                            value.getAsJsonObject().entrySet())
                        node.properties.put(
                                property.getKey(),
                                child(property.getValue(), definitions, where + "." + property.getKey()));
                    break;
                case "additionalProperties":
                    if (value.isJsonObject()) node.additional = child(value, definitions, where + ".*");
                    else if (value.isJsonPrimitive()
                            && value.getAsJsonPrimitive().isBoolean()) node.closed = !value.getAsBoolean();
                    else throw new IllegalStateException(where + ": unsupported additionalProperties " + value);
                    break;
                case "items":
                    node.items = child(value, definitions, where + "[]");
                    break;
                case "anyOf":
                    node.anyOf = children(value, definitions, where + ".anyOf");
                    break;
                case "oneOf":
                    node.oneOf = children(value, definitions, where + ".oneOf");
                    break;
                case "$ref":
                    String text = value.getAsString();
                    node.reference = text.startsWith("#/$defs/") ? definitions.get(text.substring(8)) : null;
                    if (node.reference == null)
                        throw new IllegalStateException(where + ": unresolved reference " + text);
                    break;
                default:
                    if (!ANNOTATIONS.contains(key))
                        throw new IllegalStateException(where + ": unsupported schema keyword " + key);
            }
        }
    }

    private static Node child(JsonElement schema, Map<String, Node> definitions, String where) {
        Node node = new Node();
        build(schema.getAsJsonObject(), node, definitions, where);
        return node;
    }

    private static List<Node> children(JsonElement array, Map<String, Node> definitions, String where) {
        List<Node> out = new ArrayList<>();
        for (JsonElement branch : list(array, where)) out.add(child(branch, definitions, where));
        return out;
    }

    private static List<JsonElement> list(JsonElement value, String where) {
        if (!value.isJsonArray()) throw new IllegalStateException(where + ": expected an array");
        List<JsonElement> out = new ArrayList<>();
        for (JsonElement item : (JsonArray) value) out.add(item);
        return out;
    }

    private static int count(JsonElement value, String where) {
        if (!isInteger(number(value)) || value.getAsInt() < 0)
            throw new IllegalStateException(where + ": expected a non-negative integer");
        return value.getAsInt();
    }

    private static JsonPrimitive number(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalStateException("expected a number, got " + value);
        return value.getAsJsonPrimitive();
    }

    /** The regex subset Python's and Java's engines read alike; {@code $} must mean end of text, as in Python. */
    static Pattern pattern(String source, String where) {
        if (!PATTERN_SUBSET.matcher(source).matches() || source.contains("(?")) throw outsideSubset(where, source);
        StringBuilder text = new StringBuilder();
        boolean inClass = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                text.append(c).append(source.charAt(++i));
                continue;
            }
            if (inClass && c == '[') throw outsideSubset(where, source);
            if (c == '[') inClass = true;
            else if (c == ']') inClass = false;
            if (c == '$' && !inClass) text.append("\\z");
            else text.append(c);
        }
        try {
            return Pattern.compile(text.toString());
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException(where + ": invalid pattern " + source, e);
        }
    }

    private static IllegalStateException outsideSubset(String where, String source) {
        return new IllegalStateException(where + ": pattern outside the shared regex subset: " + source);
    }

    private static boolean isInteger(JsonPrimitive value) {
        return value.isNumber() && INTEGER_LITERAL.matcher(value.getAsString()).matches();
    }

    private static boolean matches(String type, JsonElement value) {
        if (type.equals("object")) return value.isJsonObject();
        if (type.equals("array")) return value.isJsonArray();
        if (type.equals("null")) return value.isJsonNull();
        if (!value.isJsonPrimitive()) return false;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (type.equals("string")) return primitive.isString();
        if (type.equals("boolean")) return primitive.isBoolean();
        if (type.equals("integer")) return isInteger(primitive);
        return primitive.isNumber() && (isInteger(primitive) || Double.isFinite(primitive.getAsDouble()));
    }

    private static boolean same(JsonElement a, JsonElement b) {
        if (a.isJsonPrimitive()
                && b.isJsonPrimitive()
                && a.getAsJsonPrimitive().isNumber()
                && b.getAsJsonPrimitive().isNumber()) return new Bound(a).compare(b.getAsJsonPrimitive()) == 0;
        return a.equals(b);
    }

    private static String at(String path, String key) {
        return path + "." + key;
    }

    private static Violation fail(String path, String rule) {
        return new Violation(path + " " + rule);
    }

    private static JsonElement check(Node node, JsonElement value, String path) {
        JsonElement out = value;
        if (node.type != null && !matches(node.type, value))
            throw fail(path, "must be " + article(node.type) + ", not " + describe(value));
        if (node.options != null && node.options.stream().noneMatch(option -> same(option, value)))
            throw fail(path, "must be one of " + node.options);
        if (node.constant != null && !same(node.constant, value)) throw fail(path, "must be " + node.constant);
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isNumber()) number(node, primitive, path);
            else if (primitive.isString()) string(node, primitive.getAsString(), path);
        }
        if (value.isJsonArray()) out = array(node, value.getAsJsonArray(), path);
        if (value.isJsonObject()) out = object(node, value.getAsJsonObject(), path);
        if (node.reference != null) out = check(node.reference, out, path);
        if (!node.anyOf.isEmpty()) out = anyOf(node, out, path);
        if (!node.oneOf.isEmpty()) oneOf(node, out, path);
        return out;
    }

    private static void number(Node node, JsonPrimitive value, String path) {
        if (!matches("number", value)) throw fail(path, "must be a finite number");
        if (node.minimum != null && node.minimum.compare(value) < 0)
            throw fail(path, "must be at least " + node.minimum.exact.toPlainString());
        if (node.maximum != null && node.maximum.compare(value) > 0)
            throw fail(path, "must be at most " + node.maximum.exact.toPlainString());
        if (node.exclusiveMinimum != null && node.exclusiveMinimum.compare(value) <= 0)
            throw fail(path, "must be greater than " + node.exclusiveMinimum.exact.toPlainString());
        if (node.exclusiveMaximum != null && node.exclusiveMaximum.compare(value) >= 0)
            throw fail(path, "must be less than " + node.exclusiveMaximum.exact.toPlainString());
    }

    private static void string(Node node, String text, String path) {
        int length = text.codePointCount(0, text.length());
        if (node.minLength != null && length < node.minLength)
            throw fail(path, "must have at least " + node.minLength + " characters");
        if (node.maxLength != null && length > node.maxLength)
            throw fail(path, "must have at most " + node.maxLength + " characters");
        if (node.pattern != null && !node.pattern.matcher(text).find())
            throw fail(path, "must match " + node.pattern.pattern());
    }

    private static JsonElement array(Node node, JsonArray items, String path) {
        if (node.minItems != null && items.size() < node.minItems)
            throw fail(path, "must have at least " + node.minItems + " items");
        if (node.maxItems != null && items.size() > node.maxItems)
            throw fail(path, "must have at most " + node.maxItems + " items");
        if (node.items == null) return items.deepCopy();
        JsonArray out = new JsonArray();
        for (int i = 0; i < items.size(); i++) out.add(check(node.items, items.get(i), path + "[" + i + "]"));
        return out;
    }

    private static JsonElement object(Node node, JsonObject fields, String path) {
        for (String name : node.required) if (!fields.has(name)) throw fail(path, "is missing required field " + name);
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> field : fields.entrySet()) {
            Node known = node.properties.get(field.getKey());
            if (known == null) known = node.additional;
            if (known == null && node.closed) throw fail(path, "has unknown field " + field.getKey());
            out.add(
                    field.getKey(),
                    known == null
                            ? field.getValue().deepCopy()
                            : check(known, field.getValue(), at(path, field.getKey())));
        }
        for (Map.Entry<String, Node> property : node.properties.entrySet())
            if (!fields.has(property.getKey()) && property.getValue().defaultValue != null)
                out.add(property.getKey(), property.getValue().defaultValue.deepCopy());
        return out;
    }

    private static JsonElement anyOf(Node node, JsonElement value, String path) {
        List<String> reasons = new ArrayList<>();
        for (Node branch : node.anyOf) {
            try {
                return check(branch, value, path);
            } catch (Violation violation) {
                reasons.add(violation.getMessage());
            }
        }
        throw new Violation(path + " matches none of the allowed forms: " + String.join("; ", reasons));
    }

    private static void oneOf(Node node, JsonElement value, String path) {
        int matched = 0;
        for (Node branch : node.oneOf) {
            try {
                check(branch, value, path);
                matched++;
            } catch (Violation ignored) {
                // a non-matching branch is the normal case
            }
        }
        if (matched != 1) throw fail(path, "must match exactly one allowed form, matched " + matched);
    }

    private static String article(String type) {
        return (type.equals("object") || type.equals("array") || type.equals("integer") ? "an " : "a ") + type;
    }

    private static String describe(JsonElement value) {
        if (value.isJsonNull()) return "null";
        if (value.isJsonObject()) return "an object";
        if (value.isJsonArray()) return "an array";
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isString() ? "a string" : primitive.isBoolean() ? "a boolean" : "the number " + primitive;
    }
}
