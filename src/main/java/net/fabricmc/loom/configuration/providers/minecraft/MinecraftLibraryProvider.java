/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2018-2023 FabricMC
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactRepositoryContainer;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.minecraft.library.LibraryProcessorManager;
import net.fabricmc.loom.configuration.providers.minecraft.library.processors.RuntimeLog4jLibraryProcessor;
import net.fabricmc.loom.configuration.providers.minecraft.library.processors.RuntimeLwjglGraphicsLibraryProcessor;
import net.fabricmc.loom.task.DownloadMinecraftLibrariesTask;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.MirrorUtil;
import net.fabricmc.loom.util.Platform;
import net.fabricmc.loom.util.gradle.GradleUtils;

public class MinecraftLibraryProvider {
	private static final String DOWNLOAD_LIBRARIES_TASK = "downloadMinecraftLibraries";

	private final Project project;
	private final MinecraftProvider minecraftProvider;

	public MinecraftLibraryProvider(MinecraftProvider minecraftProvider, Project project) {
		this.project = project;
		this.minecraftProvider = minecraftProvider;
	}

	private List<String> getEnabledProcessors() {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);

		var enabledProcessors = new ArrayList<String>();

		if (extension.getRuntimeOnlyLog4j().get()) {
			enabledProcessors.add(RuntimeLog4jLibraryProcessor.class.getSimpleName());
		}

		if (extension.getRuntimeOnlyLwjglGraphics().get()) {
			enabledProcessors.add(RuntimeLwjglGraphicsLibraryProcessor.class.getSimpleName());
		}

		final Provider<String> libraryProcessorsProperty = project.getProviders().gradleProperty(Constants.Properties.LIBRARY_PROCESSORS);

		if (libraryProcessorsProperty.isPresent()) {
			String[] split = libraryProcessorsProperty.get().split(":");
			enabledProcessors.addAll(Arrays.asList(split));
		}

