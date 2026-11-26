package dev.bitwigagent.rpc;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.bitwigagent.HostFixture;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.MethodSpecs;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.grid.GridConnectionWriter;
import dev.bitwigagent.grid.GridModule;
import dev.bitwigagent.grid.GridParameterWriter;
import dev.bitwigagent.grid.GridSnapshotReaderTest;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Runs against actual TCP sockets; requires the API jar, never a native host instance. */
public final class RpcServerTest {
    private static final MethodSpecs SPECS = HostFixture.SPECS;

    private static final String PROTOCOL = "\"protocol\":\"" + SPECS.vocabulary().protocol + "\",";

    private static JsonObject ok() {
        JsonObject result = new JsonObject();
        result.addProperty("outcome", "applied");
        return result;
    }

    private static String request(String id, String params, String timeout) {
        return "{" + PROTOCOL + "\"id\":\"" + id + "\",\"method\":\"status\",\"params\":" + params
                + (timeout == null ? "" : ",\"timeoutMs\":" + timeout) + "}\n";
    }

    private static Socket connect(RpcServer server) throws IOException {
        Socket socket = new Socket("127.0.0.1", server.getPort());
        socket.setSoTimeout(5000);
        return socket;
    }

    private static JsonObject read(Socket socket) throws IOException {
        String line =
                new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
        if (line == null) throw new EOFException("No response");
        return JsonParser.parseString(line).getAsJsonObject();
    }

    private static JsonObject call(RpcServer server, byte[] bytes) throws IOException {
        try (Socket socket = connect(server)) {
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
            return read(socket);
        }
    }

    /** Requests written out in full get the envelope's protocol field unless they test it themselves. */
    private static JsonObject call(RpcServer server, String text) throws IOException {
        String framed = text.startsWith("{\"id\":\"") ? "{" + PROTOCOL + text.substring(1) : text;
        return call(server, framed.getBytes(StandardCharsets.UTF_8));
    }

    /** An error: nothing changed, so it names a code and reason and never an outcome. */
    private static void error(JsonObject response, String reason) {
        assertTrue(response.has("error"), "Expected error: " + response);
        JsonObject error = response.getAsJsonObject("error");
        assertEquals(reason, error.get("reason").getAsString(), "reason: " + response);
        assertEquals(
                Reason.valueOf(reason.toUpperCase(Locale.ROOT)).code().wire(),
                error.get("code").getAsString());
        assertFalse(error.has("outcome"), "an error has no outcome: " + response);
    }

    private static void unknown(JsonObject response, String reason) {
        assertTrue(response.has("result"), "Expected result: " + response);
        JsonObject result = response.getAsJsonObject("result");
        assertEquals("unknown", result.get("outcome").getAsString(), "outcome: " + response);
        assertEquals(reason, result.get("reason").getAsString(), "reason: " + response);
    }

    @Test
    void gridIntegerTokenValidation() throws Exception {
        try (RpcServer server = new RpcServer(0, SPECS, (method, params, until) -> {
            if (method.equals("grid.modules.move") || method.equals("grid.modules.insert"))
                GridModule.validateParams(method, params);
            else GridParameterWriter.Request.parse(params);
            return CompletableFuture.completedFuture(ok());
        })) {
            server.start();
            for (String values : List.of(
                    "\"expectedKind\":\"integer\",\"expectedValue\":0,\"value\":1e0",
                    "\"expectedKind\":\"enum\",\"expectedValue\":1e0,\"value\":0")) {
                String request = "{\"id\":\"raw-token\",\"method\":\"grid.parameters.set\",\"params\":{"
                        + "\"trackId\":\"00000000-0000-0000-0000-000000000001\",\"deviceIndex\":0,"
                        + "\"expectedDeviceName\":\"Poly Grid\",\"moduleId\":\"00000000-0000-0000-0000-000000000002\","
                        + "\"parameterId\":\"MODE\"," + values + "}}\n";
                error(call(server, request), "invalid_params");
            }
            for (String key : List.of("expectedX", "expectedY", "x", "y")) {
                String coordinates = "\"expectedX\":0,\"expectedY\":0,\"x\":1,\"y\":1";
                coordinates = coordinates.replace(
                        "\"" + key + "\":" + (key.startsWith("expected") ? "0" : "1"), "\"" + key + "\":1e0");
                String request = "{\"id\":\"move-token\",\"method\":\"grid.modules.move\",\"params\":{"
                        + "\"trackId\":\"00000000-0000-0000-0000-000000000001\",\"deviceIndex\":0,"
                        + "\"expectedDeviceName\":\"Poly Grid\",\"moduleId\":\"00000000-0000-0000-0000-000000000002\","
                        + coordinates + "}}\n";
                error(call(server, request), "invalid_params");
            }
            for (String token : List.of("1e0", "1.0", "\"1\"", "true", "2147483648")) {
                String request = "{\"id\":\"insert-token\",\"method\":\"grid.modules.insert\",\"params\":{"
                        + "\"trackId\":\"00000000-0000-0000-0000-000000000001\",\"deviceIndex\":0,"
                        + "\"expectedDeviceName\":\"Poly Grid\",\"typeId\":\"00000000-0000-0000-0000-000000000002\","
                        + "\"x\":" + token + ",\"y\":0}}\n";
                error(call(server, request), "invalid_params");
            }
        }
    }

