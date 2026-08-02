package net.onelitefeather.falco.instance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that {@link FalcoInstance} is a facade and not the class it replaced with delegation in
 * front of it.
 * <p>
 * §4.3 of the design says it in one sentence: <em>the facade must hold no state of its own, or it is
 * the same class with delegation in front of it</em>. §8 lists whether that holds as the open question
 * of this stage. A question that can only be answered by a person reading the file is answered again
 * every time somebody reads it, and differently; this class answers it once per build.
 * </p>
 *
 * <h2>Why this is reflection, and why that is allowed here</h2>
 * <p>
 * NFR-001 forbids reflection in the modules, so that they run without {@code --add-opens} and without
 * an open module. It says nothing about a test, and this repository already reads private fields of a
 * foreign library in {@code JolMeasurement} for a reason of the same shape: the property being
 * checked is a property of the declaration, and nothing but the declaration can be asked about it.
 * The alternative — a JOL walk of the shallow size — was rejected because it cannot tell a fifth
 * reference field from padding, which is exactly the blind spot the stage 2 result had to write down
 * about {@code ChunkFootprintTest}.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@DisplayName("The instance facade")
class InstanceFacadeTest {

    /**
     * The four types a field of the facade is allowed to have.
     */
    private static final Set<Class<?>> PARTS = Set.of(
            ChunkRegistry.class, ChunkLifecycle.class, BlockWriter.class, ChunkPersistence.class);

    /**
     * Returns every instance field the facade declares itself, ignoring what it inherits.
     *
     * @return the declared, non-static fields of the facade
     */
    private static List<Field> declaredFields() {
        return java.util.Arrays.stream(FalcoInstance.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> !field.isSynthetic())
                .toList();
    }

    @Test
    @DisplayName("declares exactly the four parts it delegates to")
    void testTheFacadeDeclaresOnlyItsParts() {
        final List<Field> fields = declaredFields();
        final String names = fields.stream()
                .map(field -> field.getType().getSimpleName() + " " + field.getName())
                .collect(Collectors.joining(", "));

        assertEquals(PARTS.size(), fields.size(),
                "the facade may hold one reference per part and nothing else, but it declares: " + names);
        for (Field field : fields) {
            assertTrue(PARTS.contains(field.getType()),
                    "the facade declares a field of type " + field.getType().getName() + " named "
                            + field.getName() + ", which is state of its own rather than a part; either it "
                            + "belongs in one of " + PARTS + " or the split of stage 3 has been undone");
        }
        assertEquals(PARTS,
                fields.stream().map(Field::getType).collect(Collectors.toUnmodifiableSet()),
                "every part has to be reachable from the facade, and each exactly once");
    }

    @Test
    @DisplayName("declares every one of them final")
    void testTheFacadeCannotSwapItsParts() {
        for (Field field : declaredFields()) {
            assertTrue(Modifier.isFinal(field.getModifiers()),
                    "the field " + field.getName() + " is not final; a part that can be replaced at "
                            + "runtime is a part two threads can disagree about");
        }
    }
}
