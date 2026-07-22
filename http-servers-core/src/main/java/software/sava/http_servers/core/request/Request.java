package software.sava.http_servers.core.request;

public interface Request {

  String method();

  String path();

  /// @return the raw, undecoded query string exactly as sent on the request line, or
  ///         {@code null} when the request has none. Boundary-scanning parameter parsers
  ///         ({@code HandlerUtil} here and downstream) depend on percent-encoded
  ///         delimiters not having been decoded into `&`, `=` or `,`.
  String query();

  String header(final String name);

  byte[] body();
}
