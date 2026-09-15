package software.sava.http_servers.jetty;

import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;
import software.sava.http_servers.core.response.HttpResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Jetty's `ThreadPoolBudget` refuses to start a connector ("Insufficient configured threads")
/// unless the pool's maximum exceeds the threads its components lease. A pool of one thread
/// per processor fell short on one to three processors: three leases without an executor, two
/// with a virtual-thread executor. A JVM cannot change its processor count after launch, so
/// the `smallHostTest<N>` tasks run this class under `-XX:ActiveProcessorCount=N`; the ordinary
/// `test` task pins the same contract on whatever host it runs on, and reaches the small-host
/// shapes through Jetty's processor-count override, the only route the mutation suite has.
final class JettyThreadBudgetTest {

  @Test
  void theJvmSeesTheProcessorCountItWasLaunchedWith() {
    // Without this a small-host task whose JVM flag went missing would pass on a large host.
    final var expected = System.getProperty("smallHost.availableProcessors");
    assumeTrue(expected != null, "not launched by a smallHostTest task");
    assertEquals(Integer.parseInt(expected), Runtime.getRuntime().availableProcessors());
    assertEquals(Integer.parseInt(expected), ProcessorUtils.availableProcessors(),
        "the task must pin Jetty's own count too, so an inherited JETTY_AVAILABLE_PROCESSORS cannot move it");
  }

  @Test
  void thePoolKeepsTheProcessorCountUnlessThatLeavesNoThreadBeyondTheLeases() {
    // One to three processors against Jetty 12.1's leases: 3 without an executor, 2 with one.
    assertEquals(4, JettyServerBuilder.maxThreads(1, 3));
    assertEquals(3, JettyServerBuilder.maxThreads(1, 2));
    assertEquals(3, JettyServerBuilder.maxThreads(2, 2));
    assertEquals(4, JettyServerBuilder.maxThreads(3, 3));
    // A larger host keeps one platform thread per processor, never Jetty's 200-thread default.
    assertEquals(4, JettyServerBuilder.maxThreads(4, 3));
    assertEquals(64, JettyServerBuilder.maxThreads(64, 13));
  }

  @Test
  void theLeasePredictionMatchesJettysBudgetWithoutAnExecutor() throws Exception {
    assertLeasesFit(null);
  }

  @Test
  void theLeasePredictionMatchesJettysBudgetWithAVirtualThreadExecutor() throws Exception {
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      assertLeasesFit(executor);
    }
  }

  @Test
  void aServerStartsAndAnswersWithoutAnExecutor() throws Exception {
    assertStartsAndAnswers(null);
  }

  @Test
  void aServerStartsAndAnswersWithAVirtualThreadExecutor() throws Exception {
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      assertStartsAndAnswers(executor);
    }
  }

  @Test
  void aServerStartsAndAnswersWhenJettyCountsOneToThreeProcessors() throws Exception {
    final int processors = ProcessorUtils.availableProcessors();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int count = 1; count <= 3; ++count) {
        ProcessorUtils.setAvailableProcessors(count);
        assertStartsAndAnswers(null);
        assertStartsAndAnswers(executor);
      }
    } finally {
      ProcessorUtils.setAvailableProcessors(processors);
    }
  }

  /// The oracle is Jetty's own accounting once started, not a copy of its heuristics.
  private static void assertLeasesFit(final Executor executor) throws Exception {
    final var server = new JettyServerBuilder().initRestServer(executor, "localhost", 0);
    final var threadPool = (QueuedThreadPool) server.getThreadPool();
    final int predicted = JettyServerBuilder.leasedThreads(threadPool, (ServerConnector) server.getConnectors()[0]);
    try {
      server.start();
      assertEquals(threadPool.getThreadPoolBudget().getLeasedThreads(), predicted,
          "the predicted leases must be the ones Jetty takes on start");
      final int processors = ProcessorUtils.availableProcessors();
      assertEquals(processors > predicted ? processors : predicted + 1, threadPool.getMaxThreads(),
          "the pool must hold one thread per processor, or one beyond the leases when that is more");
    } finally {
      server.stop();
    }
  }

  /// Both handlers are blocking, so without an executor they run on the pool's own threads: a
  /// single spare thread when the pool was raised on one to three processors, several otherwise.
  private static void assertStartsAndAnswers(final Executor executor) throws Exception {
    final var builder = new JettyServerBuilderFactory().createBuilder();
    builder.blockingQueryHandler("/ping", request -> HttpResponse.response("text/plain", "pong"));
    builder.blockingQueryPost("/echo", request -> HttpResponse.response("text/plain", request.body()));
    final var owned = JettyConformanceTest.startOwned(builder, executor);
    try (final var server = owned.server();
         final var client = HttpClient.newHttpClient()) {
      final var ping = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + owned.port() + "/ping"))
              .timeout(Duration.ofSeconds(10))
              .GET()
              .build(),
          BodyHandlers.ofString()
      );
      assertEquals(200, ping.statusCode());
      assertEquals("pong", ping.body());

      final var echo = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + owned.port() + "/echo"))
              .timeout(Duration.ofSeconds(10))
              .POST(HttpRequest.BodyPublishers.ofString("small host"))
              .build(),
          BodyHandlers.ofString()
      );
      assertEquals(200, echo.statusCode());
      assertEquals("small host", echo.body());
    }
  }
}
