package software.sava.http_servers.hello;

import software.sava.http_servers.core.response.HttpResponse;
import software.sava.http_servers.core.response.QueryHandler;

public final class HelloHandler implements QueryHandler {

  public HelloHandler() {
  }

  @Override
  public HttpResponse httpResponse(final String path, final String query) {
    return HttpResponse.json("""
        {
          "message": "Hello"
        }
        """);
  }
}
