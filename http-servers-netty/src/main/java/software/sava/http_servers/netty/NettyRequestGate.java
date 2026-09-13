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
/// Every field is event-loop confined: `channelRead`, `write` and the completion listener all
/// run on the channel's loop. Completion can run synchronously inside a write issued from the
/// drain (an inline route answering a small response), so the drain re-reads its state after
/// each forward instead of assuming it. Queued objects are owned here until forwarded, and
/// released when the handler leaves the pipeline, which every close does.
final class NettyRequestGate extends ChannelDuplexHandler {

  private final ArrayDeque<Object> queued;
  private boolean inFlight;
  private boolean bodyComplete;
  // the in-flight request's persistence inputs; an unsolicited response (a transport failure
  // with nothing in flight) is framed as HTTP/1.1 keep-alive, which its close header overrides
  private HttpVersion requestVersion;
  private boolean requestKeepAlive;

  NettyRequestGate() {
    this.queued = new ArrayDeque<>();
    this.requestVersion = HttpVersion.HTTP_1_1;
    this.requestKeepAlive = true;
  }

  @Override
  public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
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
    Object next;
    while (!paused() && (next = queued.poll()) != null) {
      forward(ctx, next);
    }
    pauseOrResume(ctx);
  }

  @Override
  public void handlerRemoved(final ChannelHandlerContext ctx) {
    // reached by every close: the pipeline is destroyed once the closed channel deregisters,
    // and this handler goes with it, so the emptied queue needs no clearing
    queued.forEach(ReferenceCountUtil::release);
  }
}
