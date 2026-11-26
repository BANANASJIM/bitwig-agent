package dev.bitwigagent.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

public final class SchemaValidatorTest {
    private static SchemaValidator compile(String schema) {
        return SchemaValidator.compile(JsonParser.parseString(schema).getAsJsonObject());
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** A schema whose only property is {@code v}, described by {@code field}. */
    private static SchemaValidator field(String field) {
        return compile("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"v\":" + field + "}}");
    }

    private static void accepts(SchemaValidator validator, String value) {
        validator.apply(object("{\"v\":" + value + "}"));
    }

    private static void rejects(SchemaValidator validator, String value, String fragment) {
        SchemaValidator.Violation violation = assertThrows(
                SchemaValidator.Violation.class, () -> accepts(validator, value), value + " must be rejected");
        assertTrue(
                violation.getMessage().contains(fragment),
                value + " -> " + violation.getMessage() + " should mention " + fragment);
    }

    private static void refuses(String schema, String fragment) {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> compile(schema), schema);
        assertTrue(refused.getMessage().contains(fragment), refused.getMessage());
    }

    @Test
    void typesAreStrict() {
        SchemaValidator string = field("{\"type\":\"string\"}");
        accepts(string, "\"x\"");
        for (String wrong : new String[] {"1", "true", "null", "[]", "{}"}) rejects(string, wrong, "a string");
        SchemaValidator bool = field("{\"type\":\"boolean\"}");
        accepts(bool, "false");
        for (String wrong : new String[] {"0", "\"true\"", "null"}) rejects(bool, wrong, "a boolean");
        SchemaValidator nothing = field("{\"type\":\"null\"}");
        accepts(nothing, "null");
        rejects(nothing, "0", "null");
        SchemaValidator list = field("{\"type\":\"array\"}");
        accepts(list, "[]");
        rejects(list, "{}", "an array");
        SchemaValidator map = field("{\"type\":\"object\"}");
        accepts(map, "{}");
        rejects(map, "[]", "an object");
        rejects(string, "{\"a\":1}", "a string");
    }

    @Test
    void integerAndNumberAreNotInterchangeable() {
        SchemaValidator integer = field("{\"type\":\"integer\"}");
        accepts(integer, "1");
        accepts(integer, "-12");
        accepts(integer, "123456789012345678901234567890");
        for (String wrong : new String[] {"1.0", "1e2", "1E2", "1.5", "\"1\"", "true"})
            rejects(integer, wrong, "integer");
        SchemaValidator number = field("{\"type\":\"number\"}");
        accepts(number, "1");
        accepts(number, "1.5");
        accepts(number, "-0.0");
        accepts(number, "1e2");
        for (String wrong : new String[] {"\"1\"", "true", "null"}) rejects(number, wrong, "a number");
        rejects(number, "1e999", "a number");
    }

    @Test
    void integerBoundsAreExactAtAndBeyond() {
        SchemaValidator bounded = field("{\"type\":\"integer\",\"minimum\":-5,\"maximum\":15}");
        for (String ok : new String[] {"-5", "15", "0"}) accepts(bounded, ok);
        rejects(bounded, "-6", "at least -5");
        rejects(bounded, "16", "at most 15");
        rejects(bounded, "9007199254740993", "at most 15");
        SchemaValidator beyondDouble = field("{\"type\":\"integer\",\"maximum\":9007199254740992}");
        accepts(beyondDouble, "9007199254740992");
        rejects(beyondDouble, "9007199254740993", "at most");
    }

    @Test
    void floatBoundsCompareAsDoubles() {
        SchemaValidator bounded = field("{\"type\":\"number\",\"minimum\":0,\"maximum\":0.3}");
        accepts(bounded, "0");
        accepts(bounded, "-0.0");
        accepts(bounded, "0.3");
        accepts(bounded, "0.30000000000000000001");
        rejects(bounded, "0.30000000000000004", "at most");
        rejects(bounded, "-1e-300", "at least");
        rejects(field("{\"type\":\"number\",\"maximum\":0.3}"), "1", "at most");
    }

