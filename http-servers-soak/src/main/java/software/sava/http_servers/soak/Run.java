package software.sava.http_servers.soak;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/// The load generator's deadline, shared by every worker: `running()` is the loop condition,
/// `remainingMillis()` lets a worker skip a case that could not finish in time, and `think`
/// is the randomised pause between operations, clipped to the time left so no worker sleeps
/// past the end.
record Run(long deadlineNanos) {

  static Run of(final int seconds) {
    return new Run(System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds));
  }

  boolean running() {
    return System.nanoTime() < deadlineNanos;
  }

  long remainingMillis() {
    return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
  }

  void think(final Random rnd, final int minMillis, final int spreadMillis) throws InterruptedException {
    final long pause = Math.min(minMillis + rnd.nextInt(spreadMillis + 1), remainingMillis());
    if (pause > 0) {
      Thread.sleep(pause);
    }
  }
}