    @Test
    void gridConnectionNullContract() throws Exception {
        GridSnapshotReaderTest.ConnectionFixture fixture = new GridSnapshotReaderTest.ConnectionFixture();
        try (RpcServer server = new RpcServer(0, SPECS, (method, params, until) -> {
            GridModule.validateParams(method, params);
            HostOperation op = fixture.start(GridConnectionWriter.Request.parse(params));
            if (!op.result.isDone()) fixture.next();
            return op.result;
        })) {
            server.start();
            String prefix = "{\"id\":\"cable\",\"method\":\"grid.connections.set\",\"params\":{"
                    + "\"target\":{\"kind\":\"device\",\"track\":{\"kind\":\"track\",\"id\":\"00000000-0000-0000-0000-000000000001\","
                    + "\"name\":\"Track\"},\"index\":0,\"name\":\"Poly Grid\"},\"targetModuleId\":\"00000000-0000-0001-0000-000000000001\","
                    + "\"targetPortKey\":\"in\",";
            JsonObject response = call(server, prefix + "\"expectedSource\":null,\"source\":null}}\n");
            assertTrue(response.has("result"), "Expected result: " + response);
            JsonObject result = response.getAsJsonObject("result");
            assertTrue(
                    result.get("previousSource").isJsonNull()
                            && result.get("requestedSource").isJsonNull()
                            && result.get("actualSource").isJsonNull()
                            && !result.get("mutationDispatched").getAsBoolean(),
                    "explicit nullable CAS reaches real writer semantics and survives JSON response serialization");
            error(call(server, prefix + "\"source\":null}}\n"), "invalid_params");
            error(call(server, prefix + "\"expectedSource\":null}}\n"), "invalid_params");
            error(call(server, prefix + "\"expectedSource\":null,\"source\":[]}}\n"), "invalid_params");
            error(
                    call(
                            server,
                            prefix
                                    + "\"expectedSource\":null,\"source\":{\"moduleId\":\"1-1-1-1-1\",\"portKey\":\"out\"}}}\n"),
                    "invalid_params");
            error(call(server, prefix + "\"expectedSource\":null,\"source\":{1:\"out\"}}}\n"), "invalid_request");
            assertTrue(fixture.calls == 0, "malformed endpoints and valid no-op never invoke native setter");
        }
    }

    @Test
    void nullableObservations() throws Exception {
        try (RpcServer server = new RpcServer(0, SPECS, (method, params, until) -> {
            JsonObject item = new JsonObject();
            item.add("name", JsonNull.INSTANCE);
            item.add("normalizedValue", JsonNull.INSTANCE);
            JsonArray items = new JsonArray();
            items.add(item);
            JsonObject result = ok();
            result.add("items", items);
            return CompletableFuture.completedFuture(result);
        })) {
            server.start();
            JsonObject item = call(server, request("nullable", "{}", null))
                    .getAsJsonObject("result")
                    .getAsJsonArray("items")
                    .get(0)
                    .getAsJsonObject();
            assertTrue(
                    item.has("name")
                            && item.get("name").isJsonNull()
                            && item.has("normalizedValue")
                            && item.get("normalizedValue").isJsonNull(),
                    "Unavailable observations remain explicit nulls across the real wire boundary");
            JsonObject invalid = call(server, "{}\n");
            assertTrue(
                    invalid.has("id") && invalid.get("id").isJsonNull(),
                    "An uncorrelated rejection retains its explicit null id");
        }
    }

