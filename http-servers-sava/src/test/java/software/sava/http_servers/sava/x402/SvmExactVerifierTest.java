package software.sava.http_servers.sava.x402;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.idl.clients.spl.associated_token.gen.AssociatedTokenPDAs;
import software.sava.idl.clients.spl.token.gen.TokenProgram;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.encoding.ByteUtil.putInt32LE;
import static software.sava.core.encoding.ByteUtil.putInt64LE;

final class SvmExactVerifierTest {

  private static final SolanaAccounts ACCOUNTS = SolanaAccounts.MAIN_NET;
  private static final SvmExactVerifier VERIFIER = new SvmExactVerifier(ACCOUNTS);

  private static final int DECIMALS = 6;
  private static final long AMOUNT = 1_000L;

  private static final PublicKey FEE_PAYER = key(1);
  private static final PublicKey AUTHORITY = key(2);
  private static final PublicKey SOURCE_ATA = key(3);
  private static final PublicKey MINT = key(4);
  private static final PublicKey PAY_TO = key(5);

  private static PublicKey key(final int seed) {
    final byte[] b = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    Arrays.fill(b, (byte) seed);
    return PublicKey.createPubKey(b);
  }

  private static PublicKey destinationAta() {
    return AssociatedTokenPDAs.associatedTokenPDA(
        ACCOUNTS.associatedTokenAccountProgram(), PAY_TO, ACCOUNTS.tokenProgram(), MINT).publicKey();
  }

  private static Instruction computeLimit(final int units) {
    final byte[] data = new byte[5];
    data[0] = (byte) X402.COMPUTE_BUDGET_SET_LIMIT;
    putInt32LE(data, 1, units);
    return Instruction.createInstruction(ACCOUNTS.computeBudgetProgram(), List.of(), data);
  }

  private static Instruction computePrice(final long microLamports) {
    final byte[] data = new byte[9];
    data[0] = (byte) X402.COMPUTE_BUDGET_SET_PRICE;
    putInt64LE(data, 1, microLamports);
    return Instruction.createInstruction(ACCOUNTS.computeBudgetProgram(), List.of(), data);
  }

  private static Instruction transfer(final PublicKey source,
                                      final PublicKey mint,
                                      final PublicKey destination,
                                      final PublicKey authority,
                                      final long amount) {
    return TokenProgram.transferChecked(
        ACCOUNTS.invokedTokenProgram(), source, mint, destination, authority, amount, DECIMALS);
  }

  private static Instruction memo(final String value) {
    return Instruction.createInstruction(
        X402.MEMO_PROGRAM_V2, List.of(), value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] serialize(final List<Instruction> instructions) {
    final Transaction tx = Transaction.createTx(FEE_PAYER, instructions);
    return tx.serialized();
  }

  private static PaymentRequirements requirements(final String memo) {
    return new PaymentRequirements(
        X402.SCHEME_EXACT, X402.SOLANA_MAINNET, Long.toString(AMOUNT),
        MINT, PAY_TO, 60, FEE_PAYER, memo);
  }

  private static List<Instruction> validInstructions() {
    final var list = new ArrayList<Instruction>();
    list.add(computeLimit(200_000));
    list.add(computePrice(1_000));
    list.add(transfer(SOURCE_ATA, MINT, destinationAta(), AUTHORITY, AMOUNT));
    return list;
  }

  @Test
  void validPayment() {
    final var resp = VERIFIER.verify(requirements(null), serialize(validInstructions()));
    assertTrue(resp.isValid(), () -> "expected valid but got: " + resp.invalidReason());
    assertNull(resp.invalidReason());
    assertEquals(AUTHORITY, resp.payer());
  }

  @Test
  void validPaymentWithMemo() {
    final var ixs = validInstructions();
    ixs.add(memo("invoice-123"));
    final var resp = VERIFIER.verify(requirements("invoice-123"), serialize(ixs));
    assertTrue(resp.isValid(), () -> "got: " + resp.invalidReason());
  }

  @Test
  void tooFewInstructions() {
    final var ixs = new ArrayList<Instruction>();
    ixs.add(computeLimit(200_000));
    ixs.add(computePrice(1_000));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.TRANSACTION_INSTRUCTIONS_LENGTH, resp.invalidReason());
  }

