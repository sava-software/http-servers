package software.sava.http_servers.core.request;

public interface Request {

  String method();

  String path();

  String query();

  String header(final String name);

  byte[] body();
}
