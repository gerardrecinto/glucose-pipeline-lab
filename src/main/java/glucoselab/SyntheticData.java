package glucoselab;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Deterministic fake CGM traces. Three profiles so the demo shows good control, high variability and a sensor that was off. */
public final class SyntheticData {
    private SyntheticData() {}

    public enum Profile { STEADY, VARIABLE, SPARSE }

    public static List<Reading> trace(String subject, Profile p, int days, long endTs, long seed) {
        Random rnd = new Random(seed);
        List<Reading> out = new ArrayList<>();
        int total = days * 288;
        long start = endTs - (long) (total - 1) * 300;
        double mean = p == Profile.VARIABLE ? 150 : 120;
        double swing = p == Profile.VARIABLE ? 70 : 25;
        double noise = p == Profile.VARIABLE ? 22 : 8;
        for (int i = 0; i < total; i++) {
            if (p == Profile.SPARSE && rnd.nextDouble() < 0.6) continue;
            long ts = start + (long) i * 300;
            double hour = (i % 288) / 12.0;
            double v = mean + swing * Math.sin((hour - 6) / 24.0 * 2 * Math.PI) + rnd.nextGaussian() * noise;
            int mgdl = (int) Math.max(40, Math.min(400, Math.round(v)));
            out.add(new Reading("dev-" + subject, subject, ts, mgdl));
        }
        return out;
    }

    public static List<Reading> demo(long endTs) {
        List<Reading> all = new ArrayList<>();
        all.addAll(trace("subject-001", Profile.STEADY, 14, endTs, 1));
        all.addAll(trace("subject-002", Profile.VARIABLE, 14, endTs, 2));
        all.addAll(trace("subject-003", Profile.SPARSE, 14, endTs, 3));
        return all;
    }
}
