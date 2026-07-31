package net.onelitefeather.falco.demo;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Contract;

/**
 * The {@link ServerOptions} record holds everything a demo server run may vary.
 * <p>
 * Deliberately short. Every option here is one the two servers have to agree on for the comparison
 * to mean anything, which is why they are passed by the shared part of the gradle configuration and
 * why the stack is the only value the two tasks set differently.
 * </p>
 * <p>
 * The view distance is <em>not</em> here, and that is not an omission. Minestom reads it once into
 * {@code ServerFlag.CHUNK_VIEW_DISTANCE} from the system property
 * {@code minestom.chunk-view-distance} when that class is initialised, which happens long before any
 * command line of ours could be applied. A second copy in this record could therefore disagree with
 * the value the server really uses, and the server logs the flag itself instead.
 * </p>
 *
 * @param stack                 the stack the server runs
 * @param port                  the port the server listens on
 * @param dimension             the key of the dimension which is read from the world
 * @param reportIntervalSeconds the number of seconds between two lines in the log
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.3.0
 */
public record ServerOptions(ServerStack stack, int port, Key dimension, int reportIntervalSeconds) {

    /**
     * The port both servers listen on unless the run says otherwise.
     * The vanilla default, so a client which was told nothing but the address still connects.
     */
    public static final int DEFAULT_PORT = 25565;

    /**
     * The number of seconds between two report lines in the log.
     * <p>
     * The action bar updates once a second while the figures are being watched; the log is the
     * record which is read afterwards, and one line per second would bury the rest of the output.
     * </p>
     */
    public static final int DEFAULT_REPORT_INTERVAL_SECONDS = 10;

    /**
     * The dimension a run reads unless the run says otherwise.
     */
    public static final Key DEFAULT_DIMENSION = DemoOptions.DEFAULT_DIMENSION;

    /**
     * The largest port number a socket can be bound to.
     */
    private static final int MAX_PORT = 65535;

    /**
     * Checks the bounds every field has to satisfy.
     *
     * @throws IllegalArgumentException if a value could not be used to start a server
     */
    public ServerOptions {
        if (port < 1 || port > MAX_PORT) {
            throw new IllegalArgumentException("--port has to be between 1 and " + MAX_PORT + " but was " + port);
        }
        if (reportIntervalSeconds < 1) {
            throw new IllegalArgumentException("--report has to be at least 1 second but was " + reportIntervalSeconds);
        }
    }

    /**
     * Builds the options a run uses when it passes nothing but the stack.
     *
     * @param stack the stack the server runs
     * @return the default options for that stack
     */
    @Contract(pure = true)
    public static ServerOptions defaults(ServerStack stack) {
        return new ServerOptions(stack, DEFAULT_PORT, DEFAULT_DIMENSION, DEFAULT_REPORT_INTERVAL_SECONDS);
    }

    /**
     * Parses the command line of a server run.
     * <p>
     * Every argument has the form {@code --name=value}, and an unknown name is refused rather than
     * ignored, for the same reason the measurement refuses one: a mistyped option which quietly ran
     * with the default would produce a session nobody could reproduce.
     * </p>
     *
     * @param arguments the command line arguments
     * @return the parsed options
     * @throws IllegalArgumentException if an argument is malformed, unknown, or out of bounds
     */
    public static ServerOptions parse(String[] arguments) {
        ServerStack stack = null;
        int port = DEFAULT_PORT;
        Key dimension = DEFAULT_DIMENSION;
        int reportIntervalSeconds = DEFAULT_REPORT_INTERVAL_SECONDS;

        for (String argument : arguments) {
            int separator = argument.indexOf('=');

            if (!argument.startsWith("--") || separator < 0) {
                throw new IllegalArgumentException("Expected an argument of the form --name=value but got '" + argument + "'");
            }

            String name = argument.substring(2, separator);
            String value = argument.substring(separator + 1);

            switch (name) {
                case "stack" -> stack = ServerStack.parse(value);
                case "port" -> port = number(name, value);
                case "dimension" -> dimension = key(value);
                case "report" -> reportIntervalSeconds = number(name, value);
                default -> throw new IllegalArgumentException(
                        "Unknown option '--" + name + "'. Known options are --stack, --port, --dimension, --report"
                );
            }
        }

        if (stack == null) {
            throw new IllegalArgumentException("--stack is required and has to be one of falco, minestom");
        }

        return new ServerOptions(stack, port, dimension, reportIntervalSeconds);
    }

    /**
     * Parses a numeric option value.
     *
     * @param name  the name of the option, for the message
     * @param value the raw value
     * @return the parsed number
     * @throws IllegalArgumentException if the value is not a number
     */
    private static int number(String name, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--" + name + " expects a whole number but got '" + value + "'");
        }
    }

    /**
     * Parses a dimension key, accepting a plain value without a namespace.
     *
     * @param value the raw value
     * @return the parsed key
     * @throws IllegalArgumentException if the value is not a valid key
     */
    private static Key key(String value) {
        try {
            return value.indexOf(':') < 0 ? Key.key("minecraft", value) : Key.key(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("--dimension expects a key such as minecraft:overworld but got '" + value + "'");
        }
    }
}
