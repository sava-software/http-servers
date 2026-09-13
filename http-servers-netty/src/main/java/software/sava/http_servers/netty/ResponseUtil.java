package software.sava.http_servers.netty;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import software.sava.http_servers.core.response.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/// Frames a core [HttpResponse] as a Netty `FullHttpResponse`. Every response is HTTP/1.1
/// whatever version the request named — RFC 9112 s2.3 has a server answer in its own highest
/// conformant minor version, and the other backends do — while the request's version still
/// decides the keep-alive default, in `NettyRequestGate.write`.
final class ResponseUtil {

  static final String APPLICATION_JSON = "application/json";

  static FullHttpResponse response(final int statusCode,
                                   final String contentType,
                                   final Map<String, String> headers,
                                   final byte[] body,
                                   final String origin) {
    final FullHttpResponse response;
    if (statusCode == 204 || statusCode == 304) {
      // bodyless statuses: no content, no Content-Length and no Transfer-Encoding. The codec
      // strips framing from a 204 itself; a 304 may only carry a Content-Length equal to the
      // 200 payload it stands for, which no handler here can know, so send none.
      response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(statusCode), Unpooled.EMPTY_BUFFER);
    } else {
      response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(statusCode), Unpooled.wrappedBuffer(body));
      HttpUtil.setContentLength(response, body.length);
    }
    final var responseHeaders = response.headers();
    responseHeaders.set(HttpHeaderNames.CONTENT_TYPE, contentType);
    if (origin != null) {
      // reflected before the handler's own headers are applied, so a handler that sets
      // Access-Control-Allow-Origin itself wins — the order the other controllers use
      responseHeaders.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
    }
    for (final var header : headers.entrySet()) {
      responseHeaders.set(header.getKey(), header.getValue());
    }
    return response;
  }

  /// `origin` is the request's `Origin` header, or null when it carried none.
  static FullHttpResponse response(final HttpResponse httpResponse, final String origin) {
    return response(httpResponse.statusCode(), httpResponse.contentType(), httpResponse.headers(), httpResponse.body(), origin);
  }

  static FullHttpResponse jsonResponse(final int statusCode, final String json) {
    return response(statusCode, APPLICATION_JSON, Map.of(), json.getBytes(StandardCharsets.UTF_8), null);
  }

  /// A response with no body, framed `Content-Length: 0` so a client on a persistent connection
  /// can delimit it (RFC 9112 §6.3): the pre-flight `200`, the gate's `417` and both `413`s.
  static FullHttpResponse emptyResponse(final HttpResponseStatus status) {
    final var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
    HttpUtil.setContentLength(response, 0);
    return response;
  }

  /// Marks `response` as the connection's last: `NettyRequestGate` closes the connection once
  /// a response carrying `Connection: close` is out (RFC 9112 §9.6), whoever produced it — a
  /// handler through its own headers, or the controller, the gate and the aggregator through
  /// this.
  static FullHttpResponse closeAfter(final FullHttpResponse response) {
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    return response;
  }

  private ResponseUtil() {
  }
}
