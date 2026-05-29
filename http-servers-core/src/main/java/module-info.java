module software.sava.http_servers.core {
  exports software.sava.http_servers.core.response;
  exports software.sava.http_servers.core.handlers;
  exports software.sava.http_servers.core.server;
  exports software.sava.http_servers.core.logging;
  requires java.logging;
  requires jdk.httpserver;
}