    @Test
    void exclusiveBoundsExcludeTheBound() {
        SchemaValidator greater = field("{\"type\":\"number\",\"exclusiveMinimum\":0}");
        rejects(greater, "0", "greater than 0");
        rejects(greater, "0.0", "greater than 0");
        accepts(greater, "5e-324");
        SchemaValidator less = field("{\"type\":\"integer\",\"exclusiveMaximum\":10}");
        accepts(less, "9");
        rejects(less, "10", "less than 10");
    }

    @Test
    void stringLengthCountsCodePoints() {
        SchemaValidator bounded = field("{\"type\":\"string\",\"minLength\":2,\"maxLength\":3}");
        accepts(bounded, "\"ab\"");
        accepts(bounded, "\"abc\"");
        rejects(bounded, "\"a\"", "at least 2");
        rejects(bounded, "\"abcd\"", "at most 3");
        accepts(bounded, "\"😀😀😀\"");
        rejects(bounded, "\"😀😀😀😀\"", "at most 3");
        rejects(bounded, "\"😀\"", "at least 2");
        accepts(bounded, "\"\\ud83d\\ud83d\"");
    }

    @Test
    void patternsSearchAndDollarMeansEndOfText() {
        SchemaValidator hex = field("{\"type\":\"string\",\"pattern\":\"^#[0-9a-fA-F]{6}$\"}");
        accepts(hex, "\"#00ffAA\"");
        rejects(hex, "\"#00ffA\"", "must match");
        rejects(hex, "\"#00ffAA\\n\"", "must match");
        rejects(hex, "\"x#00ffAA\"", "must match");
        accepts(field("{\"type\":\"string\",\"pattern\":\"ab\"}"), "\"xaby\"");
        SchemaValidator literal = field("{\"type\":\"string\",\"pattern\":\"^[$]\\\\$\\\\{[A-Za-z0-9_]+\\\\}$\"}");
        accepts(literal, "\"$${a_1}\"");
        rejects(literal, "\"${a_1}\"", "must match");
        SchemaValidator groups = field("{\"type\":\"string\",\"pattern\":\"^[A-G][#b]?:(ionian|dorian)$\"}");
        accepts(groups, "\"C#:dorian\"");
        rejects(groups, "\"C:lydian\"", "must match");
    }

    @Test
    void patternsOutsideTheSharedSubsetAreRefused() {
        for (String unsupported : new String[] {
            "^\\\\d+$", "^(?i)a$", "^a.b$", "^(?:a)$", "^[[a]]$", "^[a&&b]$", "^\\\\w$", "^a b$", "^[a-"
        }) {
            refuses("{\"type\":\"string\",\"pattern\":\"" + unsupported + "\"}", "pattern");
        }
    }

    @Test
    void enumAndConstCompareByValue() {
        SchemaValidator choice = field("{\"type\":\"string\",\"enum\":[\"a\",\"b\"]}");
        accepts(choice, "\"b\"");
        rejects(choice, "\"c\"", "one of");
        SchemaValidator fixed = field("{\"const\":\"master\"}");
        accepts(fixed, "\"master\"");
        rejects(fixed, "\"set\"", "master");
        SchemaValidator numbers = field("{\"enum\":[1,2.5]}");
        accepts(numbers, "1");
        accepts(numbers, "2.5");
        rejects(numbers, "3", "one of");
    }

