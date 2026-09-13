package software.sava.http_servers.soak;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/// Post-processes the soak server's flight recording into `jfr-report.md`, the section
/// `soak.sh` appends to `summary.md`, and a flat `key=value` metrics file its verdict reads.
/// One streaming pass over the file through the `jdk.jfr.consumer` API — the same reader
/// `jfr print` is built on, so field access is typed and independent of that tool's text
/// layout.
///
/// `main` takes the recording, the report path, and optionally the `nmt.csv` `soak.sh` wrote
/// from `jcmd VM.native_memory summary.diff` (rendered as the native-memory series) and the
/// metrics path. Exit status 0 once both files are written; a missing or unreadable
/// recording is an exception, which `soak.sh` turns into a failed verdict.
public final class JfrReport {

  static final int TOP_FRAMES = 8;
  static final int TOP_EVENT_TYPES = 30;
  static final int TOP_SITES = 10;
  static final int TOP_STACKS = 10;
  static final int TOP_THREADS = 5;
  static final int TOP_PINNED = 5;
  static final int TOP_SPAWNERS = 5;
  static final int TOP_NMT_TYPES = 12;
  static final int MAX_REFERRER_HOPS = 12;
  static final int MAX_TRACKED_THREADS = 200_000;
  static final int MAX_STACKS_PER_FRAME = 64;
  /// One thread holding at least this share of at least [#DOMINANT_MIN_SAMPLES] execution
  /// samples is the wedged-thread signature the report calls out.
  static final double DOMINANT_SHARE = 0.5d;
  static final long DOMINANT_MIN_SAMPLES = 100L;

  public static void main(final String[] args) throws IOException {
    if (args.length < 2) {
      System.err.println("usage: JfrReport <recording.jfr> <report.md> [nmt.csv] [metrics.properties]");
      System.exit(2);
    }
    final var recording = Path.of(args[0]);
    final var report = Path.of(args[1]);
    final var nmtCsv = args.length > 2 && !args[2].isBlank() ? Path.of(args[2]) : null;
    final var metricsPath = args.length > 3 && !args[3].isBlank() ? Path.of(args[3]) : null;

    final var pass = new Pass();
    try (final var file = new RecordingFile(recording)) {
      while (file.hasMoreEvents()) {
        pass.accept(file.readEvent());
      }
    }
    final var metrics = new TreeMap<String, String>();
    try (final var out = new PrintWriter(Files.newBufferedWriter(report, StandardCharsets.UTF_8))) {
      pass.write(out, recording, nmtCsv, metrics);
    }
    if (metricsPath != null) {
      final var sb = new StringBuilder(512);
      metrics.forEach((key, value) -> sb.append(key).append('=').append(value).append('\n'));
      Files.writeString(metricsPath, sb, StandardCharsets.UTF_8);
    }
    System.out.println("JfrReport: " + pass.events + " events of " + pass.eventCounts.size() + " types from " + recording
        + " -> " + report + (metricsPath == null ? "" : " and " + metricsPath));
  }

  /// A retained allocation site: one object type allocated from one top frame.
  static final class Site {
    long samples;
    long bytes;
    List<String> frames;
    String root;
    List<String> path;
  }

  /// One deduplicated old-object sample; the same sample is re-emitted at every chunk
  /// rotation, and only the emission at a `path-to-gc-roots=true` dump carries the root.
  record OldSample(String type, long bytes, List<String> frames, String root, List<String> path, Instant emitted) {
  }

  /// Execution samples sharing a top frame, with the most common full stack beneath it.
  static final class Hot {
    long samples;
    final Map<String, long[]> stacks = new HashMap<>();
    final Map<String, List<String>> frames = new HashMap<>();
  }

  /// Pinned events sharing a stack (and reason).
  static final class Pin {
    long count;
    long maxNanos;
    long sumNanos;
    List<String> frames;
    String reason;
  }

  static final class Pass {
    long events;
    Instant first;
    Instant last;
    final Map<String, long[]> eventCounts = new HashMap<>();

    final Map<String, OldSample> oldSamples = new HashMap<>();

    long executionSamples;
    final Map<String, Hot> hot = new HashMap<>();
    final Map<Long, long[]> threadSamples = new HashMap<>();
    final Map<Long, String> threadLabels = new HashMap<>();
    long untrackedThreadSamples;

