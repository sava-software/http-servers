package software.sava.http_servers.soak;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/// The load generator: runs the traffic profiles concurrently on virtual threads for the
/// whole duration — (a) keep-alive `HttpClient` workers, (b) raw-socket pipeliners, (c)
/// raw-socket abusers with per-backend expectations plus one idle prober, (d) a connection
/// churner — prints a summary line every 30 s and a final report, and exits 0 only when
/// there were no mismatches, no unexpected exceptions (one that escapes a worker is recorded
/// by the thread's uncaught-exception handler and counts), no worker stuck past the join
/// bound, and enough traffic to have tested anything: every profile read at least one
/// response, every abuse case ran or was skipped for a stated reason, and the run made at
/// least one request per second per connection.
///
/// `main` takes the host, the port, the duration in seconds (default 60), the connection
/// count (default 32, split across the profiles) and the backend name that selects the
/// expectation table (default `netty`; an enum name or a factory simple name).
public final class SoakLoad {

  static final int DEFAULT_CONNECTIONS = 32;
  static final int DEFAULT_DURATION_SECONDS = 60;
  static final long SUMMARY_INTERVAL_MILLIS = 30_000L;
  static final long JOIN_BOUND_MILLIS = 60_000L;
  /// Requests per second per connection below which the run is starved rather than passed.
  static final long FLOOR_RPS_PER_CONNECTION = 1L;
  private static final long SEED = 0x5EED_5041_0000L;

  public static void main(final String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("usage: SoakLoad <host> <port> [durationSeconds=" + DEFAULT_DURATION_SECONDS
          + "] [connections=" + DEFAULT_CONNECTIONS + "] [backend=netty]");
      System.exit(2);
    }
    final var host = args[0];
    final int port = Integer.parseInt(args[1]);
    final int durationSeconds = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_DURATION_SECONDS;
    final int connections = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_CONNECTIONS;
    final var backend = Backend.of(args.length > 4 ? args[4] : "netty");

    final int clients = Math.max(1, connections / 2);
    final int pipeliners = Math.max(1, connections / 4);
    final int abusers = Math.max(1, connections / 8);
    final int idlers = 1;
    final int churners = Math.max(1, connections - clients - pipeliners - abusers - idlers);

    final var clientStats = new Stats("client", true);
    final var pipelinerStats = new Stats("pipeliner", false);
    final var abuserStats = new Stats("abuser", false);
    final var churnerStats = new Stats("churner", false);
    final var all = List.of(clientStats, pipelinerStats, abuserStats, churnerStats);

    System.out.println("SoakLoad backend=" + backend + " target=" + host + ':' + port + " duration=" + durationSeconds
        + "s connections=" + connections + " (client=" + clients + " pipeliner=" + pipeliners
        + " abuser=" + abusers + " idle=" + idlers + " churner=" + churners + ')');
    System.out.flush();

    final var client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .build();
    final var run = Run.of(durationSeconds);
    final long start = System.nanoTime();
    final var workers = new ArrayList<Thread>(connections + 1);
    for (int i = 0; i < clients; ++i) {
      workers.add(start("client-" + i, clientStats, new ClientWorker(i, client, host, port, clientStats, run, SEED + i)));
    }
    for (int i = 0; i < pipeliners; ++i) {
      workers.add(start("pipeliner-" + i, pipelinerStats, new Pipeliner(i, host, port, backend, pipelinerStats, run, SEED + 1_000 + i)));
    }
    for (int i = 0; i < abusers; ++i) {
      workers.add(start("abuser-" + i, abuserStats, new Abuser(i, host, port, backend, abuserStats, run, SEED + 2_000 + i)));
    }
    for (int i = 0; i < churners; ++i) {
      workers.add(start("churner-" + i, churnerStats, new Churner(i, host, port, churnerStats, run, SEED + 3_000 + i)));
    }
    for (int i = 0; i < idlers; ++i) {
      workers.add(start("idle-" + i, abuserStats, new IdleProber(i, host, port, backend, abuserStats, run, SEED + 4_000 + i)));
    }

    long nextSummary = SUMMARY_INTERVAL_MILLIS;
    while (run.running()) {
      Thread.sleep(Math.min(1_000L, Math.max(1L, run.remainingMillis())));
      final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      if (elapsed >= nextSummary) {
        System.out.println(summary(all, elapsed));
        System.out.flush();
        nextSummary += SUMMARY_INTERVAL_MILLIS;
      }
    }

    final long joinDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(JOIN_BOUND_MILLIS);
    int stuck = 0;
    for (final var worker : workers) {
      final long remaining = joinDeadline - System.nanoTime();
      if (remaining > 0) {
        worker.join(Duration.ofNanos(remaining));
      }
      if (worker.isAlive()) {
        ++stuck;
        System.out.println("worker " + worker.getName() + " did not finish within " + JOIN_BOUND_MILLIS + " ms of the deadline");
      }
    }
    final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    client.shutdownNow();

