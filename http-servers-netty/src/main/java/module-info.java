import software.sava.http_servers.core.server.HttpServerBuilderFactory;
import software.sava.http_servers.netty.NettyBuilderFactory;

module software.sava.http_servers.netty {
  requires transitive software.sava.http_servers.core;

  requires io.netty.buffer;
  requires io.netty.codec;
  requires io.netty.codec.http;
  requires io.netty.common;
  requires io.netty.transport;

  provides HttpServerBuilderFactory with NettyBuilderFactory;
}
