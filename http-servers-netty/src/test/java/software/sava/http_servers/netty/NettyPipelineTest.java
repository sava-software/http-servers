package software.sava.http_servers.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Ticker;
import org.junit.jupiter.api.Test;
import software.sava.http_servers.core.handlers.HandlerMap;
import software.sava.http_servers.core.response.HttpResponse;
import software.sava.http_servers.core.response.QueryHandler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The production pipeline — `NettyChannelInitializer`'s codec, gate, aggregator and
/// controller, with real routes — driven in process on an `EmbeddedChannel`, where every
/// write completes synchronously, a blocking route completes exactly when the test runs it,
/// the channel's read state is readable, a queued body's buffer can be watched for release
/// and time is a clock the test advances. These are the deterministic seams a socket cannot
/// offer for flow control, ownership, write failure and the idle timeout;
/// `NettyConformanceTest` pins the same ordering and persistence rules end to end over real
/// sockets.
final class NettyPipelineTest {

  /// The idle timeout every pipeline here is built with: the shipped default, so every idle
  /// property below is a property of the value consumers get.
  private static final long IDLE_TIMEOUT_NANOS = NettyServerBuilder.DEFAULT_IDLE_TIMEOUT.toNanos();

  /// The clock the idle timeout is measured on. It is the embedded loop's ticker, which runs
  /// the gate's scheduled check, and — read through `gateNanoTime()` — the gate's own clock,
  /// so the check and the gate's arithmetic agree to the nanosecond. It moves only when a
  /// test advances it. Neither origin is zero, and they differ on purpose: the ticker's is
  /// 10^12 ns (Netty's scheduler reads a negative deadline as overflow, so its ticker cannot
  /// start below zero), while the gate reads the same instants offset to a *negative* origin
  /// — what `System.nanoTime` may deliver in production, where it is not the normalised
  /// ticker either. A deadline computed from an absolute reading rather than a difference,
  /// or a start time mutated to zero, is therefore visible here rather than equivalent by
  /// accident of the origin.
  private static final class FakeClock implements Ticker {
    private static final long ORIGIN = 1_000_000_000_000L;
    private static final long GATE_ORIGIN = -2_000_000_000_000L;
    private long now = ORIGIN;

    @Override
    public long initialNanoTime() {
      return ORIGIN;
    }

    @Override
    public long nanoTime() {
      return now;
    }

    /// The gate's reading of the same instant.
    long gateNanoTime() {
      return now - ORIGIN + GATE_ORIGIN;
    }

    @Override
    public void sleep(final long delay, final TimeUnit unit) {
      throw new UnsupportedOperationException("tests advance the clock; nothing sleeps");
    }
  }

  /// Moves the clock on and runs whatever the loop had scheduled up to the new instant — the
  /// idle check, when its deadline has come — surfacing anything it threw.
  private static void advance(final EmbeddedChannel channel, final FakeClock clock, final long nanos) {
    clock.now += nanos;
    channel.runScheduledPendingTasks();
    channel.checkException();
  }

  /// Runs a blocking route only when the test says so, which makes "the response to the
  /// request in flight is written now" a synchronous step rather than a race.
  private static final class ManualExecutor implements Executor {
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();

    @Override
    public void execute(final Runnable command) {
      pending.add(command);
    }

    void runNext() {
      final var next = pending.poll();
      assertNotNull(next, "no blocking route has been dispatched");
      next.run();
    }
  }

  /// Registers routes the way the builder does and records the order handlers actually run.
  private static final class Routes {
    private final Map<String, Map<String, NettyHandler>> byPath = new HashMap<>();
    private final List<String> ran = new ArrayList<>();

    private QueryHandler recording(final String path, final QueryHandler handler) {
      return request -> {
        ran.add(path);
        return handler.httpResponse(request);
      };
    }

    Routes blockingGet(final String path, final QueryHandler handler) {
      byPath.computeIfAbsent(path, p -> new HashMap<>()).put("GET", NettyQueryHandler.createBlockingGetHandler(recording(path, handler)));
      return this;
    }

    Routes nonBlockingGet(final String path, final QueryHandler handler) {
      byPath.computeIfAbsent(path, p -> new HashMap<>()).put("GET", NettyQueryHandler.createNonBlockingGetHandler(recording(path, handler)));
      return this;
    }

    Routes blockingPost(final String path, final QueryHandler handler) {
      byPath.computeIfAbsent(path, p -> new HashMap<>()).put("POST", NettyQueryHandler.createBlockingPostHandler(recording(path, handler)));
      return this;
    }

    Routes nonBlockingPost(final String path, final QueryHandler handler) {
      byPath.computeIfAbsent(path, p -> new HashMap<>()).put("POST", NettyQueryHandler.createNonBlockingPostHandler(recording(path, handler)));
      return this;
    }

    HandlerMap<NettyHandler> handlerMap() {
      return HandlerMap.createController(byPath, List.of());
    }
  }

  private static final QueryHandler OK = request -> HttpResponse.response("text/plain", "ok");
  private static final QueryHandler ECHO = request -> HttpResponse.response("text/plain", new String(request.body(), StandardCharsets.UTF_8));
  private static final QueryHandler CLOSING = request -> HttpResponse.response("text/plain", "bye").withHeader("Connection", "Close");

  private static EmbeddedChannel pipeline(final Routes routes,
                                          final Executor executor,
                                          final int maxContentLength,
                                          final FakeClock clock,
                                          final ChannelHandler... before) {
    final var handlers = new ChannelHandler[before.length + 1];
    System.arraycopy(before, 0, handlers, 0, before.length);
    handlers[before.length] = new NettyChannelInitializer(routes.handlerMap(), executor, maxContentLength, IDLE_TIMEOUT_NANOS, clock::gateNanoTime);
    return EmbeddedChannel.builder().ticker(clock).handlers(handlers).build();
  }

  private static EmbeddedChannel pipeline(final Routes routes, final Executor executor, final int maxContentLength, final ChannelHandler... before) {
    return pipeline(routes, executor, maxContentLength, new FakeClock(), before);
  }

  private static EmbeddedChannel pipeline(final Routes routes, final Executor executor, final FakeClock clock) {
    return pipeline(routes, executor, NettyServerBuilder.DEFAULT_MAX_CONTENT_LENGTH, clock);
  }

  private static EmbeddedChannel pipeline(final Routes routes, final Executor executor) {
    return pipeline(routes, executor, new FakeClock());
  }

  private static ByteBuf ascii(final String text) {
    return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
  }

  private static String get(final String target) {
    return "GET " + target + " HTTP/1.1\r\nHost: h\r\n\r\n";
  }

  /// Everything the encoder has put on the wire so far, lower-cased for header comparisons.
  private static String wire(final EmbeddedChannel channel) {
    final var text = new StringBuilder();
    ByteBuf buf;
    while ((buf = channel.readOutbound()) != null) {
      text.append(buf.toString(StandardCharsets.US_ASCII));
      buf.release();
    }
    return text.toString().toLowerCase();
  }

