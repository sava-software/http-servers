package software.sava.http_servers.core.handlers;

/// The result of resolving a request against a {@link HandlerMap}.
///
/// - When {@link #handler()} is non-null a handler matched both the path and the HTTP method.
/// - When {@link #handler()} is null and {@link #allowedMethods()} is non-null the path matched but
///   the HTTP method did not, so the caller should respond with {@code 405} and an {@code Allow}
///   header set to {@link #allowedMethods()}.
/// - When {@link #badRequest()} is true the raw path could not be canonicalized (a malformed
///   escape, an encoded or literal separator, an empty segment, or a {@code ".."} escaping the
///   root — see {@link PathCanonicalizer}) and the caller should respond with {@code 400}
///   without routing.
/// - Otherwise no handler is registered for the path and the caller should respond with
///   {@code 404}.
public record HandlerLookup<H>(H handler, String allowedMethods, boolean badRequest) {

  private static final HandlerLookup<?> NOT_FOUND = new HandlerLookup<>(null, null, false);
  private static final HandlerLookup<?> BAD_REQUEST = new HandlerLookup<>(null, null, true);

  @SuppressWarnings("unchecked")
  public static <H> HandlerLookup<H> notFound() {
    return (HandlerLookup<H>) NOT_FOUND;
  }

  @SuppressWarnings("unchecked")
  public static <H> HandlerLookup<H> invalidPath() {
    return (HandlerLookup<H>) BAD_REQUEST;
  }

  public static <H> HandlerLookup<H> matched(final H handler) {
    return new HandlerLookup<>(handler, null, false);
  }

  public static <H> HandlerLookup<H> methodNotAllowed(final String allowedMethods) {
    return new HandlerLookup<>(null, allowedMethods, false);
  }
}