    long pinned;
    final Map<String, Pin> pins = new HashMap<>();

    long submitFailed;
    final List<String> submitFailures = new ArrayList<>();

    final List<Long> gcPauses = new ArrayList<>();
    long gcLongestPause;
    final Map<String, long[]> gcCauses = new HashMap<>();
    final Map<String, long[]> gcNames = new HashMap<>();
    final List<Long> phasePauses = new ArrayList<>();
    final Map<String, long[]> phaseNames = new HashMap<>();

    long threadStarts;
    long threadEnds;
    final Map<String, long[]> spawners = new HashMap<>();

    long nmtTotalSamples;
    Instant nmtFirstAt;
    long nmtFirstCommitted;
    long nmtFirstReserved;
    Instant nmtLastAt;
    long nmtLastCommitted;
    long nmtLastReserved;
    /// type -> {first committed, last committed, first reserved, last reserved}, with the
    /// instants they were taken at.
    final Map<String, long[]> nmtTypes = new HashMap<>();
    final Map<String, Instant[]> nmtTypeAt = new HashMap<>();

    void accept(final RecordedEvent event) {
      ++events;
      final var at = event.getStartTime();
      if (first == null || at.isBefore(first)) {
        first = at;
      }
      if (last == null || at.isAfter(last)) {
        last = at;
      }
      final var type = event.getEventType().getName();
      count(eventCounts, type);
      switch (type) {
        case "jdk.OldObjectSample" -> oldObject(event);
        case "jdk.ExecutionSample" -> execution(event);
        case "jdk.VirtualThreadPinned" -> pinned(event);
        case "jdk.VirtualThreadSubmitFailed" -> submitFailed(event);
        case "jdk.GarbageCollection" -> gc(event);
        case "jdk.GCPhasePause" -> phase(event);
        case "jdk.ThreadStart" -> threadStart(event);
        case "jdk.ThreadEnd" -> ++threadEnds;
        case "jdk.NativeMemoryUsageTotal" -> nmtTotal(event);
        case "jdk.NativeMemoryUsage" -> nmtType(event);
        default -> {
        }
      }
    }

    private void oldObject(final RecordedEvent event) {
      final var object = value(event, "object");
      final var type = object == null ? "?" : className(object.getClass("type"));
      final long bytes = event.hasField("objectSize") ? event.getLong("objectSize") : 0L;
      final var allocated = event.hasField("allocationTime") ? event.getInstant("allocationTime") : event.getStartTime();
      final int elements = event.hasField("arrayElements") ? event.getInt("arrayElements") : Integer.MIN_VALUE;
      final var key = allocated + "|" + type + "|" + bytes + "|" + elements;
      final var previous = oldSamples.get(key);
      if (previous != null && !previous.emitted().isBefore(event.getStartTime())) {
        return;
      }
      final var root = value(event, "root");
      final var rootLine = root == null ? (previous == null ? null : previous.root()) : rootLine(root);
      final var path = root == null ? (previous == null ? List.<String>of() : previous.path()) : referrerPath(object);
      oldSamples.put(key, new OldSample(type, bytes, frames(event.getStackTrace()), rootLine, path, event.getStartTime()));
    }

    private void execution(final RecordedEvent event) {
      ++executionSamples;
      final var frames = frames(event.getStackTrace());
      final var top = frames.isEmpty() ? "(no stack)" : frames.getFirst();
      final var entry = hot.computeIfAbsent(top, t -> new Hot());
      ++entry.samples;
      final var signature = String.join(" < ", frames);
      final var counter = entry.stacks.get(signature);
      if (counter != null) {
        ++counter[0];
      } else if (entry.stacks.size() < MAX_STACKS_PER_FRAME) {
        entry.stacks.put(signature, new long[]{1L});
        entry.frames.put(signature, frames);
      }
      final var thread = event.hasField("sampledThread") ? event.getThread("sampledThread") : null;
      if (thread == null) {
        ++untrackedThreadSamples;
        return;
      }
      final long id = thread.getJavaThreadId();
      final var counted = threadSamples.get(id);
      if (counted != null) {
        ++counted[0];
      } else if (threadSamples.size() < MAX_TRACKED_THREADS) {
        threadSamples.put(id, new long[]{1L});
        threadLabels.put(id, threadLabel(thread));
      } else {
        ++untrackedThreadSamples;
      }
    }

