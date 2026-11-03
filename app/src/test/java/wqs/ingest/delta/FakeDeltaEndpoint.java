package wqs.ingest.delta;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A real HTTP {@code /wallet/delta} on the JDK's HttpServer, for tests that run the Spring-wired
 * client: serves queued bodies in order, then the default body, and records each request's query string.
 */
public final class FakeDeltaEndpoint implements AutoCloseable {

    private final HttpServer server;
    private final BlockingQueue<String> bodies = new LinkedBlockingQueue<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private volatile String fallback = "[]";

    public FakeDeltaEndpoint() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/wallet/delta", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            String body = bodies.poll();
            byte[] bytes = (body == null ? fallback : body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public void enqueue(String body) {
        bodies.add(body);
    }

    /** The body served whenever the queue is empty (default {@code []}), e.g. for a retried run. */
    public void serveByDefault(String body) {
        fallback = body;
    }

    public List<String> queries() {
        return List.copyOf(queries);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
