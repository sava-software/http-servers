package software.sava.http_servers.core.response;

import java.util.Map;

record HttpBytesResponse(int statusCode,
                         String contentType,
                         Map<String, String> headers,
                         byte[] body) implements HttpResponse {

}