    private void pinned(final RecordedEvent event) {
      ++pinned;
      final var frames = frames(event.getStackTrace());
      final var reason = string(event, "pinnedReason");
      final var operation = string(event, "blockingOperation");
      final var why = reason == null && operation == null ? null
          : (reason == null ? "" : reason) + (operation == null ? "" : (reason == null ? "" : ", ") + operation);
      final var key = (why == null ? "" : why) + "\n" + String.join(" < ", frames);
      final var pin = pins.computeIfAbsent(key, k -> {
        final var p = new Pin();
        p.frames = frames;
        p.reason = why;
        return p;
      });
      ++pin.count;
      final long nanos = event.getDuration().toNanos();
      pin.sumNanos += nanos;
      pin.maxNanos = Math.max(pin.maxNanos, nanos);
    }

    private void submitFailed(final RecordedEvent event) {
      ++submitFailed;
      if (submitFailures.size() < 3) {
        final var frames = frames(event.getStackTrace());
        submitFailures.add((event.hasField("javaThreadId") ? "thread #" + event.getLong("javaThreadId") + ": " : "")
            + string(event, "exceptionMessage") + (frames.isEmpty() ? "" : " at " + frames.getFirst()));
      }
    }

    private void gc(final RecordedEvent event) {
      final var pauses = event.hasField("sumOfPauses") ? event.getDuration("sumOfPauses") : event.getDuration();
      gcPauses.add(pauses.toNanos());
      if (event.hasField("longestPause")) {
        gcLongestPause = Math.max(gcLongestPause, event.getDuration("longestPause").toNanos());
      }
      count(gcCauses, string(event, "cause"));
      count(gcNames, string(event, "name"));
    }

    private void phase(final RecordedEvent event) {
      phasePauses.add(event.getDuration().toNanos());
      count(phaseNames, string(event, "name"));
    }

    private void threadStart(final RecordedEvent event) {
      ++threadStarts;
      final var frames = frames(event.getStackTrace());
      var spawner = "(no stack)";
      for (final var frame : frames) {
        if (!frame.startsWith("java.lang.Thread.") && !frame.startsWith("java.lang.System$")) {
          spawner = frame;
          break;
        }
      }
      count(spawners, spawner);
    }

    private void nmtTotal(final RecordedEvent event) {
      ++nmtTotalSamples;
      final var at = event.getStartTime();
      final long committed = event.getLong("committed");
      final long reserved = event.getLong("reserved");
      if (nmtFirstAt == null || at.isBefore(nmtFirstAt)) {
        nmtFirstAt = at;
        nmtFirstCommitted = committed;
        nmtFirstReserved = reserved;
      }
      if (nmtLastAt == null || !at.isBefore(nmtLastAt)) {
        nmtLastAt = at;
        nmtLastCommitted = committed;
        nmtLastReserved = reserved;
      }
    }

    private void nmtType(final RecordedEvent event) {
      final var type = string(event, "type");
      final var at = event.getStartTime();
      final long committed = event.getLong("committed");
      final long reserved = event.getLong("reserved");
      final var values = nmtTypes.get(type);
      if (values == null) {
        nmtTypes.put(type, new long[]{committed, committed, reserved, reserved});
        nmtTypeAt.put(type, new Instant[]{at, at});
        return;
      }
      final var when = nmtTypeAt.get(type);
      if (at.isBefore(when[0])) {
        when[0] = at;
        values[0] = committed;
        values[2] = reserved;
      }
      if (!at.isBefore(when[1])) {
        when[1] = at;
        values[1] = committed;
        values[3] = reserved;
      }
    }

    void write(final PrintWriter out, final Path recording, final Path nmtCsv, final Map<String, String> metrics) throws IOException {
      metrics.put("events_total", Long.toString(events));
      out.println("## Flight recording: " + recording.getFileName());
      out.println();
      out.println("- file: " + recording + " (" + bytes(Files.size(recording)) + ")");
      if (first == null) {
        out.println("- no events");
      } else {
        out.println("- events: " + events + " of " + eventCounts.size() + " types, from " + first + " to " + last
            + " (" + seconds(Duration.between(first, last)) + ")");
      }
      writeCounts(out);
      writeOldObjects(out, metrics);
      writeExecution(out, metrics);
      writePinned(out, metrics);
      writeGc(out, metrics);
      writeThreads(out, metrics);
      writeNmt(out, nmtCsv, metrics);
    }

