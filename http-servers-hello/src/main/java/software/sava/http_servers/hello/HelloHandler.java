package software.sava.http_servers.hello;

import software.sava.http_servers.core.request.Request;
import software.sava.http_servers.core.response.HttpResponse;
import software.sava.http_servers.core.response.QueryHandler;

public final class HelloHandler implements QueryHandler {

  public HelloHandler() {
  }

  @Override
  public HttpResponse httpResponse(final Request request) {
    return HttpResponse.json("""
        {
          "message": "Hello"
        }
        """);
  }
}
