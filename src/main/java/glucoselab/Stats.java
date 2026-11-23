package glucoselab;

import java.util.List;

/** Standard CGM report metrics. Targets follow the international consensus on time in range. */
public final class Stats {
    public static final int LOW = 70;
    public static final int VERY_LOW = 54;
    public static final int HIGH = 180;
    public static final int VERY_HIGH = 250;
    public static final int SAMPLE_MINUTES = 5;
    public static final int GAP_MINUTES = 15;
    public static final double MIN_COVERAGE_PCT = 70.0;

    private Stats() {}

    /** readings must be sorted by ts and fall inside the window. */
    public static Summary compute(String subject, List<Reading> readings, int days) {
        int expected = days * 24 * 60 / SAMPLE_MINUTES;
        int n = readings.size();
        double coverage = expected == 0 ? 0 : 100.0 * n / expected;
        int gaps = 0;
        for (int i = 1; i < n; i++) {
            if (readings.get(i).ts() - readings.get(i - 1).ts() > GAP_MINUTES * 60L) gaps++;
        }
        if (n == 0) {
            return new Summary(subject, days, 0, expected, 0, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        double sum = 0;
        int inRange = 0, below = 0, veryLow = 0, above = 0, veryHigh = 0;
        for (Reading r : readings) {
            sum += r.mgdl();
            if (r.mgdl() < LOW) below++;
            if (r.mgdl() < VERY_LOW) veryLow++;
            if (r.mgdl() > HIGH) above++;
            if (r.mgdl() > VERY_HIGH) veryHigh++;
            if (r.mgdl() >= LOW && r.mgdl() <= HIGH) inRange++;
        }
        double mean = sum / n;
        double sq = 0;
        for (Reading r : readings) sq += (r.mgdl() - mean) * (r.mgdl() - mean);
        double sd = Math.sqrt(sq / n);
        double gmi = 3.31 + 0.02392 * mean;
        return new Summary(subject, days, n, expected, round(coverage), coverage >= MIN_COVERAGE_PCT, gaps,
                round(mean), round(sd), round(100 * sd / mean), round(gmi),
                pct(inRange, n), pct(below, n), pct(veryLow, n), pct(above, n), pct(veryHigh, n));
    }

    private static double pct(int part, int whole) {
        return round(100.0 * part / whole);
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