    private void writeCounts(final PrintWriter out) {
      out.println();
      out.println("### (a) Event counts (top " + TOP_EVENT_TYPES + " of " + eventCounts.size() + " types, the same counts as `jfr summary`)");
      out.println();
      out.println("| event type | count |");
      out.println("|---|---:|");
      final var sorted = new ArrayList<>(eventCounts.entrySet());
      sorted.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
      for (final var entry : sorted.subList(0, Math.min(TOP_EVENT_TYPES, sorted.size()))) {
        out.println("| " + entry.getKey() + " | " + entry.getValue()[0] + " |");
      }
    }

    private void writeOldObjects(final PrintWriter out, final Map<String, String> metrics) {
      final var sites = new HashMap<String, Site>();
      final var types = new HashMap<String, long[]>();
      long withRoot = 0;
      for (final var sample : oldSamples.values()) {
        final var top = sample.frames().isEmpty() ? "(no stack)" : sample.frames().getFirst();
        final var site = sites.computeIfAbsent(sample.type() + "\n" + top, k -> new Site());
        ++site.samples;
        site.bytes += sample.bytes();
        if (site.frames == null || (site.root == null && sample.root() != null)) {
          site.frames = sample.frames();
        }
        if (site.root == null && sample.root() != null) {
          site.root = sample.root();
          site.path = sample.path();
        }
        if (sample.root() != null) {
          ++withRoot;
        }
        count(types, sample.type());
      }
      final var topTypes = new ArrayList<>(types.entrySet());
      topTypes.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
      final var sb = new StringBuilder();
      for (final var entry : topTypes.subList(0, Math.min(3, topTypes.size()))) {
        sb.append(sb.isEmpty() ? "" : ";").append(entry.getKey()).append('=').append(entry.getValue()[0]);
      }
      metrics.put("old_object_samples", Long.toString(oldSamples.size()));
      metrics.put("old_object_samples_with_root", Long.toString(withRoot));
      metrics.put("old_object_top_types", sb.toString());

      out.println();
      out.println("### (b) Retained allocation sites (jdk.OldObjectSample, top " + TOP_SITES + " of " + sites.size() + " sites)");
      out.println();
      out.println("- " + oldSamples.size() + " distinct sampled objects reported at the dump, " + withRoot
          + " with a path to a GC root — the sites with one are listed first, and they are the evidence; a sample"
          + " without one was not found when the dump walked the heap from the roots (garbage the next collection"
          + " would clear, or past the walk's depth), or the dump was not taken with path-to-gc-roots — the same"
          + " sample re-emitted at every chunk rotation counted once, of "
          + count(eventCounts, "jdk.OldObjectSample", 0) + " events");
      if (!topTypes.isEmpty()) {
        out.print("- top types by sample count:");
        var separator = " ";
        for (final var entry : topTypes.subList(0, Math.min(5, topTypes.size()))) {
          out.print(separator + entry.getKey() + " (" + entry.getValue()[0] + ")");
          separator = ", ";
        }
        out.println();
      }
      // rooted sites first: a site with a path to a GC root is a retained object the dump
      // proved reachable, which is what this section is for; then by samples and bytes
      final var sorted = new ArrayList<>(sites.entrySet());
      sorted.sort((a, b) -> {
        final int rooted = Boolean.compare(b.getValue().root != null, a.getValue().root != null);
        if (rooted != 0) {
          return rooted;
        }
        final int c = Long.compare(b.getValue().samples, a.getValue().samples);
        return c != 0 ? c : Long.compare(b.getValue().bytes, a.getValue().bytes);
      });
      int rank = 0;
      for (final var entry : sorted.subList(0, Math.min(TOP_SITES, sorted.size()))) {
        final var site = entry.getValue();
        final var type = entry.getKey().substring(0, entry.getKey().indexOf('\n'));
        out.println();
        out.println((++rank) + ". `" + type + "`: " + site.samples + " sample(s), " + bytes(site.bytes) + " sampled, "
            + (site.root != null ? "reachable from a GC root" : "no path to a GC root recorded") + ", allocated at");
        out.println();
        out.println("   ```");
        for (final var frame : site.frames) {
          out.println("   " + frame);
        }
        if (site.frames.isEmpty()) {
          out.println("   (no allocation stack recorded)");
        }
        if (site.root != null) {
          out.println("   root: " + site.root);
          if (!site.path.isEmpty()) {
            out.println("   path: " + String.join(" <- ", site.path));
          }
        } else {
          out.println("   root: (none recorded: not found from the roots when the dump walked the heap, or the dump was not taken with path-to-gc-roots)");
        }
        out.println("   ```");
      }
    }

