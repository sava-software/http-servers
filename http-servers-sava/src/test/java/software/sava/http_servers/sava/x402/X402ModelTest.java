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
  void paymentRequirementsNullFieldsRoundTrip() {
    final var reqs = new PaymentRequirements(X402.SCHEME_EXACT, null, null, null, null, 0, null, null);
    final var json = new StringBuilder();
    reqs.appendTo(json);
    final var parsed = PaymentRequirements.parse(systems.comodal.jsoniter.JsonIterator.parse(json.toString()));
    assertEquals(reqs, parsed);
  }

  @Test
  void paymentRequirementsExactJsonWithoutMemo() {
    // a null memo must omit the field entirely, not write "memo":null
    final var reqs = new PaymentRequirements("exact", "n", "5", ASSET, PAY_TO, 9, FEE_PAYER, null);
    final var b = new StringBuilder();
    reqs.appendTo(b);
    assertEquals(
        "{\"scheme\":\"exact\",\"network\":\"n\",\"amount\":\"5\",\"asset\":\"" + ASSET.toBase58()
            + "\",\"payTo\":\"" + PAY_TO.toBase58() + "\",\"maxTimeoutSeconds\":9,\"extra\":{\"feePayer\":\""
            + FEE_PAYER.toBase58() + "\"}}",
        b.toString());
  }

  @Test
  void paymentRequirementsDecoyParse() {
    final var json = """
        {"pad":"!!not-a-key!!","scheme":"exact","network":"n","amount":"5","asset":"%s",\
        "payTo":"%s","maxTimeoutSeconds":9,\
        "extra":{"zzz":"!!not-a-key!!","feePayer":"%s","memo":"m","memo2":"WRONG"},"amount2":"WRONG"}"""
        .formatted(ASSET.toBase58(), PAY_TO.toBase58(), FEE_PAYER.toBase58());
    final var parsed = PaymentRequirements.parse(systems.comodal.jsoniter.JsonIterator.parse(json));
    assertEquals(new PaymentRequirements("exact", "n", "5", ASSET, PAY_TO, 9, FEE_PAYER, "m"), parsed);
  }

  @Test
  void paymentPayloadDecoyParse() {
    final var json = """
        {"pad":"x","x402Version":3,\
        "payload":{"pad":true,"transaction":"AAAA","transaction2":"WRONG"},"x402Version2":9}""";
    final var parsed = PaymentPayload.parse(json);
    assertEquals(3, parsed.x402Version());
    assertEquals("AAAA", parsed.transaction());
  }

  @Test
  void paymentRequiredTwoAcceptsRoundTrip() {
    final var reqs1 = new PaymentRequirements(
        X402.SCHEME_EXACT, X402.SOLANA_MAINNET, "1000", ASSET, PAY_TO, 60, FEE_PAYER, null);
    final var reqs2 = new PaymentRequirements(
        X402.SCHEME_EXACT, X402.SOLANA_MAINNET, "2000", ASSET, PAY_TO, 30, FEE_PAYER, "m");
    final var required = new PaymentRequired(X402.X402_VERSION, null, null, List.of(reqs1, reqs2));

    final var parsed = PaymentRequired.parse(
        systems.comodal.jsoniter.JsonIterator.parse(required.toJsonBytes()));
    assertEquals(List.of(reqs1, reqs2), parsed.accepts());
    assertNull(parsed.error());
    assertNull(parsed.resource());
  }

  @Test
  void paymentRequiredDecoyParse() {
    final var reqs = new PaymentRequirements(
        X402.SCHEME_EXACT, X402.SOLANA_MAINNET, "1000", ASSET, PAY_TO, 60, FEE_PAYER, null);
    final var json = """
        {"pad":"x","x402Version":2,"error":"e","accepts":[%s],"error2":"WRONG"}"""
        .formatted(jsonOf(reqs));
    final var parsed = PaymentRequired.parse(systems.comodal.jsoniter.JsonIterator.parse(json));
    assertEquals(2, parsed.x402Version());
    assertEquals("e", parsed.error());
    assertEquals(List.of(reqs), parsed.accepts());
  }

  private static String jsonOf(final PaymentRequirements reqs) {
    final var b = new StringBuilder();
    reqs.appendTo(b);
    return b.toString();
  }

  @Test
  void settlementResponseSuccessExactJson() {
    // exact form: a successful settlement must not carry an errorReason field at all
    final var response = SettlementResponse.success("5sig", "net", PAY_TO);
    assertEquals(
        "{\"success\":true,\"transaction\":\"5sig\",\"network\":\"net\",\"payer\":\"" + PAY_TO.toBase58() + "\"}",
        response.toJson());
  }

  @Test
  void settlementResponseNullFieldsRoundTrip() {
    final var response = SettlementResponse.success(null, null, null);
    final var parsed = SettlementResponse.parse(
        systems.comodal.jsoniter.JsonIterator.parse(response.toJsonBytes()));
    assertEquals(response, parsed);
  }

  @Test
  void verifyResponseNullPayerRoundTrip() {
    final var response = VerifyResponse.invalid(X402Errors.UNSUPPORTED_SCHEME, null);
    final var parsed = VerifyResponse.parse(systems.comodal.jsoniter.JsonIterator.parse(response.toJson()));
    assertEquals(response, parsed);
  }

  @Test
  void jsonStringEscapingExact() {
    // space (0x20) stays raw while 0x01 and 0x1f are unicode-escaped: pins the < 0x20 boundary
    final var resource = new Resource("u", "a b\u0001\u001Fc", "m");
    final var b = new StringBuilder();
    resource.appendTo(b);
    assertEquals("{\"url\":\"u\",\"description\":\"a b\\u0001\\u001fc\",\"mimeType\":\"m\"}", b.toString());
  }

  @Test
  void jsonStringEscapingRoundTrip() {
    final var nasty = "a\"b\\c\nd\re\tf\bg\fhi";
    final var resource = new Resource("https://example.com", nasty, "application/json");
    final var b = new StringBuilder();
    resource.appendTo(b);
    final var parsed = Resource.parse(systems.comodal.jsoniter.JsonIterator.parse(b.toString()));
    assertEquals(resource, parsed);
  }

  @Test
  void parsersSkipUnknownFieldsAndDecoys() {
    // a leading unknown field kills stop-iteration mutants; a trailing decoy of the same JSON
    // type with a different value kills always-match dispatch mutants
    final var resource = Resource.parse(systems.comodal.jsoniter.JsonIterator.parse(
        "{\"pad\":\"x\",\"url\":\"u\",\"description\":\"d\",\"mimeType\":\"m\",\"mimeType2\":\"WRONG\"}"));
    assertEquals(new Resource("u", "d", "m"), resource);

    final var verify = VerifyResponse.parse(systems.comodal.jsoniter.JsonIterator.parse(
        "{\"pad\":\"x\",\"isValid\":false,\"invalidReason\":\"r\",\"payer\":\"" + PAY_TO.toBase58()
            + "\",\"invalidReason2\":\"WRONG\"}"));
    assertEquals(VerifyResponse.invalid("r", PAY_TO), verify);

    final var settlement = SettlementResponse.parse(systems.comodal.jsoniter.JsonIterator.parse(
        "{\"pad\":\"x\",\"success\":true,\"errorReason\":\"e\",\"transaction\":\"t\",\"network\":\"n\",\"payer\":\""
            + PAY_TO.toBase58() + "\",\"network2\":\"WRONG\"}"));
    assertEquals(new SettlementResponse(true, "e", "t", "n", PAY_TO), settlement);
  }

  @Test
  void settlementResponseFailureRoundTrip() {
    final var failure = SettlementResponse.failure("insufficient_funds", "5sig", X402.SOLANA_MAINNET, PAY_TO);
    final var decoded = new String(Base64.getDecoder().decode(failure.toBase64Header()), UTF_8);
    final var parsed = SettlementResponse.parse(systems.comodal.jsoniter.JsonIterator.parse(decoded));
    assertEquals(failure, parsed);
    assertFalse(parsed.success());
    assertEquals("insufficient_funds", parsed.errorReason());
    assertEquals("5sig", parsed.transaction());
    assertEquals(X402.SOLANA_MAINNET, parsed.network());
  }

  @Test
  void verifyResponseJsonBytes() {
    final var response = VerifyResponse.invalid(X402Errors.MINT_MISMATCH, PAY_TO);
    assertArrayEquals(response.toJson().getBytes(UTF_8), response.toJsonBytes());
  }

  @Test
  void verifyResponseValidRoundTrip() {
    final var valid = VerifyResponse.valid(PAY_TO);
    final var parsed = VerifyResponse.parse(systems.comodal.jsoniter.JsonIterator.parse(valid.toJson()));
    assertEquals(valid, parsed);
    assertTrue(parsed.isValid());
    assertNull(parsed.invalidReason());
    assertEquals(PAY_TO, parsed.payer());
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
