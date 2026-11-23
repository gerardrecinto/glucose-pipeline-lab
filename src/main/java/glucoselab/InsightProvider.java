package glucoselab;

import java.util.List;

/** Seam for a model-backed provider. Any provider must return only grounded insights; see InsightGuard. */
public interface InsightProvider {
    List<Insight> insights(Summary s);
}
