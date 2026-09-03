/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2026 FabricMC
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

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import javax.inject.Inject;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.LocalState;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.api.RunConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.configuration.providers.minecraft.assets.AssetIndex;
import net.fabricmc.loom.util.MirrorUtil;
import net.fabricmc.loom.util.download.Download;
import net.fabricmc.loom.util.download.DownloadBuilder;
import net.fabricmc.loom.util.download.DownloadExecutor;

@DisableCachingByDefault(because = "Downloaded Minecraft assets should not be stored in the build cache")
public abstract class DownloadAssetsTask extends AbstractLoomTask {
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getMinecraftMetadata();

	@Input
	public abstract Property<Integer> getDownloadThreads();

	@Input
	public abstract Property<String> getResourcesBaseUrl();

	@Input
	public abstract Property<Boolean> getOffline();

	@Input
	public abstract Property<Boolean> getRefresh();

	@LocalState
	public abstract DirectoryProperty getAssetsDirectory();

	@LocalState
	public abstract DirectoryProperty getLegacyResourcesDirectory();

	@OutputFile
	public abstract RegularFileProperty getCompletionMarker();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public DownloadAssetsTask() {
		final File assetsDir = new File(getExtension().getFiles().getUserCache(), "assets");
		final RunConfiguration client = getExtension().getRunConfigs().findByName("client");

		getMinecraftMetadata().fileValue(getExtension().getMinecraftProvider().getMinecraftMetadataPath().toFile());
		getAssetsDirectory().set(assetsDir);
		getCompletionMarker().fileValue(getExtension().getFiles().getProjectPersistentCache().toPath()
				.resolve("assets")
				.resolve("download-complete.marker")
				.toFile());

		if (client != null) {
			getLegacyResourcesDirectory().set(client.getRunDirectory().dir("resources"));
		} else {
			getLegacyResourcesDirectory().set(getProject().getLayout().getProjectDirectory().dir("run/resources"));
		}

		getDownloadThreads().convention(Math.min(Runtime.getRuntime().availableProcessors(), 10));
		getResourcesBaseUrl().set(MirrorUtil.getResourcesBase(getProject()));
		getOffline().set(getProject().getGradle().getStartParameter().isOffline());
		getRefresh().set(getExtension().refreshDeps());
		getOutputs().upToDateWhen(task -> {
			final DownloadAssetsTask downloadAssets = (DownloadAssetsTask) task;
			return !downloadAssets.getRefresh().get() && downloadAssets.hasAllAssets();
		});
	}

	private boolean hasAllAssets() {
		try {
			final MinecraftVersionMeta versionInfo = LoomGradlePlugin.GSON.fromJson(
					Files.readString(getMinecraftMetadata().get().getAsFile().toPath(), StandardCharsets.UTF_8),
					MinecraftVersionMeta.class
			);

			if (versionInfo == null) {
				return false;
			}

			final Path assetsDirectory = getAssetsDirectory().get().getAsFile().toPath();
			final Path indexFile = assetsDirectory.resolve("indexes")
					.resolve(versionInfo.assetIndex().fabricId(versionInfo.id()) + ".json");

			if (!Files.isRegularFile(indexFile)) {
				return false;
			}

			final AssetIndex assetIndex = LoomGradlePlugin.GSON.fromJson(
					Files.readString(indexFile, StandardCharsets.UTF_8),
					AssetIndex.class
			);

			if (assetIndex == null) {
				return false;
			}

			final Path legacyResources = versionInfo.assets().equals("legacy")
					? assetsDirectory.resolve("legacy").resolve(versionInfo.id())
					: getLegacyResourcesDirectory().get().getAsFile().toPath();

			for (AssetIndex.Object object : assetIndex.getObjects()) {
				final Path asset = DownloadAssetsAction.getAssetsPath(object, assetIndex, assetsDirectory, legacyResources);

				if (!Files.isRegularFile(asset) || Files.size(asset) != object.size()) {
					return false;
				}
			}

			return true;
		} catch (Exception ignored) {
			return false;
		}
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(DownloadAssetsAction.class, parameters -> {
			parameters.getMinecraftMetadata().set(getMinecraftMetadata());
			parameters.getDownloadThreads().set(getDownloadThreads());
			parameters.getResourcesBaseUrl().set(getResourcesBaseUrl());
			parameters.getOffline().set(getOffline());
			parameters.getRefresh().set(getRefresh());
			parameters.getAssetsDirectory().set(getAssetsDirectory());
			parameters.getLegacyResourcesDirectory().set(getLegacyResourcesDirectory());
			parameters.getCompletionMarker().set(getCompletionMarker());
		});
	}

