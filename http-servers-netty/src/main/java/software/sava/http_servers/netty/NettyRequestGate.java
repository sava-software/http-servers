package software.sava.http_servers.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpExpectationFailedEvent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayDeque;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/// One connection's sequencing, placed between the HTTP codec and the aggregator so that
/// every party that answers a request — this gate's own expectation refusals (`417`, `413`),
/// the aggregator (`100`, the oversized-body `413`), the controller and its `exceptionCaught`
/// — is ordered and closed by the same rule.
///
/// **One request at a time.** A decoded request head (`HttpRequest`) is forwarded only while
/// no request is in flight; forwarding one puts it in flight. Anything decoded after that —
/// the next request's head and body — waits in a queue, in wire order, and nothing is
/// forwarded out of order: a body chunk is queued exactly when something is queued ahead
/// of it. HTTP/1.1 pipelining requires responses in request order (RFC 9112 §9.3.2), and a
/// blocking route runs on the executor handed to `createServer`, where it can finish after
/// a later, faster request; with the aggregator seeing request N+1 only once N's response
/// has completed, its own interim and error answers are ordered by construction, and its
/// `413` can no longer close the connection under an earlier, unwritten response.
///
/// **Expectations are refused where the codec stands, and answered in turn.** An `Expect`
/// this server does not support is refused with `417`, and an `Expect: 100-continue` whose
/// declared `Content-Length` is over the aggregator's limit with `413` — the two refusals
/// Netty's aggregator would make, decided here instead, the moment the codec hands over the
/// head. Refusing tells the codec, through the same `HttpExpectationFailedEvent` the
/// aggregator fires, that the refused request's body is not expected and what follows its
/// head is the next request line; the codec resets whatever it was set to read, and that
/// reset is right only while the codec still stands on the refused head, which it does in
/// `channelRead`: the codec fires each decoded message before decoding the next. Delayed to
/// the refusal's turn — behind a request in flight — the reset would land on whatever the codec
/// had decoded since, a later request's body parsed from then on as request lines. So the
/// decision is immediate and only the answer waits: the refusal takes the request's place in
/// the queue, whatever the codec still emits for that request is released here (the empty
/// last content of a bodiless head; after a reset, nothing), and when its turn comes the gate
/// writes the answer itself through the outbound path below — `Content-Length: 0`, no body,
/// the `413` with `Connection: close` as the aggregator's oversized answer already carries —
/// so completion and persistence follow the rules below like any other response. A client that
/// sends the refused body anyway has it read as the next request line, which is Netty's own
/// outcome and is bounded: an unroutable line is answered, an unparsable one is `400` and
/// closed, silence is closed by the idle timeout, and a refusal is never paired with another
/// request. The aggregator keeps only the `100 Continue`, written in turn when the head is
/// forwarded, and a malformed head is refused by nothing here: it is the controller's `400`
/// whatever expectation the codec parsed before it failed. An `Expect` on an HTTP/1.0 request
/// is ignored, as the aggregator ignores it (RFC 9110 §10.1.1).
///
/// **The request ends when its final response has been written.** Outbound `HttpResponse`s
/// pass through here on their way to the encoder; a non-informational one completes the
/// in-flight request when its write completes (a `100 Continue` does not — the request is
/// still being received). Completion drains the queue up to the next request's end, then
/// resumes reading when nothing is in flight.
///
/// **Flow control.** Reads are paused (`autoRead` off) exactly while a fully received
/// request awaits its response, so a pipelining client that never reads answers is stalled
/// at the socket instead of buffered without bound; a body still streaming is always read.
/// A refused request is fully received the moment it is refused.
///
/// **Persistence** is decided from both sides, once, here: the connection stays open when the
/// request asked for it (HTTP/1.1 unless `Connection: close`, HTTP/1.0 only with
/// `Connection: keep-alive`) *and* the response does not carry `Connection: close`. The
/// response's `Connection` header is then framed for the request's version
/// (`HttpUtil.setKeepAlive`) — HTTP/1.1 for a head the codec could not parse, whose version
/// may be the codec's own invented `HTTP/1.0` for a request line that never parsed, so that
/// the close its `400` carries is signalled rather than left implicit — and a connection that
/// will not persist is closed once the response is out, its last request left in flight so
/// nothing queued behind it is ever processed (§9.6). A handler, the controller's
/// malformed-request `400` and its `exceptionCaught` `500`, both `413`s and a refused request
/// that asked to close all end the connection the same way: with `Connection: close` on the
/// response.
///
/// **Idle timeout.** A connection is idle when it is neither carrying a fully received request
/// that awaits its response nor making progress, and it is closed once the idle timeout has
/// elapsed since the later of its last decoded read and its last completed response. That
/// covers a silent connection between requests — what the JDK backend's idle interval and
/// Jetty's connector idle timeout close — and a request whose head has arrived but whose body
/// has stopped (a client that sends a head and then nothing, the slowloris shape: Jetty fails
/// and closes it too when the handler is waiting for that body, while the JDK, whose request
/// timeout defaults to unlimited, holds it for good): while a body is still owed the check
/// applies, and because every decoded chunk refreshes the clock, an upload that is slow but
/// progressing is never idle. The exemption is exactly the paused state — a received request
/// awaiting its answer, however long its handler takes: the check that finds one re-arms for
/// a full timeout, and the clock is read again at the response's completion, so a client gets
/// the full timeout of grace after every answer (neither of those backends interrupts a
/// handler that is busy rather than waiting on I/O either). A refused request is answered in
/// turn like any other, so its answer is activity too. A request queued behind the one
/// in flight needs no rule of its own — nothing can be queued while nothing is in flight,
/// because the drain stops only at a request in flight or an empty queue — so the paused
/// state alone says whether the connection is exempt. The check is one scheduled task per connection on
/// its own loop, re-armed lazily for the time still to run rather than reset on every read,
/// cancelled when the handler leaves the pipeline; the close writes nothing and releases
/// whatever was queued through the same removal path every close takes. The clock is
/// injected so the timeout is tested by advancing time, never by waiting.
///
/// Every field is event-loop confined: `channelRead`, `write`, the completion listener and
/// the idle check all run on the channel's loop. Completion can run synchronously inside a
/// write issued from the drain (an inline route answering a small response, or a refusal),
/// so the drain re-reads its state after each forward instead of assuming it. Queued objects
/// are owned here until forwarded, and released when the handler leaves the pipeline, which
/// every close does.
final class NettyRequestGate extends ChannelDuplexHandler {

