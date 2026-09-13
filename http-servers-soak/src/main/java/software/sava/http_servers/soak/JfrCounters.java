package software.sava.http_servers.soak;

import jdk.jfr.consumer.RecordingStream;

import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;

/// The virtual-thread events counted over the whole run, independently of the disk ring the
/// `soak` recording is kept to: `jdk.VirtualThreadSubmitFailed` — the scheduler refused a
/// continuation, which fails the run — and `jdk.VirtualThreadPinned` at the ring's own 20 ms
/// threshold (report only). The ring holds its last hours, so on a long run the recording
/// `soak.sh` post-processes cannot see an event from earlier in the day; these counters can,
/// and the sampler prints them in every `server.csv` row.
///
/// A [RecordingStream] is a second recording in the same JVM. Its chunks are released as soon
/// as the stream has consumed them (`RecordingStream.ChunkConsumer` calls
/// `PlatformRecording.removeBefore`), so it neither pins the ring's chunks on disk nor keeps
/// the events anywhere but here; and event settings are the union over every running
/// recording, so enabling the two events at the ring's own settings changes nothing about
/// what the ring records. Its consumer thread is not a daemon (`AbstractEventStream` starts
/// it with `daemon = false`, and [RecordingStream] offers no switch), so [#start] is called
/// only once the server is listening: a JVM whose start-up failed must still exit on its own.
final class JfrCounters {

  static final Duration PINNED_THRESHOLD = Duration.ofMillis(20);

  private final LongAdder submitFailed;
  private final LongAdder pinned;

  private JfrCounters() {
    this.submitFailed = new LongAdder();
    this.pinned = new LongAdder();
  }

  static JfrCounters start() {
    final var counters = new JfrCounters();
    final var stream = new RecordingStream();
    stream.enable("jdk.VirtualThreadSubmitFailed").withStackTrace();
    stream.enable("jdk.VirtualThreadPinned").withThreshold(PINNED_THRESHOLD).withStackTrace();
    stream.onEvent("jdk.VirtualThreadSubmitFailed", event -> counters.submitFailed.increment());
    stream.onEvent("jdk.VirtualThreadPinned", event -> counters.pinned.increment());
    stream.onError(t -> System.err.println("JfrCounters: the event stream failed: " + t));
    stream.startAsync();
    return counters;
  }

  long submitFailed() {
    return submitFailed.sum();
  }

  long pinned() {
    return pinned.sum();
  }
}