    @Test
    void objectsRequireKnownFields() {
        SchemaValidator strict = compile(
                "{\"type\":\"object\",\"required\":[\"a\"],\"additionalProperties\":false,\"properties\":{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"}}}");
        strict.apply(object("{\"a\":1}"));
        strict.apply(object("{\"a\":1,\"b\":2}"));
        assertTrue(assertThrows(SchemaValidator.Violation.class, () -> strict.apply(object("{}")))
                .getMessage()
                .contains("missing required field a"));
        assertTrue(assertThrows(SchemaValidator.Violation.class, () -> strict.apply(object("{\"a\":1,\"c\":1}")))
                .getMessage()
                .contains("unknown field c"));
        assertTrue(assertThrows(SchemaValidator.Violation.class, () -> strict.apply(object("{\"a\":null}")))
                .getMessage()
                .contains("params.a"));
        compile("{\"type\":\"object\",\"additionalProperties\":true}").apply(object("{\"z\":[1]}"));
        SchemaValidator map = compile("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"boolean\"}}");
        map.apply(object("{\"x\":true,\"y\":false}"));
        assertThrows(SchemaValidator.Violation.class, () -> map.apply(object("{\"x\":1}")));
        assertThrows(SchemaValidator.Violation.class, () -> strict.apply(null));
    }

    @Test
    void arraysCheckSizeAndEveryItem() {
        SchemaValidator list =
                field("{\"type\":\"array\",\"minItems\":1,\"maxItems\":2,\"items\":{\"type\":\"integer\"}}");
        accepts(list, "[1]");
        accepts(list, "[1,2]");
        rejects(list, "[]", "at least 1 items");
        rejects(list, "[1,2,3]", "at most 2 items");
        rejects(list, "[1,\"x\"]", "v[1]");
        rejects(list, "[1.0]", "v[0]");
    }

    @Test
    void referencesResolveIncludingRecursion() {
        SchemaValidator tree = compile(
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"root\":{\"$ref\":\"#/$defs/Node\"}},"
                        + "\"$defs\":{\"Node\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"id\"],"
                        + "\"properties\":{\"id\":{\"type\":\"integer\",\"minimum\":0},\"kids\":{\"type\":\"array\",\"items\":{\"$ref\":\"#/$defs/Node\"}}}}}}");
        tree.apply(object("{\"root\":{\"id\":1,\"kids\":[{\"id\":2,\"kids\":[{\"id\":3}]}]}}"));
        SchemaValidator.Violation deep = assertThrows(
                SchemaValidator.Violation.class,
                () -> tree.apply(object("{\"root\":{\"id\":1,\"kids\":[{\"id\":2,\"kids\":[{\"id\":-3}]}]}}")));
        assertTrue(deep.getMessage().contains("root.kids[0].kids[0].id"), deep.getMessage());
        refuses("{\"properties\":{\"a\":{\"$ref\":\"#/$defs/Missing\"}}}", "unresolved reference");
        refuses("{\"properties\":{\"a\":{\"$ref\":\"http://example.com/x\"}}}", "unresolved reference");
    }

    @Test
    void anyOfAcceptsNullOnlyWhereSpelledOut() {
        SchemaValidator nullable = field("{\"anyOf\":[{\"type\":\"integer\",\"minimum\":0},{\"type\":\"null\"}]}");
        accepts(nullable, "null");
        accepts(nullable, "0");
        rejects(nullable, "-1", "none of the allowed forms");
        rejects(nullable, "\"x\"", "none of the allowed forms");
        rejects(field("{\"type\":\"integer\"}"), "null", "integer");
        rejects(
                field("{\"anyOf\":[{\"type\":\"integer\"},{\"type\":\"string\"}]}"),
                "null",
                "none of the allowed forms");
        SchemaValidator union =
                field("{\"anyOf\":[{\"type\":\"boolean\"},{\"type\":\"integer\"},{\"type\":\"number\"}]}");
        accepts(union, "true");
        accepts(union, "1");
        accepts(union, "1.5");
        rejects(union, "\"1\"", "none of the allowed forms");
    }

    @Test
    void oneOfNeedsExactlyOneMatch() {
        SchemaValidator one = compile("{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"properties\":{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"}},"
                + "\"oneOf\":[{\"required\":[\"a\"]},{\"required\":[\"b\"]}]}");
        one.apply(object("{\"a\":1}"));
        one.apply(object("{\"b\":1}"));
        assertTrue(assertThrows(SchemaValidator.Violation.class, () -> one.apply(object("{}")))
                .getMessage()
                .contains("matched 0"));
        assertTrue(assertThrows(SchemaValidator.Violation.class, () -> one.apply(object("{\"a\":1,\"b\":2}")))
                .getMessage()
                .contains("matched 2"));
    }

    @Test
    void defaultsAreFilledEverywhereAndNeverShared() {
        SchemaValidator validator = compile(
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                        + "\"limit\":{\"type\":\"integer\",\"default\":10},\"tags\":{\"type\":\"array\",\"default\":[\"x\"],\"items\":{\"type\":\"string\"}},"
                        + "\"rows\":{\"type\":\"array\",\"items\":{\"$ref\":\"#/$defs/Row\"}}},"
                        + "\"$defs\":{\"Row\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"gain\":{\"type\":\"number\",\"default\":1.5}}}}}");
        JsonObject filled = validator.apply(object("{\"rows\":[{},{\"gain\":2}]}"));
        assertEquals(10, filled.get("limit").getAsInt());
        assertEquals("x", filled.getAsJsonArray("tags").get(0).getAsString());
        assertEquals(
                1.5,
                filled.getAsJsonArray("rows")
                        .get(0)
                        .getAsJsonObject()
                        .get("gain")
                        .getAsDouble());
        assertEquals(
                2,
                filled.getAsJsonArray("rows")
                        .get(1)
                        .getAsJsonObject()
                        .get("gain")
                        .getAsInt());
        assertEquals(3, validator.apply(object("{\"limit\":3}")).get("limit").getAsInt());
        filled.getAsJsonArray("tags").add("mutated");
        assertEquals(1, validator.apply(new JsonObject()).getAsJsonArray("tags").size());
        JsonObject input = object("{\"rows\":[{}]}");
        validator.apply(input);
        assertEquals(object("{\"rows\":[{}]}"), input, "the request itself is never modified");
    }

    @Test
    void anyOfFillsDefaultsOfTheMatchingForm() {
        SchemaValidator validator = field("{\"anyOf\":[{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"properties\":{\"n\":{\"type\":\"integer\",\"default\":7}}},{\"type\":\"null\"}]}");
        assertEquals(
                7,
                validator
                        .apply(object("{\"v\":{}}"))
                        .getAsJsonObject("v")
                        .get("n")
                        .getAsInt());
        assertTrue(validator.apply(object("{\"v\":null}")).get("v").isJsonNull());
    }

    @Test
    void anyKeywordOutsideTheSupportedSetIsRefusedAtLoad() {
        for (String keyword : new String[] {
            "\"format\":\"uuid\"",
            "\"uniqueItems\":true",
            "\"multipleOf\":2",
            "\"allOf\":[]",
            "\"not\":{}",
            "\"prefixItems\":[]",
            "\"patternProperties\":{}",
            "\"if\":{}",
            "\"dependentRequired\":{}",
            "\"type\":[\"string\",\"null\"]"
        }) {
            refuses("{\"type\":\"object\",\"properties\":{\"a\":{" + keyword + "}}}", "unsupported");
            refuses("{" + keyword + "}", "unsupported");
        }
        refuses("{\"$defs\":{\"X\":{\"format\":\"date\"}}}", "$defs.X");
        refuses("{\"properties\":{\"a\":{\"items\":{\"unknownThing\":1}}}}", "unknownThing");
        refuses("{\"additionalProperties\":\"no\"}", "additionalProperties");
        refuses("{\"minLength\":-1}", "non-negative");
    }

    @Test
    void annotationsAreIgnored() {
        accepts(field("{\"type\":\"string\",\"title\":\"V\",\"description\":\"text\",\"examples\":[\"a\"]}"), "\"a\"");
    }

    @Test
    void everyBundleSchemaCompilesAndTimeoutIsLeftToTheEnvelope() {
        MethodSpecs specs = MethodSpecs.load();
        for (String method : specs.methods()) assertNotNull(specs.get(method).params, method);
        assertEquals(new JsonObject(), specs.get("status").params.apply(new JsonObject()));
        assertThrows(
                SchemaValidator.Violation.class,
                () -> specs.get("status").params.apply(object("{\"timeoutMs\":5000}")));
        assertEquals(
                100,
                specs.get("actions.list")
                        .params
                        .apply(new JsonObject())
                        .get("limit")
                        .getAsInt());
    }

    @Test
    void aBundleMethodWithoutASchemaIsMalformed() {
        JsonObject bundle = MethodSpecsTest.bundle();
        bundle.getAsJsonObject("methods").getAsJsonObject("status").remove("inputSchema");
        assertTrue(
                assertThrows(IllegalStateException.class, () -> MethodSpecs.parse(new StringReader(bundle.toString())))
                        .getMessage()
                        .contains("status has no inputSchema"));
        JsonObject unknown = MethodSpecsTest.bundle();
        unknown.getAsJsonObject("methods")
                .getAsJsonObject("status")
                .getAsJsonObject("inputSchema")
                .addProperty("format", "x");
        assertTrue(
                assertThrows(IllegalStateException.class, () -> MethodSpecs.parse(new StringReader(unknown.toString())))
                        .getMessage()
                        .contains("status inputSchema"));
    }
}
