package software.sava.http_servers.core.response;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public interface HttpResponse {

  HttpResponse EMPTY = response(200, "application/json", "{}".getBytes(StandardCharsets.US_ASCII));

  static HttpResponse response(final int statusCode, final String contentType, final byte[] body) {
    return new HttpBytesResponse(statusCode, contentType, Map.of(), body);
  }

  static HttpResponse response(final int statusCode,
                               final String contentType,
                               final Map<String, String> headers,
                               final byte[] body) {
    return new HttpBytesResponse(statusCode, contentType, headers, body);
  }

  static HttpResponse response(final String contentType, final byte[] body) {
    return response(200, contentType, body);
  }

  static HttpResponse response(final String contentType, final String body) {
    return response(200, contentType, body.getBytes(StandardCharsets.UTF_8));
  }

  static HttpResponse json(final int statusCode, final byte[] body) {
    return response(statusCode, "application/json", body);
  }

  static HttpResponse json(final byte[] body) {
    return json(200, body);
  }

  static HttpResponse json(final int statusCode, final String body) {
    return response(statusCode, "application/json", body.getBytes(StandardCharsets.UTF_8));
  }

  static HttpResponse json(final String body) {
    return json(200, body);
  }

  int statusCode();

  String contentType();

  /// @return additional response headers (beyond {@code Content-Type}) to be written, never {@code null}.
  default Map<String, String> headers() {
    return Map.of();
  }

  /// @return a copy of this response with the given response header set.
  default HttpResponse withHeader(final String name, final String value) {
    final var headers = new LinkedHashMap<>(headers());
    headers.put(name, value);
    return new HttpBytesResponse(statusCode(), contentType(), headers, body());
  }

  byte[] body();
}
