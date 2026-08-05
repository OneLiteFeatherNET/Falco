/**
 * The individual steps {@link net.onelitefeather.falco.migration.ChunkMigration} runs over a chunk's
 * root compound, one {@link net.onelitefeather.falco.migration.MigrationStep} implementation per row
 * of the step chain in
 * {@code docs/superpowers/specs/2026-08-04-falco-migration-design.md}.
 * <p>
 * Every public type here is experimental and may still change in a minor release.
 * </p>
 *
 * @since 2.1.0
 */
@NotNullByDefault
package net.onelitefeather.falco.migration.steps;

import org.jetbrains.annotations.NotNullByDefault;
