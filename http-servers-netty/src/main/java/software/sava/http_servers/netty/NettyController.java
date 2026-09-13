package software.sava.http_servers.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import software.sava.http_servers.core.handlers.HandlerMap;

import java.util.concurrent.Executor;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;

/// The root handler of every connection: resolves the shared [HandlerMap] on the raw,
/// undecoded request target, answers 400/404/405 with the same JSON bodies as the Jetty and
/// FusionAuth controllers, reflects `Origin` into `Access-Control-Allow-Origin` and answers
/// CORS pre-flights the same way they do.
///
/// It decides *what* to answer; the connection's ordering and persistence belong to the
/// [NettyRequestGate] in front of the aggregator, which hands this controller one aggregated
/// request at a time and holds the next until the response to this one is on the wire. So
/// there is nothing to queue here: a non-blocking route or a cached response is answered
/// inline on the event loop, a blocking route is offloaded with `executor.execute` and writes
/// back through `ctx.writeAndFlush`, which hops to the loop itself. A response that must end
/// the connection says so with `Connection: close` — the malformed-request 400 (the decoder
/// has discarded the rest of the stream) and the [#exceptionCaught] 500 — exactly as a
/// handler does, and the gate closes after writing it. One instance serves one connection
/// (it is not `@Sharable`), matching the gate and the aggregator beside it.
///
/// Known divergences from the other backends:
/// - A body past the builder's `maxContentLength` (64 MiB for consumers) is answered 413 in
///   request order — by [NettyRequestGate] when `Expect: 100-continue` announced it, by
///   [NettyRequestAggregator] otherwise — before this controller sees the request, and the
///   connection is then closed; the other backends have no such limit. An `Expect` the server
///   does not support is answered 417 by the gate, in request order, and the connection
///   stays open.
/// - A request the codec cannot parse arrives with a failed decoder result and is answered
///   400 (`"Malformed request."`) before the connection is closed — the other servers refuse
///   such requests in their own layer.
/// - A `RuntimeException` from a handler is answered 500 on both the inline and the
///   offloaded path and logged through [System.Logger]; an `Error` on the offloaded path
///   escapes to the executor thread, as it does in the JDK adapter's executor task. An
///   `Error` on the inline path reaches [#exceptionCaught] through the pipeline — Netty
///   routes a throw from `channelRead` to the throwing handler's `exceptionCaught` whether the
///   read was fired by the socket or by the gate's drain — which answers 500 and closes.
/// - A connection that closes while a request body is still arriving — the client aborted
///   an upload — is reported by Netty's aggregator as a `PrematureChannelClosureException`.
///   That is the peer going away, not a failure of this server: it is logged at `DEBUG` and
///   nothing is written.
/// - An HTTP/1.1 request without a `Host` header (or with a blank one) is answered, not
///   refused with the 400 RFC 9112 §3.2 asks for — parity with the JDK backend; Jetty,
///   FusionAuth and Helidon refuse it.
/// - An absolute-form request target (`GET http://host/p HTTP/1.1`) is refused with the
///   ambiguous-path 400 by the shared routing, as on FusionAuth; the JDK and Jetty servers
///   reduce it to its path first (see [NettyRequest]).
/// - A request whose head has arrived but whose body has stopped is closed by the idle
///   timeout 30 s after the last byte decoded, with nothing written — as Jetty does, failing
///   a handler that is waiting for that body with a `500` and closing; the JDK backend, whose
///   request timeout defaults to unlimited, holds such a connection for good.
///
/// An idle connection — one that is neither carrying a fully received request awaiting its
/// response nor making progress for 30 s, measured from the later of its last decoded read
/// and its last completed response — is closed by the [NettyRequestGate], as the JDK
/// backend's idle interval and Jetty's connector idle timeout close a silent connection
/// between requests. A fully received request is never idle, so a blocking route that takes
/// minutes keeps its connection; neither of those backends interrupts a busy handler either.
final class NettyController extends SimpleChannelInboundHandler<FullHttpRequest> {

  private static final System.Logger logger = System.getLogger(NettyController.class.getName());

  private final HandlerMap<NettyHandler> handlerMap;
  private final Executor executor;

