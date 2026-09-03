/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2019-2026 FabricMC
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

package net.fabricmc.loom.configuration.mods;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.DocsType;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.FileTree;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.api.RemapConfigurationSettings;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.InstallerDataTaskConfiguration;
import net.fabricmc.loom.configuration.RemapConfigurations;
import net.fabricmc.loom.configuration.providers.mappings.RemapMappingConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftSourceSets;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftTaskGraph;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.ProcessedNamedMinecraftProvider;
import net.fabricmc.loom.task.service.ClasspathGroupService;
import net.fabricmc.loom.task.service.SourceRemapperService;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.Strings;
import net.fabricmc.loom.util.gradle.SourceSetHelper;
import net.fabricmc.loom.util.kotlin.KotlinClasspathService;
import net.fabricmc.loom.util.kotlin.KotlinPluginUtils;

@SuppressWarnings("UnstableApiUsage")
public final class ModConfigurationRemapper {
	// This is a placeholder that is used when the actual group is missing (null or empty).
	// This can happen when the dependency is a FileCollectionDependency or from a flatDir repository.
	public static final String MISSING_GROUP = "unspecified";

	private ModConfigurationRemapper() {
	}

	public static void supplyModConfigurations(Project project, LoomGradleExtension extension) {
		final List<RemapConfigurationSettings> settings = extension.getRemapConfigurations()
				.stream()
				.sorted(Comparator.comparing(setting -> !setting.getName().equals("modImplementation")))
				.toList();
		InstallerDataTaskConfiguration.register(project, settings.stream()
				.map(RemapConfigurationSettings::getSourceConfiguration)
				.toList());
		final List<NamedDomainObjectProvider<Configuration>> compileClasspath = settings.stream()
				.filter(setting -> setting.getOnCompileClasspath().get())
				.map(RemapConfigurationSettings::getSourceConfiguration)
				.toList();
		final List<NamedDomainObjectProvider<Configuration>> runtimeClasspath = settings.stream()
				.filter(setting -> setting.getOnRuntimeClasspath().get())
				.map(RemapConfigurationSettings::getSourceConfiguration)
				.toList();
		final Provider<KotlinClasspathService.Options> kotlinClasspath = KotlinPluginUtils.hasKotlinPlugin(project)
				? KotlinClasspathService.createOptions(project)
				: null;

		for (RemapConfigurationSettings setting : settings) {
			if (setting.getOnCompileClasspath().get()) {
				registerUsage(project, extension, setting, false, compileClasspath, kotlinClasspath);
			}

			if (setting.getOnRuntimeClasspath().get()) {
				registerUsage(project, extension, setting, true, runtimeClasspath, kotlinClasspath);
			}

			if (setting.getTargetConfigurationName().get().equals(JavaPlugin.API_CONFIGURATION_NAME)) {
				registerPublishingConfiguration(project, extension, setting, compileClasspath, kotlinClasspath);
			}
		}
	}

	private static void registerUsage(Project project, LoomGradleExtension extension, RemapConfigurationSettings setting, boolean runtime, List<NamedDomainObjectProvider<Configuration>> remapClasspath, @Nullable Provider<KotlinClasspathService.Options> kotlinClasspath) {
		final Configuration source = setting.getSourceConfiguration().get();
		final NamedDomainObjectProvider<? extends Configuration> target = RemapConfigurations.getOrRegisterCollectorConfiguration(project, setting, runtime);
		final NamedDomainObjectProvider<? extends Configuration> clientTarget;

		if (setting.getClientSourceConfigurationName().isPresent()) {
			final SourceSet clientSourceSet = SourceSetHelper.getSourceSetByName(MinecraftSourceSets.Split.CLIENT_ONLY_SOURCE_SET_NAME, project);
			clientTarget = RemapConfigurations.getOrRegisterCollectorConfiguration(project, clientSourceSet, runtime);
		} else {
			clientTarget = null;
		}

		final String taskName = "remap" + Strings.capitalize(setting.getName()) + (runtime ? "Runtime" : "Compile") + "Dependencies";
		final RemapWork work = registerTask(
				project,
				extension,
				taskName,
				source,
				remapClasspath,
				kotlinClasspath,
				clientTarget != null && extension.getSplitModDependencies().get(),
				runtime ? Usage.JAVA_RUNTIME : Usage.JAVA_API
		);
		addTaskOutput(project, target, work, "common");

		if (clientTarget != null) {
			addTaskOutput(project, clientTarget, work, "client");
		}

		if (runtime && clientTarget != null && extension.getSplitModDependencies().get()) {
			addGeneratedClasspathGroups(project, work);
		}
	}

