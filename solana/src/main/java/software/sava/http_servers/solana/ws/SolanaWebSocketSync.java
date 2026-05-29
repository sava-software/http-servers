package systems.glam.server.core.rest.ws;

import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.services.solana.remote.call.RpcCaller;
import systems.glam.server.core.rest.config.DefensivePollingDelays;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;

public abstract class SolanaWebSocketSync implements WebsocketListener, Runnable {

  private static final System.Logger logger = System.getLogger(SolanaWebSocketSync.class.getName());

  protected final RpcCaller rpcCaller;
  private final long disconnectedDelayNanos;
  private final long connectedDelayNanos;
  private final long minDelayMillis;
  private final ReentrantLock lock;
  private final Condition webSocketStateChange;

  public SolanaWebSocketSync(final RpcCaller rpcCaller, final DefensivePollingDelays defensivePollingDelays) {
    this.rpcCaller = rpcCaller;
    this.disconnectedDelayNanos = defensivePollingDelays.disconnectedDelay().toNanos();
    this.connectedDelayNanos = defensivePollingDelays.connectedDelay().toNanos();
    this.minDelayMillis = defensivePollingDelays.minDelay().toMillis();
    this.lock = new ReentrantLock();
    this.webSocketStateChange = lock.newCondition();
  }

  protected abstract CompletableFuture<List<AccountInfo<byte[]>>> poll();

  protected abstract void onResult(final List<AccountInfo<byte[]>> result);

  private volatile boolean webSocketConnected = false;

  @SuppressWarnings({"BusyWait"})
  @Override
  public final void run() {
    try {
      long lastPoll = System.currentTimeMillis();
      Thread.sleep(disconnectedDelayNanos);
      for (long delay, delayMillis; ; ) {
        lock.lock();
        try {
          delay = webSocketConnected ? connectedDelayNanos : disconnectedDelayNanos;
          if (this.webSocketStateChange.await(delay, TimeUnit.NANOSECONDS)) {
            if (webSocketConnected) {
              logger.log(INFO, getClass().getSimpleName() + " websocket connected.");
            } else {
              logger.log(INFO, getClass().getSimpleName() + " websocket disconnected.");
            }
          }
          delayMillis = minDelayMillis - (System.currentTimeMillis() - lastPoll);
          if (delayMillis > 0) {
            Thread.sleep(delayMillis);
          }
          onResult(poll().join());
          lastPoll = System.currentTimeMillis();
        } finally {
          lock.unlock();
        }
      }
    } catch (final InterruptedException ex) {
      // exit
    } catch (final Throwable error) {
      logger.log(ERROR, "Unhandled error.", error);
    }
  }

  protected final void websocketConnected() {
    this.webSocketConnected = true;
    lock.lock();
    try {
      webSocketStateChange.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void onError(final SolanaRpcWebsocket ws, final Throwable error) {
    this.webSocketConnected = false;
    lock.lock();
    try {
      webSocketStateChange.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void onClose(final SolanaRpcWebsocket ws, final int statusCode, final String reason) {
    this.webSocketConnected = false;
    lock.lock();
    try {
      webSocketStateChange.signalAll();
    } finally {
      lock.unlock();
    }
  }
}
