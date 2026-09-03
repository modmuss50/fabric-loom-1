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
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;
import org.jetbrains.annotations.ApiStatus;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;

@ApiStatus.Internal
@DisableCachingByDefault(because = "Downloaded Minecraft metadata should not be stored in the build cache")
public abstract class ValidateMinecraftMetadataTask extends AbstractLoomTask {
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getInputMetadata();

	@Input
	public abstract Property<Boolean> getRequireClient();

	@Input
	public abstract Property<Boolean> getRequireServer();

	@Input
	public abstract Property<Boolean> getRequireLegacyVersion();

	@Input
	public abstract Property<Boolean> getRequireModernVersion();

	@Input
	public abstract Property<Boolean> getCheckJavaVersion();

	@Input
	public abstract Property<Integer> getCurrentJavaVersion();

	@OutputFile
	public abstract RegularFileProperty getOutputMetadata();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public ValidateMinecraftMetadataTask() {
		getRequireClient().convention(false);
		getRequireServer().convention(false);
		getRequireLegacyVersion().convention(false);
		getRequireModernVersion().convention(false);
		getCheckJavaVersion().convention(true);
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(ValidateMinecraftMetadataAction.class, parameters -> {
			parameters.getInputMetadata().set(getInputMetadata());
			parameters.getRequireClient().set(getRequireClient());
			parameters.getRequireServer().set(getRequireServer());
			parameters.getRequireLegacyVersion().set(getRequireLegacyVersion());
			parameters.getRequireModernVersion().set(getRequireModernVersion());
			parameters.getCheckJavaVersion().set(getCheckJavaVersion());
			parameters.getCurrentJavaVersion().set(getCurrentJavaVersion());
			parameters.getOutputMetadata().set(getOutputMetadata());
		});
	}

	public interface Parameters extends WorkParameters {
		RegularFileProperty getInputMetadata();
		Property<Boolean> getRequireClient();
		Property<Boolean> getRequireServer();
		Property<Boolean> getRequireLegacyVersion();
		Property<Boolean> getRequireModernVersion();
		Property<Boolean> getCheckJavaVersion();
		Property<Integer> getCurrentJavaVersion();
		RegularFileProperty getOutputMetadata();
	}

	public abstract static class ValidateMinecraftMetadataAction implements WorkAction<Parameters> {
		@Override
		public void execute() {
			final Path input = getParameters().getInputMetadata().get().getAsFile().toPath();
			final Path output = getParameters().getOutputMetadata().get().getAsFile().toPath();

			try {
				final MinecraftVersionMeta metadata;

				try (Reader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
					metadata = Objects.requireNonNull(LoomGradlePlugin.GSON.fromJson(reader, MinecraftVersionMeta.class), "Minecraft metadata is empty");
				}

				validate(metadata);
				Files.createDirectories(output.getParent());
				Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception e) {
				try {
					Files.deleteIfExists(output);
				} catch (IOException cleanupException) {
					e.addSuppressed(cleanupException);
				}

				throw new RuntimeException("Failed to validate Minecraft metadata", e);
			}
		}

		private void validate(MinecraftVersionMeta metadata) {
			if (getParameters().getRequireClient().get() && !metadata.hasClient()) {
				throw new IllegalStateException("The selected Minecraft version does not provide a client jar");
			}

			if (getParameters().getRequireServer().get() && !metadata.hasServer()) {
				throw new IllegalStateException("The selected Minecraft version does not provide a server jar");
			}

			if (getParameters().getRequireLegacyVersion().get() && !metadata.isLegacyVersion()) {
				throw new IllegalStateException("The legacy merged jar layout requires a Minecraft version older than 1.3");
			}

			if (getParameters().getRequireModernVersion().get() && metadata.isLegacyVersion()) {
				throw new IllegalStateException("The merged jar layout cannot merge differently-obfuscated legacy client and server jars; select legacyMergedMinecraftJar()");
			}

			final MinecraftVersionMeta.JavaVersion requiredJava = metadata.javaVersion();

			if (getParameters().getCheckJavaVersion().get()
					&& requiredJava != null
					&& requiredJava.majorVersion() > getParameters().getCurrentJavaVersion().get()) {
				throw new IllegalStateException("Minecraft requires Java %d but Gradle is using Java %d".formatted(
						requiredJava.majorVersion(),
						getParameters().getCurrentJavaVersion().get()
				));
			}
		}
	}
}
