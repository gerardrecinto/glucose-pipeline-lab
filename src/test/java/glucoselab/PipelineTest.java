package glucoselab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelineTest {
    static final long NOW = 1_800_000_000L;
    static final ObjectMapper JSON = new ObjectMapper();

    static Reading r(String subject, long ts, int mgdl) {
        return new Reading("dev-1", subject, ts, mgdl);
    }

    @Test
    void validatorRejectsBadRowsWithReasons() {
        assertEquals("out_of_range", Validator.check(r("s", NOW, 5), NOW).get());
        assertEquals("out_of_range", Validator.check(r("s", NOW, 601), NOW).get());
        assertEquals("future_timestamp", Validator.check(r("s", NOW + 301, 100), NOW).get());
        assertEquals("bad_timestamp", Validator.check(r("s", 0, 100), NOW).get());
        assertEquals("missing_subject", Validator.check(r(" ", NOW, 100), NOW).get());
        assertTrue(Validator.check(r("s", NOW + 300, 100), NOW).isEmpty());
    }

    @Test
    void ingestIsIdempotentAndAcceptsOutOfOrder() {
        ReadingStore st = new ReadingStore(null);
        List<Reading> batch = List.of(r("s", NOW, 100), r("s", NOW - 600, 110), r("s", NOW - 300, 105));
        assertEquals(3, st.ingest(batch, NOW).accepted());
        IngestResult again = st.ingest(batch, NOW);
        assertEquals(0, again.accepted());
        assertEquals(3, again.duplicates());
        assertEquals(List.of(NOW - 600, NOW - 300, NOW), st.range("s", 0, NOW).stream().map(Reading::ts).toList());
    }

    @Test
    void goodRowsSurviveABatchWithBadRows() {
        ReadingStore st = new ReadingStore(null);
        IngestResult res = st.ingest(List.of(r("s", NOW, 100), r("s", NOW - 300, 900), r("s", NOW - 600, 120)), NOW);
        assertEquals(2, res.accepted());
        assertEquals(1, res.rejected().size());
        assertEquals(1, res.rejected().get(0).index());
        assertEquals(1L, st.rejectedTotals().get("out_of_range"));
    }

    @Test
    void logReplayRebuildsTheIndex(@TempDir Path dir) {
        Path log = dir.resolve("readings.jsonl");
        ReadingStore a = new ReadingStore(log);
        a.ingest(List.of(r("s", NOW, 100), r("s", NOW - 300, 110)), NOW);
        a.ingest(List.of(r("s", NOW, 100)), NOW);
        ReadingStore b = new ReadingStore(log);
        assertEquals(2, b.range("s", 0, NOW).size());
    }

    @Test
    void purgeDropsOldRowsAndRewritesTheLog(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("readings.jsonl");
        ReadingStore a = new ReadingStore(log);
        a.ingest(List.of(r("s", NOW, 100), r("s", NOW - 10_000, 110)), NOW);
        assertEquals(1, a.purgeOlderThan(NOW - 5_000));
        assertEquals(1, Files.readAllLines(log).size());
        assertEquals(1, new ReadingStore(log).range("s", 0, NOW).size());
    }

    @Test
    void statsMatchAHandComputedSeries() {
        int[] v = {60, 100, 100, 200, 100, 100, 100, 100, 100, 140};
        List<Reading> rows = new ArrayList<>();
        for (int i = 0; i < v.length; i++) rows.add(r("s", NOW + i * 300L, v[i]));
        Summary s = Stats.compute("s", rows, 1);
        assertEquals(10, s.readings());
        assertEquals(288, s.expectedReadings());
        assertEquals(110.0, s.meanMgdl());
        assertEquals(80.0, s.timeInRangePct());
        assertEquals(10.0, s.timeBelowPct());
        assertEquals(10.0, s.timeAbovePct());
        assertEquals(0.0, s.timeVeryHighPct());
        assertEquals(5.9, s.gmiPct());
        assertFalse(s.sufficient());
    }

    @Test
    void gapsAreCountedWhenSamplesAreMoreThan15MinutesApart() {
        List<Reading> rows = List.of(r("s", NOW, 100), r("s", NOW + 300, 100), r("s", NOW + 300 + 901, 100));
        assertEquals(1, Stats.compute("s", rows, 1).gaps());
    }

    @Test
    void insufficientCoverageSuppressesInterpretation() {
        Summary s = Stats.compute("s", List.of(r("s", NOW, 300)), 14);
        List<Insight> out = InsightGuard.apply(s, new RuleInsights().insights(s));
        assertEquals(1, out.size());
        assertEquals("insufficient_data", out.get(0).code());
    }

    @Test
    void guardDropsUngroundedAndAdviceInsights() {
        Summary s = Stats.compute("s", SyntheticData.trace("s", SyntheticData.Profile.VARIABLE, 14, NOW, 2), 14);
        List<Insight> candidates = List.of(
                new Insight("ok", "Time above 180 mg/dL is above the 25% limit.", Map.of("timeAbovePct", s.timeAbovePct())),
                new Insight("wrong_number", "Time above 180 mg/dL is above the 25% limit.", Map.of("timeAbovePct", 1.0)),
                new Insight("no_evidence", "Glucose looks bad.", Map.of()),
                new Insight("unknown_field", "Something.", Map.of("madeUp", 1.0)),
                new Insight("advice", "Take more insulin after meals.", Map.of("timeAbovePct", s.timeAbovePct())));
        assertEquals(List.of("ok"), InsightGuard.apply(s, candidates).stream().map(Insight::code).toList());
    }

    @Test
    void syntheticProfilesProduceTheExpectedStories() {
        Summary steady = Stats.compute("a", SyntheticData.trace("a", SyntheticData.Profile.STEADY, 14, NOW, 1), 14);
        Summary variable = Stats.compute("b", SyntheticData.trace("b", SyntheticData.Profile.VARIABLE, 14, NOW, 2), 14);
        Summary sparse = Stats.compute("c", SyntheticData.trace("c", SyntheticData.Profile.SPARSE, 14, NOW, 3), 14);
        assertTrue(steady.timeInRangePct() > 90, "steady TIR " + steady.timeInRangePct());
        assertTrue(variable.timeInRangePct() < 70, "variable TIR " + variable.timeInRangePct());
        assertFalse(sparse.sufficient(), "sparse coverage " + sparse.coveragePct());
        assertTrue(new RuleInsights().insights(steady).isEmpty());
    }

    // ---- API over a real socket ----

    record Server(Api api, int port, HttpClient http) {
        HttpResponse<String> get(String path, String token) throws Exception {
            var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
            if (token != null) b.header("Authorization", "Bearer " + token);
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, String body, String token) throws Exception {
            var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).POST(HttpRequest.BodyPublishers.ofString(body));
            if (token != null) b.header("Authorization", "Bearer " + token);
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    static Server serve(String token) throws Exception {
        Api api = new Api(new ReadingStore(null), new RuleInsights(), () -> NOW, token);
        return new Server(api, api.start(0), HttpClient.newHttpClient());
    }

    @Test
    void apiIngestThenSummaryEndToEnd() throws Exception {
        Server s = serve(null);
        try {
            String body = JSON.writeValueAsString(SyntheticData.trace("subject-9", SyntheticData.Profile.VARIABLE, 14, NOW, 2));
            JsonNode ing = JSON.readTree(s.post("/v1/readings", body, null).body());
            assertEquals(4032, ing.get("accepted").asInt());
            JsonNode again = JSON.readTree(s.post("/v1/readings", body, null).body());
            assertEquals(0, again.get("accepted").asInt());
            assertEquals(4032, again.get("duplicates").asInt());

            JsonNode sum = JSON.readTree(s.get("/v1/subjects/subject-9/summary?days=14", null).body());
            assertTrue(sum.get("summary").get("sufficient").asBoolean());
            assertTrue(sum.get("insights").size() >= 1);
            assertTrue(sum.get("notice").asText().contains("Not medical advice"));

            assertEquals(404, s.get("/v1/subjects/nobody/summary", null).statusCode());
            assertEquals(400, s.get("/v1/subjects/subject-9/summary?days=0", null).statusCode());
            assertEquals(400, s.post("/v1/readings", "{not json", null).statusCode());
            assertEquals(405, s.get("/v1/readings", null).statusCode());
            String metrics = s.get("/metrics", null).body();
            assertTrue(metrics.contains("glucose_readings_accepted_total 4032"));
            assertTrue(metrics.contains("http_requests_total{route=\"/v1/readings\",status=\"200\"} 2"));
        } finally {
            s.api().stop();
        }
    }

    @Test
    void apiRequiresTheTokenWhenOneIsSet() throws Exception {
        Server s = serve("secret");
        try {
            assertEquals(401, s.get("/v1/quality", null).statusCode());
            assertEquals(401, s.get("/v1/quality", "wrong").statusCode());
            assertEquals(200, s.get("/v1/quality", "secret").statusCode());
            assertEquals(200, s.get("/healthz", null).statusCode());
        } finally {
            s.api().stop();
        }
    }

    @Test
    void apiRejectsOversizedBatches() throws Exception {
        Server s = serve(null);
        try {
            List<Reading> big = new ArrayList<>();
            for (int i = 0; i < Api.MAX_BATCH + 1; i++) big.add(r("s", NOW - i * 300L, 100));
            assertEquals(413, s.post("/v1/readings", JSON.writeValueAsString(big), null).statusCode());
        } finally {
            s.api().stop();
        }
    }
}
