package software.sava.http_servers.jdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import software.sava.http_servers.core.handlers.HandlerMap;

import java.io.IOException;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;

/// Dispatches every request through the shared [HandlerMap] from a single root context, so
/// JDK routing agrees with the Jetty and FusionAuth adapters: query-handler paths match
/// exactly (plus the builder's trailing-slash alias) and only path handlers match by
/// prefix. Registering one jdk-httpserver context per path would instead prefix-match
/// everything — `/echo` would serve `/echo/anything`.
///
/// Every answer is written on the thread that entered [#handle], and an `IOException` from
/// any of them escapes it. That is the whole of this adapter's connection hygiene, and it
/// follows from how jdk.httpserver (JDK 25.0.2, `sun.net.httpserver`) keeps its books:
///
/// - `ServerImpl.Dispatcher.run` registers every accepted connection in `allConnections`
///   (`allConnections.add (c)`, ServerImpl.java:557) and hands each exchange to the server's
///   executor (`executor.execute (t)`, :609) — the one [JdkServerBuilder] set from
///   `createServer`, so the handler is already on that executor's thread.
/// - A connection leaves the set in exactly two ways. The dispatcher removes it while handling
///   the `Event.WriteFinished` that the response stream's `close()` posts (:439-466), and
///   `FixedLengthOutputStream.close` (:90, then :97-98) and `UndefLengthOutputStream.close`
///   (:71, then :78-79) post that event only after their flush succeeded. Or
///   `ServerImpl.closeConnection` (:642-663) removes it, which `ServerImpl.Exchange.run` calls
///   when an exception escapes the handler chain and no `WriteFinished` was posted:
///   `catch (Exception e) { ... if (tx == null || !tx.writefinished) closeConnection(connection); }`
///   (:894-898).
/// - A failure that reaches neither is a leak for the life of the server. `ExchangeImpl.close`
///   (:125-152) answers an `IOException` from closing the streams with a bare
///   `connection.close()` (:149-151): the channel is closed, the `HttpConnection` stays
///   registered, and — with `sun.net.httpserver.maxReqTime`/`maxRspTime` unset — no timer
///   ever visits `reqConnections`/`rspConnections` (`ReqRspTimeoutTask` is scheduled only when
///   `reqRspTimeoutEnabled`, :159-161). Work handed to another executor after `handle`
///   returned cannot reach `closeConnection` at all.
///
/// Hence no executor hop (see [JdkQueryHandler]), no swallowed `IOException`, and the
/// bodyless answers go through [#answerWithoutBody], which moves the one drain the JDK would
/// swallow onto this frame. A client that leaves is logged at `DEBUG` and its connection is
/// closed; a handler that throws is logged at `ERROR` and answered `500` — and if writing
/// that `500` fails, the failure escapes like any other.
final class JdkController implements HttpHandler {

  private static final System.Logger logger = System.getLogger(JdkController.class.getName());

  private final HandlerMap<HttpHandler> handlerMap;

  JdkController(final HandlerMap<HttpHandler> handlerMap) {
    this.handlerMap = handlerMap;
  }

  @Override
  public void handle(final HttpExchange exchange) throws IOException {
    try {
      // getPath() percent-decodes, which would let an encoded traversal reach a prefix
      // handler decoded; routing canonicalization is owned by the shared HandlerMap
      final var lookup = handlerMap.lookupHandler(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath());
      final var handler = lookup.handler();
      if (handler == null) {
        final var allowedMethods = lookup.allowedMethods();
        try (exchange) {
          if (lookup.badRequest()) {
            answerWithoutBody(exchange, 400);
          } else if (allowedMethods == null) {
            answerWithoutBody(exchange, 404);
          } else {
            exchange.getResponseHeaders().set("Allow", allowedMethods);
            answerWithoutBody(exchange, 405);
          }
        }
        return;
      }
      try {
        handler.handle(exchange);
      } catch (final JdkRequest.BodyReadException e) {
        // the client's doing, not the handler's: logged and closed below, never a 500
        throw e;
      } catch (final RuntimeException e) {
        // without this the jdk server aborts the connection, and the client sees EOF, not a status
        logger.log(ERROR, "Failed to process request.", e);
        try (exchange) {
          answerWithoutBody(exchange, 500);
        }
      }
    } catch (final JdkRequest.BodyReadException e) {
      logger.log(DEBUG, "Client left before its request body was read; closing the connection.", e.getCause());
      throw e.getCause();
    } catch (final IOException e) {
      logger.log(DEBUG, "Client left before its response was written; closing the connection.", e);
      throw e;
    }
  }

  /// Answers `status` with no body. jdk.httpserver frames `contentLen -1` with neither
  /// `Content-Length` nor `Transfer-Encoding` and ends the exchange itself:
  /// `ExchangeImpl.sendResponseHeaders` runs `if (noContentToSend) { ros.flush(); close(); }`
  /// (ExchangeImpl.java:287-290). The head's flush (:288) sits outside any `try`, so a failed
  /// flush already escapes; the `close()` behind it (:289) does not. `ExchangeImpl.close` closes
  /// the request stream (:145-147), and `LeftOverInputStream.close` reads and discards whatever
  /// of a declared body the handler left unread (:64-72, up to `sun.net.httpserver.drainAmount`).
  /// A client that resets or half-closes during that drain throws inside `close()`'s `try`,
  /// which catches the `IOException` and calls a bare `connection.close()` (:149-151): the
  /// channel is closed and the connection stays registered. Closing the request body here
  /// first runs the same drain on the caller's frame, where its failure escapes [#handle] and
  /// `ServerImpl.closeConnection` unregisters the connection; once it has been read to its
  /// declared end the JDK's own `close()` has nothing left to drain. A body the drain limit
  /// leaves unread is the dispatcher's business, as before: `WriteFinished` with
  /// `!is.isEOF()` marks the exchange `close` and the connection is closed and removed
  /// (ServerImpl.java:446-456).
  ///
  /// The drain's failure is a request body that could not be read to its end, and is raised as
  /// [JdkRequest.BodyReadException] so [#handle] logs it as that — the client left before its
  /// request was read, not before its response was written, which is what a failure of the
  /// head's flush means. That order is also the cost of this method: the head waits for the
  /// body to arrive, for as long as the client takes to send it, up to the drain amount.
  static void answerWithoutBody(final HttpExchange exchange, final int status) throws IOException {
    try {
      exchange.getRequestBody().close();
    } catch (final IOException e) {
      throw new JdkRequest.BodyReadException(e);
    }
    exchange.sendResponseHeaders(status, -1);
  }
}
