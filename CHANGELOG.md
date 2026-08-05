# Changelog

## [2.1.0](https://github.com/OneLiteFeatherNET/Falco/compare/v2.0.0...v2.1.0) (2026-08-05)


### Features

* **anvil:** let a loader migrate the chunks it reads ([#50](https://github.com/OneLiteFeatherNET/Falco/issues/50)) ([e6eefd0](https://github.com/OneLiteFeatherNET/Falco/commit/e6eefd02ae132c6a969c7e78fdb8d9d416b48b20))


### Bug Fixes

* **anvil:** refuse a full chunk that carries no sections ([#49](https://github.com/OneLiteFeatherNET/Falco/issues/49)) ([f198feb](https://github.com/OneLiteFeatherNET/Falco/commit/f198feb67052dc7236fd8b5fed153080922ce29f))

## [2.0.0](https://github.com/OneLiteFeatherNET/Falco/compare/v1.0.0...v2.0.0) (2026-08-05)


### ⚠ BREAKING CHANGES

* **anvil:** make the version guard and the unknown-entry fallback replaceable services ([#47](https://github.com/OneLiteFeatherNET/Falco/issues/47))
* **anvil:** refuse a world the loader cannot read instead of returning air ([#45](https://github.com/OneLiteFeatherNET/Falco/issues/45))

### Features

* **anvil:** make the version guard and the unknown-entry fallback replaceable services ([#47](https://github.com/OneLiteFeatherNET/Falco/issues/47)) ([94dc761](https://github.com/OneLiteFeatherNET/Falco/commit/94dc7617cf985ddf1b72037ffd3a50138a0070e6))
* **migration:** an engine that lifts a stored 1.13 chunk to the version the server writes ([#48](https://github.com/OneLiteFeatherNET/Falco/issues/48)) ([22a9736](https://github.com/OneLiteFeatherNET/Falco/commit/22a973613dcdec973f781838952a38f331097f01))


### Bug Fixes

* **anvil:** refuse a world the loader cannot read instead of returning air ([#45](https://github.com/OneLiteFeatherNET/Falco/issues/45)) ([1bdd0cc](https://github.com/OneLiteFeatherNET/Falco/commit/1bdd0cca920d19ed934395fcedc620a01cb84507))

## [1.0.0](https://github.com/OneLiteFeatherNET/Falco/compare/v0.3.0...v1.0.0) (2026-08-03)


### ⚠ BREAKING CHANGES

* **anvil:** RegionFile.open and RegionFile.readRaw declare RegionFormatException, and the format classes declare ChunkDataException instead of IOException. Binary compatible - a checked exception is not part of the binary signature - but a caller that catches IOException around them has to widen the catch.
* FalcoAnvilLoader.Builder slots return a new builder instead of the same one. Chained calls are unaffected; a caller who relied on mutating a builder through a stored reference has to keep the returned value. The type was never published in a release.

### Features

* **anvil:** give every failure of the stored data its own type ([#21](https://github.com/OneLiteFeatherNET/Falco/issues/21)) ([54a9c65](https://github.com/OneLiteFeatherNET/Falco/commit/54a9c6579a5205bd41f144057deb6f783315c223))
* **archunit:** enforce the architecture rules the compiler cannot see ([#12](https://github.com/OneLiteFeatherNET/Falco/issues/12)) ([cb30cf0](https://github.com/OneLiteFeatherNET/Falco/commit/cb30cf0a44efa0d4eb700c0f4b69271bf7fdc72b))
* **bom:** add falco-bom, a platform pinning the published modules ([#5](https://github.com/OneLiteFeatherNET/Falco/issues/5)) ([33f220f](https://github.com/OneLiteFeatherNET/Falco/commit/33f220f4b27d2791b691ca65dee3afa8413bd34c))
* **demo:** put the sun where you want to judge the light from ([#6](https://github.com/OneLiteFeatherNET/Falco/issues/6)) ([94b287c](https://github.com/OneLiteFeatherNET/Falco/commit/94b287cf2aaf774ad1c0b7e78bc06d3378151ab5))
* give the three modules a fluent construction API ([#16](https://github.com/OneLiteFeatherNET/Falco/issues/16)) ([86f098b](https://github.com/OneLiteFeatherNET/Falco/commit/86f098bfe68d4bb51eb07df8eb3adbba71a3a00c))
* **instance:** a chunk that owns its storage, and an instance split along what it does ([#39](https://github.com/OneLiteFeatherNET/Falco/issues/39)) ([b357944](https://github.com/OneLiteFeatherNET/Falco/commit/b357944a03e7f41ad84d5c4a9b70b0a883fc92ba))
* **instance:** a shared instance that repairs what it inherits ([#40](https://github.com/OneLiteFeatherNET/Falco/issues/40)) ([144a609](https://github.com/OneLiteFeatherNET/Falco/commit/144a6098c1f3f7959a5656ef64210b53a651bb64))


### Bug Fixes

* **build:** drop the JUnit parallelism that was never in effect ([#17](https://github.com/OneLiteFeatherNET/Falco/issues/17)) ([96276f7](https://github.com/OneLiteFeatherNET/Falco/commit/96276f7004d7513f4e5d9d3ff36b10896762ecc5))
* close three latent defects found while modernising for Java 25 ([#10](https://github.com/OneLiteFeatherNET/Falco/issues/10)) ([124d21d](https://github.com/OneLiteFeatherNET/Falco/commit/124d21da23cc4192bddd2bd8b3cc0008094d6f0b))
* **demo:** compare report paths independently of the platform separator ([#8](https://github.com/OneLiteFeatherNET/Falco/issues/8)) ([6907459](https://github.com/OneLiteFeatherNET/Falco/commit/6907459380df9303f97ab44f944add25b98ca6c4))
* **demo:** put the player where the world is, not at the origin ([#18](https://github.com/OneLiteFeatherNET/Falco/issues/18)) ([f405968](https://github.com/OneLiteFeatherNET/Falco/commit/f405968cf5123c105510b03101e44e0a0e3d762d))
* **instance:** publish the chunk supplier and loader safely ([#11](https://github.com/OneLiteFeatherNET/Falco/issues/11)) ([e45dc1e](https://github.com/OneLiteFeatherNET/Falco/commit/e45dc1e101088e60af3c5ec7705808cf5db72a1b))
* **light:** forget chunks that left the instance ([#14](https://github.com/OneLiteFeatherNET/Falco/issues/14)) ([1f72962](https://github.com/OneLiteFeatherNET/Falco/commit/1f72962af41d5ccd2e1188ca240dff273bd869a5))
* **light:** read the diagonal chunks of an area's ring ([#24](https://github.com/OneLiteFeatherNET/Falco/issues/24)) ([6e59cb7](https://github.com/OneLiteFeatherNET/Falco/commit/6e59cb7c44c42c47e7f681578b136202a5ef1e18))
* **test:** stop comparing rows that no walk could measure ([#43](https://github.com/OneLiteFeatherNET/Falco/issues/43)) ([c4239bd](https://github.com/OneLiteFeatherNET/Falco/commit/c4239bdf6acfcd3e8feceec5d0046ca895d16afc))


### Performance Improvements

* **light:** keep the opacity tables of a chunk with its light ([#34](https://github.com/OneLiteFeatherNET/Falco/issues/34)) ([ec35f61](https://github.com/OneLiteFeatherNET/Falco/commit/ec35f612e4fcda08ed2bad1f63297820584f0dde))
* **light:** resolve the opposite face once in the chunk propagator ([#32](https://github.com/OneLiteFeatherNET/Falco/issues/32)) ([d717ec8](https://github.com/OneLiteFeatherNET/Falco/commit/d717ec873491d088ed9f1a07cfb5aab889b77d1c))
* **light:** resolve the opposite face once instead of per neighbour ([#28](https://github.com/OneLiteFeatherNET/Falco/issues/28)) ([13ccbfb](https://github.com/OneLiteFeatherNET/Falco/commit/13ccbfba40966d3daa857b30d3f08c8b20ebd92f))
* **light:** seed the sky from the heightmap instead of every open cell ([#41](https://github.com/OneLiteFeatherNET/Falco/issues/41)) ([272cb0b](https://github.com/OneLiteFeatherNET/Falco/commit/272cb0b309709f08987eeed5684a7148cb6004de))
* **light:** three changes to the propagation loop from item 2 of Open ([#36](https://github.com/OneLiteFeatherNET/Falco/issues/36)) ([c206ef3](https://github.com/OneLiteFeatherNET/Falco/commit/c206ef33f34a6b3bda36a09d2b297ac9fe39191e))

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