  NettyController(final HandlerMap<NettyHandler> handlerMap, final Executor executor) {
    this.handlerMap = handlerMap;
    this.executor = executor;
  }

  @Override
  protected void channelRead0(final ChannelHandlerContext ctx, final FullHttpRequest msg) {
    // copy before anything else: msg is released when this method returns
    dispatch(ctx, new NettyRequest(msg));
  }

  private void dispatch(final ChannelHandlerContext ctx, final NettyRequest request) {
    if (request.malformed()) {
      ctx.writeAndFlush(ResponseUtil.closeAfter(ResponseUtil.jsonResponse(400, """
          {
            "msg": "Malformed request."
          }"""
      )));
      return;
    }
    final String accessControlRequestMethod;
    final boolean preFlight;
    // case-sensitive on purpose: RFC 9110 §9.1 makes the method token case-sensitive, so a
    // lowercase "options" is an unknown method here (405), where the Jetty and FusionAuth
    // controllers' is() would treat it as a pre-flight
    if (HttpMethod.OPTIONS.name().equals(request.method())) {
      accessControlRequestMethod = request.header("Access-Control-Request-Method");
      preFlight = accessControlRequestMethod != null && !accessControlRequestMethod.isBlank();
    } else {
      accessControlRequestMethod = null;
      preFlight = false;
    }
    final var method = preFlight
        ? accessControlRequestMethod
        : request.method();

    final var lookup = handlerMap.lookupHandler(method, request.path());
    final var handler = lookup.handler();
    if (handler == null) {
      final var allowedMethods = lookup.allowedMethods();
      final FullHttpResponse response;
      if (lookup.badRequest()) {
        response = ResponseUtil.jsonResponse(400, """
            {
              "msg": "Ambiguous request path."
            }"""
        );
      } else if (allowedMethods == null) {
        response = ResponseUtil.jsonResponse(404, """
            {
              "msg": "No handler for path."
            }"""
        );
      } else {
        response = ResponseUtil.jsonResponse(405, """
            {
              "msg": "Method not allowed."
            }"""
        );
        response.headers().set(HttpHeaderNames.ALLOW, allowedMethods);
      }
      ctx.writeAndFlush(response);
    } else {
      final var origin = request.header("Origin");
      // if pre-flight check.
      if (origin != null && preFlight) {
        // the requested method resolved to a handler, so it is allowed; without this
        // header browsers reject the pre-flight
        final var response = ResponseUtil.emptyResponse(HttpResponseStatus.OK);
        final var responseHeaders = response.headers();
        responseHeaders.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        responseHeaders.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, method);
        final var requestHeaders = request.header("Access-Control-Request-Headers");
        if (requestHeaders != null) {
          // DefaultHttpHeaders refuses a null value where the other backends treat it as a no-op
          responseHeaders.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, requestHeaders);
        }
        ctx.writeAndFlush(response);
      } else if (handler.blocking()) {
        executor.execute(() -> invoke(ctx, request, handler));
      } else {
        invoke(ctx, request, handler);
      }
    }
  }

  /// Runs on the event loop for a non-blocking route and on the executor for a blocking one.
  private void invoke(final ChannelHandlerContext ctx, final NettyRequest request, final NettyHandler handler) {
    FullHttpResponse response;
    try {
      response = ResponseUtil.response(handler.handle(request), request.header("Origin"));
    } catch (final RuntimeException e) {
      // an unanswered request would hang the client, and everything queued behind it
      logger.log(ERROR, "Failed to process request.", e);
      response = serverError();
    }
    ctx.writeAndFlush(response);
  }

  @Override
  public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
    if (cause instanceof PrematureChannelClosureException) {
      // the connection is already closed and no handler ran: there is nothing to answer and
      // nothing of ours failed
      logger.log(DEBUG, "Connection closed while a request was still being received.", cause);
      return;
    }
    // the failing request is unknown here — an Error past invoke's guard, or the transport —
    // so answer on the channel and end it; a dead socket fails the write harmlessly
    logger.log(ERROR, "Failed to process request.", cause);
    ctx.writeAndFlush(ResponseUtil.closeAfter(serverError()));
  }

  private static FullHttpResponse serverError() {
    return ResponseUtil.jsonResponse(500, """
        {
          "msg": "Failed to process request."
        }"""
    );
  }
}
