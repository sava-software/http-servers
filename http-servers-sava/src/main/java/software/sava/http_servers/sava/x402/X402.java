package software.sava.http_servers.sava.x402;

import software.sava.core.accounts.PublicKey;

/// Shared constants for the x402 protocol as implemented for the Solana (SVM) {@code exact} scheme.
///
/// See the protocol specification at
/// {@code specs/schemes/exact/scheme_exact_svm.md} of the x402 repository.
public final class X402 {

  /// The {@code exact} scheme identifier.
  public static final String SCHEME_EXACT = "exact";

  /// The x402 protocol version supported by this module.
  public static final int X402_VERSION = 2;

  /// HTTP request header carrying the Base64 encoded {@code PaymentPayload}.
  public static final String PAYMENT_HEADER = "X-PAYMENT";

  /// HTTP response header carrying the Base64 encoded {@code SettlementResponse}.
  public static final String PAYMENT_RESPONSE_HEADER = "X-PAYMENT-RESPONSE";

  /// CAIP-2 prefix identifying a Solana network, e.g. {@code solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp}.
  public static final String SOLANA_NETWORK_PREFIX = "solana:";

  /// Solana mainnet-beta CAIP-2 network identifier (genesis hash prefix).
  public static final String SOLANA_MAINNET = "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp";

  /// Lighthouse program injected by some wallets (Phantom, Solflare) as a user-protection assertion.
  public static final PublicKey LIGHTHOUSE_PROGRAM =
      PublicKey.fromBase58Encoded("L2TExMFKdjpN9kozasaurPirfHy9P8sbXoAN1qA3S95");

  /// SPL Memo program (v2), used to attach a payment reference / uniqueness nonce.
  public static final PublicKey MEMO_PROGRAM_V2 =
      PublicKey.fromBase58Encoded("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr");

  /// Compute Budget {@code SetComputeUnitLimit} instruction discriminator.
  public static final int COMPUTE_BUDGET_SET_LIMIT = 2;

  /// Compute Budget {@code SetComputeUnitPrice} instruction discriminator.
  public static final int COMPUTE_BUDGET_SET_PRICE = 3;

  /// Maximum allowed compute unit price, in micro-lamports, to prevent gas abuse (5 lamports / CU).
  public static final long MAX_COMPUTE_UNIT_PRICE_MICRO_LAMPORTS = 5_000_000L;

  /// Maximum byte length of a memo, per the spec.
  public static final int MAX_MEMO_BYTES = 256;

  private X402() {
  }
}