    @Test
    void validation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong deadline = new AtomicLong();
        try (RpcServer server = new RpcServer(0, SPECS, (method, params, until) -> {
            calls.incrementAndGet();
            deadline.set(until);
            return CompletableFuture.completedFuture(ok());
        })) {
            server.start();
            long before = System.nanoTime();
            JsonObject response = call(server, request("汉字🎛", "{}", null));
            assertTrue(response.get("id").getAsString().equals("汉字🎛"), "UTF-8 correlation");
            assertTrue(
                    response.getAsJsonObject("result")
                            .get("outcome")
                            .getAsString()
                            .equals("applied"),
                    "Result envelope");
            assertTrue(
                    deadline.get() > before && deadline.get() < before + TimeUnit.SECONDS.toNanos(6),
                    "Monotonic deadline");
            int accepted = calls.get();
            String[] invalid = {
                "{}\n",
                "[]\n",
                "{\"id\":1,\"method\":\"status\",\"params\":{}}\n",
                request("", "{}", null),
                request("x".repeat(129), "{}", null),
                request("a", "null", null),
                request("a", "[]", null),
                request("a", "{}", "99"),
                request("a", "{}", "10001"),
                request("a", "{}", "1.5"),
                request("a", "{}", "\"500\""),
                request("a", "{}", "true"),
                request("a", "{\"x\":1,\"x\":2}", null),
                "{\"id\":\"a\",\"id\":\"b\",\"method\":\"status\",\"params\":{}}\n",
                "{\"id\":\"a\",\"method\":\"status\",\"params\":{},\"extra\":0}\n",
                "{'id':'a','method':'status','params':{}}\n",
                "{\"id\":\"a\",/*comment*/\"method\":\"status\",\"params\":{}}\n",
                request("a", "{\"x\":NaN}", null),
                request("a", "{\"x\":01}", null),
                request("a", "{}", null).replace("}\n", "} {}\n"),
                request("a", "{\"x\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}", null)
            };
            for (String bad : invalid) error(call(server, bad), "invalid_request");
            for (String method : SPECS.methods())
                error(
                        call(
                                server,
                                request("a", "{}", String.valueOf(SPECS.get(method).maxTimeoutMs + 1))
                                        .replace("status", method)),
                        "invalid_request");
            error(call(server, request("a", "{}", null).replace("status", "unknown")), "unknown_method");
            byte[] badUtf8 = request("a", "{\"x\":\"?\"}", null).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < badUtf8.length; i++) if (badUtf8[i] == '?') badUtf8[i] = (byte) 0xff;
            error(call(server, badUtf8), "invalid_request");
            assertTrue(calls.get() == accepted, "Invalid input never invokes handler");
            for (String method : SPECS.methods())
                assertTrue(
                        call(
                                        server,
                                        request("a", "{}", String.valueOf(SPECS.get(method).maxTimeoutMs))
                                                .replace("status", method))
                                .has("result"),
                        method + " admits its own ceiling");
            assertTrue(
                    call(server, request("a", "{}", null).replace("status", "project.saveAs"))
                                    .has("result")
                            && deadline.get() - System.nanoTime() > TimeUnit.SECONDS.toNanos(25),
                    "an omitted timeoutMs takes the method's spec default");
            try (Socket socket = connect(server)) {
                socket.getOutputStream()
                        .write(request("half", "{}", null).trim().getBytes(StandardCharsets.UTF_8));
                socket.shutdownOutput();
                error(read(socket), "invalid_request");
            }
            try (Socket socket = connect(server)) {
                long start = System.nanoTime();
                socket.getOutputStream().write('{');
                socket.getOutputStream().flush();
                error(read(socket), "invalid_request");
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(4), "Idle reader bounded");
            }
            String base = request("limit", "{\"padding\":\"\"}", null);
            String boundary = base.replace(
                    "\"padding\":\"\"",
                    "\"padding\":\"" + "x".repeat(RpcServer.MAX_REQUEST_BYTES - (base.length() - 1)) + "\"");
            assertTrue(
                    boundary.getBytes(StandardCharsets.UTF_8).length == RpcServer.MAX_REQUEST_BYTES + 1,
                    "Boundary fixture");
            assertTrue(call(server, boundary).has("result"), "1 MiB request excluding newline accepted");
            error(call(server, boundary.replace("padding", "paddingX")), "invalid_request");
            try (Socket socket = connect(server)) {
                byte[] bytes = request("fragmented", "{}", null).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    socket.getOutputStream().write(b);
                    socket.getOutputStream().flush();
                }
                assertTrue(read(socket).get("id").getAsString().equals("fragmented"), "Fragmented TCP request");
                assertTrue(socket.getInputStream().read() == -1, "One response then connection closes");
            }
        }
    }

    @Test
    void protocolMismatchIsRefusedBeforeTheMethod() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (RpcServer server = new RpcServer(0, SPECS, (method, params, until) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(ok());
        })) {
            server.start();
            String missing = "{\"id\":\"p\",\"method\":\"status\",\"params\":{}}\n";
            error(call(server, missing.getBytes(StandardCharsets.UTF_8)), "protocol_mismatch");
            error(
                    call(server, request("p", "{}", null).replace(SPECS.vocabulary().protocol, "0.4")),
                    "protocol_mismatch");
            assertEquals(0, calls.get(), "a mismatched client never reaches the handler");
        }
    }

    @Test
    void deadlines() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<JsonObject> pending = new CompletableFuture<>();
        try (RpcServer server = new RpcServer(0, SPECS, (method, params, until) -> {
            calls.incrementAndGet();
            return pending;
        })) {
            server.start();
            error(call(server, request("late", "{}", "100")), "deadline");
            unknown(call(server, request("late-write", "{\"id\":\"x\"}", "100").replace("status", "ack")), "deadline");
            assertTrue(!pending.isCancelled(), "Transport does not claim to cancel host work");
            pending.complete(ok());
            assertTrue(calls.get() == 2, "No automatic retry");
        }
        try (RpcServer server = new RpcServer(0, SPECS, (method, params, until) -> {
            CompletableFuture<JsonObject> failure = new CompletableFuture<>();
            failure.completeExceptionally(
                    method.equals("ack")
                            ? Errors.unknown(Reason.NOT_OBSERVED, "Not observed")
                            : Errors.notApplied(Reason.TARGET_CHANGED, "Target changed"));
            return failure;
        })) {
            server.start();
            error(call(server, request("host-error", "{}", null)), "target_changed");
            unknown(call(server, request("host-unknown", "{}", null).replace("status", "ack")), "not_observed");
        }
    }

    @Test
    void anOperationStillWaitingAtTheDeadlineIsRefusedAndNeverDispatches() throws Exception {
        List<HostOperation> operations = new CopyOnWriteArrayList<>();
        try (RpcServer server = new RpcServer(0, SPECS, (method, params, until) -> {
            HostOperation op = new HostOperation(until);
            operations.add(op);
            return op.result;
        })) {
            server.start();
            error(call(server, request("waiting", "{}", "100").replace("status", "ack")), "deadline");
            assertThrows(RpcException.class, operations.get(0)::markDispatched, "it can no longer dispatch");
        }
    }

    /** A dispatched operation: asked to expire at the deadline, it refuses and then answers with its job. */
    private static final class Dispatched extends CompletableFuture<JsonObject> implements RpcServer.Expiring {
        @Override
        public boolean expire() {
            HostOperation op = new HostOperation(Long.MAX_VALUE);
            op.markDispatched();
            op.job = "render:1";
            op.fail(Errors.notApplied(Reason.DEADLINE, "Deadline expired."));
            complete(op.result.join());
            return false;
        }
    }

    @Test
    void aDispatchedOperationAnswersItselfAfterTheDeadline() throws Exception {
        try (RpcServer server = new RpcServer(0, SPECS, (method, params, until) -> new Dispatched())) {
            server.start();
            JsonObject response = call(server, request("late", "{}", "100").replace("status", "ack"));
            unknown(response, "deadline");
            assertEquals(
                    "render:1", response.getAsJsonObject("result").get("job").getAsString(), "its job id");
        }
    }

    private static JsonObject unknownResult() {
        JsonObject result = new JsonObject();
        result.addProperty("outcome", "unknown");
        result.addProperty("reason", "not_observed");
        return result;
    }

    @Test
    void anUnknownResultPointsAtTheReadOfItsMethodUnlessItNamesOne() throws Exception {
        String inspect = SPECS.get("ack").inspect;
        assertEquals("status", inspect, "the spec of a write names its read");
        assertNull(SPECS.get("status").inspect, "a read has none");
        JsonObject bare = unknownResult();
        JsonObject own = unknownResult();
        own.addProperty("next", "inspect it yourself");
        JsonObject[] answer = {bare};
        try (RpcServer server = new RpcServer(0, SPECS, (method, params, until) -> {
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            future.complete(answer[0]);
            return future;
        })) {
            server.start();
            String ack = request("w", "{}", null).replace("status", "ack");
            assertEquals(
                    inspect,
                    call(server, ack).getAsJsonObject("result").get("next").getAsString());
            answer[0] = own;
            assertEquals(
                    "inspect it yourself",
                    call(server, ack).getAsJsonObject("result").get("next").getAsString(),
                    "a module's own next wins");
            answer[0] = unknownResult();
            assertFalse(call(server, request("r", "{}", null))
                    .getAsJsonObject("result")
                    .has("next"));
        }
    }

    @Test
    void queuedDeadline() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<JsonObject> hold = new CompletableFuture<>();
        List<Socket> occupying = new ArrayList<>();
        try (RpcServer server = new RpcServer(0, SPECS, (method, params, until) -> {
            calls.incrementAndGet();
            entered.countDown();
            return hold;
        })) {
            server.start();
            for (int i = 0; i < 4; i++) {
                Socket socket = connect(server);
                occupying.add(socket);
                socket.getOutputStream()
                        .write(request("occupy-" + i, "{}", "10000").getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS), "Workers occupied before queued request");
            try (Socket queued = connect(server)) {
                queued.getOutputStream()
                        .write(request("expired-in-queue", "{}", "100").getBytes(StandardCharsets.UTF_8));
                queued.getOutputStream().flush();
                Thread.sleep(250); // Deliberately exceed the queued request's declared budget.
                hold.complete(ok());
                error(read(queued), "deadline");
                assertTrue(calls.get() == 4, "Expired queued request never invokes handler");
            }
            for (Socket socket : occupying) assertTrue(read(socket).has("result"), "Occupying request completes");
        } finally {
            for (Socket socket : occupying) socket.close();
        }
    }

    @Test
    void responseLimit() throws Exception {
        try (RpcServer server = new RpcServer(0, SPECS, (method, params, until) -> {
            JsonObject result = ok();
            result.addProperty("large", "x".repeat(RpcServer.MAX_RESPONSE_BYTES));
            return CompletableFuture.completedFuture(result);
        })) {
            server.start();
            error(call(server, request("large", "{}", null)), "internal");
            unknown(call(server, request("large-write", "{}", null).replace("status", "ack")), "internal");
        }
    }

    @Test
    void overloadAndClose() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch four = new CountDownLatch(4);
        RpcServer server = new RpcServer(0, SPECS, (method, params, until) -> {
            calls.incrementAndGet();
            four.countDown();
            return new CompletableFuture<>();
        });
        List<Socket> sockets = new ArrayList<>();
        server.start();
        int port = server.getPort();
        try {
            for (int i = 0; i < 20; i++) {
                Socket socket = connect(server);
                sockets.add(socket);
                try {
                    socket.getOutputStream()
                            .write(request("busy-" + i, "{}", "10000").getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    /* A saturated server may close immediately. */
                }
            }
            assertTrue(four.await(2, TimeUnit.SECONDS), "Four workers dispatched");
            assertTrue(calls.get() == 4, "Network dispatch concurrency bounded to four");
            server.close();
            server.close();
            for (Socket socket : sockets) {
                try {
                    assertTrue(socket.getInputStream().read() == -1, "Close releases active/queued peers");
                } catch (SocketException ignored) {
                    // a reset also proves the peer was released
                }
            }
            try {
                new Socket("127.0.0.1", port).close();
                throw new AssertionError("Listener remained open");
            } catch (ConnectException expected) {
                // the listener is closed
            }
        } finally {
            server.close();
            for (Socket socket : sockets) socket.close();
        }
    }
}
