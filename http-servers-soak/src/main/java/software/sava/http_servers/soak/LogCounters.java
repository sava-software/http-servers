package software.sava.http_servers.soak;

import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/// The one thing the soak server installs on JUL: a root-logger handler that counts what the
/// sampler reports. Netty's `ResourceLeakDetector` logs through its `InternalLogger`, which
/// selects JUL when no SLF4J or Log4j is present, and every leak report's message starts
/// with `LEAK:`; the backends' own failures reach JUL through `System.Logger` at `ERROR`,
/// which is JUL `SEVERE`. Nothing else is touched — the default `ConsoleHandler` keeps
/// writing to stderr, which `soak.sh` captures as `server.log` and greps independently.
final class LogCounters extends Handler {

  private final LongAdder leaks;
  private final LongAdder severe;

  private LogCounters() {
    this.leaks = new LongAdder();
    this.severe = new LongAdder();
  }

  static LogCounters install() {
    final var counters = new LogCounters();
    counters.setLevel(Level.ALL);
    Logger.getLogger("").addHandler(counters);
    return counters;
  }

  @Override
  public void publish(final LogRecord record) {
    if (record == null) {
      return;
    }
    final var level = record.getLevel();
    if (level != null && level.intValue() >= Level.SEVERE.intValue()) {
      severe.increment();
    }
    final var message = record.getMessage();
    if (message != null && message.contains("LEAK:")) {
      leaks.increment();
    }
  }

  @Override
  public void flush() {
  }

  @Override
  public void close() {
  }

  long leaks() {
    return leaks.sum();
  }

  long severe() {
    return severe.sum();
  }
}
