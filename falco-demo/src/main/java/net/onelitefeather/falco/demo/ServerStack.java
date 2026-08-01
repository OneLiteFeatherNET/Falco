package net.onelitefeather.falco.demo;

import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.utils.chunk.ChunkSupplier;
import net.onelitefeather.falco.instance.FalcoInstance;
import net.onelitefeather.falco.light.ChunkLightScheduler;
import net.onelitefeather.falco.light.ChunkLightService;
import net.onelitefeather.falco.light.FalcoLightingChunk;
import org.jetbrains.annotations.Contract;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The {@link ServerStack} enum names the two stacks the demo server can run and is the only place
 * the two differ.
 * <p>
 * The comparison is worth something only if a reader can see that it rests on one variable. The two
 * server tasks therefore start the identical class with the identical arguments except for
 * {@code --stack}, and everything a stack decides — which loader reads the region files, which chunk
 * type carries the light — is listed here rather than scattered through the server. The world, the
 * view distance, the port, the game mode and the reporting are shared code and cannot drift apart.
 * </p>
 * <p>
 * <b>Both stacks run on an {@code InstanceContainer}, and that is a decision rather than an
 * oversight.</b> {@link FalcoInstance} is the third published module of this repository and would be
 * the obvious third component of the Falco stack, but it cannot be combined with
 * {@link FalcoLightingChunk}: {@code FalcoInstance} refuses every chunk which is not a
 * {@code FalcoChunk}, because {@code Chunk#onLoad} and {@code Chunk#unload} are package private in
 * Minestom and it re-exposes them through that subclass. {@code FalcoLightingChunk} extends
 * {@code DynamicChunk} instead, so an instance handed the light supplier would throw on the first
 * chunk it loaded. One of the two has to go, and for this demo it is the instance: what
 * {@code FalcoInstance} buys — a clean unregister and a block write guarded per chunk instead of per
 * instance — is invisible to somebody flying through a world nobody edits, while the light is the
 * first thing they look at. Using the container on both sides has a second benefit that is worth as
 * much: the two servers then differ in the loader and the chunk type and in nothing else.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.3.0
 */
public enum ServerStack {

    /**
     * The stack of this repository: the Falco Anvil loader, and chunks which keep their own light up
     * to date through the Falco light engine.
     */
    FALCO("falco", LoaderKind.FALCO, FalcoLightingChunk.class.getName(),
            ChunkLightScheduler.class.getName() + ", off the tick thread, one pass per tick"),

    /**
     * The stack Minestom ships with, run as it is rather than as a reimplementation.
     */
    MINESTOM("minestom", LoaderKind.MINESTOM, LightingChunk.class.getName(),
            "built into " + LightingChunk.class.getName());

    private final String option;

    private final LoaderKind loader;

    private final String chunkImplementationName;

    private final String lightEngineDescription;

    /**
     * Creates a constant with everything which distinguishes one stack from the other.
     *
     * @param option                  the value the {@code --stack} argument accepts
     * @param loader                  the chunk loader this stack reads its world with
     * @param chunkImplementationName the fully qualified name of the chunk type this stack uses
     * @param lightEngineDescription  how the light of a chunk is kept up to date in this stack
     */
    ServerStack(String option, LoaderKind loader, String chunkImplementationName, String lightEngineDescription) {
        this.option = option;
        this.loader = loader;
        this.chunkImplementationName = chunkImplementationName;
        this.lightEngineDescription = lightEngineDescription;
    }

    /**
     * Returns the value the {@code --stack} argument accepts for this constant.
     *
     * @return the option value
     */
    @Contract(pure = true)
    public String option() {
        return this.option;
    }

    /**
     * Returns the loader this stack reads its chunks with.
     *
     * @return the loader of this stack
     */
    @Contract(pure = true)
    public LoaderKind loader() {
        return this.loader;
    }

    /**
     * Returns the fully qualified name of the chunk type this stack uses.
     *
     * @return the fully qualified class name of the chunk
     */
    @Contract(pure = true)
    public String chunkImplementationName() {
        return this.chunkImplementationName;
    }

    /**
     * Returns how the light of a chunk is kept up to date in this stack.
     *
     * @return the description of the light engine
     */
    @Contract(pure = true)
    public String lightEngineDescription() {
        return this.lightEngineDescription;
    }

    /**
     * Returns the short name shown in the action bar and in the chat.
     *
     * @return the name of this stack for a player
     */
    @Contract(pure = true)
    public String displayName() {
        return switch (this) {
            case FALCO -> "Falco";
            case MINESTOM -> "Minestom";
        };
    }

    /**
     * Selects the constant for the given option value, ignoring case.
     *
     * @param value the value of the {@code --stack} argument
     * @return the selected constant
     * @throws IllegalArgumentException if no constant carries that option value
     */
    public static ServerStack parse(String value) {
        String normalised = value.toLowerCase(Locale.ROOT);

        for (ServerStack stack : values()) {
            if (stack.option.equals(normalised)) {
                return stack;
            }
        }

        String known = Arrays.stream(values()).map(ServerStack::option).collect(Collectors.joining(", "));
        throw new IllegalArgumentException("Unknown stack '" + value + "'. Known stacks are " + known);
    }

    /**
     * Builds the chunk supplier of this stack.
     * <p>
     * For the Falco stack this is where the light engine is wired up: a fresh
     * {@link ChunkLightScheduler} is created and its supplier hands out chunks which report every
     * change to it. A scheduler serves exactly one instance, so a new one is built per call rather
     * than kept as a constant of the enum.
     * </p>
     *
     * @return the supplier the instance is given
     */
    public ChunkSupplier chunkSupplier() {
        return switch (this) {
            case FALCO -> new ChunkLightScheduler(new ChunkLightService()).supplier();
            case MINESTOM -> LightingChunk::new;
        };
    }

    /**
     * Lists what this stack is made of, one component per line, for the log the server prints on
     * startup.
     * <p>
     * Printed rather than documented, because a reader comparing two consoles has to be able to see
     * which types produced the numbers in front of them without trusting the name of a task.
     * </p>
     *
     * @return the components of this stack
     */
    public List<String> composition() {
        return List.of(
                "chunk loader   " + this.loader.implementationName(),
                "chunk type     " + this.chunkImplementationName,
                "light          " + this.lightEngineDescription,
                "instance       " + InstanceContainer.class.getName() + "  (both stacks, so they differ in nothing else)"
        );
    }

    /**
     * Returns the sentence which explains a choice the log would otherwise leave the reader guessing
     * about.
     *
     * @return the note for this stack, or an empty string when there is nothing to explain
     */
    @Contract(pure = true)
    public String note() {
        if (this != FALCO) {
            return "";
        }

        return FalcoInstance.class.getName() + " is deliberately not part of this stack: it only manages "
                + "FalcoChunk, so it cannot hold a FalcoLightingChunk, and its advantages do not show "
                + "in a world nobody edits. See falco-demo/README.md.";
    }
}
