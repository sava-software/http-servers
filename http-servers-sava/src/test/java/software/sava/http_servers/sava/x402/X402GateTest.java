package software.sava.http_servers.sava.x402;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.http_servers.core.request.Request;
import software.sava.http_servers.core.response.HttpResponse;
import software.sava.http_servers.core.response.QueryHandler;
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

final class X402GateTest {

  private static final SolanaAccounts ACCOUNTS = SolanaAccounts.MAIN_NET;

  private static final int DECIMALS = 6;
  private static final long AMOUNT = 1_000L;

  private static final PublicKey FEE_PAYER = key(1);
  private static final PublicKey AUTHORITY = key(2);
  private static final PublicKey SOURCE_ATA = key(3);
  private static final PublicKey MINT = key(4);
  private static final PublicKey PAY_TO = key(5);

  private static final QueryHandler PROTECTED = (path, query) -> HttpResponse.json("{\"secret\":42}");

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

  private static PaymentRequirements requirements() {
    return new PaymentRequirements(
        X402.SCHEME_EXACT, X402.SOLANA_MAINNET, Long.toString(AMOUNT), MINT, PAY_TO, 60, FEE_PAYER, null);
  }

  private static String validPaymentHeader() {
    final var ixs = new ArrayList<Instruction>();
    ixs.add(computeLimit(200_000));
    ixs.add(computePrice(1_000));
    ixs.add(transfer());
    final byte[] txBytes = Transaction.createTx(FEE_PAYER, ixs).serialized();
    final String txB64 = Base64.getEncoder().encodeToString(txBytes);
    final String json = "{\"x402Version\":2,\"payload\":{\"transaction\":\"" + txB64 + "\"}}";
    return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  private static X402Gate gate() {
    return new X402Gate(PROTECTED, requirements(), null, new SvmExactVerifier(ACCOUNTS));
  }

  private record FakeRequest(String path, String query, String paymentHeader) implements Request {

    @Override
    public String header(final String name) {
      return X402.PAYMENT_HEADER.equals(name) ? paymentHeader : null;
    }

    @Override
    public byte[] body() {
      return new byte[0];
    }
  }

  @Test
  void missingHeaderReturns402() {
    final var resp = gate().httpResponse(new FakeRequest("/resource", null, null));
    assertEquals(402, resp.statusCode());
    assertTrue(resp.headers().isEmpty());
    final var body = PaymentRequired.parse(systems.comodal.jsoniter.JsonIterator.parse(resp.body()));
    assertEquals(1, body.accepts().size());
    assertEquals(PAY_TO, body.accepts().getFirst().payTo());
  }

  @Test
  void twoArgOverloadReturns402() {
    final var resp = gate().httpResponse("/resource", null);
    assertEquals(402, resp.statusCode());
  }

  @Test
  void invalidPaymentReturns402() {
    final var resp = gate().httpResponse(new FakeRequest("/resource", null, "not-base64-json"));
    assertEquals(402, resp.statusCode());
  }

  @Test
  void validPaymentServesResourceAndSetsHeader() {
    final var resp = gate().httpResponse(new FakeRequest("/resource", null, validPaymentHeader()));
    assertEquals(200, resp.statusCode());
    assertEquals("{\"secret\":42}", new String(resp.body(), StandardCharsets.UTF_8));

    final var headerValue = resp.headers().get(X402.PAYMENT_RESPONSE_HEADER);
    assertNotNull(headerValue, "expected X-PAYMENT-RESPONSE header");
    final byte[] settlementJson = Base64.getDecoder().decode(headerValue);
    final var settlement = SettlementResponse.parse(systems.comodal.jsoniter.JsonIterator.parse(settlementJson));
    assertTrue(settlement.success());
    assertEquals(AUTHORITY, settlement.payer());
  }

  @Test
  void settlerBackedGateAttachesTransactionSignature() {
    final var signer = software.sava.core.accounts.Signer.createFromKeyPair(
        software.sava.core.accounts.Signer.generatePrivateKeyPairBytes());
    final var managedFeePayer = signer.publicKey();
    final var requirements = new PaymentRequirements(
        X402.SCHEME_EXACT, X402.SOLANA_MAINNET, Long.toString(AMOUNT), MINT, PAY_TO, 60, managedFeePayer, null);

    final var ixs = new ArrayList<Instruction>();
    ixs.add(computeLimit(200_000));
    ixs.add(computePrice(1_000));
    ixs.add(transfer());
    final byte[] txBytes = Transaction.createTx(managedFeePayer, ixs).serialized();
    final String json = "{\"x402Version\":2,\"payload\":{\"transaction\":\""
        + Base64.getEncoder().encodeToString(txBytes) + "\"}}";
    final String header = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

    final SvmExactSettler.TransactionSubmitter submitter = new SvmExactSettler.TransactionSubmitter() {
      @Override
      public String send(final String base64SignedTransaction) {
        return "ON_CHAIN_SIG";
      }

      @Override
      public void confirm(final String signature) {
      }
    };
    final var settler = new SvmExactSettler(new SvmExactVerifier(ACCOUNTS), signer, submitter, new SettlementCache());
    final var gate = new X402Gate(PROTECTED, requirements, null, settler);

    final var resp = gate.httpResponse(new FakeRequest("/resource", null, header));
    assertEquals(200, resp.statusCode());

    final var headerValue = resp.headers().get(X402.PAYMENT_RESPONSE_HEADER);
    assertNotNull(headerValue, "expected X-PAYMENT-RESPONSE header");
    final var settlement = SettlementResponse.parse(systems.comodal.jsoniter.JsonIterator.parse(
        Base64.getDecoder().decode(headerValue)));
    assertTrue(settlement.success());
    assertEquals("ON_CHAIN_SIG", settlement.transaction());
    assertEquals(AUTHORITY, settlement.payer());
  }

  @Test
  void settlerBackedGateReturns402OnSettlementFailure() {
    final var signer = software.sava.core.accounts.Signer.createFromKeyPair(
        software.sava.core.accounts.Signer.generatePrivateKeyPairBytes());
    final var managedFeePayer = signer.publicKey();
    final var requirements = new PaymentRequirements(
        X402.SCHEME_EXACT, X402.SOLANA_MAINNET, Long.toString(AMOUNT), MINT, PAY_TO, 60, managedFeePayer, null);

    final var ixs = new ArrayList<Instruction>();
    ixs.add(computeLimit(200_000));
    ixs.add(computePrice(1_000));
    ixs.add(transfer());
    final byte[] txBytes = Transaction.createTx(managedFeePayer, ixs).serialized();
    final String json = "{\"x402Version\":2,\"payload\":{\"transaction\":\""
        + Base64.getEncoder().encodeToString(txBytes) + "\"}}";
    final String header = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

    final SvmExactSettler.TransactionSubmitter submitter = new SvmExactSettler.TransactionSubmitter() {
      @Override
      public String send(final String base64SignedTransaction) {
        throw new IllegalStateException("rpc down");
      }

      @Override
      public void confirm(final String signature) {
      }
    };
    final var settler = new SvmExactSettler(new SvmExactVerifier(ACCOUNTS), signer, submitter, new SettlementCache());
    final var gate = new X402Gate(PROTECTED, requirements, null, settler);

    final var resp = gate.httpResponse(new FakeRequest("/resource", null, header));
    assertEquals(402, resp.statusCode());
    final var body = PaymentRequired.parse(systems.comodal.jsoniter.JsonIterator.parse(resp.body()));
    assertEquals(X402Errors.TRANSACTION_FAILED, body.error());
  }
}
