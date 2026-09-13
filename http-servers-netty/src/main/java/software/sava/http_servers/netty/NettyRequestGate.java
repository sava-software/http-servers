package software.sava.http_servers.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
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
/// every party that answers a request — the aggregator (`100`, `417`, `413`), the controller
/// and its `exceptionCaught` — is ordered and closed by the same rule.
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
/// **The request ends when its final response has been written.** Outbound `HttpResponse`s
/// pass through here on their way to the encoder; a non-informational one completes the
/// in-flight request when its write completes (a `100 Continue` does not — the request is
/// still being received). Completion drains the queue up to the next request's end, then
/// resumes reading when nothing is in flight. Because the aggregator answers `417` and the
/// `Expect`-refusing `413` on the request head, those complete the request while its body
/// may still be arriving; the body chunks then pass straight through and the aggregator
/// discards them, which is what keeps the queue's head-first invariant.
///
/// **Flow control.** Reads are paused (`autoRead` off) exactly while a fully received
/// request awaits its response, so a pipelining client that never reads answers is stalled
/// at the socket instead of buffered without bound; a body still streaming is always read.
///
/// **Persistence** is decided from both sides, once, here: the connection stays open when the
/// request asked for it (HTTP/1.1 unless `Connection: close`, HTTP/1.0 only with
/// `Connection: keep-alive`) *and* the response does not carry `Connection: close`. The
/// response's `Connection` header is then framed for the request's version
/// (`HttpUtil.setKeepAlive`), and a connection that will not persist is closed once the
/// response is out, its last request left in flight so nothing queued behind it is ever
/// processed (§9.6). A handler, the controller's
/// malformed-request `400` and its `exceptionCaught` `500`, and the aggregator's `413` all end
/// the connection the same way: by putting `Connection: close` on their response.
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
/// handler that is busy rather than waiting on I/O either). A request queued behind the one
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
/// write issued from the drain (an inline route answering a small response), so the drain
/// re-reads its state after each forward instead of assuming it. Queued objects are owned
/// here until forwarded, and released when the handler leaves the pipeline, which every close
/// does.
final class NettyRequestGate extends ChannelDuplexHandler {

  private final ArrayDeque<Object> queued;
  private final long idleTimeoutNanos;
  private final LongSupplier clock;
  private boolean inFlight;
  private boolean bodyComplete;
  // the in-flight request's persistence inputs; an unsolicited response (a transport failure
  // with nothing in flight) is framed as HTTP/1.1 keep-alive, which its close header overrides
  private HttpVersion requestVersion;
  private boolean requestKeepAlive;
  // the clock at the accept, the last decoded read or the last completed response: the idle
  // deadline runs from it
  private long lastActivity;
  private Future<?> idleCheck;

  NettyRequestGate(final long idleTimeoutNanos, final LongSupplier clock) {
    this.queued = new ArrayDeque<>();
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
    if (queued.isEmpty() && !(inFlight && msg instanceof HttpRequest)) {
      forward(ctx, msg);
      pauseOrResume(ctx);
    } else {
      queued.add(msg);
    }
  }

  private void forward(final ChannelHandlerContext ctx, final Object msg) {
    if (msg instanceof HttpRequest request) {
      inFlight = true;
      bodyComplete = false;
      requestVersion = request.protocolVersion();
      requestKeepAlive = HttpUtil.isKeepAlive(request);
    }
    if (msg instanceof LastHttpContent) {
      // a FullHttpRequest (the decoder's shape for a request line it could not parse) is both
      bodyComplete = true;
    }
    ctx.fireChannelRead(msg);
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