	private static void registerPublishingConfiguration(Project project, LoomGradleExtension extension, RemapConfigurationSettings setting, List<NamedDomainObjectProvider<Configuration>> remapClasspath, @Nullable Provider<KotlinClasspathService.Options> kotlinClasspath) {
		final NamedDomainObjectProvider<? extends Configuration> remappedConfiguration = project.getConfigurations().register(
				setting.getRemappedConfigurationName(),
				configuration -> configuration.setTransitive(false)
		);
		project.getConfigurations().named(Constants.Configurations.NAMED_ELEMENTS).configure(configuration -> configuration.extendsFrom(remappedConfiguration.get()));
		final String taskName = "remap" + Strings.capitalize(setting.getName()) + "PublishedDependencies";
		final RemapWork work = registerTask(
				project,
				extension,
				taskName,
				setting.getSourceConfiguration().get(),
				remapClasspath,
				kotlinClasspath,
				false,
				null
		);
		addTaskOutput(project, remappedConfiguration, work, "common");
	}

	private static RemapWork registerTask(Project project, LoomGradleExtension extension, String taskName, Configuration source, List<NamedDomainObjectProvider<Configuration>> remapClasspath, @Nullable Provider<KotlinClasspathService.Options> kotlinClasspath, boolean splitModDependencies, @Nullable String usageName) {
		final RemapMappingConfiguration mappingConfiguration = extension.getMappingConfiguration();
		final List<Path> minecraftClasspath = extension.getProductionNamespaceEnum().get() == MappingsNamespace.NAMED
				&& extension.getNamedMinecraftProvider() instanceof ProcessedNamedMinecraftProvider<?, ?> processedProvider
				? processedProvider.getParentMinecraftProvider().getMinecraftJarPaths()
				: extension.getMinecraftJars(extension.getProductionNamespaceEnum().get());
		final ArtifactInputs artifactInputs = createArtifactInputs(project, source, usageName);
		final Provider<List<File>> passThroughJars = artifactInputs.binaries().getElements()
				.zip(extension.getDefaultMixinRemapTypeEnum(), ModConfigurationRemapper::findPassThroughJars);
		final Provider<Directory> outputDirectory = project.getLayout().getBuildDirectory().dir("loom-cache/remapped-mods/" + taskName);
		final Provider<RemapModDependenciesTask.ExecutionLimiter> executionLimiter = project.getGradle().getSharedServices().registerIfAbsent(
				"fabricLoomModDependencyRemapLimiter",
				RemapModDependenciesTask.ExecutionLimiter.class,
				spec -> spec.getMaxParallelUsages().set(1)
		);
		final TaskProvider<RemapModDependenciesTask> task = project.getTasks().register(taskName, RemapModDependenciesTask.class, remapTask -> {
			remapTask.setDescription("Remaps mod dependencies from " + taskName + ".");
			remapTask.setGroup(Constants.TaskGroup.FABRIC);
			remapTask.getInputJars().from(artifactInputs.binaries());
			remapTask.getSourceJars().from(artifactInputs.sources());
			remapTask.getArtifactPaths().set(artifactInputs.paths());
			remapTask.getArtifactIdentities().set(artifactInputs.identities());
			remapTask.getMappingsFile().fileValue(mappingConfiguration.tinyMappings.toFile());
			remapTask.getMinecraftClasspath().from(minecraftClasspath.stream().map(Path::toFile).toList());
			remapTask.getRemapClasspath().from(remapClasspath);
			remapTask.getRemapClasspath().from(project.getConfigurations().named(Constants.Configurations.LOADER_DEPENDENCIES));
			remapTask.getSourceRemapClasspath().from(project.getConfigurations().named(Constants.Configurations.MINECRAFT_COMPILE_LIBRARIES));
			remapTask.usesService(executionLimiter);

			if (kotlinClasspath != null) {
				remapTask.getKotlinClasspath().from(kotlinClasspath.map(KotlinClasspathService.Options::getClasspath));
				remapTask.getKotlinVersion().set(kotlinClasspath.flatMap(KotlinClasspathService.Options::getKotlinVersion));
			}

			remapTask.getSourceNamespace().set(extension.getProductionNamespace());
			remapTask.getDefaultMixinRemapType().set(extension.getDefaultMixinRemapType());
			remapTask.getInlineDependencyRefmaps().set(extension.getMixin().getInlineDependencyRefmaps());
			remapTask.getHasCustomRemapperExtensions().set(extension.getRemapperExtensions().map(extensions -> !extensions.isEmpty()));
			remapTask.getSplitModDependencies().set(splitModDependencies);
			remapTask.getKnownIndyBsms().set(extension.getKnownIndyBsms());
			remapTask.getJavaCompileRelease().set(SourceRemapperService.getJavaCompileRelease(project));
			remapTask.getConfigurationName().set(taskName);
			remapTask.getOutputDirectory().set(outputDirectory);
		});
		final MinecraftTaskGraph taskGraph = MinecraftTaskGraph.get(project);
		taskGraph.dependsOn(task, mappingConfiguration.tinyMappings);

		for (Path minecraftJar : minecraftClasspath) {
			if (taskGraph.hasProducer(minecraftJar)) {
				taskGraph.dependsOn(task, minecraftJar);
			}
		}

		return new RemapWork(task, outputDirectory, project.files(passThroughJars));
	}

