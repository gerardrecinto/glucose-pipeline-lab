package glucoselab;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Request counters and latency sums rendered as Prometheus text. */
public final class Counters {
    private final Map<String, LongAdder> requests = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> micros = new ConcurrentHashMap<>();

    public void record(String route, int status, long nanos) {
        requests.computeIfAbsent(route + "|" + status, k -> new LongAdder()).increment();
        micros.computeIfAbsent(route, k -> new LongAdder()).add(nanos / 1000);
    }

    public String render(ReadingStore store) {
        StringBuilder sb = new StringBuilder();
        sb.append("# TYPE glucose_readings_accepted_total counter\n")
          .append("glucose_readings_accepted_total ").append(store.acceptedTotal()).append('\n')
          .append("# TYPE glucose_readings_duplicate_total counter\n")
          .append("glucose_readings_duplicate_total ").append(store.duplicatesTotal()).append('\n')
          .append("# TYPE glucose_readings_rejected_total counter\n");
        store.rejectedTotals().forEach((reason, n) ->
                sb.append("glucose_readings_rejected_total{reason=\"").append(reason).append("\"} ").append(n).append('\n'));
        sb.append("# TYPE http_requests_total counter\n");
        new TreeMap<>(requests).forEach((k, v) -> {
            String[] p = k.split("\\|");
            sb.append("http_requests_total{route=\"").append(p[0]).append("\",status=\"").append(p[1]).append("\"} ")
              .append(v.sum()).append('\n');
        });
        sb.append("# TYPE http_request_duration_microseconds_total counter\n");
        new TreeMap<>(micros).forEach((route, v) ->
                sb.append("http_request_duration_microseconds_total{route=\"").append(route).append("\"} ").append(v.sum()).append('\n'));
        return sb.toString();
    }
}
