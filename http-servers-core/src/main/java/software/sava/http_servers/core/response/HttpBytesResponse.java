package software.sava.http_servers.core.response;

record HttpBytesResponse(int statusCode, String contentType, byte[] body) implements HttpResponse {

}
