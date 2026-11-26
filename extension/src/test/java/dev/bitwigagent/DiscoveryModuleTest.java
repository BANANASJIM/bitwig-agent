package dev.bitwigagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

public final class DiscoveryModuleTest {
    private static final class Section implements HostModule {
        HostOperation seen;

        @Override
        public Set<String> methods() {
            return Set.of();
        }

        @Override
        public void validate(String method, JsonObject params) {}

        @Override
        public void dispatch(HostOperation operation, String method, JsonObject params) {}

        @Override
        public void status(HostOperation operation, JsonObject out, boolean full) {
            seen = operation;
            out.addProperty("full", full);
        }
    }

    @Test
    void statusHooksReceiveTheOperation() {
        Section section = new Section();
        HostOperation operation = new HostOperation(Long.MAX_VALUE);
        JsonObject out = new JsonObject();
        DiscoveryModule.addDomainSections(operation, out, List.of(section), true);
        assertSame(operation, section.seen);
        assertEquals(true, out.get("full").getAsBoolean());
    }
}
