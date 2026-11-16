package so.alaz.taffy.metrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;

/**
 * An HTTP server on the loopback address and a free port that answers every request with {@code 200} and records
 * its path and gunzipped body. {@link #point()} sends both FastStats services to it; {@link #close()} stops it and
 * points them back at the closed port the test task sets.
 */
final class LoopbackSink implements AutoCloseable {

    static final String METRICS_SERVER = "faststats.metrics-server";
    static final String ERROR_SERVER = "faststats.error-tracker-server";
    static final String CLOSED_PORT = "http://127.0.0.1:9";

    /** One request the sink received. */
    record Request(String method, String path, String body) {
    }

    final List<Request> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    LoopbackSink() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** Points both FastStats services at this sink. A context reads them when it is created. */
    void point() {
        String host = server.getAddress().getAddress().getHostAddress();
        String url = "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + server.getAddress().getPort();
        System.setProperty(METRICS_SERVER, url);
        System.setProperty(ERROR_SERVER, url);
    }

    /** The paths of the requests received so far, in order. */
    List<String> paths() {
        return requests.stream().map(Request::path).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body;
            try (InputStream in = new GZIPInputStream(exchange.getRequestBody())) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body));
            exchange.sendResponseHeaders(200, -1);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        System.setProperty(METRICS_SERVER, CLOSED_PORT);
        System.setProperty(ERROR_SERVER, CLOSED_PORT);
    }
}