    private void writeExecution(final PrintWriter out, final Map<String, String> metrics) {
      metrics.put("execution_samples", Long.toString(executionSamples));
      out.println();
      out.println("### (c) Hottest stacks (jdk.ExecutionSample, top " + TOP_STACKS + " of " + hot.size() + " top frames)");
      out.println();
      out.println("- " + executionSamples + " samples over " + threadSamples.size() + " thread(s)"
          + (untrackedThreadSamples > 0 ? " (+" + untrackedThreadSamples + " on threads past the " + MAX_TRACKED_THREADS + " tracked)" : ""));
      final var threads = new ArrayList<>(threadSamples.entrySet());
      threads.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
      if (!threads.isEmpty() && executionSamples > 0) {
        final var top = threads.getFirst();
        final double share = top.getValue()[0] / (double) executionSamples;
        final boolean dominates = share >= DOMINANT_SHARE && executionSamples >= DOMINANT_MIN_SAMPLES;
        metrics.put("dominant_thread", threadLabels.get(top.getKey()));
        metrics.put("dominant_thread_share_pct", Long.toString(Math.round(share * 100)));
        metrics.put("dominant_thread_flag", dominates ? "1" : "0");
        out.print("- by thread:");
        var separator = " ";
        for (final var entry : threads.subList(0, Math.min(TOP_THREADS, threads.size()))) {
          out.print(separator + threadLabels.get(entry.getKey()) + " " + entry.getValue()[0]
              + " (" + Math.round(entry.getValue()[0] * 100d / executionSamples) + " %)");
          separator = ", ";
        }
        out.println();
        out.println("- " + (dominates
            ? "**one thread dominates**: " + threadLabels.get(top.getKey()) + " holds " + Math.round(share * 100)
            + " % of the samples, the wedged-thread signature (a spinning event loop or a handler that never returns)"
            : "no single thread dominates (top share " + Math.round(share * 100) + " %; the signature is >= "
            + Math.round(DOMINANT_SHARE * 100) + " % over >= " + DOMINANT_MIN_SAMPLES + " samples)"));
      } else {
        metrics.put("dominant_thread_flag", "0");
      }
      final var sorted = new ArrayList<>(hot.entrySet());
      sorted.sort((a, b) -> Long.compare(b.getValue().samples, a.getValue().samples));
      out.println();
      out.println("| samples | share | top frame | most common stack beneath it |");
      out.println("|---:|---:|---|---|");
      for (final var entry : sorted.subList(0, Math.min(TOP_STACKS, sorted.size()))) {
        final var h = entry.getValue();
        String best = null;
        long bestCount = -1;
        for (final var stack : h.stacks.entrySet()) {
          if (stack.getValue()[0] > bestCount) {
            bestCount = stack.getValue()[0];
            best = stack.getKey();
          }
        }
        final var frames = best == null ? List.<String>of() : h.frames.get(best);
        final var beneath = frames.size() > 1 ? String.join(" < ", frames.subList(1, frames.size())) : "";
        out.println("| " + h.samples + " | " + Math.round(h.samples * 100d / Math.max(1, executionSamples)) + " % | `"
            + entry.getKey() + "` | " + (beneath.isEmpty() ? "" : "`" + beneath + "`" + (bestCount > 0 ? " (" + bestCount + ")" : "")) + " |");
      }
    }

