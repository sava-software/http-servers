module software.sava.http_servers.sava {
  requires transitive software.sava.core;
  requires software.sava.http_servers.core;

  exports software.sava.http_servers.sava.handlers;
}
