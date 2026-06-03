package software.sava.http_servers.sava.x402;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.idl.clients.spl.associated_token.gen.AssociatedTokenPDAs;
import software.sava.idl.clients.spl.token.gen.TokenProgram;

import java.nio.charset.StandardCharsets;

import static software.sava.core.encoding.ByteUtil.getInt64LE;

/// Stateless verifier implementing the x402 facilitator {@code /verify} rules for the Solana
/// (SVM) {@code exact} scheme.
///
/// It decodes the client-provided, partially-signed transaction and enforces every {@code MUST}
/// rule from the scheme specification before a facilitator would sponsor and sign it:
///
/// 1. Instruction layout (3–6 instructions: ComputeBudget set-limit, ComputeBudget set-price,
///    TransferChecked, then only optional Lighthouse / Memo instructions).
/// 2. Fee-payer (facilitator) safety — the configured fee payer must not be an instruction account,
///    the transfer authority, or the source of funds.
/// 3. Compute-budget validity — correct programs/discriminators and a bounded compute-unit price.
/// 4. Transfer intent — SPL Token or Token-2022, destination equal to the ATA of {@code (payTo, asset)}.
/// 5. Amount — must exactly equal the required amount.
/// 6. Memo — when {@code extra.memo} is present, exactly one matching Memo instruction must exist.
public final class SvmExactVerifier {

  private static final int TRANSFER_CHECKED_DISCRIMINATOR = 12;

  private final SolanaAccounts accounts;

  public SvmExactVerifier(final SolanaAccounts accounts) {
    this.accounts = accounts;
  }

  public SvmExactVerifier() {
    this(SolanaAccounts.MAIN_NET);
  }

  /// Verify a full payment payload against the given requirements.
  public VerifyResponse verify(final PaymentPayload payload, final PaymentRequirements requirements) {
    if (payload == null || payload.transaction() == null) {
      return VerifyResponse.invalid(X402Errors.INVALID_PAYLOAD_TRANSACTION, null);
    }
    final byte[] txBytes;
    try {
      txBytes = payload.transactionBytes();
    } catch (final RuntimeException e) {
      return VerifyResponse.invalid(X402Errors.INVALID_PAYLOAD_TRANSACTION, null);
    }
    return verify(requirements, txBytes);
  }

  /// Verify the serialized transaction bytes against the given requirements.
  public VerifyResponse verify(final PaymentRequirements requirements, final byte[] txBytes) {
    if (requirements == null
        || !X402.SCHEME_EXACT.equals(requirements.scheme())
        || requirements.asset() == null
        || requirements.payTo() == null) {
      return VerifyResponse.invalid(X402Errors.UNSUPPORTED_SCHEME, null);
    }
    if (requirements.feePayer() == null) {
      return VerifyResponse.invalid(X402Errors.MISSING_FEE_PAYER, null);
    }

    final TransactionSkeleton skeleton;
    final Instruction[] instructions;
    try {
      skeleton = TransactionSkeleton.deserializeSkeleton(txBytes);
      // Address Lookup Tables would leave instruction accounts unresolved, which we cannot verify.
      if (skeleton.numIndexedAccounts() > 0) {
        return VerifyResponse.invalid(X402Errors.INVALID_PAYLOAD_TRANSACTION, null);
      }
      final AccountMeta[] accountMetas = skeleton.parseAccounts();
      instructions = skeleton.parseInstructions(accountMetas);
    } catch (final RuntimeException e) {
      return VerifyResponse.invalid(X402Errors.TRANSACTION_COULD_NOT_BE_DECODED, null);
    }

    // Rule 1: Instruction layout — 3 to 6 instructions.
    final int numInstructions = instructions.length;
    if (numInstructions < 3 || numInstructions > 6) {
      return VerifyResponse.invalid(X402Errors.TRANSACTION_INSTRUCTIONS_LENGTH, null);
    }

    // Rule 3: Compute budget validity.
    final var limitCheck = verifyComputeLimit(instructions[0]);
    if (limitCheck != null) {
      return limitCheck;
    }
    final var priceCheck = verifyComputePrice(instructions[1]);
    if (priceCheck != null) {
      return priceCheck;
    }

    // Rule 4/5/2: Transfer instruction.
    final var transferIx = instructions[2];
    final var transferProgram = transferIx.programId().publicKey();
    final boolean isToken = transferProgram.equals(accounts.tokenProgram());
    final boolean isToken2022 = transferProgram.equals(accounts.token2022Program());
    if (!isToken && !isToken2022) {
      return VerifyResponse.invalid(X402Errors.NO_TRANSFER_INSTRUCTION, null);
    }
    final var transferAccounts = transferIx.accounts();
    if (transferAccounts.size() < 4) {
      return VerifyResponse.invalid(X402Errors.NO_TRANSFER_INSTRUCTION, null);
    }

    final TokenProgram.TransferCheckedIxData transferData;
    try {
      transferData = TokenProgram.TransferCheckedIxData.read(transferIx.data(), transferIx.offset());
    } catch (final RuntimeException e) {
      return VerifyResponse.invalid(X402Errors.NO_TRANSFER_INSTRUCTION, null);
    }
    if (transferData == null || transferData.discriminator() != TRANSFER_CHECKED_DISCRIMINATOR) {
      return VerifyResponse.invalid(X402Errors.NO_TRANSFER_INSTRUCTION, null);
    }

    // TransferChecked accounts: [source, mint, destination, authority, ...].
    final var source = transferAccounts.getFirst().publicKey();
    final var mint = transferAccounts.get(1).publicKey();
    final var destination = transferAccounts.get(2).publicKey();
    final var authority = transferAccounts.get(3).publicKey();

    // payer reported back is the transfer authority (owner of the source funds).
    final var payer = authority;
    final var feePayer = requirements.feePayer();

    // Rule 2: Fee-payer safety.
    if (feePayer.equals(authority) || feePayer.equals(source)) {
      return VerifyResponse.invalid(X402Errors.FEE_PAYER_TRANSFERRING_FUNDS, payer);
    }
    for (final var instruction : instructions) {
      for (final var account : instruction.accounts()) {
        if (feePayer.equals(account.publicKey())) {
          return VerifyResponse.invalid(X402Errors.FEE_PAYER_TRANSFERRING_FUNDS, payer);
        }
      }
    }

    // Rule 4: Transfer intent and destination.
    if (!mint.equals(requirements.asset())) {
      return VerifyResponse.invalid(X402Errors.MINT_MISMATCH, payer);
    }
    final var expectedDestination = AssociatedTokenPDAs.associatedTokenPDA(
        accounts.associatedTokenAccountProgram(),
        requirements.payTo(),
        transferProgram,
        requirements.asset()
    ).publicKey();
    if (!destination.equals(expectedDestination)) {
      return VerifyResponse.invalid(X402Errors.RECIPIENT_MISMATCH, payer);
    }

    // Rule 6: Amount must match exactly.
    final long requiredAmount;
    try {
      requiredAmount = requirements.amountAsLong();
    } catch (final RuntimeException e) {
      return VerifyResponse.invalid(X402Errors.AMOUNT_INSUFFICIENT, payer);
    }
    if (transferData.amount() != requiredAmount) {
      return VerifyResponse.invalid(X402Errors.AMOUNT_INSUFFICIENT, payer);
    }

    // Rule 1 (cont.) / 6: Optional instructions and memo.
    final var optionalCheck = verifyOptionalInstructions(instructions, requirements, payer);
    if (optionalCheck != null) {
      return optionalCheck;
    }

    return VerifyResponse.valid(payer);
  }

