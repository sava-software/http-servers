package software.sava.http_servers.core.handlers;

/// What a request says about CORS, read once per request by the Jetty, Helidon and Netty
/// adapters from the headers their servers hand them: the origin it names, if any, and, for a
/// pre-flight, the method and the headers it asks for. The rule, in one place: a blank header
/// names nothing; an `OPTIONS` request is a pre-flight only when it names an origin and a
/// non-blank `Access-Control-Request-Method`, which is then the method to route and to echo in
/// `Access-Control-Allow-Methods` when it resolves; any other request routes as itself, so an
/// `OPTIONS` missing either header is answered like any unrouted request, `405` with `Allow`
/// where the path serves other methods and `404` where it serves none, since nothing
/// registered through `HttpServerBuilder` answers `OPTIONS` (it registers GET and POST). The
/// JDK backend has no CORS handling and answers every `OPTIONS` that way. An adapter reads an
/// `OPTIONS` request through [#options] and any other through [#of], so the pre-flight headers
/// are looked up only where they can matter. The adapters' socket suites pin that each asks
/// here; this class's own tests pin the rule.
public final class CorsRequest {

  private final String origin;
  private final String requestedMethod;
  private final String requestedHeaders;

  private CorsRequest(final String origin, final String requestedMethod, final String requestedHeaders) {
    this.origin = origin;
    this.requestedMethod = requestedMethod;
    this.requestedHeaders = requestedHeaders;
  }

  /// A request whose method is not `OPTIONS`, as the adapter's own parser read the token: it
  /// names an origin or not, and is never a pre-flight. `originHeader` is the raw `Origin`
  /// value, `null` when absent.
  public static CorsRequest of(final String originHeader) {
    return new CorsRequest(named(originHeader), null, null);
  }

  /// An `OPTIONS` request: a pre-flight when it names an origin and a method. The three are
  /// the raw `Origin`, `Access-Control-Request-Method` and `Access-Control-Request-Headers`
  /// values, each `null` when absent.
  public static CorsRequest options(final String originHeader,
                                    final String requestedMethodHeader,
                                    final String requestedHeadersHeader) {
    final var origin = named(originHeader);
    final var requestedMethod = origin != null ? named(requestedMethodHeader) : null;
    final var requestedHeaders = requestedMethod != null ? named(requestedHeadersHeader) : null;
    return new CorsRequest(origin, requestedMethod, requestedHeaders);
  }

  /// A header's value, or `null` when it is absent or blank: a blank header names nothing.
  private static String named(final String header) {
    return header == null || header.isBlank() ? null : header;
  }

  /// The origin the request names, or `null`: nothing to reflect.
  public String origin() {
    return origin;
  }

  /// Whether the request is a pre-flight: it names an origin and the method it asks for.
  public boolean preflight() {
    return requestedMethod != null;
  }

  /// The method to route: the one a pre-flight asks for, otherwise the request's own.
  public String method(final String requestMethod) {
    return requestedMethod != null ? requestedMethod : requestMethod;
  }

  /// The headers a pre-flight asks to send, to echo in `Access-Control-Allow-Headers`, or
  /// `null` when it names none or the request is no pre-flight.
  public String requestedHeaders() {
    return requestedHeaders;
  }
}
