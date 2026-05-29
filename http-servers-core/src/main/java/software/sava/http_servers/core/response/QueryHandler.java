package software.sava.http_servers.core.response;

public interface QueryHandler {

  HttpResponse httpResponse(final String path, final String query);
}
