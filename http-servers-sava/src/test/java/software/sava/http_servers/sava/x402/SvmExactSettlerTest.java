package software.sava.http_servers.sava.x402;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.idl.clients.spl.associated_token.gen.AssociatedTokenPDAs;
import software.sava.idl.clients.spl.token.gen.TokenProgram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.encoding.ByteUtil.putInt32LE;
import static software.sava.core.encoding.ByteUtil.putInt64LE;

final class SvmExactSettlerTest {

  private static final SolanaAccounts ACCOUNTS = SolanaAccounts.MAIN_NET;

  private static final int DECIMALS = 6;
  private static final long AMOUNT = 1_000L;

  private final Signer feePayerSigner = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes());
  private final PublicKey feePayer = feePayerSigner.publicKey();

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

  private static Instruction transfer() {
    return TokenProgram.transferChecked(
        ACCOUNTS.invokedTokenProgram(), SOURCE_ATA, MINT, destinationAta(), AUTHORITY, AMOUNT, DECIMALS);
  }

  private PaymentRequirements requirements(final PublicKey requirementsFeePayer) {
    return new PaymentRequirements(
        X402.SCHEME_EXACT, X402.SOLANA_MAINNET, Long.toString(AMOUNT), MINT, PAY_TO, 60, requirementsFeePayer, null);
  }

  private byte[] txBytes(final PublicKey txFeePayer) {
    final var ixs = new ArrayList<Instruction>();
    ixs.add(computeLimit(200_000));
    ixs.add(computePrice(1_000));
    ixs.add(transfer());
    return Transaction.createTx(txFeePayer, ixs).serialized();
  }

  private PaymentPayload payload(final PublicKey txFeePayer) {
    return new PaymentPayload(2, null, null, Base64.getEncoder().encodeToString(txBytes(txFeePayer)));
  }

  private static final class FakeSubmitter implements SvmExactSettler.TransactionSubmitter {

    private final RuntimeException sendError;
    private final RuntimeException confirmError;
    private final String signature;
    private String submitted;
    private boolean confirmed;
    private int sendCount;

    private FakeSubmitter(final String signature, final RuntimeException sendError, final RuntimeException confirmError) {
      this.signature = signature;
      this.sendError = sendError;
      this.confirmError = confirmError;
    }

    static FakeSubmitter ok(final String signature) {
      return new FakeSubmitter(signature, null, null);
    }

    @Override
    public String send(final String base64SignedTransaction) {
      ++sendCount;
      if (sendError != null) {
        throw sendError;
      }
      submitted = base64SignedTransaction;
      return signature;
    }

    @Override
    public void confirm(final String signature) {
      if (confirmError != null) {
        throw confirmError;
      }
      confirmed = true;
    }
  }

  private SvmExactSettler settler(final FakeSubmitter submitter) {
    return settler(submitter, new SettlementCache());
  }

  private SvmExactSettler settler(final FakeSubmitter submitter, final SettlementCache cache) {
    return new SvmExactSettler(new SvmExactVerifier(ACCOUNTS), feePayerSigner, submitter, cache);
  }

  @Test
  void settlesValidPaymentOnChain() {
    final var submitter = FakeSubmitter.ok("SIGNATURE_OK");
    final var response = settler(submitter).settle(payload(feePayer), requirements(feePayer));

    assertTrue(response.success(), () -> "expected success but got: " + response.errorReason());
    assertEquals("SIGNATURE_OK", response.transaction());
    assertEquals(X402.SOLANA_MAINNET, response.network());
    assertEquals(AUTHORITY, response.payer());
    assertNull(response.errorReason());

    assertTrue(submitter.confirmed, "transaction should have been confirmed");
    assertNotNull(submitter.submitted, "transaction should have been submitted");

    // The fee-payer signature (slot 0) must have been filled in before submission.
    final byte[] signed = Base64.getDecoder().decode(submitter.submitted);
    final byte[] feePayerSig = Arrays.copyOfRange(signed, 1, 1 + Transaction.SIGNATURE_LENGTH);
    assertFalse(isAllZero(feePayerSig), "fee-payer signature slot 0 should be populated");
  }

  @Test
  void invalidPaymentDoesNotSubmit() {
    final var submitter = FakeSubmitter.ok("UNUSED");
    // Require a larger amount than the transfer carries -> verification fails.
    final var requirements = new PaymentRequirements(
        X402.SCHEME_EXACT, X402.SOLANA_MAINNET, Long.toString(AMOUNT + 1), MINT, PAY_TO, 60, feePayer, null);

    final var response = settler(submitter).settle(payload(feePayer), requirements);

    assertFalse(response.success());
    assertEquals(X402Errors.AMOUNT_INSUFFICIENT, response.errorReason());
    assertEquals(0, submitter.sendCount, "must not submit an unverified transaction");
  }

  @Test
  void feePayerMismatchDoesNotSubmit() {
    final var submitter = FakeSubmitter.ok("UNUSED");
    // Transaction fee payer is the managed signer, but requirements advertise a different fee payer.
    final var response = settler(submitter).settle(payload(feePayer), requirements(key(9)));

    assertFalse(response.success());
    assertEquals(X402Errors.FEE_PAYER_MISMATCH, response.errorReason());
    assertEquals(0, submitter.sendCount, "must not submit on a fee-payer mismatch");
  }

  @Test
  void duplicateSettlementIsRejected() {
    final var cache = new SettlementCache();
    final var first = settler(FakeSubmitter.ok("SIG1"), cache).settle(payload(feePayer), requirements(feePayer));
    assertTrue(first.success());

    final var submitter = FakeSubmitter.ok("SIG2");
    final var second = settler(submitter, cache).settle(payload(feePayer), requirements(feePayer));

    assertFalse(second.success());
    assertEquals(X402Errors.DUPLICATE_SETTLEMENT, second.errorReason());
    assertEquals(0, submitter.sendCount, "a duplicate must not be re-submitted");
  }

  @Test
  void submissionFailureReportsTransactionFailed() {
    final var submitter = new FakeSubmitter("UNUSED", new IllegalStateException("rpc down"), null);
    final var response = settler(submitter).settle(payload(feePayer), requirements(feePayer));

    assertFalse(response.success());
    assertEquals(X402Errors.TRANSACTION_FAILED, response.errorReason());
    assertNull(response.transaction());
  }

  @Test
  void confirmationFailureReportsSignature() {
    final var submitter = new FakeSubmitter("SIG_PENDING", null, new IllegalStateException("timeout"));
    final var response = settler(submitter).settle(payload(feePayer), requirements(feePayer));

    assertFalse(response.success());
    assertEquals(X402Errors.TRANSACTION_CONFIRMATION_FAILED, response.errorReason());
    assertEquals("SIG_PENDING", response.transaction(), "confirmation failure should still report the signature");
  }

  private static boolean isAllZero(final byte[] bytes) {
    for (final byte b : bytes) {
      if (b != 0) {
        return false;
      }
    }
    return true;
  }
}
