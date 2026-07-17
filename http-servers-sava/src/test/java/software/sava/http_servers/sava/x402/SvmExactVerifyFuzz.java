package software.sava.http_servers.sava.x402;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import systems.comodal.jsoniter.JsonIterator;

import java.util.Arrays;

/// Jazzer entry point for the facilitator {@code /verify} rules, the security boundary of the
/// x402 module: [SvmExactVerifier#verify] receives attacker-controlled transaction bytes and
/// {@link X402Gate} calls it with no try/catch, so its contract is total — any input must
/// produce a [VerifyResponse], never a throwable. A transaction the verifier wrongly accepts
/// is a payment the facilitator would sponsor, so every accepted input must also satisfy the
/// response invariants (a payer that is not the fee payer, no invalid reason).
///
/// Seeded from valid payment transactions (with and without memo) built by the same helpers as
/// [SvmExactVerifierTest] — header/offset/length agreement is unreachable from a from-scratch
/// mutator, and without it only the early decode rejections are exercised.
///
/// Deliberately free of Jazzer imports so it compiles with the regular test sources.
///
/// Run with `./gradlew :http-servers-sava:fuzzSvmVerify [-PmaxFuzzTime=<seconds>]`.
public final class SvmExactVerifyFuzz {

  private static final SolanaAccounts ACCOUNTS = SolanaAccounts.MAIN_NET;
  private static final SvmExactVerifier VERIFIER = new SvmExactVerifier(ACCOUNTS);

  private static PublicKey key(final int seed) {
    final byte[] b = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    Arrays.fill(b, (byte) seed);
    return PublicKey.createPubKey(b);
  }

  // mirrors SvmExactVerifierTest so the committed seed transactions verify as valid
  static final PublicKey FEE_PAYER = key(1);
  static final PublicKey MINT = key(4);
  static final PublicKey PAY_TO = key(5);
  static final String AMOUNT = "1000";
  static final String MEMO = "invoice-123";

  static final PaymentRequirements REQUIREMENTS = new PaymentRequirements(
      X402.SCHEME_EXACT, X402.SOLANA_MAINNET, AMOUNT, MINT, PAY_TO, 60, FEE_PAYER, null);
  static final PaymentRequirements MEMO_REQUIREMENTS = new PaymentRequirements(
      X402.SCHEME_EXACT, X402.SOLANA_MAINNET, AMOUNT, MINT, PAY_TO, 60, FEE_PAYER, MEMO);

  public static void fuzzerTestOneInput(final byte[] data) {
    check(VERIFIER.verify(REQUIREMENTS, data));
    check(VERIFIER.verify(MEMO_REQUIREMENTS, data));
  }

  private static void check(final VerifyResponse response) {
    if (response == null) {
      throw new AssertionError("verify returned null");
    }
    if (response.isValid()) {
      if (response.invalidReason() != null) {
        throw new AssertionError("valid response carries invalidReason: " + response.invalidReason());
      }
      if (response.payer() == null) {
        throw new AssertionError("valid response without payer");
      }
      if (FEE_PAYER.equals(response.payer())) {
        throw new AssertionError("fee payer accepted as the paying authority");
      }
    } else if (response.invalidReason() == null) {
      throw new AssertionError("invalid response without a reason");
    }
    // the response must survive its own wire format
    final var parsed = VerifyResponse.parse(JsonIterator.parse(response.toJson()));
    if (!parsed.equals(response)) {
      throw new AssertionError("toJson/parse round trip changed the response: " + response.toJson());
    }
  }

  private SvmExactVerifyFuzz() {
  }
}
