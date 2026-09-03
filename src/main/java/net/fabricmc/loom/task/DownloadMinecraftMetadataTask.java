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
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.LocalState;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;
import org.jetbrains.annotations.ApiStatus;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.configuration.providers.minecraft.VersionsManifest;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.download.Download;
import net.fabricmc.loom.util.download.DownloadBuilder;

@ApiStatus.Internal
@DisableCachingByDefault(because = "Downloaded Minecraft metadata should not be stored in the build cache")
public abstract class DownloadMinecraftMetadataTask extends AbstractLoomTask {
	@Input
	public abstract Property<String> getMinecraftVersion();

	@Input
	public abstract ListProperty<String> getManifestUrls();

	@Optional
	@Input
	public abstract Property<String> getCustomMetadataUrl();

	@Input
	public abstract Property<Boolean> getOffline();

	@Input
	public abstract Property<Boolean> getRefresh();

	@LocalState
	public abstract DirectoryProperty getManifestCacheDirectory();

	@OutputFile
	public abstract RegularFileProperty getOutputFile();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public DownloadMinecraftMetadataTask() {
		getManifestUrls().convention(List.of());
		getOffline().convention(false);
		getRefresh().convention(false);
		getOutputs().upToDateWhen(task -> !((DownloadMinecraftMetadataTask) task).getRefresh().get());
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(DownloadMinecraftMetadataAction.class, parameters -> {
			parameters.getMinecraftVersion().set(getMinecraftVersion());
			parameters.getManifestUrls().set(getManifestUrls());
			parameters.getCustomMetadataUrl().set(getCustomMetadataUrl());
			parameters.getOffline().set(getOffline());
			parameters.getRefresh().set(getRefresh());
			parameters.getManifestCacheDirectory().set(getManifestCacheDirectory());
			parameters.getOutputFile().set(getOutputFile());
		});
	}

	public interface Parameters extends WorkParameters {
		Property<String> getMinecraftVersion();
		ListProperty<String> getManifestUrls();
		Property<String> getCustomMetadataUrl();
		Property<Boolean> getOffline();
		Property<Boolean> getRefresh();
		DirectoryProperty getManifestCacheDirectory();
		RegularFileProperty getOutputFile();
	}

	public abstract static class DownloadMinecraftMetadataAction implements WorkAction<Parameters> {
		@Override
		public void execute() {
			final Path output = getParameters().getOutputFile().get().getAsFile().toPath();

			try {
				if (getParameters().getOffline().get() && !getParameters().getRefresh().get() && Files.exists(output)) {
					validateMetadata(output);
					return;
				}

				final VersionsManifest.Version version = getParameters().getCustomMetadataUrl().isPresent()
						? new VersionsManifest.Version(getParameters().getMinecraftVersion().get(), getParameters().getCustomMetadataUrl().get())
						: findVersion();
				final DownloadBuilder download = createDownload(version.url());

				if (version.sha1() != null) {
					download.sha1(version.sha1());
				} else {
					download.defaultCache();
				}

				download.downloadPath(output);
				validateMetadata(output);
			} catch (Exception e) {
				cleanOutput(output, e);
				throw new RuntimeException("Failed to download metadata for Minecraft " + getParameters().getMinecraftVersion().get(), e);
			}
		}

		private VersionsManifest.Version findVersion() throws Exception {
			final String minecraftVersion = getParameters().getMinecraftVersion().get();
			final List<Boolean> forceDownloadPasses = getParameters().getOffline().get() || getParameters().getRefresh().get()
					? List.of(false)
					: List.of(false, true);

			for (boolean forceDownload : forceDownloadPasses) {
				for (String manifestUrl : getParameters().getManifestUrls().get()) {
					final String fileName = Checksum.of(manifestUrl).sha1().hex() + ".json";
					final Path cacheFile = getParameters().getManifestCacheDirectory().file(fileName).get().getAsFile().toPath();
					final DownloadBuilder download = createDownload(manifestUrl).defaultCache();

					if (forceDownload) {
						download.forceDownload();
					}

					final String manifestJson = download.downloadString(cacheFile);
					final VersionsManifest manifest = LoomGradlePlugin.GSON.fromJson(manifestJson, VersionsManifest.class);
					final VersionsManifest.Version version = manifest.getVersion(minecraftVersion);

					if (version != null) {
						return version;
					}
				}
			}

			throw new IllegalStateException("Failed to find Minecraft version: " + minecraftVersion);
		}

		private DownloadBuilder createDownload(String url) throws URISyntaxException {
			final DownloadBuilder download = Download.create(url);

			if (getParameters().getOffline().get()) {
				download.offline();
			}

			if (getParameters().getRefresh().get()) {
				download.forceDownload();
			}

			return download;
		}

		private static void validateMetadata(Path output) throws IOException {
			try (Reader reader = Files.newBufferedReader(output, StandardCharsets.UTF_8)) {
				if (LoomGradlePlugin.GSON.fromJson(reader, MinecraftVersionMeta.class) == null) {
					throw new IOException("Downloaded Minecraft metadata is empty");
				}
			}
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
