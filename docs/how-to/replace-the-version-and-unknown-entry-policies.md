# Replace the version and unknown-entry policies

Swap out the check that decides whether a chunk is readable, or the substitution applied to an
unknown block or biome — either by handing the loader an instance directly or by registering one on
the classpath.

**Before you start:** `falco-anvil` on the classpath, and a loader built through
`FalcoAnvilLoader.builder()`. A loader built through the two-argument constructor uses classpath
discovery for both policies, which is what keeps its behaviour unchanged.

## Set a policy explicitly

```java
FalcoAnvilLoader loader = FalcoAnvilLoader.builder()
        .versionPolicy(myVersionPolicy)
        .unknownEntryPolicy(myUnknownEntryPolicy)
        .build(Path.of("worlds", "lobby"), dimension);
```

Setting a slot turns classpath discovery off for that policy. Setting both the slot and discovery
for the same policy throws `IllegalStateException`.

## Register a policy on the classpath instead

1. Implement `ChunkVersionPolicy` or `UnknownEntryPolicy`.
2. Register it in your own `META-INF/services` file.
3. Leave the builder slot untouched, or call `discoverVersionPolicy()` /
   `discoverUnknownEntryPolicy()` explicitly.

Falco's own default steps aside for a single foreign provider. Registering **two** foreign providers
for the same service throws at build time, naming both classes.

## Change only the version floor

Keep the shipped policy and move the number it compares against:

```java
FalcoAnvilLoader loader = FalcoAnvilLoader.builder()
        .minimumDataVersion(FalcoAnvilLoader.DEFAULT_MINIMUM_DATA_VERSION)  // read: reject below this
        .dataVersion(4189)                                                  // write: stamp this
        .build(Path.of("worlds", "lobby"), dimension);
```

The two slots are unrelated: one is what a load has to clear, the other is what a save claims.

## Turn the version guard off

```java
FalcoAnvilLoader loader = FalcoAnvilLoader.builder()
        .versionPolicy(null)
        .build(Path.of("worlds", "lobby"), dimension);
```

**Losing the guard has a cost.** A loader with no version policy reads a pre-`21w43a` world as air
again, with no error and no log line — the failure the guard exists to end. Read
[Explanation The chunk version guard](../explanation/the-chunk-version-guard.md) before doing this. There is no equivalent for
`UnknownEntryPolicy`: passing `null` there falls back to the shipped default, because a resolver
always needs an id to keep decoding.

## Check it worked

The loader's opening log line names the resolved version policy, or the word `none`. That line is
the only way to tell a guard that was replaced apart from one that was silently lost.

## If it does not work

A build-time throw naming two classes means two foreign providers are registered for the same
service; remove one. A guard that reports `none` when you expected discovery to find yours means the
provider is not visible to the `falco-anvil` classloader — under CloudNet, extension or plugin
classloaders this is the usual cause.

See also: [Reference Replaceable policies](../reference/replaceable-policies.md) for every slot, default and resolution rule ·
[Explanation The chunk version guard](../explanation/the-chunk-version-guard.md)
