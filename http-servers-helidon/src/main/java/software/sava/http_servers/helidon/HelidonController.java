package software.sava.http_servers.helidon;

import io.helidon.http.HeaderNames;
import io.helidon.http.Method;
import io.helidon.webserver.http.Handler;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import software.sava.http_servers.core.handlers.CorsRequest;
import software.sava.http_servers.core.handlers.HandlerMap;

import java.nio.charset.StandardCharsets;

import static java.lang.System.Logger.Level.ERROR;

/// Dispatches every request through the shared [HandlerMap] from a single catch-all route, so
/// Helidon routing agrees with the JDK, Jetty and Netty adapters: query-handler paths
/// match exactly (plus the builder's trailing-slash alias), only path handlers match by
/// prefix, and the raw as-received path is what the map canonicalizes. Divergences from the
/// other backends:
///
/// - Helidon does not canonicalize before routing, so the ambiguous-path 400 is a live branch
///   here (Jetty's `UriCompliance` answers most such targets first). Helidon does answer
///   a zero-byte 400 itself, before this controller, for a malformed percent escape
///   (`/pct%zz`), an illegal character in the request target (a backslash, say), a missing
///   or blank `Host` header on HTTP/1.1 and, measured on 4.5.6 (2026-10-23), with a close for
///   a request line it cannot parse (an invalid method token, which upstream PR 12767 turned
///   from an escaped parse failure into a 400, or a line without a target or version) and
///   for an absolute-form target with an `https` scheme, or an http(s) target whose authority
///   is missing, invalid or carries userinfo; the plain `http` absolute form, like any other
///   scheme, still arrives here as received and is refused as ambiguous. HTTP/1.0 never gets
///   this far (505, see the README).
/// - Helidon writes an entity on `HEAD` instead of stripping it, which would desync a
///   keep-alive connection. Error answers to `HEAD` (400/404/405) therefore carry status and
///   headers only, where Netty, and Jetty for 404 and 405, write the JSON body and their
///   servers drop it, and the JDK answers these statuses without a body for every method.
///   The `Content-Length` on
///   such an answer is Helidon's `0` for an empty send, not the length the matching `GET`
///   would declare (RFC 9110 s9.3.2 says it should be) — a recorded choice: the framing of
///   the following response is what matters on a keep-alive connection, and `0` keeps it.
/// - Helidon refuses a response completed from another thread, so a handler that throws a
///   `RuntimeException` or an `Error` is answered with the JSON 500 on the request thread here
///   — blocking, non-blocking and cached routes alike — and the failure is logged at ERROR
///   through `System.Logger`; Helidon's own error handling would answer it with a `text/plain`
///   500 and log it at WARNING under its own logger. A response whose status is not final
///   fails the same way, since `ResponseUtil` throws for it before writing anything.
///   A send that fails part-way (a header value Helidon refuses, for instance) has already
///   stamped its `Content-Length` and the offending header on the response, so the 500 clears
///   the headers first and frames its own body.
final class HelidonController implements Handler {

  private static final System.Logger logger = System.getLogger(HelidonController.class.getName());

  private static final String JSON = "application/json";
  private static final byte[] AMBIGUOUS_PATH = """
      {
        "msg": "Ambiguous request path."
      }""".getBytes(StandardCharsets.UTF_8);
  private static final byte[] NOT_FOUND = """
      {
        "msg": "No handler for path."
      }""".getBytes(StandardCharsets.UTF_8);
  private static final byte[] METHOD_NOT_ALLOWED = """
      {
        "msg": "Method not allowed."
      }""".getBytes(StandardCharsets.UTF_8);
  private static final byte[] INTERNAL_ERROR = """
      {
        "msg": "Failed to process request."
      }""".getBytes(StandardCharsets.UTF_8);

  private final HandlerMap<Handler> handlerMap;

  HelidonController(final HandlerMap<Handler> handlerMap) {
    this.handlerMap = handlerMap;
  }

  @Override
  public void handle(final ServerRequest request, final ServerResponse response) throws Exception {
    final var requestMethod = request.prologue().method();
    // rawPath() is the target as received; path() has already percent-decoded, and the shared
    // HandlerMap decodes during canonicalization — routing on the decoded form would decode
    // twice ("/a%2541" would route as "/aA" here and "/a%41" elsewhere)
    final var path = request.path().rawPath();
    final var requestHeaders = request.headers();
    // the shared CORS rule (CorsRequest): a pre-flight names an origin and the method it asks
    // for, which is then the method to route; anything else, an OPTIONS missing either
    // header included, routes as itself, which nothing registered through the builder
    // answers, so it gets 405 with Allow as on the JDK backend
    final var cors = Method.OPTIONS.equals(requestMethod)
        ? CorsRequest.options(
            requestHeaders.first(HeaderNames.ORIGIN).orElse(null),
            requestHeaders.first(HeaderNames.ACCESS_CONTROL_REQUEST_METHOD).orElse(null),
            requestHeaders.first(HeaderNames.ACCESS_CONTROL_REQUEST_HEADERS).orElse(null))
        : CorsRequest.of(requestHeaders.first(HeaderNames.ORIGIN).orElse(null));
    final var method = cors.method(requestMethod.text());

    final var lookup = handlerMap.lookupHandler(method, path);
    final var handler = lookup.handler();
    if (handler == null) {
      final var allowedMethods = lookup.allowedMethods();
      // Helidon sends the entity on HEAD, so the JSON error bodies stay off HEAD answers
      final boolean bodyless = Method.HEAD.equals(requestMethod);
      if (lookup.badRequest()) {
        error(response, 400, AMBIGUOUS_PATH, bodyless);
      } else if (allowedMethods == null) {
        error(response, 404, NOT_FOUND, bodyless);
      } else {
        response.header(HeaderNames.ALLOW, allowedMethods);
        error(response, 405, METHOD_NOT_ALLOWED, bodyless);
      }
    } else {
      final var origin = cors.origin();
      if (origin != null) {
        response.header(HeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        // the pre-flight branch: never entered without an origin (CorsRequest)
        if (cors.preflight()) {
          // the requested method resolved to a handler, so it is allowed; browsers reject a
          // pre-flight that does not name the method
          response.header(HeaderNames.ACCESS_CONTROL_ALLOW_METHODS, method);
          final var requestedHeaders = cors.requestedHeaders();
          if (requestedHeaders != null) {
            // Helidon refuses a null header value, so the no-headers pre-flight echoes none
            response.header(HeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, requestedHeaders);
          }
          response.send();
          return;
        }
      }
      try {
        handler.handle(request, response);
      } catch (final RuntimeException | Error e) {
        // Helidon would answer 500 itself, as text/plain, and log it at WARNING through its own
        // logger; owning the answer keeps the JSON error shape, keeps the failure in this
        // module's log and keeps it off Helidon's error-handling path
        logger.log(ERROR, "Failed to process request.", e);
        // a send that failed during header validation has already stamped Content-Length (and
        // the refused header) on the response; Helidon only fills in a missing length, so the
        // 500 would otherwise declare the failed body's length and desync a keep-alive
        // connection (RFC 9112 s6.3). Clearing the headers first frames the 500 from scratch.
        response.headers().clear();
        error(response, 500, INTERNAL_ERROR, false);
      }
    }
  }

  private static void error(final ServerResponse response,
                            final int status,
                            final byte[] json,
                            final boolean bodyless) {
    response.status(status);
    response.header(HeaderNames.CONTENT_TYPE, JSON);
    if (bodyless) {
      response.send();
    } else {
      response.send(json);
    }
  }
}
