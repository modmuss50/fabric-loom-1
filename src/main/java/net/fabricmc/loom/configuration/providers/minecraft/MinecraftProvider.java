/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2018-2025 FabricMC
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

package net.fabricmc.loom.configuration.providers.minecraft;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.gradle.api.Project;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.configuration.providers.BundleMetadata;
import net.fabricmc.loom.configuration.providers.minecraft.ManifestLocations.ManifestLocation;
import net.fabricmc.loom.task.DownloadMinecraftJarTask;
import net.fabricmc.loom.task.DownloadMinecraftMetadataTask;
import net.fabricmc.loom.task.NormalizeMinecraftServerJarTask;
import net.fabricmc.loom.task.ValidateMinecraftMetadataTask;
import net.fabricmc.loom.task.VerifyMinecraftJarTask;
import net.fabricmc.loom.util.Check;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.gradle.GradleUtils;

public abstract class MinecraftProvider {
	public static final String DOWNLOAD_METADATA_TASK = "downloadMinecraftMetadata";
	public static final String VALIDATE_METADATA_TASK = "validateMinecraftMetadata";
	public static final String DOWNLOAD_CLIENT_TASK = "downloadMinecraftClientJar";
	public static final String DOWNLOAD_SERVER_TASK = "downloadMinecraftServerJar";
	public static final String NORMALIZE_SERVER_TASK = "normalizeMinecraftServerJar";
	public static final String VERIFY_CLIENT_TASK = "verifyMinecraftClientJar";
	public static final String VERIFY_SERVER_TASK = "verifyMinecraftServerJar";

	private final MinecraftMetadataProvider metadataProvider;

	private File minecraftMetadataFile;
	private File minecraftDownloadedMetadataFile;
	private File minecraftDownloadedClientJar;
	private File minecraftClientJar;
	// Note this will be the boostrap jar starting with 21w39a
	private File minecraftServerJar;
	private File minecraftNormalizedServerJar;
	// The normalized and verified server jar.
	private File minecraftExtractedServerJar;
	private boolean initialized;

	private final ConfigContext configContext;

	public MinecraftProvider(MinecraftMetadataProvider metadataProvider, ConfigContext configContext) {
		this.metadataProvider = metadataProvider;
		this.configContext = configContext;
	}

	protected boolean provideClient() {
		return true;
	}

	protected boolean provideServer() {
		return true;
	}

	public void provide() throws Exception {
		initialize();
		registerDownloadTasks();

		final MinecraftLibraryProvider libraryProvider = new MinecraftLibraryProvider(this, configContext.project());
		libraryProvider.provide();
	}

	public final void initialize() {
		if (initialized) {
			return;
		}

		initFiles();
		initialized = true;
	}

	protected void initFiles() {
		minecraftDownloadedMetadataFile = file("minecraft-metadata-download.json");
		minecraftMetadataFile = file("minecraft-metadata.json");

		if (provideClient()) {
			minecraftDownloadedClientJar = file("minecraft-client-download.jar");
			minecraftClientJar = file("minecraft-client.jar");
		}

		if (provideServer()) {
			minecraftServerJar = file("minecraft-server-download.jar");
			minecraftNormalizedServerJar = file("minecraft-server-normalized.jar");
			minecraftExtractedServerJar = file("minecraft-extracted_server.jar");
		}
	}