  private VerifyResponse verifyComputeLimit(final Instruction instruction) {
    if (!instruction.programId().publicKey().equals(accounts.computeBudgetProgram())) {
      return VerifyResponse.invalid(X402Errors.COMPUTE_LIMIT_INSTRUCTION, null);
    }
    if (instruction.len() < 1 || discriminatorByte(instruction) != X402.COMPUTE_BUDGET_SET_LIMIT) {
      return VerifyResponse.invalid(X402Errors.COMPUTE_LIMIT_INSTRUCTION, null);
    }
    return null;
  }

  private VerifyResponse verifyComputePrice(final Instruction instruction) {
    if (!instruction.programId().publicKey().equals(accounts.computeBudgetProgram())) {
      return VerifyResponse.invalid(X402Errors.COMPUTE_PRICE_INSTRUCTION, null);
    }
    if (instruction.len() < 9 || discriminatorByte(instruction) != X402.COMPUTE_BUDGET_SET_PRICE) {
      return VerifyResponse.invalid(X402Errors.COMPUTE_PRICE_INSTRUCTION, null);
    }
    final long microLamports = getInt64LE(instruction.data(), instruction.offset() + 1);
    // Treat as unsigned: any negative (high-bit set) value is far above the cap.
    if (microLamports < 0 || microLamports > X402.MAX_COMPUTE_UNIT_PRICE_MICRO_LAMPORTS) {
      return VerifyResponse.invalid(X402Errors.COMPUTE_PRICE_INSTRUCTION_TOO_HIGH, null);
    }
    return null;
  }

  private VerifyResponse verifyOptionalInstructions(final Instruction[] instructions,
                                                    final PaymentRequirements requirements,
                                                    final PublicKey payer) {
    final var memoProgram = accounts.memoProgram();
    final var memoProgramV2 = X402.MEMO_PROGRAM_V2;
    final String[] unknownReasons = {
        X402Errors.UNKNOWN_FOURTH_INSTRUCTION,
        X402Errors.UNKNOWN_FIFTH_INSTRUCTION,
        X402Errors.UNKNOWN_SIXTH_INSTRUCTION
    };

    int memoCount = 0;
    String memoData = null;
    for (int i = 3; i < instructions.length; ++i) {
      final var programId = instructions[i].programId().publicKey();
      final boolean isMemo = programId.equals(memoProgram) || programId.equals(memoProgramV2);
      if (isMemo) {
        ++memoCount;
        memoData = new String(instructions[i].copyData(), StandardCharsets.UTF_8);
        continue;
      } else if (programId.equals(X402.LIGHTHOUSE_PROGRAM)) {
        continue;
      }
      final int reasonIndex = Math.min(i - 3, unknownReasons.length - 1);
      return VerifyResponse.invalid(unknownReasons[reasonIndex], payer);
    }

    final var expectedMemo = requirements.memo();
    if (expectedMemo != null && !expectedMemo.isEmpty()) {
      if (memoCount != 1) {
        return VerifyResponse.invalid(X402Errors.MEMO_COUNT, payer);
      }
      if (!expectedMemo.equals(memoData)) {
        return VerifyResponse.invalid(X402Errors.MEMO_MISMATCH, payer);
      }
    }
    return null;
  }

  private static int discriminatorByte(final Instruction instruction) {
    return instruction.data()[instruction.offset()] & 0xFF;
  }
}