    private void writePinned(final PrintWriter out, final Map<String, String> metrics) {
      long maxNanos = 0;
      for (final var pin : pins.values()) {
        maxNanos = Math.max(maxNanos, pin.maxNanos);
      }
      metrics.put("pinned", Long.toString(pinned));
      metrics.put("pinned_max_ms", millis(maxNanos));
      metrics.put("submit_failed", Long.toString(submitFailed));
      out.println();
      out.println("### (d) Virtual threads pinned (jdk.VirtualThreadPinned, top " + TOP_PINNED + " of " + pins.size() + " stacks)");
      out.println();
      out.println("- " + pinned + " pinned event(s) at or over the 20 ms threshold, longest " + millis(maxNanos) + " ms");
      out.println("- jdk.VirtualThreadSubmitFailed: " + submitFailed + " event(s)"
          + (submitFailures.isEmpty() ? "" : " — " + String.join("; ", submitFailures)));
      final var sorted = new ArrayList<>(pins.values());
      sorted.sort((a, b) -> Long.compare(b.count, a.count));
      int rank = 0;
      for (final var pin : sorted.subList(0, Math.min(TOP_PINNED, sorted.size()))) {
        out.println();
        out.println((++rank) + ". " + pin.count + " event(s), max " + millis(pin.maxNanos) + " ms, total " + millis(pin.sumNanos) + " ms"
            + (pin.reason == null ? "" : " (" + pin.reason + ")"));
        out.println();
        out.println("   ```");
        for (final var frame : pin.frames) {
          out.println("   " + frame);
        }
        if (pin.frames.isEmpty()) {
          out.println("   (no stack)");
        }
        out.println("   ```");
      }
    }

    private void writeGc(final PrintWriter out, final Map<String, String> metrics) {
      final long gcMax = gcPauses.isEmpty() ? 0 : Collections.max(gcPauses);
      final long gcP99 = percentile(gcPauses, 0.99d);
      final long phaseMax = phasePauses.isEmpty() ? 0 : Collections.max(phasePauses);
      final long phaseP99 = percentile(phasePauses, 0.99d);
      metrics.put("gc_count", Long.toString(gcPauses.size()));
      metrics.put("gc_pause_max_ms", millis(gcMax));
      metrics.put("gc_pause_p99_ms", millis(gcP99));
      metrics.put("gc_phase_pauses", Long.toString(phasePauses.size()));
      metrics.put("gc_phase_pause_max_ms", millis(phaseMax));
      out.println();
      out.println("### (e) GC pauses");
      out.println();
      out.println("- jdk.GarbageCollection: " + gcPauses.size() + " collection(s); sum-of-pauses per collection max "
          + millis(gcMax) + " ms, p99 " + millis(gcP99) + " ms; longest single pause " + millis(gcLongestPause) + " ms");
      out.println("- jdk.GCPhasePause: " + phasePauses.size() + " pause(s); max " + millis(phaseMax) + " ms, p99 " + millis(phaseP99) + " ms");
      if (!gcNames.isEmpty()) {
        out.println("- collectors: " + joinCounts(gcNames, 5) + "; causes: " + joinCounts(gcCauses, 5));
      }
      if (!phaseNames.isEmpty()) {
        out.println("- phases: " + joinCounts(phaseNames, 5));
      }
    }

    private void writeThreads(final PrintWriter out, final Map<String, String> metrics) {
      metrics.put("thread_starts", Long.toString(threadStarts));
      metrics.put("thread_ends", Long.toString(threadEnds));
      out.println();
      out.println("### (f) Platform thread starts and ends");
      out.println();
      out.println("- jdk.ThreadStart: " + threadStarts + ", jdk.ThreadEnd: " + threadEnds + ", net " + (threadStarts - threadEnds)
          + " (platform threads only; virtual thread start/end events are not recorded)");
      if (!spawners.isEmpty()) {
        out.println("- started from (top " + TOP_SPAWNERS + "): " + joinCounts(spawners, TOP_SPAWNERS));
      }
    }

