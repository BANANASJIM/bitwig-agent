package dev.bitwigagent.rpc;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.MethodSpecs;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.SchemaValidator;
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

    /** A pending answer that can be refused for good at the deadline if its operation never dispatched. */
    public interface Expiring {
        /** True when the operation had not dispatched and now never will; false when it already dispatched. */
        boolean expire();
    }

    /** One cap for requests and responses (protocol limits.py). */
    public static final int MAX_MESSAGE_BYTES = HostLimits.LIMITS.maxMessageBytes;

    private static final Set<String> FIELDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("id", "method", "params", "timeoutMs", "protocol")));
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private final int port;
    private final MethodSpecs specs;
    private final Handler handler;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(8), daemonFactory("bitwig-agent-rpc-worker"));
    private final ScheduledThreadPoolExecutor timer =
            new ScheduledThreadPoolExecutor(1, daemonFactory("bitwig-agent-rpc-timeout"));
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private ServerSocket listener;
    private Thread acceptThread;

    public RpcServer(int port, MethodSpecs specs, Handler handler) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port");
        this.port = port;
        this.specs = Objects.requireNonNull(specs);
        this.handler = Objects.requireNonNull(handler);
        timer.setRemoveOnCancelPolicy(true);
    }

    private static ThreadFactory daemonFactory(String name) {
        return task -> {
            Thread t = new Thread(task, name);
            t.setDaemon(true);
            return t;
        };
    }

    public synchronized void start() throws IOException {
        if (closed || listener != null) throw new IllegalStateException("Server already started or closed");
        ServerSocket socket = new ServerSocket();
        try {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        listener = socket;
        acceptThread = daemonFactory("bitwig-agent-rpc-accept").newThread(this::acceptLoop);
        acceptThread.start();
    }

    public synchronized int getPort() {
        return listener == null ? port : listener.getLocalPort();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket socket = null;
            try {
                socket = listener.accept();
                long acceptedAt = System.nanoTime();
                if (closed) {
                    closeSocket(socket);
                    break;
                }
                socket.setSoTimeout(1000);
                clients.add(socket);
                Socket client = socket;
                // Also bounds blocked writes, queued connections, and peers that never read.
                java.util.concurrent.atomic.AtomicReference<ScheduledFuture<?>> expiry =
                        new java.util.concurrent.atomic.AtomicReference<>(
                                timer.schedule(() -> closeSocket(client), SOCKET_EXPIRY_MS, TimeUnit.MILLISECONDS));
                // A request with a longer deadline keeps its socket until that deadline plus delivery.
                java.util.function.IntConsumer extend = timeoutMs -> {
                    if (timeoutMs + SOCKET_GRACE_MS > SOCKET_EXPIRY_MS
                            && expiry.get().cancel(false))
                        expiry.set(timer.schedule(
                                () -> closeSocket(client), timeoutMs + SOCKET_GRACE_MS, TimeUnit.MILLISECONDS));
                };
                try {
                    workers.execute(() -> {
                        try {
                            serve(client, acceptedAt, extend);
                        } finally {
                            expiry.get().cancel(false);
                            clients.remove(client);
                            closeSocket(client);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    expiry.get().cancel(false);
                    clients.remove(client);
                    closeSocket(
                            client); // Overload closes before dispatch; clients must not retry writes automatically.
                }
            } catch (IOException | RejectedExecutionException e) {
                if (socket != null) {
                    clients.remove(socket);
                    closeSocket(socket);
                }
                if (!closed && listener.isClosed()) break;
            }
        }
    }

    private void serve(Socket socket, long acceptedAt, java.util.function.IntConsumer extendExpiry) {
        String id = null;
        boolean dispatched = false;
        MethodSpecs.Spec spec = null;
        try {
            JsonObject request = readRequest(socket);
            String candidateId = string(request, "id");
            if (candidateId.isEmpty() || candidateId.codePointCount(0, candidateId.length()) > 128)
                throw invalid("id must have 1-128 characters");
            id = candidateId;
            for (String key : request.keySet())
                if (!FIELDS.contains(key)) throw invalid("Unknown request field: " + key);
            String protocol = specs.vocabulary().protocol;
            if (!request.has("protocol") || !protocol.equals(string(request, "protocol")))
                throw Errors.notApplied(
                        Reason.PROTOCOL_MISMATCH,
                        "This extension speaks protocol " + protocol + "; install the matching client and extension.");
            String method = string(request, "method");
            if (!specs.contains(method)) throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown method");
            if (!request.has("params") || !request.get("params").isJsonObject())
                throw invalid("params must be an object");
            spec = specs.get(method);
            int timeout = spec.defaultTimeoutMs;
            if (request.has("timeoutMs")) {
                JsonObject envelope = new JsonObject();
                envelope.add("timeoutMs", request.get("timeoutMs"));
                try {
                    timeout = spec.timeout.apply(envelope).get("timeoutMs").getAsInt();
                } catch (SchemaValidator.Violation violation) {
                    throw invalid(violation.getMessage() + " (" + method + ")");
                }
            }
            extendExpiry.accept(timeout);
            long deadline = acceptedAt + TimeUnit.MILLISECONDS.toNanos(timeout);
            if (System.nanoTime() >= deadline)
                throw Errors.notApplied(
                        Reason.DEADLINE, "Request expired before dispatch; no host operation was scheduled.");
            dispatched = true;
            CompletableFuture<JsonObject> pending = handler.handle(method, request.getAsJsonObject("params"), deadline);
            if (pending == null) throw new IllegalStateException("Handler returned no future");
            JsonObject result = answer(pending, deadline);
            if (result == null) throw new IllegalStateException("Handler returned no result");
            JsonObject response = envelope(id);
            response.add("result", Errors.withInspect(result, spec.inspect));
            send(socket, response);
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            boolean write = dispatched && !spec.readOnly;
            RpcException error;
            if (cause instanceof RpcException) error = (RpcException) cause;
            else if (cause instanceof TimeoutException)
                error = Errors.of(
                        Reason.DEADLINE,
                        "No answer before the deadline; inspect state (status.holds lists any job it started) before another write.",
                        write);
            else if (!dispatched) error = invalid("Invalid or incomplete UTF-8 JSON request");
            else error = Errors.of(Reason.INTERNAL, "Internal failure; inspect state before another write.", write);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            JsonObject response = envelope(id);
            if (error.unknown) response.add("result", Errors.unknownResult(error, spec == null ? null : spec.inspect));
            else response.add("error", Errors.error(error));
            try {
                send(socket, response);
            } catch (IOException | RuntimeException ignored) {
                /* Peer closed or cannot receive. */
            }
        }
    }

    static final int MIN_TIMEOUT_MS = HostLimits.LIMITS.minTimeoutMs, SOCKET_EXPIRY_MS = 15000, SOCKET_GRACE_MS = 5000;
    /** Half the client's wait past the deadline, so a dispatched operation's own answer still reaches it. */
    static final int ANSWER_GRACE_MS = HostLimits.LIMITS.responseGraceMs / 2;

    /**
     * The operation's own answer. At the deadline an operation that never dispatched is refused for good (an error,
     * nothing changed); one that did gets a short grace to answer itself, so its result keeps what it dispatched.
     */
    private static JsonObject answer(CompletableFuture<JsonObject> pending, long deadline) throws Exception {
        try {
            return pending.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException late) {
            if (pending instanceof Expiring && ((Expiring) pending).expire())
                throw Errors.notApplied(
                        Reason.DEADLINE, "Nothing was dispatched before the deadline; nothing changed.");
            return pending.get(ANSWER_GRACE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static JsonObject envelope(String id) {
        JsonObject response = new JsonObject();
        if (id == null) response.add("id", JsonNull.INSTANCE);
        else response.addProperty("id", id);
        return response;
    }

    private static RpcException invalid(String message) {
        return Errors.notApplied(Reason.INVALID_REQUEST, message);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) throw invalid(key + " must be a string");
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
            if (bytes.size() >= MAX_MESSAGE_BYTES) throw invalid("Request exceeds " + mebibytes(MAX_MESSAGE_BYTES));
            bytes.write(b);
        }
        String text = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement result = readJson(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT || !result.isJsonObject())
                throw invalid("Expected one JSON object");
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
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(readJson(reader, depth + 1));
                reader.endArray();
                return array;
            case STRING:
                return new JsonPrimitive(reader.nextString());
            case NUMBER:
                return new JsonPrimitive(ToNumberPolicy.LAZILY_PARSED_NUMBER.readNumber(reader));
            case BOOLEAN:
                return new JsonPrimitive(reader.nextBoolean());
            case NULL:
                reader.nextNull();
                return JsonNull.INSTANCE;
            default:
                throw invalid("Invalid JSON token");
        }
    }

    private static String mebibytes(int bytes) {
        return (bytes >> 20) + " MiB";
    }

    private static void send(Socket socket, JsonObject response) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream bounded = new FilterOutputStream(bytes) {
            @Override
            public void write(int b) throws IOException {
                if (bytes.size() >= MAX_MESSAGE_BYTES)
                    throw new IOException("Response exceeds " + mebibytes(MAX_MESSAGE_BYTES));
                out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if ((long) bytes.size() + len > MAX_MESSAGE_BYTES)
                    throw new IOException("Response exceeds " + mebibytes(MAX_MESSAGE_BYTES));
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

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    /** Returns once the port is released: the blocked accept call holds the listening socket until it returns. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (listener != null)
            try {
                listener.close();
            } catch (IOException ignored) {
            }
        boolean interrupted = false;
        while (acceptThread != null && acceptThread.isAlive())
            try {
                acceptThread.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        if (interrupted) Thread.currentThread().interrupt();
        for (Socket socket : clients) closeSocket(socket);
        clients.clear();
        workers.shutdownNow();
        timer.shutdownNow();
    }
}
