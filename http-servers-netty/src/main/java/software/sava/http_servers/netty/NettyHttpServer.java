package software.sava.http_servers.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import software.sava.http_servers.core.server.HttpServer;

import java.net.InetSocketAddress;
import java.util.concurrent.Executor;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/// Netty has no server object before `bind()`, so this class is both the unstarted server
/// the builder configures (`initRestServer` supplies the address and the executor,
/// `setController` the child pipeline) and the lifecycle handle it hands back.
///
/// [#start()] creates the event loop groups — a single-threaded acceptor and the I/O
/// workers — so a never-started server holds no threads, then binds and waits for the bind
/// to complete: a failure propagates as thrown, with `java.net.BindException` at the top of
/// the cause chain. Like a Jetty `Server` left in its FAILED state, a server whose start
/// failed keeps its groups until [#stop()] releases them. A second `start()` while the
/// groups exist — after a successful start, or after a failed one that has not been stopped
/// — throws `IllegalStateException` and changes nothing: the running listener keeps
/// answering (the shape jdk.httpserver uses). A stopped server may be started again.
///
/// [#stop()] shuts both groups down with no grace period and waits for their threads to
/// exit; shutting a group down closes every channel registered on it — the listener on the
/// acceptor group, every open connection on the workers — so in-flight exchanges are cut,
/// which is the immediacy the `HttpServer` contract documents. A blocking route in flight
/// runs on the executor, not on a loop, so it is cut rather than waited for. The one thing
/// that can delay `stop()` is a *non-blocking* route that blocks the event loop against its
/// own contract: a loop thread cannot exit before the task it is running returns, so
/// `stop()` waits that long. It is a no-op on a server that never started, and idempotent.
///
/// Known divergences from the other backends: no `Server` or `X-Powered-By` header is
/// ever sent (Netty adds none), and only HTTP/1.0 and HTTP/1.1 are spoken (no h2c, unlike
/// Jetty).
final class NettyHttpServer implements HttpServer {

  private final Executor executor;
  private final InetSocketAddress address;
  private final int ioThreads;
  // written by the builder on its thread and read under start()'s monitor on another
  private volatile ChannelHandler childHandler;
  private EventLoopGroup bossGroup;
  private EventLoopGroup workerGroup;

  NettyHttpServer(final Executor executor, final InetSocketAddress address, final int ioThreads) {
    this.executor = executor;
    this.address = address;
    this.ioThreads = ioThreads;
  }

  Executor executor() {
    return executor;
  }

  InetSocketAddress address() {
    return address;
  }

  void childHandler(final ChannelHandler childHandler) {
    this.childHandler = childHandler;
  }

  @Override
  public synchronized void start() throws Exception {
    if (bossGroup != null) {
      // binding again would allocate a second group set that stop() could never release
      throw new IllegalStateException("Netty server on " + address + " has already been started; stop() it before starting again");
    }
    bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    workerGroup = new MultiThreadIoEventLoopGroup(ioThreads, NioIoHandler.newFactory());
    new ServerBootstrap()
        .group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel.class)
        .childHandler(childHandler)
        .bind(address)
        .sync();
  }

  @Override
  public synchronized void stop() throws Exception {
    if (bossGroup != null) {
      shutdown(bossGroup, workerGroup);
      bossGroup = null;
      workerGroup = null;
    }
  }

  private static void shutdown(final EventLoopGroup... groups) throws Exception {
    for (final var group : groups) {
      group.shutdownGracefully(0, 0, MILLISECONDS);
    }
    for (final var group : groups) {
      // the termination future never fails; get() is the interruptible wait for the loop
      // threads to exit, so stop() returns only once the listener and every connection
      // registered on the loops are closed
      group.terminationFuture().get();
    }
  }
}
