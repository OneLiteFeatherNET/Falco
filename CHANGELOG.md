# Changelog

## [0.3.0](https://github.com/OneLiteFeatherNET/Falco/compare/v0.2.1...v0.3.0) (2026-07-31)


### Features

* **anvil:** say why a chunk was not returned ([d7e6b35](https://github.com/OneLiteFeatherNET/Falco/commit/d7e6b35d764e64abecd639c0c1fac429cbce48af))
* **demo:** add falco-demo, a loader comparison on your own world ([87282d5](https://github.com/OneLiteFeatherNET/Falco/commit/87282d565b2595d323ba638a773204365487d585))
* **demo:** add two servers you can actually join ([bea45e6](https://github.com/OneLiteFeatherNET/Falco/commit/bea45e6d3ae6808a598583954ff191352f0d6cf1))
* **instance:** add falco-instance, an own Instance and Chunk ([cee5f94](https://github.com/OneLiteFeatherNET/Falco/commit/cee5f94e627155f49bc1150bf66cb2ca4cd1b227))
* **instance:** implement the generator path and close the load race ([e45d695](https://github.com/OneLiteFeatherNET/Falco/commit/e45d6957c2b517d0201474bba9896443abbea84f))
* **light:** add a chunk that keeps its own light up to date ([9d00a78](https://github.com/OneLiteFeatherNET/Falco/commit/9d00a78d2806bc879cb6893eea82a9f61c278708))


### Bug Fixes

* **build:** make an incomplete javadoc comment fail the build ([63334a6](https://github.com/OneLiteFeatherNET/Falco/commit/63334a64f8f597b4926c7fd71db89d46deafd475))
* **demo:** both loaders skip a partial chunk, not just Falco ([861b881](https://github.com/OneLiteFeatherNET/Falco/commit/861b8813f76b19e8f06585fe83b48f9454ef08b1))
* **light:** stop calculateWithNeighbours darkening the chunks it borrows ([e736b93](https://github.com/OneLiteFeatherNET/Falco/commit/e736b93f375406a2655e822b0130c9386357b0e2))


### Performance Improvements

* **light:** update a changed chunk instead of rebuilding it ([1063a54](https://github.com/OneLiteFeatherNET/Falco/commit/1063a54c6f785d19ac89588d4c59391c983e6d1d))

## [0.2.1](https://github.com/OneLiteFeatherNET/Falco/compare/v0.2.0...v0.2.1) (2026-07-31)


### Bug Fixes

* **test:** stop the concurrency stress tests from hanging the pipeline ([fbb4121](https://github.com/OneLiteFeatherNET/Falco/commit/fbb4121013d384b9c9d47ed68ab7890da2ce0361))

## [0.2.0](https://github.com/OneLiteFeatherNET/Falco/compare/v0.1.0...v0.2.0) (2026-07-31)


### Features

* an Anvil chunk loader and a light engine for Minestom ([fc0aef5](https://github.com/OneLiteFeatherNET/Falco/commit/fc0aef56f557ae055de414e09bf18cf45faed829))
* publish to the public Reposilite endpoints ([7e10a1e](https://github.com/OneLiteFeatherNET/Falco/commit/7e10a1e1303ebc1e7381fe8c8badfd246377a2b2))
