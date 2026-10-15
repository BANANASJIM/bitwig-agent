package dev.bitwigagent;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded local transport. The handler enqueues host work; this class never calls host APIs. */
public final class RpcServer implements AutoCloseable {
    public interface Handler {
        CompletableFuture<JsonObject> handle(String method, JsonObject params, long deadlineNanos);
    }

    public static final int MAX_BYTES = 1024 * 1024;
    private static final Set<String> METHODS = HostBridge.supportedMethods();
    private static final Set<String> FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "id", "method", "params", "timeoutMs")));
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private final int port;
    private final Handler handler;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(8), daemonFactory("bitwig-agent-rpc-worker"));
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1,
        daemonFactory("bitwig-agent-rpc-timeout"));
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private ServerSocket listener;
    private Thread acceptThread;

    public RpcServer(int port, Handler handler) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port");
        this.port = port;
        this.handler = Objects.requireNonNull(handler);
        timer.setRemoveOnCancelPolicy(true);
    }

    private static ThreadFactory daemonFactory(String name) {
        return task -> { Thread t = new Thread(task, name); t.setDaemon(true); return t; };
    }

    public synchronized void start() throws IOException {
        if (closed || listener != null) throw new IllegalStateException("Server already started or closed");
        ServerSocket socket = new ServerSocket();
        try {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        } catch (IOException e) { socket.close(); throw e; }
        listener = socket;
        acceptThread = daemonFactory("bitwig-agent-rpc-accept").newThread(this::acceptLoop);
        acceptThread.start();
    }

    public synchronized int getPort() { return listener == null ? port : listener.getLocalPort(); }

    private void acceptLoop() {
        while (!closed) {
            Socket socket = null;
            try {
                socket = listener.accept();
                long acceptedAt = System.nanoTime();
                if (closed) { closeSocket(socket); break; }
                socket.setSoTimeout(1000);
                clients.add(socket);
                Socket client = socket;
                // Also bounds blocked writes, queued connections, and peers that never read.
                ScheduledFuture<?> expiry = timer.schedule(() -> closeSocket(client), 15, TimeUnit.SECONDS);
                try {
                    workers.execute(() -> {
                        try { serve(client, acceptedAt); }
                        finally { expiry.cancel(false); clients.remove(client); closeSocket(client); }
                    });
                } catch (RejectedExecutionException e) {
                    expiry.cancel(false);
                    clients.remove(client);
                    closeSocket(client); // Overload closes before dispatch; clients must not retry writes automatically.
                }
            } catch (IOException | RejectedExecutionException e) {
                if (socket != null) { clients.remove(socket); closeSocket(socket); }
                if (!closed && listener.isClosed()) break;
            }
        }
    }

    private void serve(Socket socket, long acceptedAt) {
        String id = null;
        boolean dispatched = false;
        try {
            JsonObject request = readRequest(socket);
            String candidateId = string(request, "id");
            if (candidateId.isEmpty() || candidateId.codePointCount(0, candidateId.length()) > 128)
                throw invalid("id must have 1-128 characters");
            id = candidateId;
            for (String key : request.keySet()) if (!FIELDS.contains(key)) throw invalid("Unknown request field: " + key);
            String method = string(request, "method");
            if (!METHODS.contains(method)) throw new RpcException("method_not_found", "Unknown method", "not_dispatched");
            if (!request.has("params") || !request.get("params").isJsonObject()) throw invalid("params must be an object");
            int timeout = 5000;
            if (request.has("timeoutMs")) {
                JsonElement value = request.get("timeoutMs");
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid("timeoutMs must be an integer");
                try { timeout = value.getAsBigDecimal().intValueExact(); }
                catch (ArithmeticException | NumberFormatException e) { throw invalid("timeoutMs must be an integer"); }
                if (timeout < 100 || timeout > 10000) throw invalid("timeoutMs must be 100-10000");
            }
            long deadline = acceptedAt + TimeUnit.MILLISECONDS.toNanos(timeout);
            if (System.nanoTime() >= deadline) throw new RpcException("deadline_exceeded",
                "Request expired before dispatch; no host operation was scheduled.", "not_dispatched");
            dispatched = true;
            CompletableFuture<JsonObject> pending = handler.handle(method, request.getAsJsonObject("params"), deadline);
            if (pending == null) throw new IllegalStateException("Handler returned no future");
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            JsonObject result = pending.get(remaining, TimeUnit.NANOSECONDS);
            if (result == null) throw new IllegalStateException("Handler returned no result");
            JsonObject response = envelope(id);
            response.add("result", result);
            send(socket, response);
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            RpcException error;
            if (cause instanceof RpcException) error = (RpcException) cause;
            else if (cause instanceof TimeoutException) error = new RpcException("deadline_exceeded",
                "Outcome unknown; inspect state before issuing another write.", "unknown");
            else if (!dispatched) error = invalid("Invalid or incomplete UTF-8 JSON request");
            else error = new RpcException("internal_error", "Outcome unknown; inspect state before issuing another write.", "unknown");
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            JsonObject response = envelope(id);
            JsonObject detail = new JsonObject();
            detail.addProperty("code", error.code);
            detail.addProperty("message", error.getMessage());
            detail.addProperty("outcome", error.outcome);
            response.add("error", detail);
            try { send(socket, response); } catch (IOException | RuntimeException ignored) { /* Peer closed or cannot receive. */ }
        }
    }

    private static JsonObject envelope(String id) {
        JsonObject response = new JsonObject();
        if (id == null) response.add("id", JsonNull.INSTANCE); else response.addProperty("id", id);
        return response;
    }

    private static RpcException invalid(String message) {
        return new RpcException("invalid_request", message, "not_dispatched");
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw invalid(key + " must be a string");
        return value.getAsString();
    }

    private static JsonObject readRequest(Socket socket) throws IOException {
        InputStream input = new BufferedInputStream(socket.getInputStream());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (true) {
            if (System.nanoTime() >= deadline) throw invalid("Request read deadline exceeded");
            int b = input.read();
            if (b == -1) throw invalid("Request must end with newline");
            if (b == '\n') break;
            if (bytes.size() >= MAX_BYTES) throw invalid("Request exceeds 1 MiB");
            bytes.write(b);
        }
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement result = readJson(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT || !result.isJsonObject()) throw invalid("Expected one JSON object");
            return result.getAsJsonObject();
        }
    }

    /** Gson's convenience parser enables leniency; use strict tokens and reject duplicate names. */
    private static JsonElement readJson(JsonReader reader, int depth) throws IOException {
        if (depth > 64) throw invalid("JSON nesting exceeds 64");
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) throw invalid("Duplicate JSON field");
                    object.add(name, readJson(reader, depth + 1));
                }
                reader.endObject(); return object;
            case BEGIN_ARRAY:
                JsonArray array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) array.add(readJson(reader, depth + 1));
                reader.endArray(); return array;
            case STRING: return new JsonPrimitive(reader.nextString());
            case NUMBER: return new JsonPrimitive(ToNumberPolicy.LAZILY_PARSED_NUMBER.readNumber(reader));
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw invalid("Invalid JSON token");
        }
    }

    private static void send(Socket socket, JsonObject response) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream bounded = new FilterOutputStream(bytes) {
            @Override public void write(int b) throws IOException {
                if (bytes.size() >= MAX_BYTES) throw new IOException("Response exceeds 1 MiB");
                out.write(b);
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                if ((long) bytes.size() + len > MAX_BYTES) throw new IOException("Response exceeds 1 MiB");
                out.write(b, off, len);
            }
        };
        Writer writer = new OutputStreamWriter(bounded, StandardCharsets.UTF_8);
        GSON.toJson(response, writer);
        writer.flush();
        bytes.write('\n');
        bytes.writeTo(socket.getOutputStream());
        socket.getOutputStream().flush();
    }

    private static void closeSocket(Socket socket) { try { socket.close(); } catch (IOException ignored) { } }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        for (Socket socket : clients) closeSocket(socket);
        clients.clear();
        workers.shutdownNow();
        timer.shutdownNow();
    }
}
