package software.sava.http_servers.core.response;

import java.nio.charset.StandardCharsets;

public interface HttpResponse {

  HttpResponse EMPTY = response(200, "application/json", "{}".getBytes(StandardCharsets.US_ASCII));

  static HttpResponse response(final int statusCode, final String contentType, final byte[] body) {
    return new HttpBytesResponse(statusCode, contentType, body);
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

  byte[] body();
}