    long mismatches = 0;
    long exceptions = 0;
    long requests = 0;
    for (final var stats : all) {
      mismatches += stats.mismatchCount();
      exceptions += stats.exceptionCount();
      requests += stats.requests();
    }
    final long floor = FLOOR_RPS_PER_CONNECTION * durationSeconds * connections;
    final var starved = new ArrayList<String>();
    for (final var stats : all) {
      if (stats.requests() == 0) {
        starved.add("the " + stats.profile() + " profile read no response");
      }
    }
    final var unexercised = Abuser.unexercised(abuserStats);
    if (!unexercised.isEmpty()) {
      starved.add("abuse cases neither run nor skipped: " + unexercised);
    }
    if (requests < floor) {
      starved.add(requests + " requests, below the floor of " + floor + " (" + FLOOR_RPS_PER_CONNECTION + " per second per connection)");
    }
    final long skippedCases = abuserStats.counterSum(key -> key.startsWith("case:") && key.contains(":skipped"));
    final boolean pass = mismatches == 0 && exceptions == 0 && stuck == 0 && starved.isEmpty();
    System.out.println(report(backend, host, port, durationSeconds, connections, clients, pipeliners, abusers, idlers, churners,
        all, elapsed, requests, floor, skippedCases, unexercised, starved, mismatches, exceptions, stuck, pass));
    System.out.flush();
    System.exit(pass ? 0 : 1);
  }

  /// A worker on a virtual thread whose escaping exception is recorded against its profile —
  /// so a dead worker is a failed run, not a silent one — and printed the way the default
  /// handler would, for `soak.sh` to see.
  private static Thread start(final String name, final Stats stats, final Runnable worker) {
    return Thread.ofVirtual()
        .name(name)
        .uncaughtExceptionHandler((thread, t) -> {
          stats.exception(t, "uncaught in " + thread.getName());
          System.err.println("Exception in thread \"" + thread.getName() + "\" escaped the worker and was recorded:");
          t.printStackTrace();
        })
        .start(worker);
  }

  private static String summary(final List<Stats> all, final long elapsedMillis) {
    long requests = 0;
    for (final var stats : all) {
      requests += stats.requests();
    }
    final var sb = new StringBuilder(256)
        .append(String.format("[t=%ds] requests=%d rps=%.1f", elapsedMillis / 1_000, requests, rps(requests, elapsedMillis)));
    for (final var stats : all) {
      sb.append(" | ").append(stats.brief());
    }
    return sb.toString();
  }

  private static double rps(final long requests, final long elapsedMillis) {
    return elapsedMillis == 0 ? 0d : requests * 1_000d / elapsedMillis;
  }

  private static String report(final Backend backend,
                               final String host,
                               final int port,
                               final int durationSeconds,
                               final int connections,
                               final int clients,
                               final int pipeliners,
                               final int abusers,
                               final int idlers,
                               final int churners,
                               final List<Stats> all,
                               final long elapsedMillis,
                               final long requests,
                               final long floor,
                               final long skippedCases,
                               final List<String> unexercised,
                               final List<String> starved,
                               final long mismatches,
                               final long exceptions,
                               final int stuck,
                               final boolean pass) {
    final var sb = new StringBuilder(2_048)
        .append("=== SoakLoad final report ===\n")
        .append("backend=").append(backend).append(" target=").append(host).append(':').append(port)
        .append(" duration=").append(durationSeconds).append("s connections=").append(connections)
        .append(" (client=").append(clients).append(" pipeliner=").append(pipeliners)
        .append(" abuser=").append(abusers).append(" idle=").append(idlers).append(" churner=").append(churners).append(")\n")
        .append(String.format("requests=%d rps=%.1f elapsed=%.1fs%n", requests, rps(requests, elapsedMillis), elapsedMillis / 1_000d));
    for (final var stats : all) {
      sb.append(stats.report()).append('\n');
    }
    sb.append("progress:");
    for (final var stats : all) {
      sb.append(' ').append(stats.profile()).append('=').append(stats.requests());
    }
    sb.append(" total=").append(requests).append(" floor=").append(floor)
        .append(" skipped_cases=").append(skippedCases).append(" unexercised_cases=").append(unexercised).append('\n');
    sb.append("mismatches: total=").append(mismatches).append('\n');
    sb.append("exceptions: total=").append(exceptions).append('\n');
    for (final var stats : all) {
      stats.appendDetails(sb);
    }
    if (stuck > 0) {
      sb.append("workers stuck past the join bound: ").append(stuck).append('\n');
    }
    sb.append("RESULT: ").append(pass ? "PASS" : "FAIL")
        .append(" (").append(mismatches).append(" mismatches, ").append(exceptions).append(" unexpected exceptions")
        .append(stuck > 0 ? ", " + stuck + " stuck workers" : "")
        .append(", ").append(requests).append(" requests against a floor of ").append(floor)
        .append(", ").append(skippedCases).append(" abuse case(s) skipped")
        .append(starved.isEmpty() ? "" : "; starved: " + String.join("; ", starved))
        .append(')');
    return sb.toString();
  }

  private SoakLoad() {
  }
}
