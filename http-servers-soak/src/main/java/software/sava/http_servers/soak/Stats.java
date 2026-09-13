package software.sava.http_servers.soak;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

/// One traffic profile's counters, shared by that profile's workers: responses read and
/// responses verified, per-status counts, mismatches by kind with the first few examples,
/// exceptions by class, profile-specific counters, abuse cases passed (a case can pass
/// without reading a response, so it is not a `verified_ok`), and, for the HttpClient
/// profile, latency histograms: one per operation and one pooled over the operations whose
/// latency the server owns — the injected `/slow` sleep is kept out of the pooled figure.
final class Stats {

  static final int EXAMPLES_PER_KIND = 3;

  private final String profile;
  private final LongAdder requests;
  private final LongAdder ok;
  private final LongAdder cases;
  private final ConcurrentHashMap<Integer, LongAdder> statuses;
  private final ConcurrentHashMap<String, LongAdder> mismatches;
  private final ConcurrentHashMap<String, LongAdder> exceptions;
  private final ConcurrentHashMap<String, LongAdder> counters;
  private final ConcurrentHashMap<String, List<String>> examples;
  private final LatencyHistogram latency;
  private final ConcurrentHashMap<String, LatencyHistogram> latencyByOp;

  Stats(final String profile, final boolean withLatency) {
    this.profile = profile;
    this.requests = new LongAdder();
    this.ok = new LongAdder();
    this.cases = new LongAdder();
    this.statuses = new ConcurrentHashMap<>();
    this.mismatches = new ConcurrentHashMap<>();
    this.exceptions = new ConcurrentHashMap<>();
    this.counters = new ConcurrentHashMap<>();
    this.examples = new ConcurrentHashMap<>();
    this.latency = withLatency ? new LatencyHistogram() : null;
    this.latencyByOp = withLatency ? new ConcurrentHashMap<>() : null;
  }

  String profile() {
    return profile;
  }

  /// A response was read and its status recorded.
  void request(final int status) {
    requests.increment();
    statuses.computeIfAbsent(status, s -> new LongAdder()).increment();
  }

  /// A response read through [#request(int)] was verified against its expectation.
  void ok() {
    ok.increment();
  }

  /// An abuse case completed with everything it asserts holding; independent of [#ok()],
  /// which counts responses, because a case may assert only a close or a survival.
  void casePassed() {
    cases.increment();
  }

  /// Records `nanos` under `op`, and in the pooled histogram when `pooled`.
  void latency(final String op, final long nanos, final boolean pooled) {
    if (latency != null) {
      if (pooled) {
        latency.record(nanos);
      }
      latencyByOp.computeIfAbsent(op, o -> new LatencyHistogram()).record(nanos);
    }
  }

  void count(final String key) {
    count(key, 1L);
  }

  void count(final String key, final long delta) {
    counters.computeIfAbsent(key, k -> new LongAdder()).add(delta);
  }

  void mismatch(final String kind, final String example) {
    mismatches.computeIfAbsent(kind, k -> new LongAdder()).increment();
    remember("mismatch:" + kind, example);
  }

  void exception(final Throwable t, final String context) {
    final var kind = t.getClass().getName();
    exceptions.computeIfAbsent(kind, k -> new LongAdder()).increment();
    remember("exception:" + kind, context + ": " + t);
  }

  private void remember(final String key, final String example) {
    final var list = examples.computeIfAbsent(key, k -> new ArrayList<>(EXAMPLES_PER_KIND));
    synchronized (list) {
      if (list.size() < EXAMPLES_PER_KIND) {
        list.add(example);
      }
    }
  }

  long requests() {
    return requests.sum();
  }

  long verifiedOk() {
    return ok.sum();
  }

  long casesPassed() {
    return cases.sum();
  }

  long mismatchCount() {
    return mismatches.values().stream().mapToLong(LongAdder::sum).sum();
  }

  long exceptionCount() {
    return exceptions.values().stream().mapToLong(LongAdder::sum).sum();
  }

  long counter(final String key) {
    final var adder = counters.get(key);
    return adder == null ? 0L : adder.sum();
  }

  /// The sum of every counter whose key `keys` accepts.
  long counterSum(final Predicate<String> keys) {
    long sum = 0;
    for (final var entry : counters.entrySet()) {
      if (keys.test(entry.getKey())) {
        sum += entry.getValue().sum();
      }
    }
    return sum;
  }

  double p50Millis() {
    return latency == null ? 0d : latency.percentileMillis(0.50);
  }

  double p99Millis() {
    return latency == null ? 0d : latency.percentileMillis(0.99);
  }

  /// One line for the periodic summary.
  String brief() {
    final var sb = new StringBuilder(96)
        .append('[').append(profile).append("] req=").append(requests())
        .append(" ok=").append(verifiedOk());
    if (casesPassed() > 0) {
      sb.append(" cases=").append(casesPassed());
    }
    sb.append(" mm=").append(mismatchCount())
        .append(" ex=").append(exceptionCount());
    if (latency != null) {
      sb.append(String.format(" p50=%.2fms p99=%.2fms", p50Millis(), p99Millis()));
    }
    return sb.toString();
  }

  /// The profile's block of the final report.
  String report() {
    final var sb = new StringBuilder(512)
        .append('[').append(profile).append("] requests=").append(requests())
        .append(" verified_ok=").append(verifiedOk());
    if (casesPassed() > 0) {
      sb.append(" cases_passed=").append(casesPassed());
    }
    sb.append(" statuses=").append(sorted(statuses))
        .append(" mismatches=").append(mismatchCount())
        .append(" exceptions=").append(exceptionCount());
    if (latency != null) {
      sb.append(String.format(" latency p50=%.2fms p99=%.2fms (bucket upper bounds, every op but slow)", p50Millis(), p99Millis()));
      sb.append('\n').append('[').append(profile).append("] latency by op (bucket upper bounds):");
      var separator = " ";
      for (final var entry : new TreeMap<>(latencyByOp).entrySet()) {
        final var histogram = entry.getValue();
        sb.append(separator).append(entry.getKey())
            .append(String.format(" p50=%.2fms p99=%.2fms", histogram.percentileMillis(0.50), histogram.percentileMillis(0.99)));
        separator = ", ";
      }
    }
    if (!counters.isEmpty()) {
      sb.append('\n').append('[').append(profile).append("] counters=").append(sorted(counters));
    }
    return sb.toString();
  }

  /// `profile/kind=count: example | example` lines for every mismatch kind and exception class.
  void appendDetails(final StringBuilder sb) {
    appendDetails(sb, "mismatch", mismatches);
    appendDetails(sb, "exception", exceptions);
  }

  private void appendDetails(final StringBuilder sb, final String category, final Map<String, LongAdder> byKind) {
    for (final var entry : sorted(byKind).entrySet()) {
      sb.append("  ").append(category).append(' ').append(profile).append('/').append(entry.getKey())
          .append('=').append(entry.getValue());
      final var list = examples.get(category + ':' + entry.getKey());
      if (list != null) {
        synchronized (list) {
          for (final var example : list) {
            sb.append("\n      e.g. ").append(example);
          }
        }
      }
      sb.append('\n');
    }
  }

  private static <K extends Comparable<K>> Map<K, Long> sorted(final Map<K, LongAdder> map) {
    final var sorted = new TreeMap<K, Long>();
    map.forEach((key, value) -> sorted.put(key, value.sum()));
    return sorted;
  }
}
