package glucoselab;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

public final class Api {
    static final int MAX_BATCH = 5000;
    static final String NOTICE = "Synthetic data. Not medical advice and not a medical device.";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ReadingStore store;
    private final InsightProvider insights;
    private final Counters counters = new Counters();
    private final LongSupplier clock;
    private final String token;
    private HttpServer server;

    public Api(ReadingStore store, InsightProvider insights, LongSupplier clockSeconds, String token) {
        this.store = store;
        this.insights = insights;
        this.clock = clockSeconds;
        this.token = token == null || token.isBlank() ? null : token;
    }

    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        long t0 = System.nanoTime();
        String path = ex.getRequestURI().getPath();
        String route = routeName(path);
        int status;
        try {
            status = dispatch(ex, path);
        } catch (RuntimeException e) {
            status = send(ex, 500, Map.of("error", "internal_error"));
        }
        counters.record(route, status, System.nanoTime() - t0);
    }

    private static String routeName(String path) {
        if (path.startsWith("/v1/subjects/") && path.endsWith("/summary")) return "/v1/subjects/{id}/summary";
        if (path.startsWith("/v1/subjects/") && path.endsWith("/readings")) return "/v1/subjects/{id}/readings";
        if (path.equals("/v1/readings") || path.equals("/v1/quality") || path.equals("/healthz") || path.equals("/metrics")) return path;
        return "other";
    }

    private int dispatch(HttpExchange ex, String path) throws IOException {
        String method = ex.getRequestMethod();
        if (path.equals("/healthz")) return send(ex, 200, Map.of("status", "ok"));
        if (path.equals("/metrics")) return sendText(ex, 200, counters.render(store));
        if (path.startsWith("/v1/") && !authorized(ex)) return send(ex, 401, Map.of("error", "unauthorized"));

        if (path.equals("/v1/readings")) {
            if (!method.equals("POST")) return send(ex, 405, Map.of("error", "method_not_allowed"));
            return ingest(ex);
        }
        if (path.equals("/v1/quality")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accepted", store.acceptedTotal());
            m.put("duplicates", store.duplicatesTotal());
            m.put("rejectedByReason", store.rejectedTotals());
            return send(ex, 200, m);
        }
        String[] p = path.split("/");
        if (p.length == 5 && p[1].equals("v1") && p[2].equals("subjects")) {
            String subject = p[3];
            if (!store.knows(subject)) return send(ex, 404, Map.of("error", "unknown_subject"));
            Map<String, String> q = query(ex);
            if (p[4].equals("summary")) return summary(ex, subject, q);
            if (p[4].equals("readings")) return readings(ex, subject, q);
        }
        return send(ex, 404, Map.of("error", "not_found"));
    }

    private boolean authorized(HttpExchange ex) {
        if (token == null) return true;
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return h != null && h.equals("Bearer " + token);
    }

    private int ingest(HttpExchange ex) throws IOException {
        List<Reading> rows;
        try (InputStream in = ex.getRequestBody()) {
            rows = JSON.readValue(in, new TypeReference<List<Reading>>() {});
        } catch (JsonProcessingException e) {
            return send(ex, 400, Map.of("error", "malformed_json"));
        }
        if (rows.size() > MAX_BATCH) return send(ex, 413, Map.of("error", "batch_too_large", "max", MAX_BATCH));
        return send(ex, 200, store.ingest(rows, clock.getAsLong()));
    }

    private int summary(HttpExchange ex, String subject, Map<String, String> q) throws IOException {
        int days;
        try {
            days = Integer.parseInt(q.getOrDefault("days", "14"));
        } catch (NumberFormatException e) {
            return send(ex, 400, Map.of("error", "bad_days"));
        }
        if (days < 1 || days > 90) return send(ex, 400, Map.of("error", "days_must_be_1_to_90"));
        long end = store.latestTs(subject);
        long from = end - days * 86400L + 1;
        Summary s = Stats.compute(subject, store.range(subject, from, end), days);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("summary", s);
        m.put("insights", InsightGuard.apply(s, insights.insights(s)));
        m.put("notice", NOTICE);
        return send(ex, 200, m);
    }

    private int readings(HttpExchange ex, String subject, Map<String, String> q) throws IOException {
        long end = store.latestTs(subject);
        long to;
        long from;
        try {
            to = Long.parseLong(q.getOrDefault("to", Long.toString(end)));
            from = Long.parseLong(q.getOrDefault("from", Long.toString(to - 86400L)));
        } catch (NumberFormatException e) {
            return send(ex, 400, Map.of("error", "bad_range"));
        }
        if (from > to) return send(ex, 400, Map.of("error", "from_after_to"));
        return send(ex, 200, store.range(subject, from, to));
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(kv.substring(0, i), kv.substring(i + 1));
        }
        return m;
    }

    private static int send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] b = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
        return status;
    }

    private static int sendText(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
        return status;
    }
}