    private void writeNmt(final PrintWriter out, final Path nmtCsv, final Map<String, String> metrics) throws IOException {
      metrics.put("nmt_total_samples", Long.toString(nmtTotalSamples));
      metrics.put("nmt_total_committed_first_kb", Long.toString(nmtFirstCommitted / 1024));
      metrics.put("nmt_total_committed_last_kb", Long.toString(nmtLastCommitted / 1024));
      out.println();
      out.println("### (g) Native memory (jdk.NativeMemoryUsageTotal every 30 s, and the jcmd series in nmt.csv)");
      out.println();
      out.println("- two series, not one number: the events below are the whole NMT total — `Tracing` (the recorder's own"
          + " buffers) and `Arena Chunk` included — sampled by JFR's periodic task and covering only the recording's"
          + " disk ring; nmt.csv is a jcmd attach at each timestamp, covers the whole run, and its last column"
          + " (the total less those two categories) is the series the verdict fits");
      if (nmtTotalSamples == 0) {
        out.println("- no jdk.NativeMemoryUsageTotal events (is -XX:NativeMemoryTracking=summary on?)");
      } else {
        out.println("- total committed first/last: " + bytes(nmtFirstCommitted) + " at " + nmtFirstAt + " / " + bytes(nmtLastCommitted)
            + " at " + nmtLastAt + " (" + signed(nmtLastCommitted - nmtFirstCommitted) + " over " + nmtTotalSamples + " samples, "
            + seconds(Duration.between(nmtFirstAt, nmtLastAt)) + "); reserved first/last " + bytes(nmtFirstReserved) + " / " + bytes(nmtLastReserved));
        final var sorted = new ArrayList<>(nmtTypes.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
        out.println();
        out.println("| type | committed first | committed last | delta | reserved last |");
        out.println("|---|---:|---:|---:|---:|");
        for (final var entry : sorted.subList(0, Math.min(TOP_NMT_TYPES, sorted.size()))) {
          final var v = entry.getValue();
          out.println("| " + entry.getKey() + " | " + bytes(v[0]) + " | " + bytes(v[1]) + " | " + signed(v[1] - v[0]) + " | " + bytes(v[3]) + " |");
        }
      }
      out.println();
      if (nmtCsv == null || !Files.isRegularFile(nmtCsv)) {
        out.println("- nmt.csv: not provided");
        return;
      }
      final var lines = Files.readAllLines(nmtCsv, StandardCharsets.UTF_8);
      if (lines.size() < 2) {
        out.println("- nmt.csv: " + (lines.isEmpty() ? "empty" : "a header and no rows"));
        return;
      }
      out.println("`jcmd VM.native_memory summary.diff` against the baseline taken at start (nmt.csv, KiB; the diff columns are against that baseline):");
      out.println();
      final var header = lines.getFirst().split(",", -1);
      out.println("| " + String.join(" | ", header) + " |");
      final var align = new StringBuilder("|");
      for (final var column : header) {
        align.append(column.endsWith("_kb") || column.endsWith("_s") ? "---:|" : "---|");
      }
      out.println(align);
      for (final var line : lines.subList(1, lines.size())) {
        if (!line.isBlank()) {
          out.println("| " + String.join(" | ", line.split(",", -1)) + " |");
        }
      }
    }
  }

  static RecordedObject value(final RecordedObject holder, final String field) {
    if (holder == null || !holder.hasField(field)) {
      return null;
    }
    final Object value = holder.getValue(field);
    return value instanceof RecordedObject object ? object : null;
  }

  static String string(final RecordedObject holder, final String field) {
    if (holder == null || !holder.hasField(field)) {
      return null;
    }
    final Object value = holder.getValue(field);
    return value == null ? null : value.toString();
  }

  static String rootLine(final RecordedObject root) {
    final var description = string(root, "description");
    final var system = string(root, "system");
    final var type = string(root, "type");
    return (system == null ? "?" : system) + " / " + (type == null ? "?" : type)
        + (description == null || description.equals("N/A") ? "" : " (" + description + ")");
  }

  /// The referrer chain from the sampled object towards the root: `holder.field` or
  /// `holder[index]` per hop, with a `(skip n)` where JFR elided hops.
  static List<String> referrerPath(final RecordedObject object) {
    final var path = new ArrayList<String>();
    var reference = value(object, "referrer");
    for (int hop = 0; reference != null && hop < MAX_REFERRER_HOPS; ++hop) {
      final var holder = value(reference, "object");
      final var field = value(reference, "field");
      final var array = value(reference, "array");
      final int skip = reference.hasField("skip") ? reference.getInt("skip") : 0;
      final var holderType = holder == null ? "?" : className(holder.getClass("type"));
      final var via = field != null ? "." + string(field, "name")
          : array != null ? "[" + array.getInt("index") + " of " + array.getInt("size") + "]"
          : "";
      path.add(holderType + via + (skip > 0 ? " (skip " + skip + ")" : ""));
      reference = value(holder, "referrer");
    }
    return path;
  }

