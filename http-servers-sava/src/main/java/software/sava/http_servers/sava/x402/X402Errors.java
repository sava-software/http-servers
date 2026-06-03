package software.sava.http_servers.sava.x402;

/// Canonical x402 {@code invalidReason} / error strings for the SVM {@code exact} scheme.
///
/// These mirror the reference implementation so that responses produced by this module are
/// interoperable with existing x402 clients and facilitators.
public final class X402Errors {

  public static final String UNSUPPORTED_SCHEME = "invalid_exact_solana_unsupported_scheme";
  public static final String NETWORK_MISMATCH = "invalid_exact_solana_network_mismatch";
  public static final String INVALID_EXTRA_FIELD = "invalid_exact_solana_extra_field";
  public static final String MISSING_FEE_PAYER = "invalid_exact_solana_payload_missing_fee_payer";
  public static final String FEE_PAYER_NOT_MANAGED = "invalid_exact_solana_fee_payer_not_managed_by_facilitator";
  public static final String INVALID_PAYLOAD_TRANSACTION = "invalid_exact_solana_payload_transaction";
  public static final String TRANSACTION_COULD_NOT_BE_DECODED = "invalid_exact_solana_payload_transaction_could_not_be_decoded";
  public static final String TRANSACTION_INSTRUCTIONS_LENGTH = "invalid_exact_solana_payload_transaction_instructions_length";
  public static final String UNKNOWN_FOURTH_INSTRUCTION = "invalid_exact_solana_payload_unknown_fourth_instruction";
  public static final String UNKNOWN_FIFTH_INSTRUCTION = "invalid_exact_solana_payload_unknown_fifth_instruction";
  public static final String UNKNOWN_SIXTH_INSTRUCTION = "invalid_exact_solana_payload_unknown_sixth_instruction";
  public static final String COMPUTE_LIMIT_INSTRUCTION = "invalid_exact_solana_payload_transaction_instructions_compute_limit_instruction";
  public static final String COMPUTE_PRICE_INSTRUCTION = "invalid_exact_solana_payload_transaction_instructions_compute_price_instruction";
  public static final String COMPUTE_PRICE_INSTRUCTION_TOO_HIGH = "invalid_exact_solana_payload_transaction_instructions_compute_price_instruction_too_high";
  public static final String NO_TRANSFER_INSTRUCTION = "invalid_exact_solana_payload_no_transfer_instruction";
  public static final String FEE_PAYER_TRANSFERRING_FUNDS = "invalid_exact_solana_payload_transaction_fee_payer_transferring_funds";
  public static final String MINT_MISMATCH = "invalid_exact_solana_payload_mint_mismatch";
  public static final String RECIPIENT_MISMATCH = "invalid_exact_solana_payload_recipient_mismatch";
  public static final String AMOUNT_INSUFFICIENT = "invalid_exact_solana_payload_amount_insufficient";
  public static final String INVALID_FEE_PAYER = "invalid_exact_solana_invalid_fee_payer";
  public static final String MEMO_MISMATCH = "invalid_exact_solana_payload_memo_mismatch";
  public static final String MEMO_COUNT = "invalid_exact_solana_payload_memo_count";

  // Settle errors
  public static final String VERIFICATION_FAILED = "invalid_exact_solana_verification_failed";
  public static final String FEE_PAYER_MISMATCH = "invalid_exact_solana_fee_payer_mismatch";
  public static final String TRANSACTION_FAILED = "invalid_exact_solana_transaction_failed";
  public static final String TRANSACTION_CONFIRMATION_FAILED = "invalid_exact_solana_transaction_confirmation_failed";
  public static final String DUPLICATE_SETTLEMENT = "duplicate_settlement";

  private X402Errors() {
  }
}