  private static int count(final String text, final String needle) {
    int n = 0;
    for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
      ++n;
    }
    return n;
  }

  /// Captures every JUL record the controller publishes at any level while {@code body} runs.
  private static List<LogRecord> controllerLogs(final Runnable body) {
    final var records = new ArrayList<LogRecord>();
    final var jul = java.util.logging.Logger.getLogger(NettyController.class.getName());
    final var handler = new java.util.logging.Handler() {
      @Override
      public void publish(final LogRecord record) {
        records.add(record);
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    final var level = jul.getLevel();
    jul.setLevel(Level.ALL);
    jul.addHandler(handler);
    try {
      body.run();
    } finally {
      jul.removeHandler(handler);
      jul.setLevel(level);
    }
    return records;
  }

  /// RFC 9112 §9.3.2: one request reaches a handler at a time, the next only once the
  /// previous final response has been written; reads pause while a received request awaits
  /// its answer and resume when nothing is in flight.
  @Test
  void theNextRequestWaitsForTheFinalResponse() {
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingGet("/a", OK).blockingGet("/b", OK).blockingGet("/c", OK);
    final var channel = pipeline(routes, executor);
    channel.writeInbound(ascii(get("/a") + get("/b") + get("/c")));
    assertEquals(1, executor.pending.size(), "only the first request is dispatched");
    assertFalse(channel.config().isAutoRead(), "a received request awaiting its response pauses reads");

    executor.runNext();
    assertEquals(List.of("/a"), routes.ran);
    assertEquals(1, executor.pending.size(), "one completed response releases exactly one request");
    assertFalse(channel.config().isAutoRead(), "the released request now awaits its own response");

    executor.runNext();
    executor.runNext();
    assertEquals(List.of("/a", "/b", "/c"), routes.ran);
    assertEquals(0, executor.pending.size());
    assertTrue(channel.config().isAutoRead(), "reads resume once nothing is in flight");
    assertTrue(channel.isActive(), "keep-alive requests leave the connection open");
    final var wire = wire(channel);
    assertEquals(3, count(wire, "http/1.1 200 ok"), wire);
    assertEquals(3, count(wire, "content-length: 2"), "on a persistent connection every response is Content-Length delimited (RFC 9112 §6.3): " + wire);
    assertFalse(wire.contains("connection:"), "a persistent HTTP/1.1 exchange needs no Connection header: " + wire);
    channel.finishAndReleaseAll();
  }

  /// The pre-flight answer has no body, and on a persistent connection a response with
  /// neither Content-Length nor Transfer-Encoding is delimited only by a close that never
  /// comes (RFC 9112 §6.3), so it must announce `Content-Length: 0`. Asserted on the wire in
  /// process because the JDK client's request timeout covers the response head only: a
  /// head-only answer leaves `HttpClient` waiting for a body without bound, which no socket
  /// bound converts into an assertion.
  @Test
  void corsPreflightIsContentLengthDelimited() {
    final var routes = new Routes().nonBlockingPost("/pay", OK);
    final var channel = pipeline(routes, Runnable::run);
    channel.writeInbound(ascii("OPTIONS /pay HTTP/1.1\r\nHost: h\r\nOrigin: https://app.example\r\nAccess-Control-Request-Method: POST\r\n\r\n"));
    final var wire = wire(channel);
    assertTrue(wire.startsWith("http/1.1 200 ok"), wire);
    assertTrue(wire.contains("content-length: 0"), "the bodyless pre-flight must be framed: " + wire);
    assertTrue(wire.contains("access-control-allow-origin: https://app.example"), wire);
    assertTrue(wire.contains("access-control-allow-methods: post"), wire);
    assertEquals(List.of(), routes.ran, "a pre-flight is answered by the controller, not the route");
    assertTrue(channel.isActive());
    channel.finishAndReleaseAll();
  }

  /// `Expect: 100-continue`: the aggregator answers `100` on the request head, before any
  /// body exists to read; being interim (RFC 9110 §15.2) it neither completes the request
  /// nor carries the request's close, which applies after the final response only.
  @Test
  void anInterimResponseCompletesNothing() {
    final var routes = new Routes().nonBlockingPost("/p", ECHO);
    final var channel = pipeline(routes, Runnable::run);
    channel.writeInbound(ascii("POST /p HTTP/1.1\r\nHost: h\r\nExpect: 100-continue\r\nContent-Length: 2\r\nConnection: close\r\n\r\n"));
    final var interim = wire(channel);
    assertTrue(interim.startsWith("http/1.1 100 continue"), "the 100 precedes the body: " + interim);
    assertFalse(interim.contains("connection:"), "an interim response is not framed for persistence: " + interim);
    assertTrue(channel.isActive(), "a 100 never ends the connection");
    assertEquals(List.of(), routes.ran);
    assertTrue(channel.config().isAutoRead(), "the body the 100 invited must be read");

    channel.writeInbound(ascii("hi"));
    assertEquals(List.of("/p"), routes.ran);
    final var response = wire(channel);
    assertTrue(response.startsWith("http/1.1 200 ok"), response);
    assertTrue(response.endsWith("hi"), response);
    assertTrue(response.contains("connection: close"), "the request's close applies to its final response: " + response);
    assertFalse(channel.isActive());
  }

  /// A request whose body is still arriving is read on, not paused: the pause is for a
  /// received request awaiting its response, never for the bytes of the one being received.
  @Test
  void aBodyStillArrivingIsReadOn() {
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingPost("/p", ECHO);
    final var channel = pipeline(routes, executor);
    channel.writeInbound(ascii("POST /p HTTP/1.1\r\nHost: h\r\nContent-Length: 4\r\n\r\nab"));
    assertEquals(0, executor.pending.size(), "the request is not complete yet");
    assertTrue(channel.config().isAutoRead(), "the rest of the body must be read");

    channel.writeInbound(ascii("cd"));
    assertEquals(1, executor.pending.size());
    assertFalse(channel.config().isAutoRead(), "received in full, the request now awaits its response");
    executor.runNext();
    assertTrue(channel.config().isAutoRead());
    assertTrue(wire(channel).endsWith("abcd"));
    channel.finishAndReleaseAll();
  }

  /// RFC 9112 §9.6: a handler's `Connection: close` — in any case — ends the connection once
  /// its response is out, nothing behind it is processed, and what was queued is released
  /// when the pipeline goes: the queued body's buffer must drop to zero references.
  @Test
  void aClosingResponseEndsTheConnectionAndReleasesTheQueue() {
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingGet("/a", CLOSING).blockingPost("/b", ECHO).blockingGet("/c", OK);
    final var channel = pipeline(routes, executor);
    final var inbound = ascii(get("/a") + "POST /b HTTP/1.1\r\nHost: h\r\nContent-Length: 3\r\n\r\nxyz" + get("/c"));
    channel.writeInbound(inbound);
    assertEquals(1, inbound.refCnt(), "the queued body chunk holds the inbound buffer");

    executor.runNext();
    final var wire = wire(channel);
    assertTrue(wire.contains("connection: close"), "the close directive reaches the wire: " + wire);
    assertFalse(channel.isActive(), "the connection closes once the closing response is out");
    assertEquals(List.of("/a"), routes.ran, "nothing behind a closing response runs");
    assertEquals(0, executor.pending.size());
    assertEquals(0, inbound.refCnt(), "the queued body must be released with the pipeline");
  }

  /// The request's own `Connection: close` ends the connection after its response, whether
  /// a handler answered it or the gate refused it: here the second request carries an
  /// unsupported `Expect` and asks to close, so the 417 is the final response — written after
  /// the first request's, framed with the close, then the close. Nothing behind it runs (the
  /// body it sent regardless, read as a request line, included) and nothing is logged: no
  /// party was left mid-request by that close.
  @Test
  void theRequestsCloseIsHonouredOnARefusal() {
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingGet("/a", OK).nonBlockingPost("/b", ECHO).nonBlockingGet("/c", OK);
    final var channel = pipeline(routes, executor);
    channel.writeInbound(ascii(get("/a")
        + "POST /b HTTP/1.1\r\nHost: h\r\nExpect: foo\r\nContent-Length: 2\r\nConnection: close\r\n\r\nhi"
        + get("/c")));
    assertTrue(wire(channel).isEmpty(), "the 417 must wait behind the request in flight");

    final var logs = controllerLogs(executor::runNext);
    final var wire = wire(channel);
    assertTrue(wire.startsWith("http/1.1 200 ok"), wire);
    final int expectationFailed = wire.indexOf("http/1.1 417 expectation failed");
    assertTrue(expectationFailed > 0, "the 417 follows the first response: " + wire);
    assertTrue(wire.indexOf("connection: close", expectationFailed) > 0, "the 417 is framed with the requested close: " + wire);
    assertFalse(channel.isActive(), "the request asked to close");
    assertEquals(List.of("/a"), routes.ran, "the refused request never reaches a handler, and nothing follows a close");
    assertTrue(logs.isEmpty(), "closing after a refusal leaves no one mid-request: " + logs.stream().map(LogRecord::getMessage).toList());
  }

  /// The handler-answered sibling: `Connection: close` on the request, a plain response.
  @Test
  void theRequestsCloseIsHonouredOnAHandlerAnswer() {
    final var routes = new Routes().nonBlockingGet("/a", OK).nonBlockingGet("/b", OK);
    final var channel = pipeline(routes, Runnable::run);
    channel.writeInbound(ascii("GET /a HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n" + get("/b")));
    final var wire = wire(channel);
    assertTrue(wire.contains("connection: close"), "an HTTP/1.1 connection about to close says so: " + wire);
    assertFalse(channel.isActive());
    assertEquals(List.of("/a"), routes.ran);
  }

  /// A body past the limit is answered 413 with `Connection: close` and `Content-Length: 0`
  /// (framed, so a client can delimit it before the close), in order, and the connection is
  /// then closed without the body ever reaching a handler — and without an error logged.
  @Test
  void anOversizedRequestIsRefusedInOrderThenClosed() {
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingGet("/a", OK).nonBlockingPost("/big", ECHO).nonBlockingGet("/c", OK);
    final var channel = pipeline(routes, executor, 8);
    channel.writeInbound(ascii(get("/a") + "POST /big HTTP/1.1\r\nHost: h\r\nContent-Length: 16\r\n\r\n0123456789abcdef" + get("/c")));
    assertTrue(wire(channel).isEmpty(), "the 413 must wait behind the request in flight");

    final var logs = controllerLogs(executor::runNext);
    final var wire = wire(channel);
    assertTrue(wire.startsWith("http/1.1 200 ok"), wire);
    final int tooLarge = wire.indexOf("http/1.1 413 request entity too large");
    assertTrue(tooLarge > 0, "the 413 follows the first response: " + wire);
    assertTrue(wire.indexOf("connection: close", tooLarge) > 0, wire);
    assertTrue(wire.indexOf("content-length: 0", tooLarge) > 0, wire);
    assertFalse(channel.isActive(), "the connection is closed after a 413");
    assertEquals(List.of("/a"), routes.ran, "neither the oversized request nor anything behind it runs");
    assertTrue(logs.isEmpty(), "a refused body is not a server failure: " + logs.stream().map(LogRecord::getMessage).toList());
  }

  /// A closing response written from inside the drain — the released request answered inline
  /// with `Connection: close` — closes the connection there and then, and the request behind
  /// it stays unprocessed.
  @Test
  void aCloseFromInsideTheDrainStopsTheDrain() {
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingGet("/a", OK).nonBlockingGet("/b", CLOSING).nonBlockingGet("/c", OK);
    final var channel = pipeline(routes, executor);
    channel.writeInbound(ascii(get("/a") + get("/b") + get("/c")));
    executor.runNext();
    assertEquals(List.of("/a", "/b"), routes.ran, "the request behind a closing response never runs");
    assertFalse(channel.isActive());
  }

  /// An HTTP/1.0 client is answered and closed unless it asked for keep-alive, in which case
  /// the persistence is acknowledged explicitly (RFC 9112 §9.3.1) and the connection serves
  /// the next request.
  @Test
  void http10PersistsOnlyOnRequest() {
    final var closing = pipeline(new Routes().nonBlockingGet("/a", OK), Runnable::run);
    closing.writeInbound(ascii("GET /a HTTP/1.0\r\n\r\n"));
    final var closedWire = wire(closing);
    assertTrue(closedWire.startsWith("http/1.1 200 ok"), closedWire);
    assertFalse(closedWire.contains("connection:"), "an HTTP/1.0 close is implicit: " + closedWire);
    assertFalse(closing.isActive(), "HTTP/1.0 without keep-alive closes after the response");

    final var routes = new Routes().nonBlockingGet("/a", OK).nonBlockingGet("/b", OK);
    final var keeping = pipeline(routes, Runnable::run);
    keeping.writeInbound(ascii("GET /a HTTP/1.0\r\nConnection: keep-alive\r\n\r\nGET /b HTTP/1.0\r\n\r\n"));
    final var keptWire = wire(keeping);
    assertTrue(keptWire.contains("connection: keep-alive"), "an HTTP/1.0 keep-alive must be acknowledged: " + keptWire);
    assertEquals(2, count(keptWire, "http/1.1 200 ok"), "the kept connection serves the next request: " + keptWire);
    assertEquals(List.of("/a", "/b"), routes.ran);
    assertFalse(keeping.isActive(), "the second request did not ask to keep the connection");
  }

  /// Fails every write it is handed, standing in for a transport that lost the client.
  private static final class FailingTransport extends ChannelOutboundHandlerAdapter {
    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
      ReferenceCountUtil.release(msg);
      promise.setFailure(new java.io.IOException("client gone"));
    }
  }

  /// A response that never reached the client releases nothing: the request behind it is not
  /// dispatched on the strength of a failed write.
  @Test
  void aFailedResponseWriteReleasesNothing() {
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingGet("/a", OK).blockingGet("/b", OK);
    final var channel = pipeline(routes, executor, NettyServerBuilder.DEFAULT_MAX_CONTENT_LENGTH, new FailingTransport());
    channel.writeInbound(ascii(get("/a") + get("/b")));
    executor.runNext();
    assertEquals(List.of("/a"), routes.ran);
    assertEquals(0, executor.pending.size(), "a failed write must not release the next request");
    channel.finishAndReleaseAll();
  }

  /// A client that goes away with a request body half sent is a peer that left, not a
  /// failure of this server: the aggregator's `PrematureChannelClosureException` is logged
  /// at `DEBUG`, nothing is answered and nothing is logged as an error.
  @Test
  void aClientLeavingMidRequestIsNotAServerFailure() {
    final var routes = new Routes().nonBlockingPost("/p", ECHO);
    final var channel = pipeline(routes, Runnable::run);
    channel.writeInbound(ascii("POST /p HTTP/1.1\r\nHost: h\r\nContent-Length: 4\r\n\r\nab"));
    final var logs = controllerLogs(channel::close);
    assertEquals(List.of(), routes.ran);
    assertTrue(wire(channel).isEmpty(), "there is no one to answer");
    assertTrue(logs.stream().noneMatch(r -> r.getLevel().intValue() >= Level.SEVERE.intValue()),
        "a client abort is not an error: " + logs.stream().map(LogRecord::getMessage).toList());
    assertTrue(logs.stream().anyMatch(r -> r.getLevel().equals(Level.FINE)
            && r.getThrown() instanceof io.netty.handler.codec.PrematureChannelClosureException),
        "the abort is still traceable at DEBUG: " + logs.stream().map(r -> r.getLevel() + " " + r.getMessage()).toList());
  }

  // ---- expectation refusals: decided where the codec stands, answered in turn ----

  /// Control for the cases below: three pipelined requests, the third's body split across two
  /// reads with the first request held by its blocking route in between — every response in
  /// order, the third handler given the whole of its body.
  @Test
  void pipelinedBodiesSplitAcrossReadsAreAnsweredInOrder() {
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingGet("/a", OK).nonBlockingPost("/b", ECHO).nonBlockingPost("/c", ECHO);
    final var channel = pipeline(routes, executor, 8);
    channel.writeInbound(ascii(get("/a")
        + "POST /b HTTP/1.1\r\nHost: h\r\nContent-Length: 2\r\n\r\nhi"
        + "POST /c HTTP/1.1\r\nHost: h\r\nContent-Length: 4\r\n\r\nab"));
    executor.runNext();
    channel.writeInbound(ascii("cd"));
    final var wire = wire(channel);
    assertEquals(3, count(wire, "http/1.1 200 ok"), wire);
    assertTrue(wire.endsWith("abcd"), "the third handler saw its whole body: " + wire);
    assertEquals(List.of("/a", "/b", "/c"), routes.ran);
    assertTrue(channel.isActive());
    channel.finishAndReleaseAll();
  }

  /// Refusing an expectation resets the codec (Netty's `HttpExpectationFailedEvent`: the
  /// refused request's body is not expected, so what follows its head is read as the next
  /// request line). That reset is right only while the codec still stands on the refused head;
  /// delayed until the refusal's turn, it lands on whatever the codec has decoded since — here
  /// the third request's body, whose tail then parses as a request line and is never answered.
  /// So the refusal is decided the moment the head is decoded, and only its answer waits its
  /// turn. The refused request here declares a body it never sends, which is what a client that
  /// honours the refusal does.
  @Test
  void aRefusedExpectationLeavesTheNextRequestDecodable() {
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingGet("/a", OK).nonBlockingPost("/b", ECHO).nonBlockingPost("/c", ECHO);
    final var channel = pipeline(routes, executor, 8);
    channel.writeInbound(ascii(get("/a")
        + "POST /b HTTP/1.1\r\nHost: h\r\nExpect: foo\r\nContent-Length: 2\r\n\r\n"
        + "POST /c HTTP/1.1\r\nHost: h\r\nContent-Length: 4\r\n\r\nab"));
    assertTrue(wire(channel).isEmpty(), "the 417 waits behind the request in flight");
    executor.runNext();
    final var before = wire(channel);
    assertTrue(before.startsWith("http/1.1 200 ok"), before);
    final int refused = before.indexOf("http/1.1 417 expectation failed");
    assertTrue(refused > 0, "the 417 follows the first response: " + before);
    assertTrue(before.indexOf("content-length: 0", refused) > 0, "the 417 is framed: " + before);
    assertFalse(before.contains("connection: close"), "a refused expectation does not end the connection: " + before);
    assertTrue(channel.isActive());
    assertTrue(channel.config().isAutoRead(), "the third request's body is still owed");

    channel.writeInbound(ascii("cd"));
    final var after = wire(channel);
    assertTrue(after.startsWith("http/1.1 200 ok"), "the third request is answered once its body is in: " + after);
    assertTrue(after.endsWith("abcd"), "with the whole of its body: " + after);
    assertEquals(List.of("/a", "/c"), routes.ran, "the refused request never reaches a handler");
    assertTrue(channel.isActive());
    channel.finishAndReleaseAll();
  }

  /// A client that sends the body of a refused request anyway — the shape the defect was
  /// found with — gets Netty's own outcome, bounded and in step: the refusal tells the codec
  /// the body is not coming, so those bytes are read as the next request line (`hiPOST /c`,
  /// a method nothing routes, answered 405 like any other) and no handler ever sees them; the
  /// refusal is paired with its own request and nothing else, nothing hangs, and the
  /// connection goes on serving requests after it.
  @Test
  void aBodySentAfterAnUnsupportedExpectationIsReadAsTheNextRequest() {
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingGet("/a", OK).nonBlockingPost("/b", ECHO).nonBlockingPost("/c", ECHO);
    final var channel = pipeline(routes, executor, 8);
    channel.writeInbound(ascii(get("/a")
        + "POST /b HTTP/1.1\r\nHost: h\r\nExpect: foo\r\nContent-Length: 2\r\n\r\nhi"
        + "POST /c HTTP/1.1\r\nHost: h\r\nContent-Length: 4\r\n\r\nab"));
    assertTrue(wire(channel).isEmpty(), "the 417 waits behind the request in flight");
    executor.runNext();
    final var before = wire(channel);
    assertTrue(before.startsWith("http/1.1 200 ok"), before);
    final int refused = before.indexOf("http/1.1 417 expectation failed");
    assertTrue(refused > 0, "the 417 follows the first response: " + before);
    assertEquals(2, count(before, "http/1.1 "), "the two answered requests, and nothing else: " + before);
    assertTrue(channel.isActive());

    channel.writeInbound(ascii("cd"));
    final var after = wire(channel);
    assertTrue(after.startsWith("http/1.1 405 method not allowed"),
        "the refused body is read as the next request line and answered as what it is: " + after);
    assertEquals(List.of("/a"), routes.ran, "neither the refused request nor the line made of its body reaches a handler");
    assertTrue(channel.isActive(), "the connection is in step and stays open");

    channel.writeInbound(ascii(get("/a")));
    executor.runNext();
    assertTrue(wire(channel).startsWith("http/1.1 200 ok"), "the next request is served");
    assertEquals(List.of("/a", "/a"), routes.ran);
    channel.finishAndReleaseAll();
  }

  /// The oversized sibling: `Expect: 100-continue` announcing a body past the limit is refused
  /// with 413 in its turn, framed with `Connection: close` and `Content-Length: 0` like the
  /// aggregator's own oversized answer, and the connection is then closed without that body
  /// ever being read — it was announced over the limit, and a 100-continue client may send it
  /// without waiting, as this one did. Nothing behind the refusal runs, what was queued is
  /// released with the pipeline, and nothing is logged.
  @Test
  void anOversizedContinueExpectationIsRefusedInOrderThenClosed() {
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingGet("/a", OK).nonBlockingPost("/b", ECHO).nonBlockingPost("/c", ECHO);
    final var channel = pipeline(routes, executor, 8);
    final var inbound = ascii(get("/a")
        + "POST /b HTTP/1.1\r\nHost: h\r\nExpect: 100-continue\r\nContent-Length: 9\r\n\r\n123456789"
        + "POST /c HTTP/1.1\r\nHost: h\r\nContent-Length: 4\r\n\r\nab");
    channel.writeInbound(inbound);
    assertTrue(wire(channel).isEmpty(), "the 413 waits behind the request in flight");
    assertEquals(1, inbound.refCnt(), "the queued body chunk holds the inbound buffer");

    final var logs = controllerLogs(executor::runNext);
    final var wire = wire(channel);
    assertTrue(wire.startsWith("http/1.1 200 ok"), wire);
    final int refused = wire.indexOf("http/1.1 413 request entity too large");
    assertTrue(refused > 0, "the 413 follows the first response: " + wire);
    assertTrue(wire.indexOf("connection: close", refused) > 0, "the 413 ends the connection: " + wire);
    assertTrue(wire.indexOf("content-length: 0", refused) > 0, "the 413 is framed: " + wire);
    assertFalse(wire.contains("100 continue"), "an oversized body is never invited: " + wire);
    assertFalse(channel.isActive(), "the connection is closed after the 413");
    assertEquals(List.of("/a"), routes.ran, "neither the refused request nor anything behind it runs");
    assertEquals(0, inbound.refCnt(), "the queued body is released with the pipeline");
    assertTrue(logs.isEmpty(), "a refused expectation is not a server failure: " + logs.stream().map(LogRecord::getMessage).toList());
  }

  /// A refused request that is first on its connection is answered at once — nothing is in
  /// flight to wait for — and the connection goes on: reads stay on, no handler runs, and the
  /// refusal is activity like any answer, so the idle timeout runs afresh from it.
  @Test
  void aRefusedRequestFirstOnAConnectionIsAnsweredAtOnce() {
    final var clock = new FakeClock();
    final var routes = new Routes().nonBlockingPost("/p", ECHO);
    final var channel = pipeline(routes, Runnable::run, clock);
    advance(channel, clock, IDLE_TIMEOUT_NANOS / 3);
    channel.writeInbound(ascii("POST /p HTTP/1.1\r\nHost: h\r\nExpect: foo\r\nContent-Length: 2\r\n\r\n"));
    final var wire = wire(channel);
    assertTrue(wire.startsWith("http/1.1 417 expectation failed"), wire);
    assertTrue(wire.contains("content-length: 0"), "the 417 is framed: " + wire);
    assertFalse(wire.contains("connection:"), "a persistent HTTP/1.1 refusal needs no Connection header: " + wire);
    assertTrue(channel.isActive(), "an unsupported expectation does not end the connection");
    assertTrue(channel.config().isAutoRead(), "nothing is in flight once the refusal is out");
    assertEquals(List.of(), routes.ran);
    advance(channel, clock, IDLE_TIMEOUT_NANOS - 1);
    assertTrue(channel.isActive(), "the refusal was activity: one tick short of a timeout after it the connection is open");
    advance(channel, clock, 1);
    assertFalse(channel.isActive(), "a full timeout after the refusal the connection is idle");
  }

  /// The oversized sibling, first on its connection: 413 with the close, at once.
  @Test
  void anOversizedContinueExpectationFirstOnAConnectionIsRefusedAndClosedAtOnce() {
    final var routes = new Routes().nonBlockingPost("/p", ECHO);
    final var channel = pipeline(routes, Runnable::run, 8);
    channel.writeInbound(ascii("POST /p HTTP/1.1\r\nHost: h\r\nExpect: 100-continue\r\nContent-Length: 9\r\n\r\n"));
    final var wire = wire(channel);
    assertTrue(wire.startsWith("http/1.1 413 request entity too large"), wire);
    assertTrue(wire.contains("connection: close"), wire);
    assertTrue(wire.contains("content-length: 0"), wire);
    assertFalse(channel.isActive(), "the connection is closed after the 413");
    assertEquals(List.of(), routes.ran);
  }

  /// A refused request between two routed ones, with no body to declare: the empty last
  /// content the codec emits with its head is the gate's to drop, the 417 takes its turn, and
  /// the request behind it is served on the same connection.
  @Test
  void aBodilessRefusedRequestIsAnsweredInOrderAndTheConnectionContinues() {
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingGet("/a", OK).nonBlockingGet("/b", OK).nonBlockingGet("/c", OK);
    final var channel = pipeline(routes, executor);
    channel.writeInbound(ascii(get("/a") + "GET /b HTTP/1.1\r\nHost: h\r\nExpect: foo\r\n\r\n" + get("/c")));
    assertTrue(wire(channel).isEmpty(), "the 417 waits behind the request in flight");
    executor.runNext();
    final var wire = wire(channel);
    final int refused = wire.indexOf("http/1.1 417 expectation failed");
    assertTrue(wire.startsWith("http/1.1 200 ok") && refused > 0 && wire.indexOf("http/1.1 200 ok", refused) > 0,
        "200, 417, 200 in request order: " + wire);
    assertEquals(3, count(wire, "http/1.1 "), wire);
    assertEquals(List.of("/a", "/c"), routes.ran, "the refused request never reaches a handler");
    assertTrue(channel.isActive());
    assertTrue(channel.config().isAutoRead());
    channel.finishAndReleaseAll();
  }

  /// Whatever the codec emits for a refused request after its head is the gate's to release,
  /// not to queue or forward: a body part handed over at the codec's seam while the refusal
  /// still waits its turn drops to zero references there and then. (The codec, told of the
  /// refusal, decodes nothing more of that request; the gate's ownership does not rest on
  /// that.) The refusal and the request behind it are then answered in order.
  @Test
  void theBodyPartsOfARefusedRequestAreReleased() {
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingGet("/a", OK).nonBlockingPost("/b", ECHO).nonBlockingGet("/c", OK);
    final var channel = pipeline(routes, executor);
    channel.writeInbound(ascii(get("/a") + "POST /b HTTP/1.1\r\nHost: h\r\nExpect: foo\r\nContent-Length: 2\r\n\r\n"));
    final var part = new DefaultLastHttpContent(ascii("hi"));
    channel.pipeline().context(HttpServerCodec.class).fireChannelRead(part);
    assertEquals(0, part.refCnt(), "a body part of a refused request is released at once, not queued");
    channel.writeInbound(ascii(get("/c")));
    assertTrue(wire(channel).isEmpty(), "everything waits behind the request in flight");

    executor.runNext();
    final var wire = wire(channel);
    final int refused = wire.indexOf("http/1.1 417 expectation failed");
    assertTrue(wire.startsWith("http/1.1 200 ok") && refused > 0 && wire.indexOf("http/1.1 200 ok", refused) > 0,
        "200, 417, 200 in request order: " + wire);
    assertEquals(List.of("/a", "/c"), routes.ran);
    assertTrue(channel.isActive());
    channel.finishAndReleaseAll();
  }

  /// An `Expect` on an HTTP/1.0 request is ignored, never refused — RFC 9110 §10.1.1 has a
  /// server ignore a 100-continue expectation there, and Netty's aggregator ignores any
  /// expectation on that version — so the body is read and the request served, with no
  /// interim response.
  @Test
  void anExpectationOnAnHttp10RequestIsIgnored() {
    for (final String expect : List.of("foo", "100-continue")) {
      final var routes = new Routes().nonBlockingPost("/p", ECHO);
      final var channel = pipeline(routes, Runnable::run);
      channel.writeInbound(ascii("POST /p HTTP/1.0\r\nExpect: " + expect + "\r\nContent-Length: 2\r\n\r\nhi"));
      final var wire = wire(channel);
      assertTrue(wire.startsWith("http/1.1 200 ok"), "Expect: " + expect + " on HTTP/1.0 is ignored: " + wire);
      assertTrue(wire.endsWith("hi"), wire);
      assertEquals(1, count(wire, "http/1.1 "), "no interim response either: " + wire);
      assertEquals(List.of("/p"), routes.ran);
      assertFalse(channel.isActive(), "HTTP/1.0 without keep-alive closes after the response");
    }
  }

  /// The limit is inclusive: a body announced at exactly `maxContentLength` is invited with
  /// `100 Continue` and served; one byte over is refused.
  @Test
  void aContinueExpectationAtTheLimitIsInvited() {
    final var routes = new Routes().nonBlockingPost("/p", ECHO);
    final var channel = pipeline(routes, Runnable::run, 8);
    channel.writeInbound(ascii("POST /p HTTP/1.1\r\nHost: h\r\nExpect: 100-continue\r\nContent-Length: 8\r\n\r\n"));
    final var interim = wire(channel);
    assertTrue(interim.startsWith("http/1.1 100 continue"), "a body at the limit is invited: " + interim);
    channel.writeInbound(ascii("12345678"));
    final var response = wire(channel);
    assertTrue(response.startsWith("http/1.1 200 ok"), response);
    assertTrue(response.endsWith("12345678"), response);
    assertEquals(List.of("/p"), routes.ran);
    assertTrue(channel.isActive());
    channel.finishAndReleaseAll();
  }

  /// A head the codec could not parse is the controller's 400 and a close, whatever
  /// expectation it carried up to the failure: no 417 in place of the 400, and no 100 ahead of
  /// it — Netty's aggregator would answer the expectation first (and, for a 417, leave the
  /// request unanswered on an open connection). The gate's decoder-failure guard is also what
  /// keeps a `Content-Length` the codec refused to normalise — a non-numeric value, one past
  /// `long` — from being parsed by the gate at all (Netty guards that parse with a catch; the
  /// gate with the guard's place in its rule), so an `Expect: 100-continue` beside such a length
  /// is the same 400 and nothing is logged as a failure: a `NumberFormatException` out of
  /// `channelRead` would be the controller's 500, closed and logged as the server's own.
  @Test
  void aMalformedHeadIsRefusedWith400WhateverItExpects() {
    for (final String head : List.of(
        "POST /p HTTP/1.1\r\nHost: h\r\nExpect: foo\r\nContent-Length: 2\r\nNoColon\r\n\r\n",
        "POST /p HTTP/1.1\r\nHost: h\r\nExpect: 100-continue\r\nContent-Length: 2\r\nNoColon\r\n\r\n",
        "POST /p HTTP/1.1\r\nHost: h\r\nExpect: 100-continue\r\nContent-Length: abc\r\n\r\n",
        "POST /p HTTP/1.1\r\nHost: h\r\nExpect: 100-continue\r\nContent-Length: 99999999999999999999\r\n\r\n")) {
      final var routes = new Routes().nonBlockingPost("/p", ECHO);
      final var channel = pipeline(routes, Runnable::run);
      final var logs = controllerLogs(() -> channel.writeInbound(ascii(head)));
      final var wire = wire(channel);
      assertTrue(wire.startsWith("http/1.1 400 bad request"), "a malformed head: " + head + " -> " + wire);
      assertEquals(1, count(wire, "http/1.1 "), "the 400 is the whole answer: " + wire);
      assertTrue(wire.contains("connection: close"), wire);
      assertFalse(channel.isActive(), "a malformed request closes the connection");
      assertEquals(List.of(), routes.ran);
      assertTrue(logs.stream().noneMatch(r -> r.getLevel().intValue() >= Level.SEVERE.intValue()),
          "a malformed head is the client's failure, not the server's: " + logs.stream().map(LogRecord::getMessage).toList());
    }
  }

  /// A malformed head that also declares a body over the limit is the aggregator's 413 and a
  /// close — exactly as the same head is with no expectation at all: the gate leaves a
  /// malformed head alone whatever it expects, and the aggregator tests the declared length
  /// before it looks at the decoder result, so here, unlike the cases above, the controller's
  /// 400 is never reached. What the contract promises is that the answer is independent of
  /// the `Expect`, and it is.
  @Test
  void aMalformedHeadAnnouncingAnOversizedBodyIsRefusedWith413WhateverItExpects() {
    for (final String expect : List.of("", "Expect: foo\r\n", "Expect: 100-continue\r\n")) {
      final var routes = new Routes().nonBlockingPost("/p", ECHO);
      final var channel = pipeline(routes, Runnable::run, 8);
      final var logs = controllerLogs(() ->
          channel.writeInbound(ascii("POST /p HTTP/1.1\r\nHost: h\r\n" + expect + "Content-Length: 99\r\nNoColon\r\n\r\n")));
      final var wire = wire(channel);
      assertTrue(wire.startsWith("http/1.1 413 request entity too large"), "\"" + expect.strip() + "\" on a malformed, oversized head: " + wire);
      assertEquals(1, count(wire, "http/1.1 "), "no 100 ahead of the 413 and no 417 in its place: " + wire);
      assertTrue(wire.contains("content-length: 0"), "the 413 is framed: " + wire);
      assertTrue(wire.contains("connection: close"), "the 413 ends the connection: " + wire);
      assertFalse(channel.isActive(), "the connection is closed after a 413");
      assertEquals(List.of(), routes.ran);
      assertTrue(logs.isEmpty(), "a refused body is not a server failure: " + logs.stream().map(LogRecord::getMessage).toList());
    }
  }

  /// A request line the codec cannot parse at all is the controller's 400 and a close as well
  /// — and a *signalled* one: RFC 9112 §9.6 has a server put `Connection: close` on the final
  /// response of a connection it is about to end, and this is the one shape in which nothing
  /// about the request says which version it spoke. The codec's stand-in for such a request
  /// is an invented `HTTP/1.0` head; framed for that version the close would be implicit —
  /// the header removed, a keep-alive-shaped 400 and then a bare FIN — so the gate frames a
  /// head the codec could not parse as HTTP/1.1, which every response here is anyway. The
  /// request pipelined behind the garbage is discarded by the codec with the rest of the
  /// stream, so that header is the only word the client gets that it must send it again.
  @Test
  void anUnparsableRequestLineIsRefusedWith400AndAnExplicitClose() {
    final var routes = new Routes().nonBlockingGet("/c", OK);
    final var channel = pipeline(routes, Runnable::run);
    final var logs = controllerLogs(() -> channel.writeInbound(ascii("nonsense\r\n\r\n" + get("/c"))));
    final var wire = wire(channel);
    assertTrue(wire.startsWith("http/1.1 400 bad request"), wire);
    assertEquals(1, count(wire, "http/1.1 "), "the request behind the garbage is never answered: " + wire);
    assertTrue(wire.contains("connection: close"), "the close is signalled on the 400, not implied by the codec's invented version: " + wire);
    assertFalse(channel.isActive(), "an unparsable request line closes the connection");
    assertEquals(List.of(), routes.ran);
    assertTrue(logs.stream().noneMatch(r -> r.getLevel().intValue() >= Level.SEVERE.intValue()),
        "an unparsable request is the client's failure, not the server's: " + logs.stream().map(LogRecord::getMessage).toList());
  }

  /// The same outcome reached through the refusal contract itself: a client that sends the
  /// body of a refused request anyway has those bytes read as its next request line, and when
  /// that line is unparsable the answer is the 400 and a signalled close — in turn: 200, 417
  /// (no close: the refused request did not ask for one), then the 400 with
  /// `Connection: close`, and the request pipelined behind the garbage is never answered.
  @Test
  void anUnparsableLineMadeOfARefusedBodyIsAnsweredAndClosed() {
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingGet("/a", OK).nonBlockingPost("/b", ECHO).nonBlockingGet("/c", OK);
    final var channel = pipeline(routes, executor);
    channel.writeInbound(ascii(get("/a")
        + "POST /b HTTP/1.1\r\nHost: h\r\nExpect: foo\r\nContent-Length: 10\r\n\r\n"
        + "nonsense\r\n"
        + get("/c")));
    assertTrue(wire(channel).isEmpty(), "everything waits behind the request in flight");

    final var logs = controllerLogs(executor::runNext);
    final var wire = wire(channel);
    assertTrue(wire.startsWith("http/1.1 200 ok"), wire);
    final int refused = wire.indexOf("http/1.1 417 expectation failed");
    assertTrue(refused > 0, "the 417 follows the first response: " + wire);
    final int badRequest = wire.indexOf("http/1.1 400 bad request", refused);
    assertTrue(badRequest > 0, "the line made of the refused body is answered after the 417: " + wire);
    assertEquals(3, count(wire, "http/1.1 "), "the request behind the garbage is never answered: " + wire);
    assertFalse(wire.substring(0, badRequest).contains("connection: close"), "neither earlier response ends the connection: " + wire);
    assertTrue(wire.indexOf("connection: close", badRequest) > 0, "the close is signalled on the 400: " + wire);
    assertFalse(channel.isActive(), "an unparsable request line closes the connection");
    assertEquals(List.of("/a"), routes.ran, "neither the refused request nor the line made of its body reaches a handler");
    assertTrue(logs.stream().noneMatch(r -> r.getLevel().intValue() >= Level.SEVERE.intValue()),
        "an unparsable request is the client's failure, not the server's: " + logs.stream().map(LogRecord::getMessage).toList());
  }

  // ---- the idle timeout, on a clock the test advances ----

  /// An open connection that carries no request is closed once the idle timeout has elapsed
  /// since it was accepted — one tick short of it, it is still open — and the close writes
  /// nothing: there is no response to frame. Oracle: the JDK backend's idle interval and
  /// Jetty's connector idle timeout, both of which close a silent connection.
  @Test
  void anIdleConnectionIsClosedOnceTheTimeoutElapses() {
    final var clock = new FakeClock();
    final var channel = pipeline(new Routes().nonBlockingGet("/a", OK), Runnable::run, clock);
    advance(channel, clock, IDLE_TIMEOUT_NANOS - 1);
    assertTrue(channel.isActive(), "one tick short of the timeout the connection is still open");
    advance(channel, clock, 1);
    assertFalse(channel.isActive(), "the idle timeout closes the connection");
    assertTrue(wire(channel).isEmpty(), "an idle close writes nothing");
  }

  /// A fully received request awaiting its response is never idle, however long its handler
  /// takes: the connection is held open across many timeouts, and the timeout then runs
  /// afresh from the response's completion — closing exactly one full timeout after it, not
  /// at the next check. Oracle: the JDK backend, whose request timeout defaults to unlimited
  /// and which holds a connection with a request in progress for as long as it takes; Jetty's
  /// connector timeout leaves a running handler alone too (its idle-timeout task fails only a
  /// pending read or write, or hands the timeout to a listener the handler registered).
  @Test
  void aReceivedRequestAwaitingItsResponseIsNeverIdle() {
    final var clock = new FakeClock();
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingGet("/slow", OK);
    final var channel = pipeline(routes, executor, clock);
    advance(channel, clock, IDLE_TIMEOUT_NANOS / 3);
    channel.writeInbound(ascii(get("/slow")));
    assertEquals(1, executor.pending.size());
    for (int timeouts = 1; timeouts <= 5; ++timeouts) {
      advance(channel, clock, IDLE_TIMEOUT_NANOS);
      assertTrue(channel.isActive(), "a request in flight holds the connection open past " + timeouts + " timeouts");
    }
    // off the check's cadence, so the grace is measured from the completion, not the next check
    advance(channel, clock, IDLE_TIMEOUT_NANOS / 3);
    executor.runNext();
    assertTrue(wire(channel).startsWith("http/1.1 200 ok"));
    advance(channel, clock, IDLE_TIMEOUT_NANOS - 1);
    assertTrue(channel.isActive(), "the client gets a full timeout of grace after the response");
    advance(channel, clock, 1);
    assertFalse(channel.isActive(), "a full timeout after the response the connection is idle");
    assertEquals(List.of("/slow"), routes.ran);
  }

  /// A request whose head has arrived but whose body then stops is silence, not work in
  /// progress: the connection is closed one idle timeout after the last byte it decoded, with
  /// nothing written and no handler run, so a client that sends a head and nothing more cannot
  /// hold a connection open for free. Oracle: Jetty's connector idle timeout, which fails a
  /// request that stops making progress (the JDK backend, whose request timeout defaults to
  /// unlimited, holds such a connection for good — the one rule of the three this backend
  /// does not follow). Closing a half-received request is the peer's loss, not a server
  /// failure, so nothing is logged above DEBUG.
  @Test
  void aRequestWhoseBodyStopsArrivingIsIdle() {
    final var clock = new FakeClock();
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingPost("/p", ECHO);
    final var channel = pipeline(routes, executor, clock);
    advance(channel, clock, IDLE_TIMEOUT_NANOS / 3);
    channel.writeInbound(ascii("POST /p HTTP/1.1\r\nHost: h\r\nContent-Length: 4\r\n\r\nab"));
    assertEquals(0, executor.pending.size(), "the request is not complete");
    advance(channel, clock, IDLE_TIMEOUT_NANOS - 1);
    assertTrue(channel.isActive(), "one tick short of a timeout since the last body byte the connection is still open");
    final var logs = controllerLogs(() -> advance(channel, clock, 1));
    assertFalse(channel.isActive(), "a body that stopped arriving a full timeout ago is idle");
    assertTrue(wire(channel).isEmpty(), "an idle close writes nothing");
    assertEquals(0, executor.pending.size(), "a request never received in full never reaches its handler");
    assertTrue(logs.stream().noneMatch(r -> r.getLevel().intValue() >= Level.SEVERE.intValue()),
        "closing a stalled request is not a server failure: " + logs.stream().map(LogRecord::getMessage).toList());
  }

  /// The `100 Continue` the aggregator writes on the head invites the body; it is not activity
  /// of the client's. A client that never takes the invitation up is closed one timeout after
  /// its head, and the interim response is all that is ever written.
  @Test
  void aContinueNeverFollowedByABodyIsIdle() {
    final var clock = new FakeClock();
    final var routes = new Routes().nonBlockingPost("/p", ECHO);
    final var channel = pipeline(routes, Runnable::run, clock);
    advance(channel, clock, IDLE_TIMEOUT_NANOS / 3);
    channel.writeInbound(ascii("POST /p HTTP/1.1\r\nHost: h\r\nExpect: 100-continue\r\nContent-Length: 2\r\n\r\n"));
    assertTrue(wire(channel).startsWith("http/1.1 100 continue"));
    advance(channel, clock, IDLE_TIMEOUT_NANOS - 1);
    assertTrue(channel.isActive(), "one tick short of a timeout since the head the connection is still open");
    advance(channel, clock, 1);
    assertFalse(channel.isActive(), "a body that never followed the 100 is idle");
    assertTrue(wire(channel).isEmpty(), "nothing follows the interim response");
    assertEquals(List.of(), routes.ran);
  }

  /// An upload that is slow but progressing is never idle: every decoded chunk renews the
  /// deadline, so a body whose next byte lands one tick before each deadline, across several
  /// timeouts, is received in full and answered — and the request then waits for its handler
  /// as long as that takes, with the usual full timeout of grace after the answer.
  @Test
  void aProgressingUploadIsNeverIdle() {
    final var clock = new FakeClock();
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingPost("/p", ECHO);
    final var channel = pipeline(routes, executor, clock);
    channel.writeInbound(ascii("POST /p HTTP/1.1\r\nHost: h\r\nContent-Length: 4\r\n\r\n"));
    for (final String chunk : List.of("a", "b", "c")) {
      advance(channel, clock, IDLE_TIMEOUT_NANOS - 1);
      assertTrue(channel.isActive(), "one tick short of the deadline, before chunk " + chunk + ", the connection is still open");
      channel.writeInbound(ascii(chunk));
      assertTrue(channel.config().isAutoRead(), "the rest of the body must be read");
    }
    advance(channel, clock, IDLE_TIMEOUT_NANOS - 1);
    assertTrue(channel.isActive(), "the last chunk renewed the deadline");
    channel.writeInbound(ascii("d"));
    assertEquals(1, executor.pending.size(), "received in full, the request is dispatched");
    advance(channel, clock, 3 * IDLE_TIMEOUT_NANOS);
    assertTrue(channel.isActive(), "received in full, the request waits for its handler as long as that takes");
    executor.runNext();
    assertTrue(wire(channel).endsWith("abcd"));
    advance(channel, clock, IDLE_TIMEOUT_NANOS - 1);
    assertTrue(channel.isActive(), "the client gets a full timeout of grace after the response");
    advance(channel, clock, 1);
    assertFalse(channel.isActive(), "a full timeout after the response the connection is idle");
    assertEquals(List.of("/p"), routes.ran);
  }

  /// Activity resets the deadline: a request answered one tick before the idle deadline
  /// pushes the close out by a full timeout from that answer, so the check that then fires
  /// finds time still to run and re-arms for exactly that.
  @Test
  void activityResetsTheIdleDeadline() {
    final var clock = new FakeClock();
    final var routes = new Routes().nonBlockingGet("/a", OK);
    final var channel = pipeline(routes, Runnable::run, clock);
    advance(channel, clock, IDLE_TIMEOUT_NANOS - 1);
    channel.writeInbound(ascii(get("/a")));
    assertTrue(wire(channel).startsWith("http/1.1 200 ok"));
    advance(channel, clock, 1);
    assertTrue(channel.isActive(), "the original deadline no longer applies: the request renewed it");
    advance(channel, clock, IDLE_TIMEOUT_NANOS - 2);
    assertTrue(channel.isActive(), "one tick short of the renewed deadline the connection is still open");
    advance(channel, clock, 1);
    assertFalse(channel.isActive(), "a full timeout after the last response the connection is idle");
    assertEquals(List.of("/a"), routes.ran);
  }

  /// A pipelined request waiting behind the one in flight is work owed, not silence: the
  /// connection outlives the idle deadline while it waits, serves it once the first completes,
  /// and is idle only once the last response has been out for a full timeout.
  @Test
  void aQueuedRequestKeepsTheConnectionAlive() {
    final var clock = new FakeClock();
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingGet("/a", OK).blockingGet("/b", OK);
    final var channel = pipeline(routes, executor, clock);
    channel.writeInbound(ascii(get("/a") + get("/b")));
    assertEquals(1, executor.pending.size(), "/b waits behind /a");
    for (int timeouts = 1; timeouts <= 3; ++timeouts) {
      advance(channel, clock, IDLE_TIMEOUT_NANOS);
      assertTrue(channel.isActive(), "a queued request holds the connection open past " + timeouts + " timeouts");
    }
    executor.runNext();
    assertEquals(1, executor.pending.size(), "/a's completion releases /b");
    for (int timeouts = 1; timeouts <= 3; ++timeouts) {
      advance(channel, clock, IDLE_TIMEOUT_NANOS);
      assertTrue(channel.isActive(), "the released request holds the connection open past " + timeouts + " timeouts");
    }
    executor.runNext();
    assertEquals(List.of("/a", "/b"), routes.ran);
    assertEquals(2, count(wire(channel), "http/1.1 200 ok"));
    advance(channel, clock, IDLE_TIMEOUT_NANOS - 1);
    assertTrue(channel.isActive(), "one tick short of a full timeout after the last response");
    advance(channel, clock, 1);
    assertFalse(channel.isActive(), "idle only once the last response has been out for a full timeout");
  }

  /// The peer closing while a request is in flight and another is queued behind it — the
  /// transport's own close path, not one this server decided — releases the queued body with
  /// the pipeline and leaves no idle check behind: nothing runs, throws or is written however
  /// far the clock then moves. Closed through the pipeline rather than `EmbeddedChannel.close()`,
  /// which cancels every scheduled task itself and would mask the gate's own cancellation.
  @Test
  void aPeerCloseReleasesTheQueueAndTheIdleCheck() {
    final var clock = new FakeClock();
    final var executor = new ManualExecutor();
    final var routes = new Routes().blockingGet("/a", OK).blockingPost("/b", ECHO);
    final var channel = pipeline(routes, executor, clock);
    final var inbound = ascii(get("/a") + "POST /b HTTP/1.1\r\nHost: h\r\nContent-Length: 3\r\n\r\nxyz");
    channel.writeInbound(inbound);
    assertEquals(1, inbound.refCnt(), "the queued body chunk holds the inbound buffer");
    advance(channel, clock, IDLE_TIMEOUT_NANOS / 2);

    channel.pipeline().close();
    assertFalse(channel.isActive());
    assertEquals(0, inbound.refCnt(), "the queued body is released with the pipeline");
    assertEquals(-1, channel.runScheduledPendingTasks(), "no idle check outlives the connection");
    advance(channel, clock, 10 * IDLE_TIMEOUT_NANOS);
    assertTrue(wire(channel).isEmpty(), "nothing is written to a closed connection");
    assertEquals(List.of(), routes.ran, "the queued request never runs");
  }

  /// After the idle close nothing is scheduled on the loop for the dead connection: moving
  /// the clock on runs nothing, throws nothing and writes nothing.
  @Test
  void noTaskOutlivesAnIdleClosedConnection() {
    final var clock = new FakeClock();
    final var channel = pipeline(new Routes().nonBlockingGet("/a", OK), Runnable::run, clock);
    advance(channel, clock, IDLE_TIMEOUT_NANOS);
    assertFalse(channel.isActive());
    assertEquals(-1, channel.runScheduledPendingTasks(), "an idle-closed connection holds no task");
    advance(channel, clock, 100 * IDLE_TIMEOUT_NANOS);
    assertTrue(wire(channel).isEmpty(), "nothing is written to a closed connection");
  }
}
