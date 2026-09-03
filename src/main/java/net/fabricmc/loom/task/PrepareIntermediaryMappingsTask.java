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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import net.fabricmc.loom.configuration.providers.mappings.tiny.TinyJarInfo;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.util.download.Download;
import net.fabricmc.loom.util.download.DownloadBuilder;

@ApiStatus.Internal
@DisableCachingByDefault(because = "Downloaded intermediary mappings should not be stored in the build cache")
public abstract class PrepareIntermediaryMappingsTask extends AbstractLoomTask {
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getMinecraftMetadata();

	@Input
	public abstract Property<String> getIntermediaryUrl();

	@Input
	public abstract Property<Boolean> getOffline();

	@Input
	public abstract Property<Boolean> getRefresh();

	@OutputFile
	public abstract RegularFileProperty getOutputMappings();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public PrepareIntermediaryMappingsTask() {
		getOffline().convention(false);
		getRefresh().convention(false);
		getOutputs().upToDateWhen(task -> !((PrepareIntermediaryMappingsTask) task).getRefresh().get());
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(PrepareIntermediaryMappingsAction.class, parameters -> {
			parameters.getMinecraftMetadata().set(getMinecraftMetadata());
			parameters.getIntermediaryUrl().set(getIntermediaryUrl());
			parameters.getOffline().set(getOffline());
			parameters.getRefresh().set(getRefresh());
			parameters.getOutputMappings().set(getOutputMappings());
		});
	}

	public interface Parameters extends WorkParameters {
		RegularFileProperty getMinecraftMetadata();
		Property<String> getIntermediaryUrl();
		Property<Boolean> getOffline();
		Property<Boolean> getRefresh();
		RegularFileProperty getOutputMappings();
	}

	public abstract static class PrepareIntermediaryMappingsAction implements WorkAction<Parameters> {
		@Override
		public void execute() {
			final Path output = getParameters().getOutputMappings().get().getAsFile().toPath();
			Path downloadedJar = null;

			try {
				if (getParameters().getOffline().get() && !getParameters().getRefresh().get() && Files.exists(output)) {
					return;
				}

				Files.createDirectories(output.getParent());
				Files.deleteIfExists(output);
				downloadedJar = Files.createTempFile(output.getParent(), "loom-intermediary-", ".jar");
				Files.delete(downloadedJar);
				final DownloadBuilder download = Download.create(resolveUrl()).defaultCache();

				if (getParameters().getOffline().get()) {
					download.offline();
				}

				if (getParameters().getRefresh().get()) {
					download.forceDownload();
				}

				download.downloadPath(downloadedJar);
				TinyJarInfo.extractMappings(downloadedJar, output);
			} catch (Exception e) {
				cleanOutput(output, e);
				throw new RuntimeException("Failed to prepare intermediary mappings", e);
			} finally {
				if (downloadedJar != null) {
					try {
						Files.deleteIfExists(downloadedJar);
					} catch (IOException e) {
						// The temporary download will be cleared with the surrounding cache directory.
					}
				}
			}
		}

		private String resolveUrl() throws IOException {
			final Path metadataPath = getParameters().getMinecraftMetadata().get().getAsFile().toPath();
			final MinecraftVersionMeta metadata;

			try (Reader reader = Files.newBufferedReader(metadataPath, StandardCharsets.UTF_8)) {
				metadata = Objects.requireNonNull(LoomGradlePlugin.GSON.fromJson(reader, MinecraftVersionMeta.class), "Minecraft metadata is empty");
			}

			final String minecraftVersion = URLEncoder.encode(metadata.id(), StandardCharsets.UTF_8);
			return getParameters().getIntermediaryUrl().get().formatted(minecraftVersion);
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