	public interface Parameters extends WorkParameters {
		RegularFileProperty getMinecraftMetadata();
		Property<Integer> getDownloadThreads();
		Property<String> getResourcesBaseUrl();
		Property<Boolean> getOffline();
		Property<Boolean> getRefresh();
		DirectoryProperty getAssetsDirectory();
		DirectoryProperty getLegacyResourcesDirectory();
		RegularFileProperty getCompletionMarker();
	}

	public abstract static class DownloadAssetsAction implements WorkAction<Parameters> {
		@Override
		public void execute() {
			final Path completionMarker = getParameters().getCompletionMarker().get().getAsFile().toPath();

			try {
				final MinecraftVersionMeta versionInfo = readMetadata();
				final AssetIndex assetIndex = getAssetIndex(versionInfo);
				final Path assetsDirectory = getParameters().getAssetsDirectory().get().getAsFile().toPath();
				final Path legacyResources = versionInfo.assets().equals("legacy")
						? assetsDirectory.resolve("legacy").resolve(versionInfo.id())
						: getParameters().getLegacyResourcesDirectory().get().getAsFile().toPath();

				try (DownloadExecutor executor = new DownloadExecutor(getParameters().getDownloadThreads().get())) {
					for (AssetIndex.Object object : assetIndex.getObjects()) {
						final String sha1 = object.hash();
						final String url = getParameters().getResourcesBaseUrl().get() + sha1.substring(0, 2) + "/" + sha1;
						createDownload(url).sha1(sha1).downloadPathAsync(getAssetsPath(object, assetIndex, assetsDirectory, legacyResources), executor);
					}
				}

				Files.createDirectories(completionMarker.getParent());
				Files.writeString(completionMarker, versionInfo.id() + '\n' + Objects.toString(versionInfo.assetIndex().sha1(), ""), StandardCharsets.UTF_8);
			} catch (Exception e) {
				try {
					Files.deleteIfExists(completionMarker);
				} catch (IOException cleanupException) {
					e.addSuppressed(cleanupException);
				}

				throw new RuntimeException("Failed to download Minecraft assets", e);
			}
		}

		private MinecraftVersionMeta readMetadata() throws IOException {
			try (Reader reader = Files.newBufferedReader(getParameters().getMinecraftMetadata().get().getAsFile().toPath(), StandardCharsets.UTF_8)) {
				return Objects.requireNonNull(LoomGradlePlugin.GSON.fromJson(reader, MinecraftVersionMeta.class), "Minecraft metadata is empty");
			}
		}

		private AssetIndex getAssetIndex(MinecraftVersionMeta versionInfo) throws IOException, URISyntaxException {
			final MinecraftVersionMeta.AssetIndex assetIndex = versionInfo.assetIndex();
			final Path indexFile = getParameters().getAssetsDirectory().get().getAsFile().toPath()
					.resolve("indexes")
					.resolve(assetIndex.fabricId(versionInfo.id()) + ".json");
			final String json = createDownload(assetIndex.url())
					.sha1(assetIndex.sha1())
					.downloadString(indexFile);
			return Objects.requireNonNull(LoomGradlePlugin.GSON.fromJson(json, AssetIndex.class), "Minecraft asset index is empty");
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

		static Path getAssetsPath(AssetIndex.Object object, AssetIndex index, Path assetsDirectory, Path legacyResources) {
			if (index.mapToResources() || index.virtual()) {
				return legacyResources.resolve(object.path());
			}

			return assetsDirectory.resolve("objects").resolve(object.hash().substring(0, 2)).resolve(object.hash());
		}
	}
}
