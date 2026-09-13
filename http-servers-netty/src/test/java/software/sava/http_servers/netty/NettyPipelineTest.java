package software.sava.http_servers.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
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
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The production pipeline — `NettyChannelInitializer`'s codec, gate, aggregator and
/// controller, with real routes — driven in process on an `EmbeddedChannel`, where every
/// write completes synchronously, a blocking route completes exactly when the test runs it,
/// the channel's read state is readable and a queued body's buffer can be watched for
/// release. These are the deterministic seams a socket cannot offer for flow control,
/// ownership and write failure; `NettyConformanceTest` pins the same ordering and
/// persistence rules end to end over real sockets.
final class NettyPipelineTest {

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

  private static EmbeddedChannel pipeline(final Routes routes, final Executor executor, final int maxContentLength, final ChannelHandler... before) {
    final var handlers = new ChannelHandler[before.length + 1];
    System.arraycopy(before, 0, handlers, 0, before.length);
    handlers[before.length] = new NettyChannelInitializer(routes.handlerMap(), executor, maxContentLength);
    return new EmbeddedChannel(handlers);
  }

  private static EmbeddedChannel pipeline(final Routes routes, final Executor executor) {
    return pipeline(routes, executor, NettyServerBuilder.DEFAULT_MAX_CONTENT_LENGTH);
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
  /// a handler answered it or the aggregator did: here the second request carries an
  /// unsupported `Expect` and asks to close, so the aggregator's 417 is the final response —
  /// written after the first request's, framed with the close, then the close. The
  /// aggregator reports that close as premature (its bookkeeping still counts the refused
  /// body as pending); that is not a server failure and must not be logged as one.
  @Test
  void theRequestsCloseIsHonouredOnAnAggregatorAnswer() {
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
    assertTrue(logs.stream().noneMatch(r -> r.getLevel().intValue() >= Level.SEVERE.intValue()),
        "closing after a refusal is not a server failure: " + logs.stream().map(LogRecord::getMessage).toList());
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
}