  /// `[Ljava.lang.String;` as `java.lang.String[]`, `[B` as `byte[]`.
  static String className(final RecordedClass type) {
    if (type == null) {
      return "?";
    }
    final var name = type.getName();
    int dimensions = 0;
    while (dimensions < name.length() && name.charAt(dimensions) == '[') {
      ++dimensions;
    }
    if (dimensions == 0) {
      return name;
    }
    final var element = name.substring(dimensions);
    final String base = switch (element) {
      case "B" -> "byte";
      case "C" -> "char";
      case "D" -> "double";
      case "F" -> "float";
      case "I" -> "int";
      case "J" -> "long";
      case "S" -> "short";
      case "Z" -> "boolean";
      default -> element.startsWith("L") && element.endsWith(";") ? element.substring(1, element.length() - 1) : element;
    };
    return base + "[]".repeat(dimensions);
  }

  static List<String> frames(final RecordedStackTrace stackTrace) {
    if (stackTrace == null) {
      return List.of();
    }
    final var frames = stackTrace.getFrames();
    final var labels = new ArrayList<String>(Math.min(TOP_FRAMES, frames.size()));
    for (final var frame : frames.subList(0, Math.min(TOP_FRAMES, frames.size()))) {
      labels.add(frameLabel(frame));
    }
    return labels;
  }

  static String frameLabel(final RecordedFrame frame) {
    final var method = frame.getMethod();
    final var type = method == null || method.getType() == null ? "?" : method.getType().getName();
    final var name = method == null ? "?" : method.getName();
    final int line = frame.getLineNumber();
    return type + "." + name + (line > 0 ? ":" + line : "");
  }

  static String threadLabel(final RecordedThread thread) {
    final var name = thread.getJavaName();
    final var label = name == null || name.isEmpty() ? "#" + thread.getJavaThreadId() : name;
    return thread.isVirtual() ? label + " (virtual)" : label;
  }

  static void count(final Map<String, long[]> counts, final String key) {
    final var counter = counts.get(key == null ? "?" : key);
    if (counter != null) {
      ++counter[0];
    } else {
      counts.put(key == null ? "?" : key, new long[]{1L});
    }
  }

  static long count(final Map<String, long[]> counts, final String key, final long absent) {
    final var counter = counts.get(key);
    return counter == null ? absent : counter[0];
  }

  static String joinCounts(final Map<String, long[]> counts, final int limit) {
    final var sorted = new ArrayList<>(counts.entrySet());
    sorted.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
    final var sb = new StringBuilder();
    for (final var entry : sorted.subList(0, Math.min(limit, sorted.size()))) {
      sb.append(sb.isEmpty() ? "" : ", ").append(entry.getKey()).append(" (").append(entry.getValue()[0]).append(')');
    }
    return sb.toString();
  }

  static long percentile(final List<Long> values, final double p) {
    if (values.isEmpty()) {
      return 0L;
    }
    final var sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    final int index = Math.clamp((int) Math.ceil(p * sorted.size()) - 1, 0, sorted.size() - 1);
    return sorted.get(index);
  }

  static String millis(final long nanos) {
    return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000d);
  }

  static String seconds(final Duration duration) {
    return String.format(Locale.ROOT, "%.0f s", duration.toMillis() / 1_000d);
  }

  static String bytes(final long bytes) {
    if (bytes < 0) {
      return "-" + bytes(-bytes);
    }
    if (bytes >= 1L << 30) {
      return String.format(Locale.ROOT, "%.2f GiB", bytes / (double) (1L << 30));
    }
    if (bytes >= 1L << 20) {
      return String.format(Locale.ROOT, "%.1f MiB", bytes / (double) (1L << 20));
    }
    return (bytes >> 10) + " KiB";
  }

  static String signed(final long bytes) {
    return bytes >= 0 ? "+" + bytes(bytes) : bytes(bytes);
  }

  private JfrReport() {
  }
}
