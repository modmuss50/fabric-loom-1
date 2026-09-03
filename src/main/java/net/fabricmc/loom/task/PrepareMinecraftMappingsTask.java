/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2026 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.task;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

import javax.inject.Inject;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.providers.mappings.tiny.MappingsMerger;
import net.fabricmc.loom.configuration.providers.mappings.tiny.TinyJarInfo;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.util.ZipReprocessorUtil;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.commands.CommandProposeFieldNames;

@ApiStatus.Internal
@CacheableTask
public abstract class PrepareMinecraftMappingsTask extends AbstractLoomTask {
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getMappingsJar();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	@Optional
	public abstract RegularFileProperty getIntermediaryMappings();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	@Optional
	public abstract RegularFileProperty getOfficialMinecraftJar();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getMinecraftMetadata();

	@Input
	public abstract Property<Boolean> getUseIntermediateMappings();

	@OutputFile
	public abstract RegularFileProperty getOutputMappings();

	@OutputFile
	public abstract RegularFileProperty getOutputMappingsJar();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public PrepareMinecraftMappingsTask() {
		getUseIntermediateMappings().convention(true);
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(PrepareMinecraftMappingsAction.class, parameters -> {
			parameters.getMappingsJar().set(getMappingsJar());
			parameters.getIntermediaryMappings().set(getIntermediaryMappings());
			parameters.getOfficialMinecraftJar().set(getOfficialMinecraftJar());
			parameters.getMinecraftMetadata().set(getMinecraftMetadata());
			parameters.getUseIntermediateMappings().set(getUseIntermediateMappings());
			parameters.getOutputMappings().set(getOutputMappings());
			parameters.getOutputMappingsJar().set(getOutputMappingsJar());
		});
	}

	public interface Parameters extends WorkParameters {
		RegularFileProperty getMappingsJar();
		RegularFileProperty getIntermediaryMappings();
		RegularFileProperty getOfficialMinecraftJar();
		RegularFileProperty getMinecraftMetadata();
		Property<Boolean> getUseIntermediateMappings();
		RegularFileProperty getOutputMappings();
		RegularFileProperty getOutputMappingsJar();
	}

	public abstract static class PrepareMinecraftMappingsAction implements WorkAction<Parameters> {
		private static final Logger LOGGER = LoggerFactory.getLogger(PrepareMinecraftMappingsAction.class);

		@Override
		public void execute() {
			final Path mappingsJar = getParameters().getMappingsJar().get().getAsFile().toPath();
			final Path outputMappings = getParameters().getOutputMappings().get().getAsFile().toPath();
			final Path outputMappingsJar = getParameters().getOutputMappingsJar().get().getAsFile().toPath();
			Path baseMappings = null;

			try {
				final MinecraftVersionMeta minecraftMetadata = readMinecraftMetadata();
				prepareOutput(outputMappings);
				prepareOutput(outputMappingsJar);
				validateMinecraftVersion(mappingsJar, minecraftMetadata.id());

				baseMappings = Files.createTempFile(outputMappings.getParent(), "loom-mappings-", ".tiny");
				TinyJarInfo.extractMappings(mappingsJar, baseMappings);

				if (isTinyV2(baseMappings)) {
					prepareTinyV2(baseMappings, outputMappings, minecraftMetadata.isLegacySplitOfficialNamespaceVersion());
				} else {
					prepareTinyV1(baseMappings, outputMappings);
				}

				Files.copy(mappingsJar, outputMappingsJar, StandardCopyOption.REPLACE_EXISTING);
				ZipReprocessorUtil.transformZipEntry(outputMappingsJar, TinyJarInfo.MAPPINGS_PATH, ignored -> Files.readAllBytes(outputMappings));
			} catch (Exception e) {
				cleanOutput(outputMappings, e);
				cleanOutput(outputMappingsJar, e);
				throw new RuntimeException("Failed to prepare Minecraft mappings: " + mappingsJar, e);
			} finally {
				if (baseMappings != null) {
					try {
						Files.deleteIfExists(baseMappings);
					} catch (IOException e) {
						LOGGER.warn("Failed to delete temporary mappings file {}", baseMappings, e);
					}
				}
			}
		}

		private MinecraftVersionMeta readMinecraftMetadata() throws IOException {
			final Path metadataPath = getParameters().getMinecraftMetadata().get().getAsFile().toPath();

			try (Reader reader = Files.newBufferedReader(metadataPath, StandardCharsets.UTF_8)) {
				return Objects.requireNonNull(LoomGradlePlugin.GSON.fromJson(reader, MinecraftVersionMeta.class), "Minecraft metadata is empty");
			}
		}

		private void validateMinecraftVersion(Path mappingsJar, String expectedVersion) {
			TinyJarInfo.get(mappingsJar).minecraftVersionId().ifPresent(version -> {
				if (!expectedVersion.equals(version)) {
					LOGGER.warn("The mappings were built for Minecraft version {}, but version {} is configured", version, expectedVersion);
				}
			});
		}

		private void prepareTinyV2(Path baseMappings, Path outputMappings, boolean legacySplit) throws IOException {
			if (!getParameters().getUseIntermediateMappings().get()) {
				Files.copy(baseMappings, outputMappings, StandardCopyOption.REPLACE_EXISTING);
				return;
			}

			if (!getParameters().getIntermediaryMappings().isPresent()) {
				throw new IllegalStateException("Intermediary mappings are required when useIntermediateMappings is enabled");
			}

			final String expectedSourceNamespace = legacySplit
					? MappingsNamespace.INTERMEDIARY.toString()
					: MappingsNamespace.OFFICIAL.toString();
			MappingsMerger.mergeAndSaveMappings(
					baseMappings,
					outputMappings,
					getParameters().getIntermediaryMappings().get().getAsFile().toPath(),
					expectedSourceNamespace,
					legacySplit
			);
		}

		private void prepareTinyV1(Path baseMappings, Path outputMappings) throws Exception {
			if (!getParameters().getOfficialMinecraftJar().isPresent()) {
				throw new IllegalStateException("An official Minecraft jar is required to prepare Tiny v1 mappings");
			}

			final Command command = new CommandProposeFieldNames();
			command.run(new String[] {
					getParameters().getOfficialMinecraftJar().get().getAsFile().getAbsolutePath(),
					baseMappings.toAbsolutePath().toString(),
					outputMappings.toAbsolutePath().toString()
			});
		}

		private static boolean isTinyV2(Path mappings) throws IOException {
			try (BufferedReader reader = Files.newBufferedReader(mappings, StandardCharsets.UTF_8)) {
				return MappingReader.detectFormat(reader) == MappingFormat.TINY_2_FILE;
			}
		}

		private static void prepareOutput(Path output) throws IOException {
			Files.createDirectories(output.getParent());
			Files.deleteIfExists(output);
		}

		private static void cleanOutput(Path output, Exception failure) {
			try {
				Files.deleteIfExists(output);
			} catch (IOException e) {
				failure.addSuppressed(e);
			}
		}
	}
}
