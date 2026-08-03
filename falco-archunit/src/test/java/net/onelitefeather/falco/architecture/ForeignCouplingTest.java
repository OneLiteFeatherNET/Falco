package net.onelitefeather.falco.architecture;

import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Guards the seam between Falco's own computation and the server it is meant to run inside.
 *
 * <p>Falco's second promise is a compute core that knows no server: the light engine is verified
 * against a handful of fake blocks, and the region file is measured in a bare JMH fork, neither of
 * them calling {@code MinecraftServer.init()}. That promise is what makes the published numbers
 * possible at all — Minestom's own {@code AnvilLoader} cannot be touched in a bare fork, because
 * its static fields read the registry.
 *
 * <p>Nothing in the build enforces it. Minestom, adventure-nbt and fastutil are {@code compileOnly}
 * in every module, so an import of a server type, an NBT tag or a file channel compiles straight
 * through and no test fails. These rules are the only thing standing between a stray import and a
 * core that quietly stops being independent.
 */
@AnalyzeClasses(
        packages = "net.onelitefeather.falco",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ForeignCouplingTest {

    static final String ANVIL    = "net.onelitefeather.falco.anvil..";
    static final String LIGHT    = "net.onelitefeather.falco.light..";
    static final String INSTANCE = "net.onelitefeather.falco.instance..";
    static final String DEMO     = "net.onelitefeather.falco.demo..";
    static final String BENCH    = "net.onelitefeather.falco.benchmark..";
    static final String[] PUBLISHED = {ANVIL, LIGHT, INSTANCE};

    private static final String LIGHT_BOUNDARY =
            "net\\.onelitefeather\\.falco\\.light\\."
          + "(ChunkLightService|ChunkLightArea|ChunkLightScheduler"
          + "|FalcoLightingChunk|ChunkLightListener|MinestomBlockLightSource)(\\$.*)?";

    private static final String ANVIL_MINESTOM_BOUNDARY =
            "net\\.onelitefeather\\.falco\\.anvil\\."
          + "(FalcoAnvilLoader|BlockPaletteResolver|BiomePaletteResolver)(\\$.*)?";

    private static final String ANVIL_NBT_LAYER =
            "net\\.onelitefeather\\.falco\\.anvil\\."
          + "(FalcoAnvilLoader|SectionCodec|NbtReads|PaletteEntryResolver"
          + "|BlockPaletteResolver|BiomePaletteResolver)(\\$.*)?";

    private static final String ANVIL_FILE_BOUNDARY =
            "net\\.onelitefeather\\.falco\\.anvil\\.(RegionFile|FalcoAnvilLoader)(\\$.*)?";

    private static final String LIGHT_WRITE_BOUNDARY =
            "net\\.onelitefeather\\.falco\\.light\\.ChunkLightService(\\$.*)?";

    private static final String REGISTRY_ADAPTERS =
            "net\\.onelitefeather\\.falco\\."
          + "(light\\.MinestomBlockLightSource|anvil\\.BlockPaletteResolver)(\\$.*)?";

    private static final String BIOME_RESOLVER =
            "net\\.onelitefeather\\.falco\\.anvil\\.BiomePaletteResolver(\\$.*)?";

    /**
     * F1 — Propagation has to stay verifiable without a running server, and it has to work against
     * any chunk implementation, not only Minestom's. {@code BlockLightSource.java:7-12} names that
     * seam itself, and the benefit is measurable rather than narrated: {@code LightPropagatorTest}
     * and {@code LightEngineConcurrencyTest} import no Minestom symbol, and
     * {@code LightPropagatorBenchmark} runs in a bare JMH fork against {@code FakeBlockLightSource}.
     *
     * <p>The exemption is a name regex ending in {@code (\$.*)?} rather than a list of class names,
     * because it has to fall on the outermost type: the private records
     * {@code ChunkLightService$NeighbourhoodEntry} and {@code ChunkLightArea$Entry} both carry a
     * {@code net.minestom.server.instance.Chunk} as a component. The cost of that is honest — every
     * nested type of a boundary class is exempt too, whether it needs to be or not.
     *
     * <p>{@code ChunkLightListener} is the sixth name on the list and it did not widen the boundary,
     * it split one of its members: the three reports it carries were three overrides of
     * {@code FalcoLightingChunk}, which was already exempt. It takes a
     * {@code net.minestom.server.instance.block.Block} because
     * {@code ChunkLifecycleListener#onBlockChange} hands it one.
     */
    @ArchTest
    static final ArchRule lightCoreKnowsNoMinestom = noClasses()
            .that().resideInAPackage(LIGHT).and().haveNameNotMatching(LIGHT_BOUNDARY)
            .should().dependOnClassesThat().resideInAnyPackage("net.minestom..")
            .because("propagation has to stay verifiable without a running server and work with any "
                   + "chunk implementation; only those six classes are the boundary");

    /**
     * F2 — {@code RegionFile} reads no registry, and that is the precondition of the published
     * numbers: {@code RegionFileComparisonBenchmark.java:45-48} records that Minestom's
     * {@code AnvilLoader} cannot be touched in a bare fork at all, because its static fields read
     * the biome registry and the block state count. {@code package-info.java:4-6} says the same
     * thing structurally — {@code FalcoAnvilLoader} is the entry point, everything else is a layer
     * below it. The three-stage load sequence also depends on it: "no CPU-bound work happens while
     * a lock is held" ({@code Rationale-Chunk-Loading.md:191-192}) only holds while stages one and
     * two cannot touch a chunk at all.
     *
     * <p>As in F1 the exemption falls on the outermost type, so the nested
     * {@code FalcoAnvilLoader$ResolvedRegionDirectory}, {@code $RegionHandle}, {@code $DecodedSection}
     * and {@code BiomePaletteResolver$Registries} come along with their enclosing class.
     */
    @ArchTest
    static final ArchRule anvilCoreKnowsNoMinestom = noClasses()
            .that().resideInAPackage(ANVIL).and().haveNameNotMatching(ANVIL_MINESTOM_BOUNDARY)
            .should().dependOnClassesThat().resideInAnyPackage("net.minestom..")
            .because("RegionFile reads no registry and is only for that reason measurable against "
                   + "Minestom's RegionFile in a bare JMH fork; package-info calls everything below "
                   + "the loader a layer beneath it");

    /**
     * F3a — {@code Section} is a record whose {@code clone()} ({@code instance/Section.java:26-33}
     * of Minestom {@code 2026.06.20-26.1.2}) calls {@code Light.sky()}/{@code Light.block()}
     * directly and copies existing light objects only via {@code set(array())}. A foreign
     * {@code Light} implementation is therefore silently replaced by the built-in one on the next
     * chunk copy — light looks right locally and disappears later. The prototype that carried a
     * marker byte {@code 0xCD} into the {@code LightData} record worked and was wrong anyway
     * ({@code Rationale-Lighting.md:472}); an ArchUnit test is the only safeguard, because it
     * compiles.
     */
    @ArchTest
    static final ArchRule noOwnLightImplementation = noClasses()
            .that().resideInAPackage("net.onelitefeather.falco..")
            .should().implement("net.minestom.server.instance.light.Light")
            .because("Section.clone() calls Light.sky()/Light.block() directly and silently replaces a "
                   + "foreign implementation while copying");

    /**
     * F3b — {@code calculateInternal} and {@code calculateExternal} carry {@code @ApiStatus.Internal}
     * and may change signature between Minestom versions; {@code Light#set}, the one entry Falco
     * uses, does not. Both mistakes would look identical in production, so the ban is worth a rule
     * even though nothing violates it today.
     *
     * <p>The rule is necessarily phrased over the <em>name</em> of the target method, not over its
     * annotation: this module imports only Falco classes and cannot read the annotations of a
     * Minestom method it never imported. A method that gains {@code @ApiStatus.Internal} later, or
     * an internal method under a different name, is not covered.
     */
    @ArchTest
    static final ArchRule noInternalLightCalls = noClasses()
            .that().resideInAPackage("net.onelitefeather.falco..")
            .should().callMethodWhere(
                    target(owner(nameMatching("net\\.minestom\\.server\\.instance\\.light\\..*")))
                            .and(target(nameMatching("calculate(Internal|External)"))))
            .because("both methods carry @ApiStatus.Internal and may change their signature between "
                   + "Minestom versions; Light#set may not");

    /**
     * F4 — The write path goes through {@code Light#set}, which also clears the section's update
     * flag, so the server never recomputes and a wrong result is never corrected
     * ({@code ChunkLightService.java:146-151}). Computed light therefore gets exactly one tested
     * write path: all three touching lines live in {@code ChunkLightService} ({@code :167},
     * {@code :170}, {@code :390}), and {@code ChunkLightArea} deliberately routes through
     * {@code ChunkLightService.applyLight(...)} ({@code ChunkLightArea.java:549}) instead of writing
     * itself.
     *
     * <p>The scope is deliberately narrowed to {@code falco-light}. Over
     * {@code net.onelitefeather.falco..} the rule would not hold: {@code FalcoAnvilLoader} reads
     * from and writes into that package as well ({@code :1056}, {@code :1059}, {@code :1254},
     * {@code :1255}), which is legitimate — the loader moves stored light, not computed light.
     */
    @ArchTest
    static final ArchRule onlyTheServiceWritesMinestomLight = noClasses()
            .that().resideInAPackage(LIGHT)
            .and().haveNameNotMatching(LIGHT_WRITE_BOUNDARY)
            .should().dependOnClassesThat().resideInAPackage("net.minestom.server.instance.light..")
            .because("Light#set clears the section's update flag - the server does not recompute after "
                   + "that, so computed light gets exactly one tested write path");

    /**
     * F5a — Both modules have an interface for exactly this purpose and both give the reason in
     * Javadoc ({@code BlockLightSource.java:8-11}, {@code PaletteEntryResolver.java:27}): the
     * algorithm and the codec stay verifiable without a running server. The second reason is
     * measured — Minestom's {@code LightCompute} resolves {@code Block.fromStateId} plus
     * {@code occlusionShape()} per direction while Falco answers the same question from an array,
     * "Falco resolves nothing here" ({@code Rationale-Lighting.md:143}). A {@code fromStateId}
     * resolution in {@code LightPropagator} or {@code SectionOpacity} would cost the server-free
     * testability <em>and</em> the only measured advantage.
     *
     * <p>The rule addresses the static registry entry points, not the type {@code Block} as a whole.
     * A naive "no method on {@code Block} outside the adapters" would not hold today:
     * {@code FalcoAnvilLoader} calls {@code block.withHandler(...)} ({@code :1138}),
     * {@code block.nbt()} ({@code :1215}) and {@code block.handler()} ({@code :1216}, {@code :1225})
     * on already resolved instances, which is not a registry resolution. A resolution reached
     * through some other route than these four names is not caught.
     */
    @ArchTest
    static final ArchRule blockRegistryOnlyInAdapters = noClasses()
            .that().resideInAnyPackage(LIGHT, ANVIL)
            .and().haveNameNotMatching(REGISTRY_ADAPTERS)
            .should().callMethodWhere(
                    target(owner(name("net.minestom.server.instance.block.Block")))
                            .and(target(nameMatching("from(StateId|Key|BlockId)"))))
            .orShould().accessFieldWhere(
                    JavaFieldAccess.Predicates.target(
                                    owner(name("net.minestom.server.instance.block.Block")))
                            .and(JavaFieldAccess.Predicates.target(name("AIR"))))
            .because("BlockLightSource and PaletteEntryResolver exist for exactly this: algorithm and "
                   + "codec stay verifiable without a running server, and the per-direction resolution "
                   + "is the measured cost against LightCompute");

    /**
     * F5b — The biome registry only exists on a running server, which is why it is wrapped behind a
     * supplier in a single adapter ({@code BiomePaletteResolver}, which resolves it lazily because
     * a loader is often built while the server is still starting). Any other class in
     * {@code .light} or {@code .anvil} reaching the dynamic registry would drag a live server into
     * a layer that is measured and tested without one.
     *
     * <p>{@code RegistryData} and its {@code *Entry} types are excluded, and that exclusion is the
     * point of the rule rather than a concession. The package holds two unrelated things: the
     * dynamic registry, which needs a booted server ({@code DynamicRegistry}, {@code RegistryKey},
     * {@code Registries}, {@code Holder}, {@code TagKey}), and {@code RegistryData}, the static
     * data tables a block carries. {@code MinestomBlockLightSource} reaches the latter through
     * {@code block.registry().occlusionShape()} ({@code :64}) and {@code lightEmission()}
     * ({@code :51}) — exactly the work F5a grants it by name. Over the whole package this rule
     * would revoke that grant, and the two rules would contradict each other.
     *
     * <p>Phrased as a complement rather than an allow list, so a new type in the package is covered
     * by default rather than on somebody remembering to add it.
     *
     * <p>{@code falco-instance} is out of scope here by design: an {@code Instance} implementation
     * has to query the registry ({@code FalcoInstance.java:373,434}).
     */
    @ArchTest
    static final ArchRule dynamicRegistryOnlyInBiomeResolver = noClasses()
            .that().resideInAnyPackage(LIGHT, ANVIL)
            .and().haveNameNotMatching(BIOME_RESOLVER)
            .should().dependOnClassesThat(
                    resideInAPackage("net.minestom.server.registry..")
                            .and(not(nameMatching("net\\.minestom\\.server\\.registry\\.RegistryData(\\$.*)?")))
                            .as("a type of the dynamic registry, which needs a booted server"))
            .because("the biome registry depends on a running server; it is wrapped behind a supplier "
                   + "in exactly one adapter");

    /**
     * F6 — The Javadoc of {@code RegionFile} ({@code :21-22}) claims it verbatim, and the claim is
     * load-bearing: Minestom's {@code RegionFile.readChunkData} holds its {@code ReentrantLock}
     * across the whole body including inflate and NBT parse
     * ({@code Rationale-Chunk-Loading.md:60-70}) <em>because the parser was reachable there</em>.
     * Falco's {@code RegionFile} cannot repeat that as long as it does not see adventure-nbt:
     * {@code readRaw} returns bytes and parsing happens a layer above, without a lock. A kyori
     * import down here is not untidy, it is the door through which the loss of predictability under
     * concurrency comes back.
     *
     * <p>Phrased as a complement rather than a hand-maintained allow list, so it covers
     * {@code PaletteData}, {@code AnvilChunkException} and nested types such as
     * {@code RegionFile$RawChunk} without maintenance when a class is added.
     */
    @ArchTest
    static final ArchRule byteLayerKnowsNoNbt = noClasses()
            .that().resideInAPackage(ANVIL).and().haveNameNotMatching(ANVIL_NBT_LAYER)
            .should().dependOnClassesThat().resideInAnyPackage("net.kyori..")
            .because("RegionFile is, by its own Javadoc, a pure byte container; that alone keeps the "
                   + "NBT parse from slipping inside the region lock the way it did in Minestom");

    /**
     * F7 — {@code BitPacker.java:11-12} states the reason itself: pure functions only, so the
     * encoding can be verified without any file or server access. That is not only testability but
     * the locking scheme — "no CPU-bound work happens while a lock is held" holds because the
     * decoding middle stage cannot open a file at all. Windows correctness hangs on it too: the
     * {@code .mcc} handling with {@code ATOMIC_MOVE}, {@code placeExternal} and
     * {@code removeExternal} was fixable in one place precisely because only one place knows file
     * names, and a {@code Files.readAllBytes} in {@code SectionCodec} would be a second one.
     *
     * <p>{@code java.io} is deliberately not blocked wholesale: {@code ChunkCompression.java:6-10}
     * uses {@code ByteArrayInputStream}/{@code ByteArrayOutputStream}, never a file. The name regex
     * is required over an exact class name because {@code FalcoAnvilLoader$ResolvedRegionDirectory}
     * ({@code :229}) carries a {@code Path} field.
     */
    @ArchTest
    static final ArchRule filesystemOnlyAtTheEdge = noClasses()
            .that().resideInAPackage(ANVIL)
            .and().haveNameNotMatching(ANVIL_FILE_BOUNDARY)
            .should().dependOnClassesThat(
                    resideInAnyPackage("java.nio.file..", "java.nio.channels..")
                            .or(belongToAnyOf(java.io.File.class, java.io.RandomAccessFile.class)))
            .because("the encoding layer has to stay verifiable without file or server access, and the "
                   + "rule 'no CPU-bound work under a lock' only holds if the middle stage cannot open "
                   + "a file at all; java.io stays allowed because of ChunkCompression");

    /**
     * F8 — The engine has exactly one exit, the finished {@code byte[2048]} through {@code Light#set};
     * it serialises nothing and knows no chunk storage format. That is why {@code falco-light} is
     * verified against a handful of fake blocks while {@code falco-anvil} needs region files for the
     * same purpose. The rule is not self-enforcing and the build proves it:
     * {@code falco-light/build.gradle.kts:7} pulls adventure-nbt as {@code compileOnly} although no
     * main class uses it, so a {@code CompoundBinaryTag} import in {@code LightNibbles} would compile
     * straight through unnoticed.
     *
     * <p>The {@code java.io} block is broad on purpose: the realistic slip is not a
     * {@code FileChannel} but a {@code ByteArrayOutputStream} somebody serialises light into "just
     * for a moment".
     */
    @ArchTest
    static final ArchRule lightNeitherParsesNorReads = noClasses()
            .that().resideInAPackage(LIGHT)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "net.kyori..", "java.io..", "java.nio.file..", "java.nio.channels..")
            .because("the engine has exactly one exit, Light#set; it knows no storage format and no "
                   + "file, or it would no longer be independent of the chunk implementation");
}
