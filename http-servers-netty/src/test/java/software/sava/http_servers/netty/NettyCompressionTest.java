package software.sava.http_servers.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import software.sava.http_servers.core.handlers.HandlerMap;
import software.sava.http_servers.core.response.HttpResponse;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NettyCompressionTest {

  private static final String BODY = "{\"message\":\"gzip response payload for an API client\"}".repeat(32);

  private static EmbeddedChannel pipeline(final HttpResponse response) {
    final var handlers = HandlerMap.createController(Map.of("/body", Map.of(
        "GET", NettyQueryHandler.createNonBlockingGetHandler(request -> response),
        "POST", NettyQueryHandler.createNonBlockingPostHandler(request -> response))), List.of());
    return new EmbeddedChannel(new NettyChannelInitializer(handlers, Runnable::run, 1024,
        Duration.ofSeconds(30).toNanos(), () -> 1_000_000_000_000L));
  }

  private static ByteBuf ascii(final String text) {
    return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
  }

  private static String request(final String headers) {
    return "GET /body HTTP/1.1\r\nHost: h\r\n" + headers + "\r\n";
  }

  /// Decode actual HTTP/1 wire bytes, retaining the encoded body so its framing and gzip
  /// trailer are checked independently of Netty's encoder.
  private static List<FullHttpResponse> responses(final EmbeddedChannel channel) {
    final var decoder = new EmbeddedChannel(new HttpResponseDecoder(), new HttpObjectAggregator(1 << 20));
    final var responses = new ArrayList<FullHttpResponse>();
    try {
      ByteBuf bytes;
      while ((bytes = channel.readOutbound()) != null) {
        decoder.writeInbound(bytes);
      }
      FullHttpResponse response;
      while ((response = decoder.readInbound()) != null) {
        responses.add(response);
      }
      return responses;
    } finally {
      decoder.finishAndReleaseAll();
    }
  }

  private static String wire(final EmbeddedChannel channel) {
    final var wire = new StringBuilder();
    ByteBuf buffer;
    while ((buffer = channel.readOutbound()) != null) {
      wire.append(buffer.toString(StandardCharsets.ISO_8859_1));
      buffer.release();
    }
    return wire.toString();
  }

  private static byte[] bytes(final FullHttpResponse response) {
    final byte[] bytes = new byte[response.content().readableBytes()];
    response.content().getBytes(response.content().readerIndex(), bytes);
    return bytes;
  }

  private static byte[] gunzip(final byte[] encoded) throws Exception {
    try (final var gzip = new GZIPInputStream(new ByteArrayInputStream(encoded))) {
      return gzip.readAllBytes();
    }
  }

  private static void assertGzip(final FullHttpResponse response, final String expected) throws Exception {
    assertEquals("gzip", response.headers().get(HttpHeaderNames.CONTENT_ENCODING));
    assertNull(response.headers().get(HttpHeaderNames.TRANSFER_ENCODING), "full responses retain length framing");
    assertEquals(response.content().readableBytes(), response.headers().getInt(HttpHeaderNames.CONTENT_LENGTH));
    assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), gunzip(bytes(response)));
    assertTrue(response.headers().containsValue(HttpHeaderNames.VARY, "accept-encoding", true));
  }

  @Test
  void gzipNegotiationUsesCompleteTokensAndQualityValues() throws Exception {
    for (final String accept : List.of("gzip", "GZIP", "x-gzip", "br, gzip;q=0.3", "*;q=0.8",
        "deflate;q=1, gzip;q=0.1", "gzip;q=0, gzip;q=0.9",
        "br,\t gzip \t; q=0.7", "gzip; \tq=0.7 \t,br")) {
      final var channel = pipeline(HttpResponse.response("application/json", BODY));
      try {
        channel.writeInbound(ascii(request("Accept-Encoding: " + accept + "\r\n")));
        final var received = responses(channel);
        assertEquals(1, received.size(), accept);
        final var response = received.getFirst();
        try {
          assertGzip(response, BODY);
        } finally {
          response.release();
        }
      } finally {
        channel.finishAndReleaseAll();
      }
    }
    for (final String accept : List.of("", "identity", "deflate", "br", "notgzip", "gzip-extra",
        "gzip;q=0", "gzip;q=0, *;q=1", "gzip;q=broken", "gzip;q=-1", "gzip;q=2",
        "gzip;q=0.0001", "gzip;q=NaN", "gzip;q=1e0", "*;q=0")) {
      final var channel = pipeline(HttpResponse.response("application/json", BODY));
      try {
        channel.writeInbound(ascii(request(accept.isEmpty() ? "" : "Accept-Encoding: " + accept + "\r\n")));
        final var received = responses(channel);
        assertEquals(1, received.size(), accept);
        final var response = received.getFirst();
        try {
          assertNull(response.headers().get(HttpHeaderNames.CONTENT_ENCODING), accept);
          assertArrayEquals(BODY.getBytes(StandardCharsets.UTF_8), bytes(response), accept);
          assertEquals(BODY.length(), response.headers().getInt(HttpHeaderNames.CONTENT_LENGTH), accept);
          assertTrue(response.headers().containsValue(HttpHeaderNames.VARY, "Accept-Encoding", true), accept);
        } finally {
          response.release();
        }
      } finally {
        channel.finishAndReleaseAll();
      }
    }
  }

  /// HTTP permits a response without Content-Type, and Netty's compressor is a streaming
  /// codec: a response head followed by separate content messages is a valid input. The
  /// adapter currently emits full responses, but extending this codec must preserve that
  /// inherited contract, including its chunked framing and final gzip trailer.
  @Test
  void aHeaderlessStreamingResponseIsGzipEncodedWithCompleteChunkedFraming() throws Exception {
    final var channel = new EmbeddedChannel(new HttpServerCodec(), new NettyContentCompressor());
    try {
      channel.writeInbound(ascii(request("Accept-Encoding: gzip\r\n")));
      Object inbound;
      while ((inbound = channel.readInbound()) != null) {
        ReferenceCountUtil.release(inbound);
      }
      final int split = BODY.length() / 2;
      channel.writeOutbound(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK),
          new DefaultHttpContent(Unpooled.copiedBuffer(BODY.substring(0, split), StandardCharsets.UTF_8)),
          new DefaultHttpContent(Unpooled.copiedBuffer(BODY.substring(split), StandardCharsets.UTF_8)),
          LastHttpContent.EMPTY_LAST_CONTENT);
      final String wire = wire(channel);
      final int end = wire.indexOf("\r\n\r\n");
      assertTrue(end > 0, "the response has a complete HTTP header block");
      final String head = wire.substring(0, end).toLowerCase(java.util.Locale.ROOT);
      assertTrue(head.contains("transfer-encoding: chunked"), head);
      assertFalse(head.contains("content-length:"), head);
      assertFalse(head.contains("content-type:"), head);
      final var decoder = new EmbeddedChannel(new HttpResponseDecoder(), new HttpObjectAggregator(1 << 20));
      try {
        decoder.writeInbound(Unpooled.copiedBuffer(wire, StandardCharsets.ISO_8859_1));
        final FullHttpResponse response = decoder.readInbound();
        assertTrue(response != null, "the complete chunked response decodes without waiting for connection close");
        try {
          assertEquals(200, response.status().code());
          assertEquals("gzip", response.headers().get(HttpHeaderNames.CONTENT_ENCODING));
          assertArrayEquals(BODY.getBytes(StandardCharsets.UTF_8), gunzip(bytes(response)));
          assertTrue(response.headers().containsValue(HttpHeaderNames.VARY, "Accept-Encoding", true));
          assertNull(decoder.readInbound(), "exactly one final response was encoded");
        } finally {
          response.release();
        }
      } finally {
        decoder.finishAndReleaseAll();
      }
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void repeatedAcceptEncodingFieldsAndExistingVaryValuesArePreserved() throws Exception {
    for (final String vary : List.of("Origin", "Origin, aCcEpT-EnCoDiNg", "*")) {
      final var channel = pipeline(HttpResponse.response("text/plain", BODY).withHeader("Vary", vary));
      try {
        channel.writeInbound(ascii(request("Origin: https://app.example\r\nAccept-Encoding: br\r\nAccept-Encoding: gzip\r\n")));
        final var received = responses(channel);
        assertEquals(1, received.size());
        final var response = received.getFirst();
        try {
          assertEquals("gzip", response.headers().get(HttpHeaderNames.CONTENT_ENCODING));
          assertArrayEquals(BODY.getBytes(StandardCharsets.UTF_8), gunzip(bytes(response)));
          assertEquals("https://app.example", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
          final var tokens = response.headers().getAll(HttpHeaderNames.VARY).stream()
              .flatMap(value -> java.util.Arrays.stream(value.split(",")))
              .map(String::strip).toList();
          if (vary.equals("*")) {
            assertEquals(List.of("*"), tokens);
          } else {
            assertTrue(tokens.contains("Origin"));
            assertEquals(1, tokens.stream().filter(token -> token.equalsIgnoreCase("Accept-Encoding")).count());
          }
        } finally {
          response.release();
        }
      } finally {
        channel.finishAndReleaseAll();
      }
    }
  }

  @Test
  void theJettyMinimumSizeIsAppliedToTheUncompressedBody() throws Exception {
    for (final int size : List.of(0, 31, 32)) {
      final String body = "a".repeat(size);
      final var channel = pipeline(HttpResponse.response("text/plain", body));
      try {
        channel.writeInbound(ascii(request("Accept-Encoding: gzip\r\n")));
        final var response = responses(channel).getFirst();
        try {
          if (size == 32) {
            assertGzip(response, body);
          } else {
            assertNull(response.headers().get(HttpHeaderNames.CONTENT_ENCODING));
            assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), bytes(response));
          }
        } finally {
          response.release();
        }
      } finally {
        channel.finishAndReleaseAll();
      }
    }
  }

  @Test
  void compressedMediaAndMultipartContentKeepTheirOriginalRepresentation() {
    for (final String type : List.of("image/png", "image/svg+xml", "audio/mpeg", "video/mp4", "font/woff2",
        "application/compress", "application/zip", "application/gzip", "application/x-bzip2",
        "application/brotli", "application/x-br", "application/x-xz", "application/x-rar-compressed",
        "application/vnd.bzip3", "application/zstd", "text/event-stream", "multipart/byteranges; boundary=abc",
        "IMAGE/PNG; charset=UTF-8", "TEXT/EVENT-STREAM \t; charset=UTF-8", "application/zip ; version=1")) {
      assertIdentity(HttpResponse.response(type, BODY));
    }
    assertIdentity(HttpResponse.response("text/plain", BODY).withHeader("Content-Range", "bytes 0-31/1024"));
    assertIdentity(HttpResponse.response("text/plain", BODY).withHeader("Content-Encoding", "br"));
    assertIdentity(HttpResponse.response("text/plain", BODY).withHeader("Content-Encoding", "identity"));
  }

  private static void assertIdentity(final HttpResponse applicationResponse) {
    final var channel = pipeline(applicationResponse);
    try {
      channel.writeInbound(ascii(request("Accept-Encoding: gzip\r\n")));
      final var response = responses(channel).getFirst();
      try {
        assertEquals(applicationResponse.headers().get("Content-Encoding"), response.headers().get(HttpHeaderNames.CONTENT_ENCODING));
        assertArrayEquals(applicationResponse.body(), bytes(response));
      } finally {
        response.release();
      }
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void errorsAndBodylessStatusesAreNotCompressed() {
    for (final int status : List.of(204, 205, 300, 304, 400, 402, 500)) {
      final var channel = pipeline(HttpResponse.response(status, "text/plain", BODY.getBytes(StandardCharsets.UTF_8)));
      try {
        channel.writeInbound(ascii(request("Accept-Encoding: gzip\r\n")));
        if (status == 204 || status == 304) {
          // Inspect the wire before the test decoder's aggregator adds its own length.
          final String wire = wire(channel).toLowerCase(java.util.Locale.ROOT);
          assertTrue(wire.startsWith("http/1.1 " + status), wire);
          assertFalse(wire.contains("content-encoding:"), wire);
          assertFalse(wire.contains("content-length:"), wire);
          assertFalse(wire.contains("transfer-encoding:"), wire);
          assertEquals(wire.length() - 4, wire.indexOf("\r\n\r\n"), "no body is encoded");
        } else {
          final var response = responses(channel).getFirst();
          try {
            assertEquals(status, response.status().code());
            assertNull(response.headers().get(HttpHeaderNames.CONTENT_ENCODING));
            if (status == 205) {
              assertEquals(0, response.content().readableBytes());
            }
          } finally {
            response.release();
          }
        }
      } finally {
        channel.finishAndReleaseAll();
      }
    }
  }

  @Test
  void compressionWeakensStrongValidatorsAndPreservesWeakAndIdentityValidators() {
    for (final String etag : List.of("\"version-1\"", "W/\"version-1\"")) {
      for (final String coding : List.of("gzip", "identity")) {
        final var channel = pipeline(HttpResponse.response("application/json", BODY).withHeader("ETag", etag));
        try {
          channel.writeInbound(ascii(request("Accept-Encoding: " + coding + "\r\n")));
          final var response = responses(channel).getFirst();
          try {
            assertEquals(coding.equals("gzip") ? "W/\"version-1\"" : etag, response.headers().get(HttpHeaderNames.ETAG));
          } finally {
            response.release();
          }
        } finally {
          channel.finishAndReleaseAll();
        }
      }
    }
  }

  @Test
  void anInterimContinueDoesNotConsumeTheFinalResponsesNegotiation() throws Exception {
    final var channel = pipeline(HttpResponse.response("application/json", BODY));
    try {
      channel.writeInbound(ascii("POST /body HTTP/1.1\r\nHost: h\r\nAccept-Encoding: gzip\r\n"
          + "Expect: 100-continue\r\nContent-Length: 2\r\n\r\n"));
      final var interim = responses(channel);
      assertEquals(1, interim.size());
      try {
        assertEquals(100, interim.getFirst().status().code());
        assertNull(interim.getFirst().headers().get(HttpHeaderNames.CONTENT_ENCODING));
      } finally {
        interim.forEach(FullHttpResponse::release);
      }
      channel.writeInbound(ascii("ok" + request("")));
      final var received = responses(channel);
      assertEquals(2, received.size());
      try {
        assertGzip(received.getFirst(), BODY);
        assertNull(received.getLast().headers().get(HttpHeaderNames.CONTENT_ENCODING));
        assertArrayEquals(BODY.getBytes(StandardCharsets.UTF_8), bytes(received.getLast()));
      } finally {
        received.forEach(FullHttpResponse::release);
      }
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void expectationRefusalsDoNotShiftTheFollowingRequestsNegotiation() throws Exception {
    final var channel = pipeline(HttpResponse.response("application/json", BODY));
    try {
      channel.writeInbound(ascii("POST /body HTTP/1.1\r\nHost: h\r\nAccept-Encoding: gzip\r\n"
          + "Expect: unsupported\r\nContent-Length: 8\r\n\r\n"
          + request("") + request("Accept-Encoding: gzip\r\n")));
      final var received = responses(channel);
      assertEquals(3, received.size());
      try {
        assertEquals(417, received.getFirst().status().code());
        assertEquals(0, received.getFirst().content().readableBytes());
        assertNull(received.getFirst().headers().get(HttpHeaderNames.CONTENT_ENCODING));
        assertNull(received.get(1).headers().get(HttpHeaderNames.CONTENT_ENCODING));
        assertArrayEquals(BODY.getBytes(StandardCharsets.UTF_8), bytes(received.get(1)));
        assertGzip(received.getLast(), BODY);
      } finally {
        received.forEach(FullHttpResponse::release);
      }
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void anOversizedExpectationFollowsTheCompressedResponseThenCloses() throws Exception {
    final var channel = pipeline(HttpResponse.response("application/json", BODY));
    try {
      channel.writeInbound(ascii(request("Accept-Encoding: gzip\r\n")
          + "POST /body HTTP/1.1\r\nHost: h\r\nAccept-Encoding: gzip\r\n"
          + "Expect: 100-continue\r\nContent-Length: 2048\r\n\r\n"));
      final var received = responses(channel);
      assertEquals(2, received.size());
      try {
        assertGzip(received.getFirst(), BODY);
        assertEquals(413, received.getLast().status().code());
        assertEquals(0, received.getLast().content().readableBytes());
        assertEquals("close", received.getLast().headers().get(HttpHeaderNames.CONNECTION));
        assertNull(received.getLast().headers().get(HttpHeaderNames.CONTENT_ENCODING));
        assertFalse(channel.isActive());
      } finally {
        received.forEach(FullHttpResponse::release);
      }
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void aHeadRefusalHasNoEncodedBody() {
    final var channel = pipeline(HttpResponse.response("application/json", BODY));
    try {
      channel.writeInbound(ascii("HEAD /body HTTP/1.1\r\nHost: h\r\nAccept-Encoding: gzip\r\n\r\n"));
      final String response = wire(channel);
      assertTrue(response.startsWith("HTTP/1.1 405"), response);
      assertEquals(response.length() - 4, response.indexOf("\r\n\r\n"), "HEAD emits headers only");
      assertFalse(response.toLowerCase(java.util.Locale.ROOT).contains("content-encoding:"));
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  private static final class DelayedWrites extends ChannelOutboundHandlerAdapter {
    private record Pending(ChannelHandlerContext context, Object message, ChannelPromise promise) { }
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();

    @Override
    public void write(final ChannelHandlerContext context, final Object message, final ChannelPromise promise) {
      pending.add(new Pending(context, message, promise));
    }

    void completeOne() {
      final var next = pending.remove();
      next.context().write(next.message(), next.promise());
      next.context().flush();
    }
  }

  @Test
  void aPipelinedResponseWaitsForEveryCompressedWriteIncludingTheTrailer() throws Exception {
    final var ran = new AtomicInteger();
    final var handler = NettyQueryHandler.createNonBlockingGetHandler(request -> {
      ran.incrementAndGet();
      return HttpResponse.response("application/json", BODY);
    });
    final var handlers = HandlerMap.createController(Map.of("/body", Map.of("GET", handler)), List.of());
    final var delayed = new DelayedWrites();
    final var channel = new EmbeddedChannel(delayed, new NettyChannelInitializer(handlers, Runnable::run, 1024,
        Duration.ofSeconds(30).toNanos(), () -> 1_000_000_000_000L));
    try {
      channel.writeInbound(ascii(request("Accept-Encoding: gzip\r\n") + request("")));
      assertEquals(1, ran.get());
      assertFalse(channel.config().isAutoRead());
      final int firstWrites = delayed.pending.size();
      assertTrue(firstWrites > 1, "compression produces a head and encoded content writes");
      for (int i = 1; i < firstWrites; ++i) {
        delayed.completeOne();
        assertEquals(1, ran.get(), "the next request waits for the last compressed write");
      }
      delayed.completeOne();
      assertEquals(2, ran.get(), "the final compressed write releases the next request");
      while (!delayed.pending.isEmpty()) {
        delayed.completeOne();
      }
      assertTrue(channel.config().isAutoRead());
      final var received = responses(channel);
      assertEquals(2, received.size());
      try {
        assertGzip(received.getFirst(), BODY);
        assertNull(received.getLast().headers().get(HttpHeaderNames.CONTENT_ENCODING));
        assertArrayEquals(BODY.getBytes(StandardCharsets.UTF_8), bytes(received.getLast()));
      } finally {
        received.forEach(FullHttpResponse::release);
      }
    } finally {
      while (!delayed.pending.isEmpty()) {
        delayed.completeOne();
      }
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void anUnsolicitedTransportFailureCanStillWriteItsFinalResponse() {
    final var channel = pipeline(HttpResponse.response("application/json", BODY));
    try {
      channel.pipeline().fireExceptionCaught(new IllegalStateException("transport failure before a request head"));
      final var received = responses(channel);
      assertEquals(1, received.size());
      final var response = received.getFirst();
      try {
        assertEquals(500, response.status().code());
        assertEquals("close", response.headers().get(HttpHeaderNames.CONNECTION));
        assertNull(response.headers().get(HttpHeaderNames.CONTENT_ENCODING));
        assertFalse(channel.isActive());
      } finally {
        response.release();
      }
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void aTransportFailureAfterACompletedCompressedExchangeStillGetsItsOwnFinalResponse() throws Exception {
    final var channel = pipeline(HttpResponse.response("application/json", BODY));
    try {
      channel.writeInbound(ascii(request("Accept-Encoding: gzip\r\n")));
      final var completed = responses(channel);
      try {
        assertEquals(1, completed.size());
        assertGzip(completed.getFirst(), BODY);
      } finally {
        completed.forEach(FullHttpResponse::release);
      }
      channel.pipeline().fireExceptionCaught(new IllegalStateException("transport failure between requests"));
      final var received = responses(channel);
      try {
        assertEquals(1, received.size());
        final var response = received.getFirst();
        assertEquals(500, response.status().code());
        assertEquals("close", response.headers().get(HttpHeaderNames.CONNECTION));
        assertNull(response.headers().get(HttpHeaderNames.CONTENT_ENCODING));
        assertFalse(channel.isActive());
      } finally {
        received.forEach(FullHttpResponse::release);
      }
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void compressionCanBeDisabled() {
    final var handler = NettyQueryHandler.createNonBlockingGetHandler(request -> HttpResponse.response("application/json", BODY));
    final var handlers = HandlerMap.createController(Map.of("/body", Map.of("GET", handler)), List.of());
    final var channel = new EmbeddedChannel(new NettyChannelInitializer(handlers, Runnable::run, 1024,
        Duration.ofSeconds(30).toNanos(), () -> 1_000_000_000_000L, false));
    try {
      channel.writeInbound(ascii(request("Accept-Encoding: gzip\r\n")));
      final var response = responses(channel).getFirst();
      try {
        assertNull(response.headers().get(HttpHeaderNames.CONTENT_ENCODING));
        assertNull(response.headers().get(HttpHeaderNames.VARY));
        assertArrayEquals(BODY.getBytes(StandardCharsets.UTF_8), bytes(response));
      } finally {
        response.release();
      }
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void gzipSurvivesARealHttp11RoundTrip() throws Exception {
    final int port;
    try (final var socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    final var builder = new NettyServerBuilder();
    builder.nonBlockingQueryHandler("/body", request -> HttpResponse.response("application/json", BODY));
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
         final var server = builder.createServer(executor, "127.0.0.1", port);
         final var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
      server.start();
      final var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/body"))
          .timeout(Duration.ofSeconds(2)).header("Accept-Encoding", "gzip").GET().build(),
          java.net.http.HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, response.statusCode());
      assertEquals("gzip", response.headers().firstValue("Content-Encoding").orElse(null));
      assertEquals(response.body().length, response.headers().firstValueAsLong("Content-Length").orElseThrow());
      assertArrayEquals(BODY.getBytes(StandardCharsets.UTF_8), gunzip(response.body()));
    }
  }
}
