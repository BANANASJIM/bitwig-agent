package dev.bitwigagent.launcher;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bitwigagent.core.RpcException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The note rules on shared vectors (tests/fixtures/note_rules.json): the extension refuses the same requests with
 * the same text as the Python validator (tests/test_protocol_clips.py runs the same file).
 */
final class NoteRulesVectorTest {
    @Test
    void theExtensionJudgesTheSharedVectorsLikeThePythonValidator() throws Exception {
        JsonArray cases = JsonParser.parseString(Files.readString(Path.of("tests/fixtures/note_rules.json")))
                .getAsJsonArray();
        assertTrue(cases.size() > 5);
        for (JsonElement value : cases) {
            JsonObject c = value.getAsJsonObject(), p = c.getAsJsonObject("params");
            String expected =
                    c.get("error").isJsonNull() ? null : c.get("error").getAsString();
            String actual = null;
            try {
                if (c.get("method").getAsString().equals("clips.insert"))
                    NoteModule.insertNotes(
                            p.getAsJsonArray("notes"), p.get("length").getAsBigDecimal());
                else {
                    NoteRange.require(p);
                    NotePlan.rules(NotePlan.listed(p.getAsJsonArray("notes")), NoteRange.of(p), null);
                }
            } catch (RpcException error) {
                actual = error.getMessage();
            }
            assertEquals(expected, actual, c.get("name").getAsString());
        }
    }
}
