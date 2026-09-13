package software.sava.http_servers.soak;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/// Raw HTTP/1.x over a plain socket, for the profiles `HttpClient` cannot express: pipelined
/// bursts, HTTP/1.0, malformed heads, refused expectations and silence. Every read is bounded
/// by the socket's `SO_TIMEOUT`, so a server that stops answering fails the worker with a
/// `SocketTimeoutException` instead of hanging it, and every framing defect in a response —
/// an unparsable status line, `Content-Length` or chunk size — is an `IOException` the worker
/// records rather than an unchecked exception that would kill it.
final class RawHttp {

  static final int CONNECT_TIMEOUT_MILLIS = 5_000;
  static final int READ_TIMEOUT_MILLIS = 10_000;
  private static final int MAX_HEAD = 64 << 10;

  /// One response: the status, its headers keyed by lower-cased name (first value wins), and
  /// the body as delimited by `Content-Length`, chunked framing, or the close.
  record Response(int status, Map<String, String> headers, byte[] body) {

    String header(final String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }

    String bodyText() {
      return new String(body, StandardCharsets.ISO_8859_1);
    }

    @Override
    public String toString() {
      return "HTTP " + status + ' ' + headers + " body[" + body.length + "]=" + abbreviate(bodyText());
    }
  }

  /// The peer closed where a response was expected: `reset` distinguishes an RST from an
  /// orderly EOF, `bytesRead` says how much of the response had arrived.
  static final class ClosedException extends IOException {

    final boolean reset;
    final int bytesRead;

    ClosedException(final String message, final boolean reset, final int bytesRead) {
      super(message);
      this.reset = reset;
      this.bytesRead = bytesRead;
    }
  }

  /// What a wait for the peer's close ended with.
  enum CloseOutcome {
    /// EOF or a reset: the peer closed.
    CLOSED,
    /// The peer sent a byte instead of closing.
    BYTE,
    /// The socket's `SO_TIMEOUT` elapsed with the connection still open and silent.
    TIMEOUT
  }

  static Socket connect(final String host, final int port, final int readTimeoutMillis) throws IOException {
    final var socket = new Socket();
    try {
      socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
      socket.setSoTimeout(readTimeoutMillis);
      socket.setTcpNoDelay(true);
      return socket;
    } catch (final IOException e) {
      socket.close();
      throw e;
    }
  }

  static byte[] ascii(final String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  /// Whether `e` is the peer resetting the connection (or our write hitting its reset).
  static boolean isReset(final IOException e) {
    if (!(e instanceof SocketException)) {
      return false;
    }
    final var message = e.getMessage();
    if (message == null) {
      return false;
    }
    final var lower = message.toLowerCase(Locale.ROOT);
    return lower.contains("reset") || lower.contains("broken pipe") || lower.contains("closed by peer");
  }

  /// Reads exactly one response off `in`, which should be buffered: the head up to and
  /// including its blank line, then the body the head announces. A `1xx`, `204` or `304` has
  /// no body; otherwise chunked framing, then `Content-Length`, then the close delimit it.
  static Response readResponse(final InputStream in) throws IOException {
    final var head = new ByteArrayOutputStream(256);
    int last4 = 0;
    while (last4 != 0x0D0A0D0A) {
      final int b;
      try {
        b = in.read();
      } catch (final SocketException e) {
        if (isReset(e)) {
          throw new ClosedException("reset before the response head completed after " + head.size() + " bytes: "
              + abbreviate(head.toString(StandardCharsets.ISO_8859_1)), true, head.size());
        }
        throw e;
      }
      if (b < 0) {
        throw new ClosedException("closed before the response head completed after " + head.size() + " bytes: "
            + abbreviate(head.toString(StandardCharsets.ISO_8859_1)), false, head.size());
      }
      head.write(b);
      last4 = (last4 << 8) | b;
      if (head.size() > MAX_HEAD) {
        throw new IOException("response head exceeds " + MAX_HEAD + " bytes");
      }
    }
    final var headText = head.toString(StandardCharsets.ISO_8859_1);
    final var lines = headText.split("\r\n");
    final int status = parseStatus(lines[0]);
    final var headers = new HashMap<String, String>();
    for (int i = 1; i < lines.length; ++i) {
      final var line = lines[i];
      final int colon = line.indexOf(':');
      if (colon > 0) {
        headers.putIfAbsent(line.substring(0, colon).strip().toLowerCase(Locale.ROOT), line.substring(colon + 1).strip());
      }
    }
    final byte[] body;
    try {
      if (status / 100 == 1 || status == 204 || status == 304) {
        body = new byte[0];
      } else if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
        body = readChunked(in);
      } else {
        final var contentLength = headers.get("content-length");
        if (contentLength == null) {
          body = in.readAllBytes();
        } else {
          final int length = parseContentLength(contentLength, headText);
          body = in.readNBytes(length);
          if (body.length != length) {
            throw new ClosedException("closed after " + body.length + " of " + length + " body bytes: " + abbreviate(headText),
                false, head.size() + body.length);
          }
        }
      }
    } catch (final SocketException e) {
      if (isReset(e)) {
        throw new ClosedException("reset while reading the body of: " + abbreviate(headText), true, head.size());
      }
      throw e;
    }
    return new Response(status, Map.copyOf(headers), body);
  }