  /// A refused request's place in the queue: the head it was decided on, for the persistence
  /// inputs, and the status it is answered with when its turn comes.
  private record Refusal(HttpRequest head, HttpResponseStatus status) {
  }

  private final ArrayDeque<Object> queued;
  private final int maxContentLength;
  private final long idleTimeoutNanos;
  private final LongSupplier clock;
  private boolean inFlight;
  private boolean bodyComplete;
  // set on refusing a head, cleared by the next head: whatever the codec still emits for the
  // refused request is released rather than queued or forwarded
  private boolean discarding;
  // the in-flight request's persistence inputs; an unsolicited response (a transport failure
  // with nothing in flight) is framed as HTTP/1.1 keep-alive, which its close header overrides
  private HttpVersion requestVersion;
  private boolean requestKeepAlive;
  // the clock at the accept, the last decoded read or the last completed response: the idle
  // deadline runs from it
  private long lastActivity;
  private Future<?> idleCheck;

  NettyRequestGate(final int maxContentLength, final long idleTimeoutNanos, final LongSupplier clock) {
    this.queued = new ArrayDeque<>();
    this.maxContentLength = maxContentLength;
    this.idleTimeoutNanos = idleTimeoutNanos;
    this.clock = clock;
    this.requestVersion = HttpVersion.HTTP_1_1;
    this.requestKeepAlive = true;
  }

  @Override
  public void handlerAdded(final ChannelHandlerContext ctx) {
    // the initializer adds this handler to a registered channel, so the executor is the
    // connection's event loop and the accept is the first activity
    lastActivity = clock.getAsLong();
    scheduleIdleCheck(ctx, idleTimeoutNanos);
  }

  /// Arms the check for `delayNanos` from now — never for this very instant: a zero delay
  /// would run it again in the same drain of the loop's scheduled tasks.
  private void scheduleIdleCheck(final ChannelHandlerContext ctx, final long delayNanos) {
    idleCheck = ctx.executor().schedule(() -> checkIdle(ctx), Math.max(delayNanos, 1L), NANOSECONDS);
  }

  /// Runs on the loop at each armed deadline: re-arms for a full timeout while a fully
  /// received request awaits its response (activity is then measured from its completion),
  /// closes the connection once the timeout has elapsed since the last activity — a body the
  /// client still owes counts as activity only as its chunks arrive — and otherwise re-arms
  /// for the time still to run.
  private void checkIdle(final ChannelHandlerContext ctx) {
    if (paused()) {
      scheduleIdleCheck(ctx, idleTimeoutNanos);
      return;
    }
    final long remaining = idleTimeoutNanos - (clock.getAsLong() - lastActivity);
    if (remaining <= 0) {
      ctx.close();
    } else {
      scheduleIdleCheck(ctx, remaining);
    }
  }

