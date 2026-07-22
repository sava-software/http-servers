package software.sava.http_servers.sava.x402;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.idl.clients.spl.associated_token.gen.AssociatedTokenPDAs;
import software.sava.idl.clients.spl.token.gen.TokenProgram;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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

  @Test
  void nullPayload() {
    final var resp = VERIFIER.verify(null, requirements(null));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.INVALID_PAYLOAD_TRANSACTION, resp.invalidReason());
  }

  @Test
  void payloadWithoutTransaction() {
    final var resp = VERIFIER.verify(new PaymentPayload(2, null, null, null), requirements(null));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.INVALID_PAYLOAD_TRANSACTION, resp.invalidReason());
  }

  @Test
  void payloadWithMalformedBase64Transaction() {
    final var resp = VERIFIER.verify(new PaymentPayload(2, null, null, "!!not base64!!"), requirements(null));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.INVALID_PAYLOAD_TRANSACTION, resp.invalidReason());
  }

  @Test
  void validPayloadObject() {
    final var transaction = Base64.getEncoder().encodeToString(serialize(validInstructions()));
    final var resp = VERIFIER.verify(new PaymentPayload(2, null, null, transaction), requirements(null));
    assertTrue(resp.isValid(), () -> "got: " + resp.invalidReason());
    assertEquals(AUTHORITY, resp.payer());
  }

  @Test
  void nullRequirements() {
    final var resp = VERIFIER.verify(null, serialize(validInstructions()));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.UNSUPPORTED_SCHEME, resp.invalidReason());
  }

  @Test
  void unsupportedScheme() {
    final var reqs = new PaymentRequirements(
        "subscription", X402.SOLANA_MAINNET, Long.toString(AMOUNT), MINT, PAY_TO, 60, FEE_PAYER, null);
    final var resp = VERIFIER.verify(reqs, serialize(validInstructions()));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.UNSUPPORTED_SCHEME, resp.invalidReason());
  }

  @Test
  void missingAssetRequirement() {
    final var reqs = new PaymentRequirements(
        X402.SCHEME_EXACT, X402.SOLANA_MAINNET, Long.toString(AMOUNT), null, PAY_TO, 60, FEE_PAYER, null);
    final var resp = VERIFIER.verify(reqs, serialize(validInstructions()));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.UNSUPPORTED_SCHEME, resp.invalidReason());
  }

  @Test
  void missingPayToRequirement() {
    final var reqs = new PaymentRequirements(
        X402.SCHEME_EXACT, X402.SOLANA_MAINNET, Long.toString(AMOUNT), MINT, null, 60, FEE_PAYER, null);
    final var resp = VERIFIER.verify(reqs, serialize(validInstructions()));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.UNSUPPORTED_SCHEME, resp.invalidReason());
  }

  @Test
  void lookupTableTransactionRejected() {
    // offload the mint and destination to a lookup table: unresolved indexed accounts
    // cannot be verified, so the transaction must be rejected outright
    final byte[] tableData = new byte[AddressLookupTable.LOOKUP_TABLE_META_SIZE + (2 * PublicKey.PUBLIC_KEY_LENGTH)];
    MINT.write(tableData, AddressLookupTable.LOOKUP_TABLE_META_SIZE);
    destinationAta().write(tableData, AddressLookupTable.LOOKUP_TABLE_META_SIZE + PublicKey.PUBLIC_KEY_LENGTH);
    final var table = AddressLookupTable.readWithoutReverseLookup(key(7), tableData).withReverseLookup();

    final var tx = Transaction.createTx(FEE_PAYER, validInstructions(), table);
    final var resp = VERIFIER.verify(requirements(null), tx.serialized());
    assertFalse(resp.isValid());
    assertEquals(X402Errors.INVALID_PAYLOAD_TRANSACTION, resp.invalidReason());
  }

  @Test
  void nonTokenTransferProgram() {
    final var ixs = validInstructions();
    ixs.set(2, memo("not-a-transfer"));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.NO_TRANSFER_INSTRUCTION, resp.invalidReason());
  }

  private static byte[] transferCheckedData(final int discriminator) {
    final byte[] data = new byte[10];
    data[0] = (byte) discriminator;
    putInt64LE(data, 1, AMOUNT);
    data[9] = (byte) DECIMALS;
    return data;
  }

  @Test
  void tooFewTransferAccounts() {
    final var ixs = validInstructions();
    ixs.set(2, Instruction.createInstruction(
        ACCOUNTS.invokedTokenProgram(), List.of(), transferCheckedData(12)));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.NO_TRANSFER_INSTRUCTION, resp.invalidReason());
  }

  @Test
  void truncatedTransferDataRejected() {
    // a one-byte data slice at the tail of the message makes the ix-data read overrun
    final var ixs = validInstructions();
    final var accounts = ixs.get(2).accounts();
    ixs.set(2, Instruction.createInstruction(
        ACCOUNTS.invokedTokenProgram(), accounts, new byte[]{12}));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.NO_TRANSFER_INSTRUCTION, resp.invalidReason());
  }

  @Test
  void wrongTransferDiscriminator() {
    final var ixs = validInstructions();
    final var accounts = ixs.get(2).accounts();
    ixs.set(2, Instruction.createInstruction(
        ACCOUNTS.invokedTokenProgram(), accounts, transferCheckedData(3)));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.NO_TRANSFER_INSTRUCTION, resp.invalidReason());
  }

  @Test
  void feePayerIsSource() {
    final var ixs = validInstructions();
    ixs.set(2, transfer(FEE_PAYER, MINT, destinationAta(), AUTHORITY, AMOUNT));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.FEE_PAYER_TRANSFERRING_FUNDS, resp.invalidReason());
  }

  @Test
  void feePayerInOptionalInstruction() {
    final var ixs = validInstructions();
    ixs.add(Instruction.createInstruction(
        X402.LIGHTHOUSE_PROGRAM, List.of(AccountMeta.createRead(FEE_PAYER)), new byte[]{1}));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.FEE_PAYER_TRANSFERRING_FUNDS, resp.invalidReason());
  }

  @Test
  void malformedRequiredAmount() {
    final var reqs = new PaymentRequirements(
        X402.SCHEME_EXACT, X402.SOLANA_MAINNET, "one-thousand", MINT, PAY_TO, 60, FEE_PAYER, null);
    final var resp = VERIFIER.verify(reqs, serialize(validInstructions()));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.AMOUNT_INSUFFICIENT, resp.invalidReason());
  }

  @Test
  void wrongComputeLimitProgram() {
    final var ixs = validInstructions();
    ixs.set(0, memo("not-compute-budget"));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.COMPUTE_LIMIT_INSTRUCTION, resp.invalidReason());
  }

  @Test
  void emptyComputeLimitData() {
    final var ixs = validInstructions();
    ixs.set(0, Instruction.createInstruction(ACCOUNTS.computeBudgetProgram(), List.of(), new byte[0]));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.COMPUTE_LIMIT_INSTRUCTION, resp.invalidReason());
  }

  @Test
  void singleByteComputeLimitDataAccepted() {
    // only the discriminator byte is inspected; a one-byte instruction is within contract
    final var ixs = validInstructions();
    ixs.set(0, Instruction.createInstruction(
        ACCOUNTS.computeBudgetProgram(), List.of(), new byte[]{(byte) X402.COMPUTE_BUDGET_SET_LIMIT}));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertTrue(resp.isValid(), () -> "got: " + resp.invalidReason());
  }

  @Test
  void wrongComputePriceProgram() {
    final var ixs = validInstructions();
    ixs.set(1, memo("not-compute-budget"));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.COMPUTE_PRICE_INSTRUCTION, resp.invalidReason());
  }

  @Test
  void shortComputePriceData() {
    final byte[] data = new byte[8];
    data[0] = (byte) X402.COMPUTE_BUDGET_SET_PRICE;
    final var ixs = validInstructions();
    ixs.set(1, Instruction.createInstruction(ACCOUNTS.computeBudgetProgram(), List.of(), data));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.COMPUTE_PRICE_INSTRUCTION, resp.invalidReason());
  }

  @Test
  void wrongComputePriceDiscriminator() {
    final byte[] data = new byte[9];
    data[0] = (byte) X402.COMPUTE_BUDGET_SET_LIMIT;
    putInt64LE(data, 1, 1_000L);
    final var ixs = validInstructions();
    ixs.set(1, Instruction.createInstruction(ACCOUNTS.computeBudgetProgram(), List.of(), data));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.COMPUTE_PRICE_INSTRUCTION, resp.invalidReason());
  }

  @Test
  void maxComputePriceAccepted() {
    final var ixs = validInstructions();
    ixs.set(1, computePrice(X402.MAX_COMPUTE_UNIT_PRICE_MICRO_LAMPORTS));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertTrue(resp.isValid(), () -> "got: " + resp.invalidReason());
  }

  @Test
  void negativeComputePriceRejected() {
    // the high bit set reads as negative: treated as unsigned it is far above the cap
    final var ixs = validInstructions();
    ixs.set(1, computePrice(-1L));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.COMPUTE_PRICE_INSTRUCTION_TOO_HIGH, resp.invalidReason());
  }

  @Test
  void sixInstructionsAccepted() {
    final var ixs = validInstructions();
    ixs.add(memo("invoice-123"));
    ixs.add(Instruction.createInstruction(X402.LIGHTHOUSE_PROGRAM, List.of(), new byte[]{1}));
    ixs.add(Instruction.createInstruction(X402.LIGHTHOUSE_PROGRAM, List.of(), new byte[]{2}));
    final var resp = VERIFIER.verify(requirements("invoice-123"), serialize(ixs));
    assertTrue(resp.isValid(), () -> "got: " + resp.invalidReason());
  }

  @Test
  void memoV1Accepted() {
    final var ixs = validInstructions();
    ixs.add(Instruction.createInstruction(
        ACCOUNTS.memoProgram(), List.of(), "invoice-123".getBytes(StandardCharsets.UTF_8)));
    final var resp = VERIFIER.verify(requirements("invoice-123"), serialize(ixs));
    assertTrue(resp.isValid(), () -> "got: " + resp.invalidReason());
  }

  @Test
  void lighthouseInstructionAccepted() {
    final var ixs = validInstructions();
    ixs.add(Instruction.createInstruction(X402.LIGHTHOUSE_PROGRAM, List.of(), new byte[]{1}));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertTrue(resp.isValid(), () -> "got: " + resp.invalidReason());
  }

  @Test
  void unknownFifthInstruction() {
    final var ixs = validInstructions();
    ixs.add(memo("a"));
    ixs.add(Instruction.createInstruction(key(60), List.of(), new byte[]{1}));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.UNKNOWN_FIFTH_INSTRUCTION, resp.invalidReason());
  }

  @Test
  void unknownSixthInstruction() {
    final var ixs = validInstructions();
    ixs.add(memo("a"));
    ixs.add(memo("b"));
    ixs.add(Instruction.createInstruction(key(60), List.of(), new byte[]{1}));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.UNKNOWN_SIXTH_INSTRUCTION, resp.invalidReason());
  }

  @Test
  void twoMemosRejectedWhenMemoRequired() {
    final var ixs = validInstructions();
    ixs.add(memo("invoice-123"));
    ixs.add(memo("invoice-123"));
    final var resp = VERIFIER.verify(requirements("invoice-123"), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.MEMO_COUNT, resp.invalidReason());
  }

  @Test
  void token2022TransferAccepted() {
    final var dest2022 = AssociatedTokenPDAs.associatedTokenPDA(
        ACCOUNTS.associatedTokenAccountProgram(), PAY_TO, ACCOUNTS.token2022Program(), MINT).publicKey();
    final var ixs = validInstructions();
    ixs.set(2, TokenProgram.transferChecked(
        ACCOUNTS.invokedToken2022Program(), SOURCE_ATA, MINT, dest2022, AUTHORITY, AMOUNT, DECIMALS));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertTrue(resp.isValid(), () -> "got: " + resp.invalidReason());
    assertEquals(AUTHORITY, resp.payer());
  }

  @Test
  void unknownProgramWithTransferCheckedShapeRejected() {
    // a perfectly shaped TransferChecked from a foreign program must still be rejected
    final var ixs = validInstructions();
    final var accounts = ixs.get(2).accounts();
    ixs.set(2, Instruction.createInstruction(key(60), accounts, transferCheckedData(12)));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.NO_TRANSFER_INSTRUCTION, resp.invalidReason());
  }

  @Test
  void wrongProgramWithComputeLimitShapeRejected() {
    final var ixs = validInstructions();
    ixs.set(0, Instruction.createInstruction(
        key(60), List.of(), new byte[]{(byte) X402.COMPUTE_BUDGET_SET_LIMIT}));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.COMPUTE_LIMIT_INSTRUCTION, resp.invalidReason());
  }

  @Test
  void wrongProgramWithComputePriceShapeRejected() {
    final byte[] data = new byte[9];
    data[0] = (byte) X402.COMPUTE_BUDGET_SET_PRICE;
    putInt64LE(data, 1, 1_000L);
    final var ixs = validInstructions();
    ixs.set(1, Instruction.createInstruction(key(60), List.of(), data));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.COMPUTE_PRICE_INSTRUCTION, resp.invalidReason());
  }

  @Test
  void zeroComputePriceAccepted() {
    final var ixs = validInstructions();
    ixs.set(1, computePrice(0L));
    final var resp = VERIFIER.verify(requirements(null), serialize(ixs));
    assertTrue(resp.isValid(), () -> "got: " + resp.invalidReason());
  }

  @Test
  void emptyMemoRequirementIgnored() {
    final var resp = VERIFIER.verify(requirements(""), serialize(validInstructions()));
    assertTrue(resp.isValid(), () -> "got: " + resp.invalidReason());
  }

  // The skeleton resolves lazily, so single corrupted index/length bytes yield instructions
  // with null programs, null accounts, or overrunning data slices instead of a deserialization
  // failure. Each must be caught by verify's up-front validation, not by whatever rule check
  // happens to dereference the hole first.

  private static Instruction[] parsedInstructions(final byte[] tx) {
    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx);
    return skeleton.parseInstructions(skeleton.parseAccounts());
  }

  private static byte[] corrupted(final byte[] tx, final int position, final byte value) {
    final byte[] mutated = tx.clone();
    mutated[position] = value;
    return mutated;
  }

  @Test
  void unresolvableProgramIndexRejected() {
    final byte[] tx = serialize(validInstructions());
    // instruction entry layout: [programIdIndex][numAccounts][accountIndices...][dataLen][data];
    // ix0 has no accounts, so its program-index byte sits 3 bytes before its data slice
    final int programIndexPosition = parsedInstructions(tx)[0].offset() - 3;
    final var resp = VERIFIER.verify(requirements(null), corrupted(tx, programIndexPosition, (byte) 0x7F));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.TRANSACTION_COULD_NOT_BE_DECODED, resp.invalidReason());
  }

  @Test
  void unresolvableAccountIndexRejected() {
    final byte[] tx = serialize(validInstructions());
    // the transfer instruction (ix2) has 4 account-index bytes ending just before its dataLen
    // byte; corrupt the first of them (source) to an index beyond the account table
    final int sourceIndexPosition = parsedInstructions(tx)[2].offset() - 1 - 4;
    final var resp = VERIFIER.verify(requirements(null), corrupted(tx, sourceIndexPosition, (byte) 0x7F));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.TRANSACTION_COULD_NOT_BE_DECODED, resp.invalidReason());
  }

  @Test
  void overrunningDataSliceRejected() {
    final byte[] tx = serialize(validInstructions());
    // inflate the transfer instruction's dataLen byte so its lazily-resolved slice overruns
    // the end of the transaction bytes
    final int dataLenPosition = parsedInstructions(tx)[2].offset() - 1;
    final var resp = VERIFIER.verify(requirements(null), corrupted(tx, dataLenPosition, (byte) 100));
    assertFalse(resp.isValid());
    assertEquals(X402Errors.TRANSACTION_COULD_NOT_BE_DECODED, resp.invalidReason());
  }
}