  @Test
  void tooManyInstructions() {
    final var ixs = validInstructions();
    ixs.add(memo("a"));
    ixs.add(memo("b"));
    ixs.add(memo("c"));
    ixs.add(memo("d"));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.TRANSACTION_INSTRUCTIONS_LENGTH, resp.invalidReason());
  }

  @Test
  void computePriceTooHigh() {
    final var ixs = validInstructions();
    ixs.set(1, computePrice(X402.MAX_COMPUTE_UNIT_PRICE_MICRO_LAMPORTS + 1));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.COMPUTE_PRICE_INSTRUCTION_TOO_HIGH, resp.invalidReason());
  }

  @Test
  void wrongComputeBudgetOrder() {
    final var ixs = validInstructions();
    // Swap limit and price so instruction 0 is not SetComputeUnitLimit.
    final var first = ixs.get(0);
    ixs.set(0, ixs.get(1));
    ixs.set(1, first);
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.COMPUTE_LIMIT_INSTRUCTION, resp.invalidReason());
  }

  @Test
  void mintMismatch() {
    final var ixs = validInstructions();
    final var wrongMint = key(40);
    ixs.set(2, transfer(SOURCE_ATA, wrongMint, destinationAta(), AUTHORITY, AMOUNT));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.MINT_MISMATCH, resp.invalidReason());
  }

  @Test
  void recipientMismatch() {
    final var ixs = validInstructions();
    final var wrongDest = key(50);
    ixs.set(2, transfer(SOURCE_ATA, MINT, wrongDest, AUTHORITY, AMOUNT));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.RECIPIENT_MISMATCH, resp.invalidReason());
  }

  @Test
  void amountMismatch() {
    final var ixs = validInstructions();
    ixs.set(2, transfer(SOURCE_ATA, MINT, destinationAta(), AUTHORITY, AMOUNT + 1));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.AMOUNT_INSUFFICIENT, resp.invalidReason());
  }

  @Test
  void feePayerIsAuthority() {
    final var ixs = validInstructions();
    // Authority equals the configured fee payer -> facilitator would sign away its own funds.
    ixs.set(2, transfer(SOURCE_ATA, MINT, destinationAta(), FEE_PAYER, AMOUNT));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.FEE_PAYER_TRANSFERRING_FUNDS, resp.invalidReason());
  }

  @Test
  void unknownOptionalInstruction() {
    final var ixs = validInstructions();
    final var unknownProgram = key(60);
    ixs.add(Instruction.createInstruction(unknownProgram, List.of(), new byte[]{1, 2, 3}));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.UNKNOWN_FOURTH_INSTRUCTION, resp.invalidReason());
  }

  @Test
  void memoMismatch() {
    final var ixs = validInstructions();
    ixs.add(memo("not-the-expected-memo"));
    final var resp = VERIFIER.verify(requirements("invoice-123"), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.MEMO_MISMATCH, resp.invalidReason());
  }

  @Test
  void missingMemoWhenRequired() {
    final var resp = VERIFIER.verify(requirements("invoice-123"), serialize(validInstructions()));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.MEMO_COUNT, resp.invalidReason());
  }

  @Test
  void undecodableTransaction() {
    final var resp = VERIFIER.verify(requirements(null), new byte[]{0, 1, 2, 3});
    assertFalse(resp.isValid());
    assertEquals(X402Errors.TRANSACTION_COULD_NOT_BE_DECODED, resp.invalidReason());
  }

  @Test
  void missingFeePayerRequirement() {
    final var reqs = new PaymentRequirements(
        X402.SCHEME_EXACT, X402.SOLANA_MAINNET, Long.toString(AMOUNT), MINT, PAY_TO, 60, null, null);
    final var resp = VERIFIER.verify(reqs, serialize(validInstructions()));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.MISSING_FEE_PAYER, resp.invalidReason());
  }
}