  @Override
  public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
    lastActivity = clock.getAsLong();
    final Object decoded;
    if (msg instanceof HttpRequest head) {
      decoded = admit(ctx, head);
    } else if (discarding) {
      ReferenceCountUtil.release(msg);
      return;
    } else {
      decoded = msg;
    }
    if (queued.isEmpty() && !(inFlight && msg instanceof HttpRequest)) {
      forward(ctx, decoded);
      pauseOrResume(ctx);
    } else {
      queued.add(decoded);
    }
  }

  /// Decides a just-decoded head's expectation while the codec still stands on it: a head that
  /// is not refused goes on as itself, a refused one as its [Refusal], with the codec told now —
  /// from the head of the pipeline, as the aggregator would tell it — that the refused body is
  /// not expected, so that the reset it answers with can only land on this request.
  private Object admit(final ChannelHandlerContext ctx, final HttpRequest head) {
    final var status = refusal(head);
    discarding = status != null;
    if (status == null) {
      return head;
    }
    ctx.pipeline().fireUserEventTriggered(HttpExpectationFailedEvent.INSTANCE);
    return new Refusal(head, status);
  }

  /// The status a head's expectation is refused with, or null when nothing here refuses it:
  /// the rule Netty's aggregator applies (`HttpUtil.isUnsupportedExpectation`, which is not
  /// public, then `is100ContinueExpected` against the declared length), except that a
  /// malformed head is left to the controller's `400`.
  private HttpResponseStatus refusal(final HttpRequest head) {
    final var expect = head.headers().get(HttpHeaderNames.EXPECT);
    if (expect == null
        || head.decoderResult().isFailure()
        || head.protocolVersion().compareTo(HttpVersion.HTTP_1_1) < 0) {
      return null;
    }
    if (!HttpHeaderValues.CONTINUE.contentEqualsIgnoreCase(expect)) {
      return HttpResponseStatus.EXPECTATION_FAILED;
    }
    return HttpUtil.getContentLength(head, -1L) > maxContentLength
        ? HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE
        : null;
  }

  private void forward(final ChannelHandlerContext ctx, final Object msg) {
    if (msg instanceof Refusal refusal) {
      answer(ctx, refusal);
      return;
    }
    if (msg instanceof HttpRequest request) {
      begin(request);
    }
    if (msg instanceof LastHttpContent) {
      // a FullHttpRequest (the decoder's shape for a request line it could not parse) is both
      bodyComplete = true;
    }
    ctx.fireChannelRead(msg);
  }

  /// Puts `request` in flight and takes its persistence inputs. A head the codec could not
  /// parse is framed as HTTP/1.1 whatever version it names: for a request line that never
  /// parsed the codec hands over an invented `HTTP/1.0` stand-in, and keyed on that version
  /// the close the controller's 400 carries would be made implicit — the header removed —
  /// where RFC 9112 §9.6 has it signalled; every response here is HTTP/1.1 in any case.
  private void begin(final HttpRequest request) {
    inFlight = true;
    bodyComplete = false;
    requestVersion = request.decoderResult().isFailure()
        ? HttpVersion.HTTP_1_1
        : request.protocolVersion();
    requestKeepAlive = HttpUtil.isKeepAlive(request);
  }

  /// A refused request's turn: in flight and, with nothing of it left to read, fully received;
  /// its answer goes out through [#write] like any other response, so the request's
  /// persistence and the response's close decide the connection, and its completion
  /// releases the next.
  private void answer(final ChannelHandlerContext ctx, final Refusal refusal) {
    begin(refusal.head());
    bodyComplete = true;
    final var response = ResponseUtil.emptyResponse(refusal.status());
    if (refusal.status() == HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE) {
      // the body was announced over the limit and may be sent regardless: never read it
      ResponseUtil.closeAfter(response);
    }
    write(ctx, response, ctx.newPromise());
    ctx.flush();
  }

  /// A fully received request is awaiting its response: nothing more is read until it is out.
  private boolean paused() {
    return inFlight && bodyComplete;
  }

  private void pauseOrResume(final ChannelHandlerContext ctx) {
    ctx.channel().config().setAutoRead(!paused());
  }

  @Override
  public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
    if (msg instanceof HttpResponse response && response.status().codeClass() != HttpStatusClass.INFORMATIONAL) {
      final var headers = response.headers();
      final boolean keepAlive = requestKeepAlive
          && !headers.containsValue(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE, true);
      // keyed on the request's version: an HTTP/1.0 client gets an explicit keep-alive when it
      // asked for one, an HTTP/1.1 client an explicit close when it is about to be closed
      HttpUtil.setKeepAlive(headers, requestVersion, keepAlive);
      // a connection that will not persist keeps its last request in flight for good, which
      // is what stops the drain: nothing queued behind it is ever forwarded (RFC 9112 §9.6)
      final ChannelFutureListener listener = keepAlive
          ? future -> {
            // a response that never reached the client releases nothing; the failed flush
            // has closed the channel underneath it
            if (future.isSuccess()) {
              completed(ctx);
            }
          }
          : ChannelFutureListener.CLOSE;
      promise.addListener(listener);
    }
    ctx.write(msg, promise);
  }

  private void completed(final ChannelHandlerContext ctx) {
    inFlight = false;
    lastActivity = clock.getAsLong();
    Object next;
    while (!paused() && (next = queued.poll()) != null) {
      forward(ctx, next);
    }
    pauseOrResume(ctx);
  }

  @Override
  public void handlerRemoved(final ChannelHandlerContext ctx) {
    // reached by every close: the pipeline is destroyed once the closed channel deregisters,
    // and this handler goes with it, so the emptied queue needs no clearing and the idle
    // check must not outlive the connection
    idleCheck.cancel(false);
    queued.forEach(ReferenceCountUtil::release);
  }
}