	private static ArtifactInputs createArtifactInputs(Project project, Configuration source, @Nullable String usageName) {
		final ArtifactCollection binaries = source.getIncoming().artifactView(view -> {
			if (usageName != null) {
				view.attributes(attributes -> attributes.attribute(
						Usage.USAGE_ATTRIBUTE,
						project.getObjects().named(Usage.class, usageName)
				));
			}
		}).getArtifacts();
		final ArtifactCollection sources = source.getIncoming().artifactView(view -> {
			view.withVariantReselection();
			view.setLenient(true);
			view.attributes(attributes -> {
				attributes.attribute(Category.CATEGORY_ATTRIBUTE, project.getObjects().named(Category.class, Category.DOCUMENTATION));
				attributes.attribute(DocsType.DOCS_TYPE_ATTRIBUTE, project.getObjects().named(DocsType.class, DocsType.SOURCES));
				attributes.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.JAVA_RUNTIME));
			});
		}).getArtifacts();
		final Provider<List<String>> paths = binaries.getResolvedArtifacts().zip(
				sources.getResolvedArtifacts(),
				(binaryArtifacts, sourceArtifacts) -> createArtifactRecords(binaryArtifacts, sourceArtifacts, true)
		);
		final Provider<List<String>> identities = binaries.getResolvedArtifacts().zip(
				sources.getResolvedArtifacts(),
				(binaryArtifacts, sourceArtifacts) -> createArtifactRecords(binaryArtifacts, sourceArtifacts, false)
		);

		return new ArtifactInputs(binaries.getArtifactFiles(), sources.getArtifactFiles(), paths, identities);
	}

	private static List<File> findPassThroughJars(Set<? extends FileSystemLocation> files, ArtifactMetadata.MixinRemapType defaultMixinRemapType) {
		return files.stream()
				.map(FileSystemLocation::getAsFile)
				.filter(file -> !shouldRemap(file, defaultMixinRemapType))
				.sorted(Comparator.comparing(File::getAbsolutePath))
				.toList();
	}

	private static boolean shouldRemap(File file, ArtifactMetadata.MixinRemapType defaultMixinRemapType) {
		final ArtifactRef artifact = new ArtifactRef.FileArtifactRef(file.toPath(), MISSING_GROUP, file.getName(), MISSING_GROUP);

		try {
			return ArtifactMetadata.create(artifact, LoomGradlePlugin.LOOM_VERSION, defaultMixinRemapType).shouldRemap();
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to inspect mod dependency " + file, e);
		}
	}

	private static List<String> createArtifactRecords(Set<ResolvedArtifactResult> binaries, Set<ResolvedArtifactResult> sources, boolean includePaths) {
		final Map<String, List<File>> sourcesByComponent = new HashMap<>();

		for (ResolvedArtifactResult source : sources) {
			sourcesByComponent.computeIfAbsent(componentKey(source), ignored -> new ArrayList<>()).add(source.getFile());
		}

		sourcesByComponent.values().forEach(files -> files.sort(Comparator.comparing(File::getAbsolutePath)));

		return binaries.stream()
				.sorted(Comparator.comparing(artifact -> artifact.getFile().getAbsolutePath()))
				.map(binary -> {
					final String component = componentKey(binary);
					final File source = findSource(binary.getFile(), sourcesByComponent.getOrDefault(component, List.of()));
					return includePaths
							? RemapModDependenciesTask.encodeArtifactPath(component, binary.getFile(), source)
							: RemapModDependenciesTask.encodeArtifactIdentity(cacheIdentity(binary), binary.getFile(), source);
				})
				.toList();
	}

	private static @Nullable File findSource(File binary, List<File> sources) {
		final String binaryName = binary.getName();
		final String expectedName = binaryName.endsWith(".jar")
				? binaryName.substring(0, binaryName.length() - 4) + "-sources.jar"
				: binaryName + "-sources.jar";

		return sources.stream()
				.filter(source -> source.getName().equals(expectedName))
				.findFirst()
				.or(() -> sources.stream().findFirst())
				.orElse(null);
	}

	private static String componentKey(ResolvedArtifactResult artifact) {
		final ComponentIdentifier component = artifact.getId().getComponentIdentifier();
		return component.getClass().getName() + ":" + component.getDisplayName();
	}

	private static String cacheIdentity(ResolvedArtifactResult artifact) {
		if (artifact.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier module) {
			return module.getGroup() + ":" + module.getModule() + ":" + module.getVersion();
		}

		return artifact.getFile().getName();
	}

	private static void addTaskOutput(Project project, NamedDomainObjectProvider<? extends Configuration> target, RemapWork work, String environment) {
		final FileTree outputTree = project.fileTree(
				work.outputDirectory().map(directory -> directory.dir(environment)),
				tree -> {
					tree.include("*.jar");
					tree.exclude("*-sources.jar");
				}
		);
		final ConfigurableFileCollection outputFiles = project.files(outputTree);
		outputFiles.builtBy(work.task());
		project.getDependencies().add(target.getName(), outputFiles);

		if (environment.equals("common")) {
			project.getDependencies().add(target.getName(), work.passThroughJars());
		}
	}

	private static void addGeneratedClasspathGroups(Project project, RemapWork work) {
		final Configuration generatedGroups = project.getConfigurations().maybeCreate(ClasspathGroupService.GENERATED_GROUPS_CONFIGURATION);
		generatedGroups.setCanBeConsumed(false);
		generatedGroups.setCanBeResolved(true);
		final ConfigurableFileCollection descriptor = project.files(work.outputDirectory().map(directory -> directory.file(RemapModDependenciesTask.CLASSPATH_GROUPS_FILE)));
		descriptor.builtBy(work.task());
		project.getDependencies().add(generatedGroups.getName(), descriptor);
	}

	public static String replaceIfNullOrEmpty(@Nullable String value, Supplier<String> fallback) {
		return value == null || value.isEmpty() ? fallback.get() : value;
	}

	private record RemapWork(TaskProvider<RemapModDependenciesTask> task, Provider<Directory> outputDirectory, FileCollection passThroughJars) {
	}

	private record ArtifactInputs(FileCollection binaries, FileCollection sources, Provider<List<String>> paths, Provider<List<String>> identities) {
	}
}
