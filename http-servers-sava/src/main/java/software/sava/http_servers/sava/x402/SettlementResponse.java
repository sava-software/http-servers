package software.sava.http_servers.sava.x402;

import software.sava.core.accounts.PublicKey;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static software.sava.rpc.json.PublicKeyEncoding.parseBase58Encoded;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// The response returned by a facilitator {@code /settle} endpoint for the Solana {@code exact} scheme.
///
/// @param success     whether the transaction was successfully submitted and confirmed on-chain.
/// @param errorReason a machine readable reason code (see {@link X402Errors}) when unsuccessful, otherwise {@code null}.
/// @param transaction the Base58 encoded transaction signature, when available.
/// @param network     the CAIP-2 network identifier the transaction was submitted to.
/// @param payer       the address that paid for the resource, when known.
public record SettlementResponse(boolean success,
                                 String errorReason,
                                 String transaction,
                                 String network,
                                 PublicKey payer) {

  public static SettlementResponse success(final String signature, final String network, final PublicKey payer) {
    return new SettlementResponse(true, null, signature, network, payer);
  }

  public static SettlementResponse failure(final String reason, final String network, final PublicKey payer) {
    return new SettlementResponse(false, reason, null, network, payer);
  }

  /// A failure that still carries the on-chain transaction signature, e.g. when the transaction was
  /// submitted but could not be confirmed within the allotted time.
  public static SettlementResponse failure(final String reason,
                                           final String signature,
                                           final String network,
                                           final PublicKey payer) {
    return new SettlementResponse(false, reason, signature, network, payer);
  }

  public String toJson() {
    final var b = new StringBuilder(160);
    b.append("{\"success\":").append(success);
    if (errorReason != null) {
      b.append(',');
      JsonWrite.appendString(b, "errorReason", errorReason);
    }
    b.append(',');
    JsonWrite.appendString(b, "transaction", transaction);
    b.append(',');
    JsonWrite.appendString(b, "network", network);
    b.append(',');
    JsonWrite.appendString(b, "payer", payer == null ? null : payer.toBase58());
    b.append('}');
    return b.toString();
  }

  public byte[] toJsonBytes() {
    return toJson().getBytes(StandardCharsets.UTF_8);
  }

  /// @return the JSON serialization Base64 encoded for the {@code X-PAYMENT-RESPONSE} header.
  public String toBase64Header() {
    return Base64.getEncoder().encodeToString(toJsonBytes());
  }

  public static SettlementResponse parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private boolean success;
    private String errorReason;
    private String transaction;
    private String network;
    private PublicKey payer;

    private Parser() {
    }

    private SettlementResponse create() {
      return new SettlementResponse(success, errorReason, transaction, network, payer);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("success", buf, offset, len)) {
        success = ji.readBoolean();
      } else if (fieldEquals("errorReason", buf, offset, len)) {
        errorReason = ji.readString();
      } else if (fieldEquals("transaction", buf, offset, len)) {
        transaction = ji.readString();
      } else if (fieldEquals("network", buf, offset, len)) {
        network = ji.readString();
      } else if (fieldEquals("payer", buf, offset, len)) {
        payer = parseBase58Encoded(ji);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
