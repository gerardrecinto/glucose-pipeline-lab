package glucoselab;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        int port = 8080;
        Path data = null;
        boolean demo = false;
        int retentionDays = 0;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data" -> data = Path.of(args[++i]);
                case "--demo" -> demo = true;
                case "--retention-days" -> retentionDays = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("usage: java -jar glucose-pipeline.jar [--demo] [--port 8080] [--data DIR] [--retention-days N]");
                    System.exit(2);
                }
            }
        }
        Path log = null;
        if (data != null) {
            Files.createDirectories(data);
            log = data.resolve("readings.jsonl");
        }
        long now = System.currentTimeMillis() / 1000;
        ReadingStore store = new ReadingStore(log);
        if (retentionDays > 0) System.out.println("purged " + store.purgeOlderThan(now - retentionDays * 86400L) + " old readings");
        if (demo) {
            var r = store.ingest(SyntheticData.demo(now), now);
            System.out.println("demo data: " + r.accepted() + " readings for subject-001, subject-002, subject-003");
        }
        Api api = new Api(store, new RuleInsights(), () -> System.currentTimeMillis() / 1000, System.getenv("API_TOKEN"));
        int bound = api.start(port);
        System.out.println("listening on http://127.0.0.1:" + bound + "  try: curl -s localhost:" + bound + "/v1/subjects/subject-001/summary");
    }
}
