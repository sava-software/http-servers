# Changelog

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
