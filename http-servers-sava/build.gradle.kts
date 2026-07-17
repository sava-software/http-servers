plugins {
  id("software.sava.build.feature.hardening")
}

dependencies {
  project(":http-servers-core")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  mutation.register("x402") {
    // payment gate + facilitator verify rules: a surviving mutant here is a payment the
    // facilitator would wrongly sponsor (or wrongly reject)
    targetClasses = listOf("software.sava.http_servers.sava.x402.*")
    excludedClasses = listOf(
      "software.sava.http_servers.sava.x402.*Test*",
      "software.sava.http_servers.sava.x402.*Fuzz*",
      // thin adapter over SolanaRpcClient: submit/poll-confirm against a live node
      "software.sava.http_servers.sava.x402.SvmExactSettler\$RpcTransactionSubmitter"
    )
    targetTests = "software.sava.http_servers.sava.x402.*Test*"
  }
  mutation.register("handlers") {
    targetClasses = listOf("software.sava.http_servers.sava.handlers.*")
    excludedClasses = listOf(
      "software.sava.http_servers.sava.handlers.*Test*",
      "software.sava.http_servers.sava.handlers.*Fuzz*"
    )
    targetTests = "software.sava.http_servers.sava.handlers.*Test*"
  }
  fuzz.register("x402Payload") {
    targetClass = "software.sava.http_servers.sava.x402.X402PayloadFuzz"
    // payload JSON for a max-size transaction runs ~2.3KB once base64 encoded inside the
    // harness; headroom beyond that only slows executions
    maxLen = 4096
    // a full valid payment payload: the fuzzer cannot assemble field names, base64 and a
    // valid transaction from scratch, and the gate's success path is unreachable without it
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/x402Payload")
  }
  fuzz.register("svmVerify") {
    targetClass = "software.sava.http_servers.sava.x402.SvmExactVerifyFuzz"
    // transactions cap at 1232 bytes on-chain; headroom lets the fuzzer probe over-long input
    maxLen = 1500
    // valid payment transactions (with and without memo): header/offset/length agreement is
    // unreachable from a from-scratch mutator
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/svmVerify")
  }
}