	private void registerDownloadTasks() {
		final MinecraftTaskGraph taskGraph = MinecraftTaskGraph.get(getProject());
		final boolean offline = getProject().getGradle().getStartParameter().isOffline();
		final boolean refresh = getExtension().refreshDeps();
		final List<ManifestLocation> manifestLocations = new ArrayList<>();
		getExtension().getVersionsManifests().forEach(manifestLocations::add);
		manifestLocations.sort(null);

		final var downloadMetadata = getProject().getTasks().register(DOWNLOAD_METADATA_TASK, DownloadMinecraftMetadataTask.class, task -> {
			task.setDescription("Downloads the Minecraft version metadata.");
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.getMinecraftVersion().set(minecraftVersion());
			task.getManifestUrls().set(manifestLocations.stream().map(ManifestLocation::url).toList());
			task.getCustomMetadataUrl().set(getExtension().getCustomMinecraftMetadata());
			task.getOffline().set(offline);
			task.getRefresh().set(refresh);
			task.getManifestCacheDirectory().set(getExtension().getFiles().getUserCache().toPath().resolve("version-manifests").toFile());
			task.getOutputFile().fileValue(minecraftDownloadedMetadataFile);
		});
		taskGraph.registerOutput(minecraftDownloadedMetadataFile.toPath(), downloadMetadata);

		final MinecraftJarConfiguration<?, ?, ?> jarConfiguration = getExtension().getMinecraftJarConfiguration().get();
		final var validateMetadata = getProject().getTasks().register(VALIDATE_METADATA_TASK, ValidateMinecraftMetadataTask.class, task -> {
			task.setDescription("Validates the Minecraft version metadata.");
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.getInputMetadata().fileValue(minecraftDownloadedMetadataFile);
			task.getRequireClient().set(provideClient());
			task.getRequireServer().set(provideServer());
			task.getRequireLegacyVersion().set(jarConfiguration == MinecraftJarConfiguration.LEGACY_MERGED);
			task.getRequireModernVersion().set(jarConfiguration == MinecraftJarConfiguration.MERGED || jarConfiguration == MinecraftJarConfiguration.SPLIT);
			task.getCheckJavaVersion().set(!getExtension().disableObfuscation());
			task.getCurrentJavaVersion().set(Runtime.version().feature());
			task.getOutputMetadata().fileValue(minecraftMetadataFile);
		});
		taskGraph.dependsOn(validateMetadata, minecraftDownloadedMetadataFile.toPath());
		taskGraph.registerOutput(minecraftMetadataFile.toPath(), validateMetadata);

		if (provideClient()) {
			final var downloadClient = getProject().getTasks().register(DOWNLOAD_CLIENT_TASK, DownloadMinecraftJarTask.class, task -> {
				task.setDescription("Downloads the Minecraft client jar.");
				task.setGroup(Constants.TaskGroup.FABRIC);
				task.getMetadataFile().fileValue(minecraftMetadataFile);
				task.getArtifactKind().set(DownloadMinecraftJarTask.ArtifactKind.CLIENT);
				task.getOffline().set(offline);
				task.getRefresh().set(refresh);
				task.getOutputJar().fileValue(minecraftDownloadedClientJar);
			});
			taskGraph.dependsOn(downloadClient, minecraftMetadataFile.toPath());
			taskGraph.registerOutput(minecraftDownloadedClientJar.toPath(), downloadClient);

			final var verifyClient = getProject().getTasks().register(VERIFY_CLIENT_TASK, VerifyMinecraftJarTask.class, task -> {
				task.setDescription("Verifies the Minecraft client jar.");
				task.setGroup(Constants.TaskGroup.FABRIC);
				task.getInputJar().fileValue(minecraftDownloadedClientJar);
				task.getMinecraftVersion().set(minecraftVersion());
				task.getSide().set(VerifyMinecraftJarTask.Side.CLIENT);
				task.getVerificationEnabled().set(GradleUtils.getBooleanPropertyProvider(getProject(), Constants.Properties.ENABLE_MINECRAFT_VERIFICATION));
				task.getOffline().set(offline);
				task.getRefresh().set(refresh);
				task.getCrlCacheDirectory().set(getExtension().getFiles().getUserCache().toPath().resolve("crl").toFile());
				task.getOutputJar().fileValue(minecraftClientJar);
			});
			taskGraph.dependsOn(verifyClient, minecraftDownloadedClientJar.toPath());
			taskGraph.registerOutput(minecraftClientJar.toPath(), verifyClient);
		}

		if (provideServer()) {
			final var downloadServer = getProject().getTasks().register(DOWNLOAD_SERVER_TASK, DownloadMinecraftJarTask.class, task -> {
				task.setDescription("Downloads the Minecraft server jar.");
				task.setGroup(Constants.TaskGroup.FABRIC);
				task.getMetadataFile().fileValue(minecraftMetadataFile);
				task.getArtifactKind().set(DownloadMinecraftJarTask.ArtifactKind.SERVER);
				task.getOffline().set(offline);
				task.getRefresh().set(refresh);
				task.getOutputJar().fileValue(minecraftServerJar);
			});
			taskGraph.dependsOn(downloadServer, minecraftMetadataFile.toPath());
			taskGraph.registerOutput(minecraftServerJar.toPath(), downloadServer);

			final var normalizeServer = getProject().getTasks().register(NORMALIZE_SERVER_TASK, NormalizeMinecraftServerJarTask.class, task -> {
				task.setDescription("Extracts or normalizes the Minecraft server jar.");
				task.setGroup(Constants.TaskGroup.FABRIC);
				task.getInputJar().fileValue(minecraftServerJar);
				task.getOutputJar().fileValue(minecraftNormalizedServerJar);
			});
			taskGraph.dependsOn(normalizeServer, minecraftServerJar.toPath());
			taskGraph.registerOutput(minecraftNormalizedServerJar.toPath(), normalizeServer);

			final var verifyServer = getProject().getTasks().register(VERIFY_SERVER_TASK, VerifyMinecraftJarTask.class, task -> {
				task.setDescription("Verifies the Minecraft server jar.");
				task.setGroup(Constants.TaskGroup.FABRIC);
				task.getInputJar().fileValue(minecraftNormalizedServerJar);
				task.getMinecraftVersion().set(minecraftVersion());
				task.getSide().set(VerifyMinecraftJarTask.Side.SERVER);
				task.getVerificationEnabled().set(GradleUtils.getBooleanPropertyProvider(getProject(), Constants.Properties.ENABLE_MINECRAFT_VERIFICATION));
				task.getOffline().set(offline);
				task.getRefresh().set(refresh);
				task.getCrlCacheDirectory().set(getExtension().getFiles().getUserCache().toPath().resolve("crl").toFile());
				task.getOutputJar().fileValue(minecraftExtractedServerJar);
			});
			taskGraph.dependsOn(verifyServer, minecraftNormalizedServerJar.toPath());
			taskGraph.registerOutput(minecraftExtractedServerJar.toPath(), verifyServer);
		}
	}

