package software.sava.http_servers.core.response;

import software.sava.http_servers.core.request.Request;

public interface QueryHandler {

  HttpResponse httpResponse(final Request request);
}
