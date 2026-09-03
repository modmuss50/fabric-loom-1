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

package net.fabricmc.loom.configuration.providers.mappings;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import javax.inject.Inject;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;
import org.jetbrains.annotations.ApiStatus;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.providers.mappings.extras.annotations.AnnotationsData;
import net.fabricmc.loom.configuration.providers.mappings.extras.annotations.AnnotationsLayer;
import net.fabricmc.loom.configuration.providers.mappings.tiny.TinyJarInfo;
import net.fabricmc.loom.configuration.providers.mappings.unpick.UnpickMetadata;
import net.fabricmc.loom.task.AbstractLoomTask;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.ZipUtils;

@ApiStatus.Internal
@CacheableTask
public abstract class PrepareMinecraftAnnotationsTask extends AbstractLoomTask {
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getInputJar();

	@Input
	public abstract Property<String> getMinecraftVersion();

	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(PrepareAction.class, parameters -> {
			parameters.getInputJar().set(getInputJar());
			parameters.getMinecraftVersion().set(getMinecraftVersion());
			parameters.getOutputJar().set(getOutputJar());
		});
	}

	public interface Parameters extends WorkParameters {
		RegularFileProperty getInputJar();
		Property<String> getMinecraftVersion();
		RegularFileProperty getOutputJar();
	}

	public abstract static class PrepareAction implements WorkAction<Parameters> {
		private static final Logger LOGGER = Logging.getLogger(PrepareAction.class);

		@Override
		public void execute() {
			final Path input = getParameters().getInputJar().get().getAsFile().toPath();
			final Path output = getParameters().getOutputJar().get().getAsFile().toPath();

			try {
				validate(input);
				Files.createDirectories(output.getParent());
				Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception e) {
				try {
					Files.deleteIfExists(output);
				} catch (IOException cleanupException) {
					e.addSuppressed(cleanupException);
				}

				throw new RuntimeException("Failed to prepare Minecraft annotations: " + input, e);
			}
		}

		private void validate(Path input) throws IOException {
			TinyJarInfo.get(input).minecraftVersionId().ifPresent(version -> {
				if (!getParameters().getMinecraftVersion().get().equals(version)) {
					LOGGER.warn("The annotations were built for Minecraft version {}, but version {} is configured", version, getParameters().getMinecraftVersion().get());
				}
			});

			final Path mappings = Files.createTempFile("loom-annotations-", ".tiny");

			try {
				TinyJarInfo.extractMappings(input, mappings);
				NoRemapMappingConfiguration.validateMappings(mappings);
			} finally {
				Files.deleteIfExists(mappings);
			}

			final byte[] annotations = ZipUtils.unpackNullable(input, AnnotationsLayer.ANNOTATIONS_PATH);

			if (annotations != null) {
				for (AnnotationsData data : AnnotationsData.readList(new StringReader(new String(annotations, StandardCharsets.UTF_8)))) {
					if (!MappingsNamespace.OFFICIAL.toString().equals(data.namespace())) {
						throw new IOException("Annotations patches must use the official namespace");
					}
				}
			}

			try (FileSystemUtil.Delegate delegate = FileSystemUtil.getJarFileSystem(input)) {
				validateUnpick(delegate.fs());
			}
		}

		private static void validateUnpick(FileSystem jar) throws IOException {
			final Path definitions = jar.getPath(UnpickMetadata.UNPICK_DEFINITIONS_PATH);
			final Path metadataPath = jar.getPath(UnpickMetadata.UNPICK_METADATA_PATH);
			final boolean hasDefinitions = Files.exists(definitions);
			final boolean hasMetadata = Files.exists(metadataPath);

			if (!hasDefinitions && !hasMetadata) {
				return;
			}

			if (!hasDefinitions || !hasMetadata) {
				throw new IOException("Mappings must contain both %s and %s"
						.formatted(UnpickMetadata.UNPICK_DEFINITIONS_PATH, UnpickMetadata.UNPICK_METADATA_PATH));
			}

			if (UnpickMetadata.parse(metadataPath) instanceof UnpickMetadata.V2 metadata
					&& !MappingsNamespace.OFFICIAL.toString().equals(metadata.namespace())) {
				throw new IOException("Annotations unpick definitions must use the official namespace");
			}
		}
	}
}