	public File workingDir() {
		return minecraftWorkingDirectory(configContext.project(), minecraftVersion());
	}

	public File dir(String path) {
		return file(path);
	}

	public File file(String path) {
		return new File(workingDir(), path);
	}

	public Path path(String path) {
		return file(path).toPath();
	}

	public File getMinecraftClientJar() {
		Check.require(provideClient(), "Not configured to provide client jar");
		return minecraftClientJar;
	}

	public File getMinecraftExtractedServerJar() {
		Check.require(provideServer(), "Not configured to provide server jar");
		return minecraftExtractedServerJar;
	}

	// This may be the server bundler jar on newer versions prob not what you want.
	public File getMinecraftServerJar() {
		Check.require(provideServer(), "Not configured to provide server jar");
		return minecraftServerJar;
	}

	@Nullable
	public BundleMetadata getServerBundleMetadata() {
		return null;
	}

	public String minecraftVersion() {
		return Objects.requireNonNull(metadataProvider, "Metadata provider not setup").getMinecraftVersion();
	}

	public MinecraftVersionMeta getVersionInfo() {
		return Objects.requireNonNull(metadataProvider, "Metadata provider not setup").getVersionMeta();
	}

	public Path getMinecraftMetadataPath() {
		return minecraftMetadataFile.toPath();
	}

	/**
	 * @return true if the minecraft version is older than 1.3.
	 */
	public boolean isLegacyVersion() {
		return getVersionInfo().isLegacyVersion();
	}

	/**
	 * Returns true if the minecraft version is between Beta 1.0 (inclusive) and 1.3 (exclusive),
	 * which splits the {@code official} mapping namespace into env-specific variants.
	 */
	public boolean isLegacySplitOfficialNamespaceVersion() {
		return getVersionInfo().isLegacySplitOfficialNamespaceVersion();
	}

	public abstract List<Path> getMinecraftJars();

	public abstract MappingsNamespace getOfficialNamespace();

	protected Project getProject() {
		return configContext.project();
	}

	protected LoomGradleExtension getExtension() {
		return configContext.extension();
	}

	public boolean refreshDeps() {
		return getExtension().refreshDeps();
	}

	public static File minecraftWorkingDirectory(Project project, String version) {
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		return extension.getFiles().getProjectPersistentCache().toPath()
				.resolve("minecraft")
				.resolve(version)
				.toFile();
	}
}
