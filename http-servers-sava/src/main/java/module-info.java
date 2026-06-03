module software.sava.http_servers.sava {
  requires transitive systems.comodal.json_iterator;
  requires transitive software.sava.core;
  requires transitive software.sava.rpc;
  requires software.sava.idl.clients.spl;
  requires transitive software.sava.http_servers.core;

  exports software.sava.http_servers.sava.handlers;
  exports software.sava.http_servers.sava.x402;
}
