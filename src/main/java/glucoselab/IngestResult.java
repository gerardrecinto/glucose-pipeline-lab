package glucoselab;

import java.util.List;

public record IngestResult(int accepted, int duplicates, List<Rejection> rejected) {
    public record Rejection(int index, String reason) {}
}
