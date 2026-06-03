package software.sava.http_servers.sava.x402;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;

import java.util.Base64;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

final class X402ModelTest {

  private static final PublicKey ASSET = PublicKey.fromBase58Encoded("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v");
  private static final PublicKey PAY_TO = PublicKey.fromBase58Encoded("2wKupLR9q6wXYppw8Gr2NvWxKBUqm4PPJKkQfoxHDBg4");
  private static final PublicKey FEE_PAYER = PublicKey.fromBase58Encoded("EwWqGE4ZFKLofuestmU4LDdK7XM1N4ALgdZccwYugwGd");

  @Test
  void paymentRequirementsRoundTrip() {
    final var reqs = new PaymentRequirements(
        X402.SCHEME_EXACT, X402.SOLANA_MAINNET, "1000", ASSET, PAY_TO, 60, FEE_PAYER, "pi_3abc123def456");
    final var json = new StringBuilder();
    reqs.appendTo(json);

    final var parsed = PaymentRequirements.parse(systems.comodal.jsoniter.JsonIterator.parse(json.toString()));
    assertEquals(reqs, parsed);
    assertEquals(1000L, parsed.amountAsLong());
  }

  @Test
  void paymentPayloadFromSpecJson() {
    final var json = """
        {
          "x402Version": 2,
          "resource": {
            "url": "https://example.com/weather",
            "description": "Access to protected content",
            "mimeType": "application/json"
          },
          "accepted": {
            "scheme": "exact",
            "network": "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp",
            "amount": "1000",
            "asset": "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            "payTo": "2wKupLR9q6wXYppw8Gr2NvWxKBUqm4PPJKkQfoxHDBg4",
            "maxTimeoutSeconds": 60,
            "extra": {
              "feePayer": "EwWqGE4ZFKLofuestmU4LDdK7XM1N4ALgdZccwYugwGd",
              "memo": "pi_3abc123def456"
            }
          },
          "payload": {
            "transaction": "AAAAAA=="
          }
        }""";

    final var payload = PaymentPayload.parse(json);
    assertEquals(2, payload.x402Version());
    assertEquals("https://example.com/weather", payload.resource().url());
    assertEquals(X402.SCHEME_EXACT, payload.accepted().scheme());
    assertEquals(ASSET, payload.accepted().asset());
    assertEquals(PAY_TO, payload.accepted().payTo());
    assertEquals(FEE_PAYER, payload.accepted().feePayer());
    assertEquals("pi_3abc123def456", payload.accepted().memo());
    assertEquals("AAAAAA==", payload.transaction());
    assertArrayEquals(new byte[]{0, 0, 0, 0}, payload.transactionBytes());
  }

  @Test
  void paymentPayloadFromBase64Header() {
    final var json = "{\"x402Version\":2,\"payload\":{\"transaction\":\"AAAA\"}}";
    final var header = Base64.getEncoder().encodeToString(json.getBytes(UTF_8));
    final var payload = PaymentPayload.fromBase64Header(header);
    assertEquals(2, payload.x402Version());
    assertEquals("AAAA", payload.transaction());
  }

  @Test
  void paymentRequiredSerializesAccepts() {
    final var reqs = new PaymentRequirements(
        X402.SCHEME_EXACT, X402.SOLANA_MAINNET, "1000", ASSET, PAY_TO, 60, FEE_PAYER, null);
    final var resource = new Resource("https://example.com/weather", "desc", "application/json");
    final var required = new PaymentRequired(X402.X402_VERSION, "X-PAYMENT header required", resource, List.of(reqs));

    final var parsed = PaymentRequired.parse(systems.comodal.jsoniter.JsonIterator.parse(required.toJson()));
    assertEquals(2, parsed.x402Version());
    assertEquals("X-PAYMENT header required", parsed.error());
    assertEquals(1, parsed.accepts().size());
    assertEquals(reqs, parsed.accepts().getFirst());
    assertEquals("https://example.com/weather", parsed.resource().url());
  }

  @Test
  void settlementResponseRoundTrip() {
    final var response = SettlementResponse.success("5sig", X402.SOLANA_MAINNET, FEE_PAYER);
    final var headerValue = response.toBase64Header();
    final var decoded = new String(Base64.getDecoder().decode(headerValue), UTF_8);
    final var parsed = SettlementResponse.parse(systems.comodal.jsoniter.JsonIterator.parse(decoded));
    assertTrue(parsed.success());
    assertEquals("5sig", parsed.transaction());
    assertEquals(FEE_PAYER, parsed.payer());
  }

  @Test
  void verifyResponseRoundTrip() {
    final var invalid = VerifyResponse.invalid(X402Errors.MINT_MISMATCH, PAY_TO);
    final var parsed = VerifyResponse.parse(systems.comodal.jsoniter.JsonIterator.parse(invalid.toJson()));
    assertFalse(parsed.isValid());
    assertEquals(X402Errors.MINT_MISMATCH, parsed.invalidReason());
    assertEquals(PAY_TO, parsed.payer());
  }
}
