package software.sava.http_servers.core.handlers;

/// The result of resolving a request against a {@link HandlerMap}.
///
/// - When {@link #handler()} is non-null a handler matched both the path and the HTTP method.
/// - When {@link #handler()} is null and {@link #allowedMethods()} is non-null the path matched but
///   the HTTP method did not, so the caller should respond with {@code 405} and an {@code Allow}
///   header set to {@link #allowedMethods()}.
/// - When both are null no handler is registered for the path and the caller should respond with
///   {@code 404}.
public record HandlerLookup<H>(H handler, String allowedMethods) {

  private static final HandlerLookup<?> NOT_FOUND = new HandlerLookup<>(null, null);

  @SuppressWarnings("unchecked")
  public static <H> HandlerLookup<H> notFound() {
    return (HandlerLookup<H>) NOT_FOUND;
  }

  public static <H> HandlerLookup<H> matched(final H handler) {
    return new HandlerLookup<>(handler, null);
  }

  public static <H> HandlerLookup<H> methodNotAllowed(final String allowedMethods) {
    return new HandlerLookup<>(null, allowedMethods);
  }
}