  private static int parseStatus(final String statusLine) throws IOException {
    final int space = statusLine.indexOf(' ');
    if (space < 0 || statusLine.length() < space + 4 || !statusLine.startsWith("HTTP/")) {
      throw new IOException("unparsable status line: " + abbreviate(statusLine));
    }
    try {
      return Integer.parseInt(statusLine.substring(space + 1, space + 4));
    } catch (final NumberFormatException e) {
      throw new IOException("unparsable status line: " + abbreviate(statusLine), e);
    }
  }

  private static int parseContentLength(final String value, final String headText) throws IOException {
    final int length;
    try {
      length = Integer.parseInt(value.strip());
    } catch (final NumberFormatException e) {
      throw new IOException("unparsable Content-Length '" + abbreviate(value) + "' in: " + abbreviate(headText), e);
    }
    if (length < 0) {
      throw new IOException("negative Content-Length " + length + " in: " + abbreviate(headText));
    }
    return length;
  }

  private static byte[] readChunked(final InputStream in) throws IOException {
    final var body = new ByteArrayOutputStream();
    for (; ; ) {
      final var sizeLine = readLine(in);
      final int semicolon = sizeLine.indexOf(';');
      final var hex = (semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon)).strip();
      final int size;
      try {
        size = Integer.parseInt(hex, 16);
      } catch (final NumberFormatException e) {
        throw new IOException("unparsable chunk size line: " + abbreviate(sizeLine), e);
      }
      if (size < 0) {
        throw new IOException("negative chunk size: " + abbreviate(sizeLine));
      }
      if (size == 0) {
        while (!readLine(in).isEmpty()) {
          // trailers
        }
        return body.toByteArray();
      }
      final var chunk = in.readNBytes(size);
      if (chunk.length != size) {
        throw new ClosedException("closed inside a chunk after " + chunk.length + " of " + size + " bytes", false, body.size());
      }
      body.write(chunk);
      readLine(in);
    }
  }

  private static String readLine(final InputStream in) throws IOException {
    final var line = new ByteArrayOutputStream(32);
    for (; ; ) {
      final int b = in.read();
      if (b < 0) {
        throw new ClosedException("closed inside chunked framing", false, line.size());
      }
      if (b == '\n') {
        final var text = line.toString(StandardCharsets.ISO_8859_1);
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
      }
      line.write(b);
      if (line.size() > MAX_HEAD) {
        throw new IOException("chunk line exceeds " + MAX_HEAD + " bytes");
      }
    }
  }

  /// Waits, bounded by the socket's `SO_TIMEOUT`, for the peer to close, and says which of
  /// the three things happened: EOF or a reset, a byte the peer sent instead, or the timeout.
  static CloseOutcome closeOutcome(final InputStream in) throws IOException {
    try {
      return in.read() < 0 ? CloseOutcome.CLOSED : CloseOutcome.BYTE;
    } catch (final SocketTimeoutException e) {
      return CloseOutcome.TIMEOUT;
    } catch (final SocketException e) {
      if (isReset(e)) {
        return CloseOutcome.CLOSED;
      }
      throw e;
    }
  }

  /// [#closeOutcome] reduced to whether the peer closed.
  static boolean awaitClose(final InputStream in) throws IOException {
    return closeOutcome(in) == CloseOutcome.CLOSED;
  }

  static void closeQuietly(final Socket socket) {
    if (socket != null) {
      try {
        socket.close();
      } catch (final IOException ignored) {
        // nothing to do with a socket that will not close
      }
    }
  }

  static String abbreviate(final String text) {
    final var oneLine = text.replace("\r\n", "\\n").replace('\n', ' ');
    return oneLine.length() <= 96 ? oneLine : oneLine.substring(0, 96) + "...";
  }

  private RawHttp() {
  }
}
