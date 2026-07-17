package software.sava.http_servers.sava.x402;

import software.sava.http_servers.core.request.Request;
import software.sava.http_servers.core.response.HttpResponse;
import systems.comodal.jsoniter.JsonIterator;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/// Jazzer entry point for the x402 request-facing parsers and the payment gate end to end.
///
/// The {@code X-PAYMENT} header is the module's untrusted-input surface: Base64 → JSON → model
/// records → transaction bytes. The malformed-input contract is "garbage in → RuntimeException
/// out" for the parsers, and total for [X402Gate#httpResponse] — the gate must answer every
/// request with a 402 or the protected response, never a throwable. When a parse succeeds the
/// direct-JSON and Base64-header paths must agree.
///
/// Seeded from a full valid payment payload (spec-shaped JSON around a Base64 transaction that
/// passes verification) — the gate's success path is unreachable from a from-scratch mutator.
///
/// Deliberately free of Jazzer imports so it compiles with the regular test sources.
///
/// Run with `./gradlew :http-servers-sava:fuzzX402Payload [-PmaxFuzzTime=<seconds>]`.
public final class X402PayloadFuzz {

  private static final Resource RESOURCE =
      new Resource("https://example.com/protected", "fuzz resource", "application/json");

  private static final X402Gate GATE = new X402Gate(
      request -> HttpResponse.EMPTY,
      SvmExactVerifyFuzz.REQUIREMENTS,
      RESOURCE,
      new SvmExactVerifier()
  );

  public static void fuzzerTestOneInput(final byte[] data) {
    final var json = new String(data, StandardCharsets.UTF_8);

    PaymentPayload payload = null;
    try {
      payload = PaymentPayload.parse(json);
    } catch (final RuntimeException tolerated) {
    }
    if (payload != null) {
      // the header path decodes the same JSON and must agree with the direct parse
      final var utf8 = json.getBytes(StandardCharsets.UTF_8);
      final var header = Base64.getEncoder().encodeToString(utf8);
      final PaymentPayload viaHeader;
      try {
        viaHeader = PaymentPayload.fromBase64Header(header);
      } catch (final RuntimeException e) {
        throw new AssertionError("header path rejected directly parseable JSON: " + json, e);
      }
      if (!viaHeader.equals(payload)) {
        throw new AssertionError("header parse disagrees with direct parse for: " + json);
      }
      if (payload.transaction() != null) {
        try {
          payload.transactionBytes();
        } catch (final RuntimeException tolerated) {
          // a non-base64 transaction string is rejected here, in contract
        }
      }
    }

    // every model parser shares the malformed-input contract: RuntimeException out
    tolerate(() -> PaymentRequirements.parse(JsonIterator.parse(json)));
    tolerate(() -> PaymentRequired.parse(JsonIterator.parse(json)));
    tolerate(() -> SettlementResponse.parse(JsonIterator.parse(json)));
    tolerate(() -> VerifyResponse.parse(JsonIterator.parse(json)));
    tolerate(() -> Resource.parse(JsonIterator.parse(json)));

    // end to end: raw fuzz bytes as the header value (exercises Base64 rejection), then the
    // canonical encoding of the same bytes (exercises the JSON and verify paths behind it)
    checkGate(new String(data, StandardCharsets.ISO_8859_1));
    checkGate(Base64.getEncoder().encodeToString(data));
  }

  private static void checkGate(final String headerValue) {
    final var response = GATE.httpResponse(new FuzzRequest(headerValue));
    if (response == null) {
      throw new AssertionError("gate returned null");
    }
    final int status = response.statusCode();
    if (status == 200) {
      if (response.headers().get(X402.PAYMENT_RESPONSE_HEADER) == null) {
        throw new AssertionError("gate served the resource without a settlement header");
      }
    } else if (status != 402) {
      throw new AssertionError("gate answered with unexpected status " + status);
    }
  }

  private static void tolerate(final Runnable parse) {
    try {
      parse.run();
    } catch (final RuntimeException tolerated) {
    }
  }

  private record FuzzRequest(String paymentHeader) implements Request {

    @Override
    public String method() {
      return "GET";
    }

    @Override
    public String path() {
      return "/protected";
    }

    @Override
    public String query() {
      return null;
    }

    @Override
    public String header(final String name) {
      return X402.PAYMENT_HEADER.equals(name) ? paymentHeader : null;
    }

    @Override
    public byte[] body() {
      return null;
    }
  }

  private X402PayloadFuzz() {
  }
}
