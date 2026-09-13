package software.sava.http_servers.soak;

import java.util.concurrent.atomic.AtomicLongArray;

/// A fixed-size log-scale histogram of latencies in microseconds (5 % bucket width, 1 µs to
/// about 1,000 s) so percentiles cost no per-sample allocation and readers can report while
/// workers record.
final class LatencyHistogram {

  private static final double LOG_BASE = Math.log(1.05);
  private static final int BUCKETS = 430;

  private final AtomicLongArray counts;

  LatencyHistogram() {
    this.counts = new AtomicLongArray(BUCKETS);
  }

  void record(final long nanos) {
    final long micros = Math.max(1L, nanos / 1_000L);
    final int bucket = Math.min(BUCKETS - 1, (int) (Math.log(micros) / LOG_BASE));
    counts.incrementAndGet(bucket);
  }

  /// The upper bound, in milliseconds, of the bucket holding the `percentile`-th sample,
  /// or 0 when nothing was recorded.
  double percentileMillis(final double percentile) {
    long total = 0;
    for (int i = 0; i < BUCKETS; ++i) {
      total += counts.get(i);
    }
    if (total == 0) {
      return 0d;
    }
    final long rank = Math.max(1L, (long) Math.ceil(percentile * total));
    long cumulative = 0;
    for (int i = 0; i < BUCKETS; ++i) {
      cumulative += counts.get(i);
      if (cumulative >= rank) {
        return Math.pow(1.05, i + 1) / 1_000d;
      }
    }
    return Math.pow(1.05, BUCKETS) / 1_000d;
  }
}
