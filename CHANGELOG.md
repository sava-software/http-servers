# Changelog

## [25.4.1](https://github.com/sava-software/http-servers/compare/25.4.0...25.4.1) (2026-09-16)


### Features

* add Helidon and Netty backends ([3864332](https://github.com/sava-software/http-servers/commit/386433226c518e49c3ad032a0a62196d7dfc59cd))
* **soak:** add a soak-test harness with flight recording ([30e9ed6](https://github.com/sava-software/http-servers/commit/30e9ed6527edc86ac11fa28b831d8bb6ede0d2fd))


### Bug Fixes

* **deps:** resolve Helidon and Netty through solana-version-catalog 25.30.21 ([2b30e60](https://github.com/sava-software/http-servers/commit/2b30e6043d3ddfad524066f67b4137d5d5abd5ac))
* **jdk:** stop leaking connections when a client leaves ([e76885a](https://github.com/sava-software/http-servers/commit/e76885ab07b0ddf97c71c512e4f7418d1a011f0d))
* **jetty:** start on hosts with one to three processors ([47da1f5](https://github.com/sava-software/http-servers/commit/47da1f5ab16a08558d0982d747f8c6801270355d))
* **netty:** close idle connections after 30 s ([d85a6f7](https://github.com/sava-software/http-servers/commit/d85a6f74cb54efa5d35cb3a355f1f1e8a8de7a9b))
* **netty:** decide expectation refusals at decode time ([5e2a648](https://github.com/sava-software/http-servers/commit/5e2a648fc9f68d07f95a517973d31125deab13a8))
* **netty:** log a client reset like a client close ([e29c9b3](https://github.com/sava-software/http-servers/commit/e29c9b389d9d8c60407ed5bc662c9fab0fbc70ed))

## [25.4.0](https://github.com/sava-software/http-servers/compare/25.3.0...25.4.0) (2026-09-04)


### Bug Fixes

* **core:** stop pulling jdk.httpserver into the module graph ([716db01](https://github.com/sava-software/http-servers/commit/716db01f5c204d5d49ed5dd33949824d8c00c9a4))
* **deps:** update solanaBOMVersion to 25.30.8 ([46eb890](https://github.com/sava-software/http-servers/commit/46eb890707d8f9445609ca8628c504a64cb837f1))

## [25.3.0](https://github.com/sava-software/http-servers/compare/25.2.0...25.3.0) (2026-08-07)


### Features

* **core:** add HttpServer.stop() and make servers AutoCloseable ([715c35d](https://github.com/sava-software/http-servers/commit/715c35d34fdad07e09ef4c99048418473133cc1f))
* **hardening:** adopt line-less baselines, ownership audit and certification ([81a5d1f](https://github.com/sava-software/http-servers/commit/81a5d1f6ccf7e4cd5cac0580d24ab58e0f278d07))
* **hardening:** bind every mutation record to PIT 1.25.9 and its toolchain ([86bb16c](https://github.com/sava-software/http-servers/commit/86bb16cbbdb607ea01a37c802fb56f1c275874b4))
* **pitest:** add audited timeout sets for handlers, dispatch, and logging ([a0c1403](https://github.com/sava-software/http-servers/commit/a0c140393fe0a68f89ffc7bded73b72365d22101))


### Bug Fixes

* **hardening:** audit two load-dependent timeouts, adopt PIT 1.25.9 population ([cb63671](https://github.com/sava-software/http-servers/commit/cb63671dfa1ed61410333d2e11ccf198760a4c96))
* **hardening:** retire the fusionauth null-origin timeout membership ([4a5e391](https://github.com/sava-software/http-servers/commit/4a5e3911018d18bdda9d765504e8b2a66ce724e3))


### Miscellaneous Chores

* release 25.3.0 ([bc83b4c](https://github.com/sava-software/http-servers/commit/bc83b4cb1d108537c34a2c45fc18b5d0d8b160c9))

## [25.2.0](https://github.com/sava-software/http-servers/compare/25.1.1...25.2.0) (2026-07-24)


### ⚠ BREAKING CHANGES

* **servers:** Servers now throw exceptions on port binding failures, enforcing stricter startup guarantees. Consume logs and handle exceptions accordingly.
* **core:** Routing now uses canonicalized paths. Malformed or ambiguous paths will result in a 400 response. Consumers must migrate to use valid canonical paths.
* `JettyHandler` and `BaseJettyHandler` are removed. `JettyServerBuilder` now extends `BaseHttpServerBuilder<Handler, Server>`; subclasses overriding its factory methods must return `org.eclipse.jetty .server.Handler`. `BaseJettyHandler.JSON_CONTENT` was package-private-ized onto `JettyController` and is no longer part of the public API. Consumers relying on `org.eclipse.jetty.http` or `org.eclipse.jetty.util` arriving transitively through this module must now require them directly.
* http-servers-jdk query handlers no longer prefix-match; register a path handler where prefix routing is wanted. Request.query() on that backend returns the raw query string, so values arrive percent-encoded as they already did on the other backends — decode them at the call site. RootJettyHandler is removed.

### Features

* **core:** add PathCanonicalizer for canonical routing ([f811789](https://github.com/sava-software/http-servers/commit/f811789470e3b8248503a9e217f84ea6800ee2b4))


### Bug Fixes

* correct request routing, query decoding, and CORS pre-flight ([430e6d0](https://github.com/sava-software/http-servers/commit/430e6d0839fa3674806b3e95da51d323f73c9665))
* percent-decode query values; drop the Jetty handler marker types ([eec306c](https://github.com/sava-software/http-servers/commit/eec306c06b26bfc2a68e4ebc22a3c4f34b02ebe0))
* **servers:** retry port binding on race, enforce listener start contracts ([595909f](https://github.com/sava-software/http-servers/commit/595909fed2d5aad304425adeeda3af07bebd5992))


### Miscellaneous Chores

* release 25.2.0 ([e617bcb](https://github.com/sava-software/http-servers/commit/e617bcb4f784003ba5f36eefd493a2d99a25403c))

## [25.1.1](https://github.com/sava-software/http-servers/compare/25.1.0...25.1.1) (2026-06-27)


### Bug Fixes

* **ci:** update permissions in GitHub publish workflow ([9139b11](https://github.com/sava-software/http-servers/commit/9139b117b2741a1c9d6d35c1272a20c00551ad2f))

## [25.1.0](https://github.com/sava-software/http-servers/compare/25.0.5...25.1.0) (2026-06-27)


### Features

* **http-servers-core:** add POST method support for query/path handlers ([ef874b2](https://github.com/sava-software/http-servers/commit/ef874b2034235a582c7f482d5e64ef6a1a9f7221))


### Miscellaneous Chores

* release 25.1.0 ([453ccff](https://github.com/sava-software/http-servers/commit/453ccffd870e1cccad946b830b8fca5095d0ae09))

## [25.0.5](https://github.com/sava-software/http-servers/compare/25.0.4...25.0.5) (2026-06-03)


### Bug Fixes

* **aggregation:** correct task name in GitHub Packages publishing script ([da09fde](https://github.com/sava-software/http-servers/commit/da09fde7567c58c86df21b44a25d706084dcb072))

## [25.0.4](https://github.com/sava-software/http-servers/compare/25.0.3...25.0.4) (2026-06-03)


### Miscellaneous Chores

* release 25.0.4 ([a401152](https://github.com/sava-software/http-servers/commit/a401152e8feb23c99c948c1f065a4d768d5b6742))

## [25.0.3](https://github.com/sava-software/http-servers/compare/25.0.2...25.0.3) (2026-06-03)


### Features

* **http-servers-core:** add unified request abstraction and headers to response ([530a293](https://github.com/sava-software/http-servers/commit/530a2937493c473546d11a0d2c7bb4eaa814d366))
* **http-servers-sava:** implement x402 Solana settlement and payment models ([3703a7c](https://github.com/sava-software/http-servers/commit/3703a7c51c61706429b322fe3314f9f91127d763))

## [25.0.2](https://github.com/sava-software/http-servers/compare/25.0.1...25.0.2) (2026-05-31)


### Features

* **http-servers-core:** add handler group support for query/path methods ([aac0c87](https://github.com/sava-software/http-servers/commit/aac0c8758f07f28b35f47945e6c2d7880df0c6f5))
* **http-servers-core:** enhance handler inclusion/exclusion logic ([deeafe2](https://github.com/sava-software/http-servers/commit/deeafe29adbcf994c7d56480b7ba68f30a3bb770))

## [25.0.1](https://github.com/sava-software/http-servers/compare/25.0.0...25.0.1) (2026-05-30)


### Features

* **http-servers:** add initial implementations for Jetty and FusionAuth servers ([90c7f49](https://github.com/sava-software/http-servers/commit/90c7f4907daab2fc6eb2890f80dacf22ab36b25c))
* **http-servers:** remove Solana WebSocket sync implementation ([183c338](https://github.com/sava-software/http-servers/commit/183c338d254f090bfd61fd9584778c4866b447fb))


### Bug Fixes

* update release-please manifest version to 25.0.0 to release against Java 25 ([8d617e0](https://github.com/sava-software/http-servers/commit/8d617e05087c10561b67cf259d51e215ea7a01de))

## [0.1.1](https://github.com/sava-software/http-servers/compare/0.1.0...0.1.1) (2026-05-30)


### Features

* **http-servers:** add initial implementations for Jetty and FusionAuth servers ([90c7f49](https://github.com/sava-software/http-servers/commit/90c7f4907daab2fc6eb2890f80dacf22ab36b25c))
* **http-servers:** remove Solana WebSocket sync implementation ([183c338](https://github.com/sava-software/http-servers/commit/183c338d254f090bfd61fd9584778c4866b447fb))
