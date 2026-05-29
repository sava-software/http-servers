package software.sava.http_servers.fusionauth;

import io.fusionauth.http.HTTPValues;
import io.fusionauth.http.server.HTTPResponse;
import software.sava.http_servers.core.response.HttpResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class ResponseUtil {

  static void writeResponse(final int responseCode,
                            final String contentType,
                            final HTTPResponse response,
                            final byte[] responseBytes) throws IOException {
    response.setContentType(contentType);
    response.setStatus(responseCode);
    response.setContentLength(responseBytes.length);
    try (final var os = response.getOutputStream()) {
      os.write(responseBytes);
    }
  }

  static void writeResponse(final int responseCode,
                            final String contentType,
                            final HTTPResponse response,
                            final String responseString) throws IOException {
    writeResponse(responseCode, contentType, response, responseString.getBytes(StandardCharsets.UTF_8));
  }

  static void writeResponse(final String contentType,
                            final HTTPResponse response,
                            final String responseString) throws IOException {
    writeResponse(200, contentType, response, responseString.getBytes(StandardCharsets.UTF_8));
  }

  static void writeResponse(final int responseCode,
                            final HTTPResponse response,
                            final String responseString) throws IOException {
    writeResponse(responseCode, HTTPValues.ContentTypes.ApplicationJson, response, responseString.getBytes(StandardCharsets.UTF_8));
  }

  static void writeResponse(final HTTPResponse response, final byte[] responseBytes) throws IOException {
    writeResponse(200, HTTPValues.ContentTypes.ApplicationJson, response, responseBytes);
  }

  static void writeResponse(final HTTPResponse response, final HttpResponse httpResponse) throws IOException {
    writeResponse(httpResponse.statusCode(), httpResponse.contentType(), response, httpResponse.body());
  }

  private ResponseUtil() {
  }
}
