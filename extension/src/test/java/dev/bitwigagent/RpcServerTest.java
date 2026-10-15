package dev.bitwigagent;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Runs against actual TCP sockets; requires the API jar, never a native host instance. */
public final class RpcServerTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static JsonObject ok() {
        JsonObject result = new JsonObject(); result.addProperty("status", "observed"); return result;
    }
    private static String request(String id, String params, String timeout) {
        return "{\"id\":\"" + id + "\",\"method\":\"status\",\"params\":" + params
            + (timeout == null ? "" : ",\"timeoutMs\":" + timeout) + "}\n";
    }
    private static Socket connect(RpcServer server) throws IOException {
        Socket socket = new Socket("127.0.0.1", server.getPort()); socket.setSoTimeout(5000); return socket;
    }
    private static JsonObject read(Socket socket) throws IOException {
        String line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
        if (line == null) throw new EOFException("No response");
        return JsonParser.parseString(line).getAsJsonObject();
    }
    private static JsonObject call(RpcServer server, byte[] bytes) throws IOException {
        try (Socket socket = connect(server)) {
            socket.getOutputStream().write(bytes); socket.getOutputStream().flush(); return read(socket);
        }
    }
    private static JsonObject call(RpcServer server, String text) throws IOException {
        return call(server, text.getBytes(StandardCharsets.UTF_8));
    }
    private static void error(JsonObject response, String code, String outcome) {
        check(response.has("error"), "Expected error: " + response);
        check(response.getAsJsonObject("error").get("code").getAsString().equals(code), "Error code: " + response);
        check(response.getAsJsonObject("error").get("outcome").getAsString().equals(outcome), "Outcome: " + response);
    }

    public static void main(String[] args) throws Exception {
        validation(); gridIntegerTokenValidation(); nullableObservations(); deadlines(); queuedDeadline(); responseLimit(); overloadAndClose();
        System.out.println("PASS: " + checks + " transport assertions over real IPv4 loopback; no host tests.");
    }

    private static void gridIntegerTokenValidation() throws Exception {
        try (RpcServer server = new RpcServer(0, (method, params, until) -> {
            GridParameterWriter.Request.parse(params);
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
                error(call(server, request), "invalid_params", "not_applied");
            }
        }
    }

    private static void nullableObservations() throws Exception {
        try (RpcServer server = new RpcServer(0, (method, params, until) -> {
            JsonObject item = new JsonObject();
            item.add("name", JsonNull.INSTANCE);
            item.add("normalizedValue", JsonNull.INSTANCE);
            JsonArray items = new JsonArray(); items.add(item);
            JsonObject result = ok(); result.add("items", items);
            return CompletableFuture.completedFuture(result);
        })) {
            server.start();
            JsonObject item = call(server, request("nullable", "{}", null)).getAsJsonObject("result")
                    .getAsJsonArray("items").get(0).getAsJsonObject();
            check(item.has("name") && item.get("name").isJsonNull()
                    && item.has("normalizedValue") && item.get("normalizedValue").isJsonNull(),
                    "Unavailable observations remain explicit nulls across the real wire boundary");
            JsonObject invalid = call(server, "{}\n");
            check(invalid.has("id") && invalid.get("id").isJsonNull(),
                    "An uncorrelated rejection retains its explicit null id");
        }
    }

    private static void validation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong deadline = new AtomicLong();
        try (RpcServer server = new RpcServer(0, (method, params, until) -> {
            calls.incrementAndGet(); deadline.set(until); return CompletableFuture.completedFuture(ok());
        })) {
            server.start();
            long before = System.nanoTime();
            JsonObject response = call(server, request("汉字🎛", "{}", null));
            check(response.get("id").getAsString().equals("汉字🎛"), "UTF-8 correlation");
            check(response.getAsJsonObject("result").get("status").getAsString().equals("observed"), "Result envelope");
            check(deadline.get() > before && deadline.get() < before + TimeUnit.SECONDS.toNanos(6), "Monotonic deadline");
            int accepted = calls.get();
            String[] invalid = {
                "{}\n", "[]\n", "{\"id\":1,\"method\":\"status\",\"params\":{}}\n",
                request("", "{}", null), request("x".repeat(129), "{}", null),
                request("a", "null", null), request("a", "[]", null),
                request("a", "{}", "99"), request("a", "{}", "10001"),
                request("a", "{}", "1.5"), request("a", "{}", "\"500\""), request("a", "{}", "true"),
                request("a", "{\"x\":1,\"x\":2}", null),
                "{\"id\":\"a\",\"id\":\"b\",\"method\":\"status\",\"params\":{}}\n",
                "{\"id\":\"a\",\"method\":\"status\",\"params\":{},\"extra\":0}\n",
                "{'id':'a','method':'status','params':{}}\n",
                "{\"id\":\"a\",/*comment*/\"method\":\"status\",\"params\":{}}\n",
                request("a", "{\"x\":NaN}", null), request("a", "{\"x\":01}", null),
                request("a", "{}", null).replace("}\n", "} {}\n"),
                request("a", "{\"x\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}", null)
            };
            for (String bad : invalid) error(call(server, bad), "invalid_request", "not_dispatched");
            error(call(server, request("a", "{}", null).replace("status", "unknown")), "method_not_found", "not_dispatched");
            byte[] badUtf8 = request("a", "{\"x\":\"?\"}", null).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < badUtf8.length; i++) if (badUtf8[i] == '?') badUtf8[i] = (byte) 0xff;
            error(call(server, badUtf8), "invalid_request", "not_dispatched");
            check(calls.get() == accepted, "Invalid input never invokes handler");
            try (Socket socket = connect(server)) {
                socket.getOutputStream().write(request("half", "{}", null).trim().getBytes(StandardCharsets.UTF_8));
                socket.shutdownOutput();
                error(read(socket), "invalid_request", "not_dispatched");
            }
            try (Socket socket = connect(server)) {
                long start = System.nanoTime();
                socket.getOutputStream().write('{'); socket.getOutputStream().flush();
                error(read(socket), "invalid_request", "not_dispatched");
                check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(4), "Idle reader bounded");
            }
            String base = request("limit", "{\"padding\":\"\"}", null);
            String boundary = base.replace("\"padding\":\"\"", "\"padding\":\"" + "x".repeat(RpcServer.MAX_BYTES - (base.length() - 1)) + "\"");
            check(boundary.getBytes(StandardCharsets.UTF_8).length == RpcServer.MAX_BYTES + 1, "Boundary fixture");
            check(call(server, boundary).has("result"), "1 MiB request excluding newline accepted");
            error(call(server, boundary.replace("padding", "paddingX")), "invalid_request", "not_dispatched");
            try (Socket socket = connect(server)) {
                byte[] bytes = request("fragmented", "{}", null).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) { socket.getOutputStream().write(b); socket.getOutputStream().flush(); }
                check(read(socket).get("id").getAsString().equals("fragmented"), "Fragmented TCP request");
                check(socket.getInputStream().read() == -1, "One response then connection closes");
            }
        }
    }

    private static void deadlines() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<JsonObject> pending = new CompletableFuture<>();
        try (RpcServer server = new RpcServer(0, (method, params, until) -> { calls.incrementAndGet(); return pending; })) {
            server.start();
            error(call(server, request("late", "{}", "100")), "deadline_exceeded", "unknown");
            check(!pending.isCancelled(), "Transport does not claim to cancel host work");
            pending.complete(ok());
            check(calls.get() == 1, "No automatic retry");
        }
        try (RpcServer server = new RpcServer(0, (method, params, until) -> {
            CompletableFuture<JsonObject> failure = new CompletableFuture<>();
            failure.completeExceptionally(new RpcException("target_changed", "Target changed", "not_dispatched"));
            return failure;
        })) {
            server.start(); error(call(server, request("host-error", "{}", null)), "target_changed", "not_dispatched");
        }
    }

    private static void queuedDeadline() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<JsonObject> hold = new CompletableFuture<>();
        List<Socket> occupying = new ArrayList<>();
        try (RpcServer server = new RpcServer(0, (method, params, until) -> {
            calls.incrementAndGet(); entered.countDown(); return hold;
        })) {
            server.start();
            for (int i = 0; i < 4; i++) {
                Socket socket = connect(server); occupying.add(socket);
                socket.getOutputStream().write(request("occupy-" + i, "{}", "10000").getBytes(StandardCharsets.UTF_8));
            }
            check(entered.await(2, TimeUnit.SECONDS), "Workers occupied before queued request");
            try (Socket queued = connect(server)) {
                queued.getOutputStream().write(request("expired-in-queue", "{}", "100").getBytes(StandardCharsets.UTF_8));
                queued.getOutputStream().flush();
                Thread.sleep(250); // Deliberately exceed the queued request's declared budget.
                hold.complete(ok());
                error(read(queued), "deadline_exceeded", "not_dispatched");
                check(calls.get() == 4, "Expired queued request never invokes handler");
            }
            for (Socket socket : occupying) check(read(socket).has("result"), "Occupying request completes");
        } finally { for (Socket socket : occupying) socket.close(); }
    }

    private static void responseLimit() throws Exception {
        try (RpcServer server = new RpcServer(0, (method, params, until) -> {
            JsonObject result = ok(); result.addProperty("large", "x".repeat(RpcServer.MAX_BYTES));
            return CompletableFuture.completedFuture(result);
        })) {
            server.start(); error(call(server, request("large", "{}", null)), "internal_error", "unknown");
        }
    }

    private static void overloadAndClose() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch four = new CountDownLatch(4);
        RpcServer server = new RpcServer(0, (method, params, until) -> {
            calls.incrementAndGet(); four.countDown(); return new CompletableFuture<>();
        });
        List<Socket> sockets = new ArrayList<>();
        server.start();
        int port = server.getPort();
        try {
            for (int i = 0; i < 20; i++) {
                Socket socket = connect(server); sockets.add(socket);
                try { socket.getOutputStream().write(request("busy-" + i, "{}", "10000").getBytes(StandardCharsets.UTF_8)); }
                catch (IOException ignored) { /* A saturated server may close immediately. */ }
            }
            check(four.await(2, TimeUnit.SECONDS), "Four workers dispatched");
            check(calls.get() == 4, "Network dispatch concurrency bounded to four");
            server.close(); server.close();
            for (Socket socket : sockets) {
                try { check(socket.getInputStream().read() == -1, "Close releases active/queued peers"); }
                catch (SocketException ignored) { checks++; }
            }
            try (Socket unexpected = new Socket("127.0.0.1", port)) { throw new AssertionError("Listener remained open"); }
            catch (ConnectException expected) { checks++; }
        } finally { server.close(); for (Socket socket : sockets) socket.close(); }
    }
}
