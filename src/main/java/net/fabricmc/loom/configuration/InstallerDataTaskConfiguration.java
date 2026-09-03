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

package net.fabricmc.loom.configuration;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.gradle.api.Project;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.util.Constants;

@ApiStatus.Internal
public final class InstallerDataTaskConfiguration {
	public static final String SCAN_INSTALLER_DATA_TASK = "scanInstallerData";
	public static final String DOWNLOAD_INSTALLER_LIBRARIES_TASK = "downloadInstallerLibraries";
	static final String FOUND_KEY = "found";
	static final String VERSION_KEY = "version";
	static final String INSTALLER_KEY = "installer";

	private InstallerDataTaskConfiguration() {
	}

	public static Tasks register(Project project) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final Path cacheRoot = getCacheRoot(project);
		final TaskProvider<ScanInstallerDataTask> scanTask = project.getTasks().register(SCAN_INSTALLER_DATA_TASK, ScanInstallerDataTask.class, task -> {
			task.setDescription("Scans mod dependencies for fabric-installer.json.");
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.getDescriptorFile().fileValue(getDescriptorPath(project).toFile());
			task.getInstallerJarDirectory().set(cacheRoot.resolve("loader").toFile());
		});
		final TaskProvider<DownloadInstallerLibrariesTask> downloadTask = project.getTasks().register(DOWNLOAD_INSTALLER_LIBRARIES_TASK, DownloadInstallerLibrariesTask.class, task -> {
			task.setDescription("Downloads libraries declared by fabric-installer.json.");
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.getDescriptorFile().set(scanTask.flatMap(ScanInstallerDataTask::getDescriptorFile));
			task.getOffline().set(project.getGradle().getStartParameter().isOffline());
			task.getRefresh().set(extension.refreshDeps());
			task.getOutputDirectory().set(cacheRoot.resolve("libraries").toFile());
			task.getArtifactCacheDirectory().set(cacheRoot.resolve("artifacts").toFile());
		});
		final FileTree outputTree = project.fileTree(
				downloadTask.flatMap(DownloadInstallerLibrariesTask::getOutputDirectory),
				tree -> tree.include("**/*.jar")
		);
		final ConfigurableFileCollection outputFiles = project.files(outputTree);
		outputFiles.builtBy(downloadTask);
		project.getDependencies().add(Constants.Configurations.LOADER_DEPENDENCIES, outputFiles);
		return new Tasks(scanTask, downloadTask, getDescriptorPath(project));
	}

	public static Tasks register(Project project, Iterable<?> inputJars) {
		final TaskProvider<ScanInstallerDataTask> scanTask = getDescriptorProducer(project);
		scanTask.configure(task -> task.getInputJars().from(inputJars));
		final List<String> repositoryUrls = project.getRepositories().withType(MavenArtifactRepository.class).stream()
				.map(repository -> repository.getUrl().toString())
				.distinct()
				.toList();
		final TaskProvider<DownloadInstallerLibrariesTask> downloadTask = project.getTasks().named(DOWNLOAD_INSTALLER_LIBRARIES_TASK, DownloadInstallerLibrariesTask.class);
		downloadTask.configure(task -> task.getRepositoryUrls().set(repositoryUrls));
		return new Tasks(scanTask, downloadTask, getDescriptorPath(project));
	}

	public static Path getDescriptorPath(Project project) {
		return getCacheRoot(project).resolve("descriptor.json");
	}

	public static TaskProvider<ScanInstallerDataTask> getDescriptorProducer(Project project) {
		return project.getTasks().named(SCAN_INSTALLER_DATA_TASK, ScanInstallerDataTask.class);
	}

	public static ConfigurableFileCollection getInstallerJars(Project project) {
		final TaskProvider<ScanInstallerDataTask> scanTask = getDescriptorProducer(project);
		final FileTree installerJars = project.fileTree(
				scanTask.flatMap(ScanInstallerDataTask::getInstallerJarDirectory),
				tree -> tree.include("*.jar")
		);
		final ConfigurableFileCollection files = project.files(installerJars);
		files.builtBy(scanTask);
		return files;
	}

	public static Provider<String> getInstallerVersion(Project project) {
		return getDescriptorContents(project).map(contents -> readInstallerVersion(contents)
				.orElseThrow(() -> new IllegalStateException("No fabric-installer.json was found in the project's mod dependencies")));
	}

	public static Provider<String> getInstallerVersionOrUnknown(Project project) {
		return getDescriptorContents(project).map(contents -> readInstallerVersion(contents).orElse("unknown"));
	}

	public static Provider<String> getMainClass(Project project, Provider<String> side) {
		return side.zip(getDescriptorContents(project), InstallerDataTaskConfiguration::readMainClass);
	}

	static Optional<String> readInstallerVersion(String contents) {
		final JsonObject descriptor = readDescriptor(contents);

		if (!descriptor.get(FOUND_KEY).getAsBoolean()) {
			return Optional.empty();
		}

		return Optional.of(descriptor.get(VERSION_KEY).getAsString());
	}

	static String readMainClass(String side, String contents) {
		final String fallback = switch (side) {
		case "client" -> Constants.Knot.KNOT_CLIENT;
		case "server" -> Constants.Knot.KNOT_SERVER;
		default -> throw new IllegalArgumentException("Unsupported run environment: " + side);
		};
		final JsonObject descriptor = readDescriptor(contents);

		if (!descriptor.get(FOUND_KEY).getAsBoolean()) {
			return fallback;
		}

		final JsonObject installer = descriptor.getAsJsonObject(INSTALLER_KEY);

		if (installer == null || !installer.has("mainClass")) {
			return fallback;
		}

		final JsonElement mainClass = installer.get("mainClass");
		final @Nullable JsonElement selected = mainClass.isJsonObject()
				? mainClass.getAsJsonObject().get(side)
				: mainClass;
		return selected == null || selected.isJsonNull() ? fallback : selected.getAsString();
	}

	public static Provider<String> getDescriptorContents(Project project) {
		return project.getProviders().fileContents(
				getDescriptorProducer(project).flatMap(ScanInstallerDataTask::getDescriptorFile)
		).getAsText();
	}

	private static JsonObject readDescriptor(String contents) {
		final JsonObject descriptor = LoomGradlePlugin.GSON.fromJson(contents, JsonObject.class);

		if (descriptor == null || !descriptor.has(FOUND_KEY)) {
			throw new IllegalArgumentException("Installer descriptor is empty or invalid");
		}

		return descriptor;
	}

	private static Path getCacheRoot(Project project) {
		return LoomGradleExtension.get(project).getFiles().getProjectPersistentCache().toPath().resolve("installer-data");
	}

	public record Tasks(
			TaskProvider<ScanInstallerDataTask> scanTask,
			TaskProvider<DownloadInstallerLibrariesTask> downloadTask,
			Path descriptorPath
	) {
	}
}
