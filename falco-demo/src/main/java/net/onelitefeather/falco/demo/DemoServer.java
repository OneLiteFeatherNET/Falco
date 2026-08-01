package net.onelitefeather.falco.demo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.command.builder.Command;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.server.ServerTickMonitorEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.timer.TaskSchedule;
import net.minestom.server.world.DimensionType;
import net.onelitefeather.falco.demo.ChunkInventory.ChunkPosition;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@link DemoServer} class is the server the two server tasks start, so somebody can walk into
 * their own world and judge the two stacks by eye.
 * <p>
 * It answers a question the measurement tasks of this module cannot. {@code runFalcoLoader} and
 * {@code runMinestomLoader} load a fixed list of chunks in a fixed order and produce a reproducible
 * figure, which is exactly what a comparison needs and exactly what nobody plays. A world that
 * streams in smoothly while flying, light that is where it belongs, a server that keeps its tick —
 * none of those are that figure. This server is the subjective half of the module, and the two
 * halves are kept because neither replaces the other.
 * </p>
 * <p>
 * <b>The two servers are one class.</b> They differ in {@code --stack} and in nothing else: the same
 * world, the same view distance, the same port default, the same game mode, the same reporting. What
 * a stack decides lives in {@link ServerStack}, which prints itself into the log on startup so a
 * reader comparing two consoles can see which types produced the numbers rather than trusting the
 * name of a gradle task.
 * </p>
 * <p>
 * <b>The loader outlives the start.</b> It is handed to the instance and keeps its region files open
 * for as long as the server runs, so it must not sit in a try-with-resources around the server. It is
 * closed from a shutdown task instead, which also runs when the process is stopped with a signal.
 * </p>
 * <p>
 * <b>Nothing is written back.</b> Minestom saves neither on unload nor on shutdown unless it is
 * asked, and this server never asks. The world somebody drops into the module is read and left
 * exactly as it was.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.3.0
 */