		return Collections.unmodifiableList(enabledProcessors);
	}

	public void provide() {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final MinecraftJarConfiguration jarConfiguration = extension.getMinecraftJarConfiguration().get();
		final boolean provideClient = jarConfiguration.supportedEnvironments().contains("client");
		final boolean provideServer = jarConfiguration.supportedEnvironments().contains("server");
		assert provideClient || provideServer;
		validateProcessorFactories(extension);

		if (extension.isCollectingDependencyVerificationMetadata()) {
			throw new UnsupportedOperationException("Dependency verification metadata generation is not yet supported by task-backed Minecraft libraries");
		}

		final Path output = minecraftProvider.path("libraries");
		final TaskProvider<DownloadMinecraftLibrariesTask> task = registerTask(extension, output, provideClient, provideServer);
		project.getTasks().named(TaskBasedMinecraftConfiguration.PROCESS_MINECRAFT_JARS_TASK).configure(aggregate -> aggregate.dependsOn(task));

		if (provideClient) {
			addFiles(Constants.Configurations.MINECRAFT_CLIENT_COMPILE_LIBRARIES, task,
					output.resolve(DownloadMinecraftLibrariesTask.COMMON_COMPILE_DIRECTORY),
					output.resolve(DownloadMinecraftLibrariesTask.CLIENT_COMPILE_DIRECTORY));
			addFiles(Constants.Configurations.MINECRAFT_CLIENT_RUNTIME_LIBRARIES, task,
					output.resolve(DownloadMinecraftLibrariesTask.COMMON_RUNTIME_DIRECTORY),
					output.resolve(DownloadMinecraftLibrariesTask.CLIENT_RUNTIME_DIRECTORY),
					output.resolve(DownloadMinecraftLibrariesTask.LEGACY_CLIENT_RUNTIME_DIRECTORY),
					output.resolve(DownloadMinecraftLibrariesTask.COMMON_RUNTIME_NATIVES_DIRECTORY),
					output.resolve(DownloadMinecraftLibrariesTask.CLIENT_RUNTIME_NATIVES_DIRECTORY),
					output.resolve(DownloadMinecraftLibrariesTask.LEGACY_CLIENT_RUNTIME_NATIVES_DIRECTORY));
			addFiles(Constants.Configurations.MINECRAFT_NATIVES, task,
					output.resolve(DownloadMinecraftLibrariesTask.NATIVES_DIRECTORY),
					output.resolve(DownloadMinecraftLibrariesTask.COMMON_RUNTIME_NATIVES_DIRECTORY),
					output.resolve(DownloadMinecraftLibrariesTask.CLIENT_RUNTIME_NATIVES_DIRECTORY),
					output.resolve(DownloadMinecraftLibrariesTask.LEGACY_CLIENT_RUNTIME_NATIVES_DIRECTORY));
		}

		if (provideServer) {
			addFiles(Constants.Configurations.MINECRAFT_SERVER_COMPILE_LIBRARIES, task,
					output.resolve(DownloadMinecraftLibrariesTask.COMMON_COMPILE_DIRECTORY),
					output.resolve(DownloadMinecraftLibrariesTask.SERVER_COMPILE_DIRECTORY));
			addFiles(Constants.Configurations.MINECRAFT_SERVER_RUNTIME_LIBRARIES, task,
					output.resolve(DownloadMinecraftLibrariesTask.COMMON_RUNTIME_DIRECTORY),
					output.resolve(DownloadMinecraftLibrariesTask.SERVER_RUNTIME_DIRECTORY),
					output.resolve(DownloadMinecraftLibrariesTask.COMMON_RUNTIME_NATIVES_DIRECTORY),
					output.resolve(DownloadMinecraftLibrariesTask.SERVER_RUNTIME_NATIVES_DIRECTORY));
		}

		final String localMods = extension.disableObfuscation() ? Constants.Configurations.LOCAL_RUNTIME : "modLocalRuntime";
		addFiles(localMods, task, output.resolve(DownloadMinecraftLibrariesTask.LOCAL_MODS_DIRECTORY));
	}

	private void validateProcessorFactories(LoomGradleExtension extension) {
		if (!extension.getLibraryProcessors().get().equals(LibraryProcessorManager.DEFAULT_LIBRARY_PROCESSORS)) {
			throw new UnsupportedOperationException("Custom Minecraft library processor factories are not yet supported by task-backed Minecraft libraries");
		}
	}

	private JavaVersion getTargetRuntimeJavaVersion() {
		final Object property = GradleUtils.getProperty(project, Constants.Properties.RUNTIME_JAVA_COMPATIBILITY_VERSION);

		if (property != null) {
			// This is very much a last ditch effort to allow users to set the runtime java version
			// It's not recommended and will likely cause support confusion if it has been changed without good reason.
			project.getLogger().warn("Runtime java compatibility version has manually been set to: %s".formatted(property));
			return JavaVersion.toVersion(property);
		}

		return JavaVersion.current();
	}

	private TaskProvider<DownloadMinecraftLibrariesTask> registerTask(LoomGradleExtension extension, Path output, boolean provideClient, boolean provideServer) {
		final Platform platform = Platform.CURRENT;
		final Path artifactCache = output.resolveSibling("library-artifacts");
		final TaskProvider<DownloadMinecraftLibrariesTask> task = project.getTasks().register(DOWNLOAD_LIBRARIES_TASK, DownloadMinecraftLibrariesTask.class, downloadTask -> {
			downloadTask.setDescription("Downloads and prepares the Minecraft libraries.");
			downloadTask.setGroup(Constants.TaskGroup.FABRIC);
			downloadTask.getMinecraftMetadata().fileValue(minecraftProvider.getMinecraftMetadataPath().toFile());
			downloadTask.getProvideClient().set(provideClient);
			downloadTask.getProvideServer().set(provideServer);

			if (provideServer) {
				downloadTask.getMinecraftServerJar().fileValue(minecraftProvider.getMinecraftServerJar());
			}

			downloadTask.getRuntimeJavaVersion().set(Integer.parseInt(getTargetRuntimeJavaVersion().getMajorVersion()));
			downloadTask.getOperatingSystem().set(platform.getOperatingSystem());
			downloadTask.getArchitecture64Bit().set(platform.getArchitecture().is64Bit());
			downloadTask.getArchitectureArm().set(platform.getArchitecture().isArm());
			downloadTask.getArchitectureRiscV().set(platform.getArchitecture().isRiscV());
			downloadTask.getEnabledProcessors().set(getEnabledProcessors());
			downloadTask.getRepositoryUrls().set(List.of(
					MirrorUtil.getLibrariesBase(project),
					String.valueOf(ArtifactRepositoryContainer.MAVEN_CENTRAL_URL),
					MirrorUtil.getFabricRepository(project)
			));
			downloadTask.getOffline().set(project.getGradle().getStartParameter().isOffline());
			downloadTask.getRefresh().set(extension.refreshDeps());
			downloadTask.getOutputDirectory().set(output.toFile());
			downloadTask.getArtifactCacheDirectory().set(artifactCache.toFile());
		});

		final MinecraftTaskGraph taskGraph = MinecraftTaskGraph.get(project);
		taskGraph.dependsOn(task, minecraftProvider.getMinecraftMetadataPath());

		if (provideServer) {
			taskGraph.dependsOn(task, minecraftProvider.getMinecraftServerJar().toPath());
		}

		taskGraph.registerOutput(output, task);
		taskGraph.registerOutput(output.resolve(DownloadMinecraftLibrariesTask.COMMON_COMPILE_DIRECTORY), task);
		taskGraph.registerOutput(output.resolve(DownloadMinecraftLibrariesTask.CLIENT_COMPILE_DIRECTORY), task);
		taskGraph.registerOutput(output.resolve(DownloadMinecraftLibrariesTask.CLIENT_RUNTIME_DIRECTORY), task);
		taskGraph.registerOutput(output.resolve(DownloadMinecraftLibrariesTask.SERVER_COMPILE_DIRECTORY), task);
		taskGraph.registerOutput(output.resolve(DownloadMinecraftLibrariesTask.COMMON_RUNTIME_DIRECTORY), task);
		taskGraph.registerOutput(output.resolve(DownloadMinecraftLibrariesTask.SERVER_RUNTIME_DIRECTORY), task);
		taskGraph.registerOutput(output.resolve(DownloadMinecraftLibrariesTask.LEGACY_CLIENT_RUNTIME_DIRECTORY), task);
		taskGraph.registerOutput(output.resolve(DownloadMinecraftLibrariesTask.COMMON_RUNTIME_NATIVES_DIRECTORY), task);
		taskGraph.registerOutput(output.resolve(DownloadMinecraftLibrariesTask.CLIENT_RUNTIME_NATIVES_DIRECTORY), task);
		taskGraph.registerOutput(output.resolve(DownloadMinecraftLibrariesTask.SERVER_RUNTIME_NATIVES_DIRECTORY), task);
		taskGraph.registerOutput(output.resolve(DownloadMinecraftLibrariesTask.LEGACY_CLIENT_RUNTIME_NATIVES_DIRECTORY), task);
		taskGraph.registerOutput(output.resolve(DownloadMinecraftLibrariesTask.NATIVES_DIRECTORY), task);
		taskGraph.registerOutput(output.resolve(DownloadMinecraftLibrariesTask.LOCAL_MODS_DIRECTORY), task);
		return task;
	}

	private void addFiles(String configuration, TaskProvider<DownloadMinecraftLibrariesTask> task, Path... directories) {
		final ConfigurableFileCollection files = project.files();

		for (Path directory : directories) {
			files.from(project.fileTree(directory.toFile(), tree -> tree.include("**/*.jar")));
		}

		files.builtBy(task);
		project.getDependencies().add(configuration, files);
	}
}
