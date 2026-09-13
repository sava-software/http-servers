package software.sava.http_servers.soak;

import java.util.Locale;
import java.util.Set;

/// The per-backend expectation table for the abuse profile, the pipeliner's close variant and
/// the idle probe, transcribed from README.md's "Backend divergences" — the one place the load
/// generator encodes what a backend is documented to do with hostile traffic. What the README
/// does not document for a backend is left permissive here (`Closes.EITHER`, a status set of
/// the documented answers) or, where it documents no answer at all, `null` so the case is
/// skipped and counted as skipped rather than guessed: a mismatch on Netty is a contract
/// violation while the same case on another backend can only fail on a shape no backend may
/// produce.
///
/// The abuser sends a `Host` header on every request but the one that probes the `Host` rule,
/// HTTP/1.0 included, because FusionAuth requires one on both versions and Jetty and Helidon on
/// HTTP/1.1; the JDK and Netty require it on neither, so sending it costs nothing.
enum Backend {

  JDK("JDKHttpServerBuilderFactory",
      -1L,
      new Expectation(Set.of(200, 417), Closes.EITHER),
      null,
      new Expectation(Set.of(200), Closes.YES),
      new Expectation(Set.of(200), Closes.EITHER),
      new Expectation(Set.of(200), Closes.EITHER),
      30_000L,
      false),
  JETTY("JettyServerBuilderFactory",
      -1L,
      new Expectation(Set.of(200, 417), Closes.EITHER),
      null,
      new Expectation(Set.of(200), Closes.YES),
      new Expectation(Set.of(200), Closes.EITHER),
      new Expectation(Set.of(400), Closes.EITHER),
      30_000L,
      false),
  FUSIONAUTH("FusionAuthBuilderFactory",
      -1L,
      new Expectation(Set.of(200, 417), Closes.EITHER),
      null,
      new Expectation(Set.of(200), Closes.YES),
      new Expectation(Set.of(400), Closes.EITHER),
      new Expectation(Set.of(400), Closes.EITHER),
      -1L,
      false),
  HELIDON("HelidonBuilderFactory",
      -1L,
      new Expectation(Set.of(200, 417), Closes.EITHER),
      null,
      new Expectation(Set.of(505), Closes.EITHER),
      null,
      new Expectation(Set.of(400), Closes.EITHER),
      -1L,
      false),
  NETTY("NettyBuilderFactory",
      64L << 20,
      new Expectation(Set.of(417), Closes.NO),
      new Expectation(Set.of(400), Closes.YES),
      new Expectation(Set.of(200), Closes.YES),
      new Expectation(Set.of(400), Closes.EITHER),
      new Expectation(Set.of(200), Closes.EITHER),
      30_000L,
      true);

  enum Closes {
    YES, NO, EITHER
  }

  /// The statuses a case may be answered with and whether the connection is then closed.
  record Expectation(Set<Integer> statuses, Closes closes) {

    boolean accepts(final int status) {
      return statuses.contains(status);
    }
  }

  private final String factoryName;
  private final long bodyCap;
  private final Expectation unsupportedExpect;
  private final Expectation malformed;
  private final Expectation http10;
  private final Expectation absoluteForm;
  private final Expectation noHost;
  private final long idleTimeoutMillis;
  private final boolean idleAfterAccept;

  Backend(final String factoryName,
          final long bodyCap,
          final Expectation unsupportedExpect,
          final Expectation malformed,
          final Expectation http10,
          final Expectation absoluteForm,
          final Expectation noHost,
          final long idleTimeoutMillis,
          final boolean idleAfterAccept) {
    this.factoryName = factoryName;
    this.bodyCap = bodyCap;
    this.unsupportedExpect = unsupportedExpect;
    this.malformed = malformed;
    this.http10 = http10;
    this.absoluteForm = absoluteForm;
    this.noHost = noHost;
    this.idleTimeoutMillis = idleTimeoutMillis;
    this.idleAfterAccept = idleAfterAccept;
  }

  /// Accepts the enum name, the factory simple name, or either without regard to case.
  static Backend of(final String name) {
    final var upper = name.strip().toUpperCase(Locale.ROOT);
    for (final var backend : values()) {
      if (backend.name().equals(upper) || backend.factoryName.toUpperCase(Locale.ROOT).equals(upper)) {
        return backend;
      }
    }
    throw new IllegalArgumentException("unknown backend '" + name + "'; one of jdk, jetty, fusionauth, helidon, netty or a factory simple name");
  }

  String factoryName() {
    return factoryName;
  }

  /// The request-body size past which a body is refused with 413 and the connection closed,
  /// or -1 when the backend imposes no cap: the README documents the cap on Netty only (64 MiB),
  /// and the other backends stream a body of any declared length to the handler — so the
  /// oversized cases are skipped there rather than hold a handler on a body that never comes.
  long bodyCap() {
    return bodyCap;
  }

  /// `Expect: foo`. Netty answers 417 with `Content-Length: 0` and keeps the connection, without
  /// reading a body; that is strict and verified by reusing the connection. The other backends
  /// are not documented: the abuser sends the body with the head and accepts a 417 or the
  /// handler's 200, and asserts nothing about the connection afterwards.
  Expectation unsupportedExpect() {
    return unsupportedExpect;
  }

  /// Whether the unsupported-`Expect` case is verified strictly (head only, 417 framed
  /// `Content-Length: 0`) or leniently (body sent with the head, either documented answer
  /// accepted). What happens to the connection afterwards is [Expectation#closes()]'s.
  boolean strictExpect() {
    return this == NETTY;
  }

  /// A request line the codec cannot parse: the README documents it for Netty alone (400,
  /// `Connection: close`, then the close); `null` elsewhere, so the case is skipped there.
  Expectation malformed() {
    return malformed;
  }

  /// `GET /ping HTTP/1.0` with a `Host` header: answered and then closed by every backend but
  /// Helidon, which refuses HTTP/1.0 with 505.
  Expectation http10() {
    return http10;
  }

  /// An absolute-form target, `GET http://host:port/ping HTTP/1.1` with a matching `Host`:
  /// refused 400 by Netty and FusionAuth, reduced to its path and served by the JDK and Jetty
  /// (Jetty additionally refuses a `Host` naming another authority, so the probe's matches);
  /// not documented for Helidon, so `null` there.
  Expectation absoluteForm() {
    return absoluteForm;
  }

  /// `GET /ping HTTP/1.1` with no `Host` at all: 400 on Jetty, FusionAuth and Helidon, served
  /// by the JDK and Netty, which require it on neither version.
  Expectation noHost() {
    return noHost;
  }

  /// The documented idle timeout for a silent connection between requests, or -1 where the
  /// README documents none (FusionAuth, Helidon), in which case the idle probe is skipped. The
  /// JDK checks its idle set on a 10 s timer tick, so the wait bound adds a margin well past
  /// that.
  long idleTimeoutMillis() {
    return idleTimeoutMillis;
  }

  /// Whether the README also documents the idle timer from accept — a connection that never
  /// sends a request — which it does for Netty alone; the JDK and Jetty paragraphs speak of a
  /// silent connection *between requests*, so their probe always sends one first.
  boolean idleAfterAccept() {
    return idleAfterAccept;
  }

  /// Whether nothing pipelined behind a request carrying `Connection: close` may be answered
  /// (README "Pipelining on Netty", RFC 9112 §9.6). Undocumented elsewhere: the pipeliner
  /// still requires the close there, but answers that arrive before it are counted, not failed.
  boolean pipelineCloseStrict() {
    return this == NETTY;
  }
}