public final class DemoServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoServer.class);

    /**
     * The address the server binds to.
     * <p>
     * All interfaces rather than the loopback, so the demo can also be looked at from the machine
     * next to it, which is a normal thing to want when the point is to watch somebody fly.
     * </p>
     */
    private static final String BIND_ADDRESS = "0.0.0.0";

    /**
     * The number of blocks above the ground a player is put down at.
     */
    private static final int SPAWN_CLEARANCE = 2;

    /**
     * The height a player is put at when the spawn chunk turns out to hold no block at all.
     */
    private static final int FALLBACK_SPAWN_Y = 128;

    /**
     * This class is only an entry point and is never instantiated.
     */
    private DemoServer() {
    }

    /**
     * Starts one demo server and blocks until it is stopped.
     * <p>
     * Everything the user can get wrong — the command line, a missing world, a world without chunks —
     * ends in a printed explanation and a normal exit, the same way the measurement tasks handle it.
     * A stack trace on this console always means a defect.
     * </p>
     *
     * @param arguments the command line of the run
     * @throws Exception if the world cannot be listed, which is a defect and not a user error
     */
    public static void main(String[] arguments) throws Exception {
        ServerOptions options;

        try {
            options = ServerOptions.parse(arguments);
        } catch (IllegalArgumentException exception) {
            System.out.print(DemoReport.invalidServerOptions(exception.getMessage()));
            return;
        }

        Path worldsDirectory = ChunkLoadDemo.worldsDirectory(
                System.getProperty(ChunkLoadDemo.WORLD_DIRECTORY_PROPERTY), Path.of("").toAbsolutePath()
        );

        WorldSearchResult search = WorldLocator.locate(worldsDirectory, options.dimension());

        if (search instanceof WorldSearchResult.Missing missing) {
            System.out.print(DemoReport.missingWorld(worldsDirectory, missing));
            return;
        }

        WorldSearchResult.Located world = (WorldSearchResult.Located) search;
        List<ChunkPosition> chunks = ChunkInventory.scan(world.regionDirectory(), 1);

        if (chunks.isEmpty()) {
            System.out.print(DemoReport.missingWorld(worldsDirectory, new WorldSearchResult.Missing(
                    "the region files in " + world.regionDirectory() + " mark no chunk as written"
            )));
            return;
        }

        start(options, world, chunks.getFirst());
    }

    /**
     * Builds and starts the server for a world which is already known to exist.
     *
     * @param options    the options of the run
     * @param world      the world the server reads
     * @param spawnChunk the first chunk the world holds, which the player is put into
     */
    private static void start(ServerOptions options, WorldSearchResult.Located world, ChunkPosition spawnChunk) {
        MinecraftServer server = MinecraftServer.init();

        LiveMetrics metrics = new LiveMetrics(System.nanoTime());
        TimingChunkLoader loader = new TimingChunkLoader(options.stack().loader().create(world), metrics);

        // The loader lives as long as the instance does, so it is closed from a shutdown task rather
        // than from a try-with-resources. Minestom runs the shutdown tasks for a signal as well,
        // which is how a demo stopped with ctrl-c still releases its region files.
        MinecraftServer.getSchedulerManager().buildShutdownTask(() -> close(loader));

        InstanceContainer instance = MinecraftServer.getInstanceManager()
                .createInstanceContainer(dimensionType(options), loader);
        instance.setChunkSupplier(options.stack().chunkSupplier());

        Pos spawn = new Pos(0, 65, 0);

        registerEvents(instance, spawn, options, metrics);
        AtomicReference<LiveMetrics.Snapshot> latest = scheduleReporting(instance, options, metrics);
        registerCommands(instance, options, latest);

        describe(options, world, spawn);
        server.start(BIND_ADDRESS, options.port());

        LOGGER.info("listening on {}:{} — connect with a Minecraft {} client (protocol {}) to localhost:{}",
                BIND_ADDRESS, options.port(), MinecraftServer.VERSION_NAME, MinecraftServer.PROTOCOL_VERSION, options.port());
        LOGGER.info("the figures appear in your action bar once a second; /falco prints them in full, "
                + "and /{} <{}> puts the sun where you want to judge the light from", TimeCommand.NAME, DayTime.names());
    }

    /**
     * Resolves the dimension type the instance is created with.
     *
     * @param options the options of the run
     * @return the registry key of the dimension, falling back to the overworld
     */
    private static RegistryKey<DimensionType> dimensionType(ServerOptions options) {
        @Nullable RegistryKey<DimensionType> key = MinecraftServer.getDimensionTypeRegistry().getKey(options.dimension());

        if (key != null) {
            return key;
        }

        LOGGER.warn("the server knows no dimension type {}, so the instance uses {} — the region files are still read "
                + "from the directory of {}", options.dimension().asString(), DimensionType.OVERWORLD.key().asString(),
                options.dimension().asString());
        return DimensionType.OVERWORLD;
    }

    /**
     * Loads the spawn chunk and picks a position above its ground.
     * <p>
     * This also serves as the first proof that the loader reads anything at all. A chunk which the
     * region header says exists but which arrives empty is the signature of a loader looking in the
     * wrong directory, and saying so here is far better than letting somebody fly through an
     * invisible world and conclude that the other stack is faster.
     * </p>
     *
     * @param instance   the instance the world is read into
     * @param spawnChunk the chunk the player is put into
     * @return the position the player spawns at
     */
    private static Pos spawn(InstanceContainer instance, ChunkPosition spawnChunk) {
        instance.loadChunk(spawnChunk.x(), spawnChunk.z()).join();

        int x = spawnChunk.x() * 16 + 8;
        int z = spawnChunk.z() * 16 + 8;
        DimensionType dimension = instance.getCachedDimensionType();

        for (int y = dimension.minY() + dimension.height() - 1; y >= dimension.minY(); y--) {
            if (!instance.getBlock(x, y, z).isAir()) {
                return new Pos(x + 0.5, y + SPAWN_CLEARANCE, z + 0.5);
            }
        }

        LOGGER.warn("the chunk {}:{} is listed in the region header but arrived without a single block. The loader "
                + "found nothing to read, so anything this session shows about it is meaningless.",
                spawnChunk.x(), spawnChunk.z());
        return new Pos(x + 0.5, FALLBACK_SPAWN_Y, z + 0.5);
    }

    /**
     * Registers everything the server reacts to.
     *
     * @param instance the instance players are put into
     * @param spawn    the position players start at
     * @param options  the options of the run
     * @param metrics  the metrics the tick times are reported to
     */
    private static void registerEvents(InstanceContainer instance, Pos spawn, ServerOptions options, LiveMetrics metrics) {
        GlobalEventHandler events = MinecraftServer.getGlobalEventHandler();

        events.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(spawn);
        });

        events.addListener(PlayerSpawnEvent.class, event -> {
            Player player = event.getPlayer();
            // Creative and flying, because the whole exercise is to cover ground fast enough that
            // the chunk streaming has to keep up with somebody.
            player.setGameMode(GameMode.CREATIVE);
            player.setAllowFlying(true);
            player.setFlying(true);
            welcome(player, options);
        });

        events.addListener(ServerTickMonitorEvent.class,
                event -> metrics.tickCompleted(event.getTickMonitor().getTickTime()));
    }

    /**
     * Tells a player which stack they just joined and how to read the figures.
     *
     * @param player  the player who joined
     * @param options the options of the run
     */
    private static void welcome(Player player, ServerOptions options) {
        ServerStack stack = options.stack();

        player.sendMessage(Component.text("Falco demo — " + stack.displayName() + " stack", colour(stack)));

        for (String line : stack.composition()) {
            player.sendMessage(Component.text("  " + line, NamedTextColor.GRAY));
        }

        player.sendMessage(Component.text(
                "  view distance  " + ServerFlag.CHUNK_VIEW_DISTANCE + " chunks", NamedTextColor.GRAY));
        player.sendMessage(Component.text(
                "The three numbers above your hotbar are p50/p95/max in milliseconds. Fly in a "
                        + "straight line for a while and watch the maximum; that is the stutter. "
                        + "/falco prints everything in full.", NamedTextColor.YELLOW));
        player.sendMessage(Component.text(
                "/" + TimeCommand.NAME + " <" + DayTime.names() + "> moves the sun, and /"
                        + TimeCommand.NAME + " " + TimeCommand.HOLD + " holds it there — the light "
                        + "is what you came to look at, and it should be the same light on both "
                        + "servers.", NamedTextColor.YELLOW));
    }

    /**
     * Starts the task which reports the figures while somebody is playing.
     * <p>
     * One task at one hertz drives both outputs. The action bar is where somebody flying actually
     * looks, and the log is the record that is still there afterwards; letting two tasks take their
     * own snapshots would make the two disagree, because a snapshot ends the interval the rate is
     * computed over.
     * </p>
     *
     * @param instance the instance whose chunks are counted
     * @param options  the options of the run
     * @param metrics  the metrics the figures come from
     * @return the most recent snapshot, for the status command
     */
    private static AtomicReference<LiveMetrics.Snapshot> scheduleReporting(
            InstanceContainer instance,
            ServerOptions options,
            LiveMetrics metrics
    ) {
        AtomicReference<LiveMetrics.Snapshot> latest = new AtomicReference<>(metrics.snapshot(System.nanoTime()));
        AtomicInteger secondsSinceLogLine = new AtomicInteger();

        MinecraftServer.getSchedulerManager().scheduleTask(() -> {
            LiveMetrics.Snapshot snapshot = metrics.snapshot(System.nanoTime());
            latest.set(snapshot);

            int loadedChunks = instance.getChunks().size();
            Collection<Player> players = MinecraftServer.getConnectionManager().getOnlinePlayers();
            Component line = Component.text(
                    LiveStatusLine.actionBar(options.stack(), snapshot, loadedChunks), colour(options.stack())
            );

            for (Player player : players) {
                player.sendActionBar(line);
            }

            if (secondsSinceLogLine.incrementAndGet() >= options.reportIntervalSeconds()) {
                secondsSinceLogLine.set(0);
                LOGGER.info(LiveStatusLine.logLine(options.stack(), snapshot, loadedChunks, players.size()));
            }
        }, TaskSchedule.seconds(1), TaskSchedule.seconds(1));

        return latest;
    }

    /**
     * Registers the commands a session offers: the figures in full, and the sky they were taken
     * under.
     *
     * @param instance the instance whose chunks are counted and whose clock is set
     * @param options  the options of the run
     * @param latest   the most recent snapshot the reporting task took
     */
    private static void registerCommands(
            InstanceContainer instance,
            ServerOptions options,
            AtomicReference<LiveMetrics.Snapshot> latest
    ) {
        Command command = new Command("falco", "demo");

        command.setDefaultExecutor((sender, _) -> {
            List<String> lines = LiveStatusLine.details(
                    options.stack(),
                    latest.get(),
                    instance.getChunks().size(),
                    MinecraftServer.getConnectionManager().getOnlinePlayerCount(),
                    ServerFlag.CHUNK_VIEW_DISTANCE
            );

            for (String line : lines) {
                sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
            }
        });

        MinecraftServer.getCommandManager().register(command);

        // The light of a world is a different thing at noon than at midnight, and the light is the
        // first thing anybody looks at here. Without this the sky is whatever the clock happens to
        // show, and two stacks looked at a few minutes apart are compared under two of them.
        MinecraftServer.getCommandManager().register(new TimeCommand(instance));
    }

    /**
     * Writes the conditions of the run into the log, above everything the session produces.
     * <p>
     * The same reason the measurement prints its conditions above its result: a figure whose stack,
     * world and view distance are not in front of the reader is not a comparison of anything.
     * </p>
     *
     * @param options the options of the run
     * @param world   the world the server reads
     * @param spawn   the position players start at
     */
    private static void describe(ServerOptions options, WorldSearchResult.Located world, Pos spawn) {
        ServerStack stack = options.stack();

        LOGGER.info("Falco demo server — {} stack", stack.displayName());

        for (String component : stack.composition()) {
            LOGGER.info("  {}", component);
        }

        if (!stack.note().isEmpty()) {
            LOGGER.info("  note           {}", stack.note());
        }

        LOGGER.info("  view distance  {} chunks  (-PviewDistance, identical on both servers)", ServerFlag.CHUNK_VIEW_DISTANCE);
        LOGGER.info("  world root     {}", world.worldRoot());
        LOGGER.info("  region files   {}  ({})", world.regionDirectory(), world.legacyLayout() ? "legacy layout" : "dimension layout");
        LOGGER.info("  dimension      {}", world.dimension().asString());
        LOGGER.info("  spawn          {} {} {}", (int) spawn.x(), (int) spawn.y(), (int) spawn.z());
        LOGGER.info("  minecraft      {} (protocol {}), offline mode — no Mojang authentication",
                MinecraftServer.VERSION_NAME, MinecraftServer.PROTOCOL_VERSION);
        LOGGER.info("  nothing is written back to this world");
    }

    /**
     * Picks the colour a stack is printed in, so two consoles or two sessions cannot be confused.
     *
     * @param stack the stack the server runs
     * @return the colour of that stack
     */
    private static NamedTextColor colour(ServerStack stack) {
        return switch (stack) {
            case FALCO -> NamedTextColor.AQUA;
            case MINESTOM -> NamedTextColor.GOLD;
        };
    }

    /**
     * Closes the loader during shutdown without letting a failure hide the shutdown itself.
     *
     * @param loader the loader to close
     */
    private static void close(TimingChunkLoader loader) {
        try {
            loader.close();
        } catch (Exception exception) {
            LOGGER.warn("the chunk loader did not close cleanly", exception);
        }
    }
}
