/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
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

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.ResolutionStrategy;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.api.mappings.layered.spec.FileSpec;
import net.fabricmc.loom.api.mappings.layered.spec.MappingsSpec;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.configuration.providers.mappings.extras.signatures.SignatureFixesSpec;
import net.fabricmc.loom.configuration.providers.mappings.file.FileMappingsSpec;
import net.fabricmc.loom.configuration.providers.mappings.intermediary.IntermediaryMappingsSpec;
import net.fabricmc.loom.configuration.providers.mappings.mojmap.MojangMappingsSpec;
import net.fabricmc.loom.configuration.providers.mappings.parchment.ParchmentMappingsSpec;
import net.fabricmc.loom.configuration.providers.mappings.utils.DependencyFileSpec;
import net.fabricmc.loom.configuration.providers.mappings.utils.LocalFileSpec;
import net.fabricmc.loom.configuration.providers.mappings.utils.MavenFileSpec;
import net.fabricmc.loom.configuration.providers.mappings.utils.MinimalExternalModuleDependencyFileSpec;
import net.fabricmc.loom.configuration.providers.mappings.utils.ProviderFileSpec;
import net.fabricmc.loom.configuration.providers.mappings.utils.URLFileSpec;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftTaskGraph;
import net.fabricmc.loom.task.GenerateLayeredMappingsTask;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.gradle.GradleUtils;

public record LayeredMappingsFactory(LayeredMappingSpec spec, int declarationIndex) {
	public static final String DEPENDENCY_REASON_PREFIX = "loom-layered:";
	private static final String TASK_NAME_PREFIX = "generateLayeredMappings";
	private static final String CONFIGURED_MARKER_PREFIX = "loom.layeredMappings.configured.";

	public LayeredMappingsFactory(LayeredMappingSpec spec) {
		this(spec, 0);
	}

	public static void afterEvaluate(ConfigContext configContext) {
		for (LayeredMappingsFactory layeredMappingFactory : configContext.extension().getLayeredMappingFactories()) {
			layeredMappingFactory.configure(configContext.project());
		}

		// The migrateMappings target is selected through a task option, after the task graph is ready.
		// Register its built-in Mojang target eagerly, while keeping all file and network work in the task.
		new LayeredMappingsFactory(LayeredMappingSpecBuilderImpl.buildOfficialMojangMappings()).configure(configContext.project());
	}

	public Dependency createDependency(Project project) {
		final String identity = identity();
		final TaskProvider<GenerateLayeredMappingsTask> task = getOrRegisterTask(project, identity);
		final ConfigurableFileCollection output = project.files(task.flatMap(GenerateLayeredMappingsTask::getOutputMappings));
		output.builtBy(task);
		final Dependency dependency = project.getDependencies().create(output);
		dependency.because(DEPENDENCY_REASON_PREFIX + identity);
		return dependency;
	}

	public Provider<File> createFileProvider(Project project) {
		final TaskProvider<GenerateLayeredMappingsTask> task = getOrRegisterTask(project, identity());
		configure(project);
		return task.flatMap(GenerateLayeredMappingsTask::getOutputMappings).map(file -> file.getAsFile());
	}

	public String mavenNotation() {
		return "loom:mappings:" + spec.getVersion();
	}

	private void configure(Project project) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final MinecraftProvider minecraftProvider = extension.getMinecraftProvider();
		final boolean useIntermediateMappings = extension.getUseIntermediateMappings().get();
		final String identity = identity();
		final String configuredMarker = CONFIGURED_MARKER_PREFIX + identity;

		if (project.getExtensions().getExtraProperties().has(configuredMarker)) {
			return;
		}

		project.getExtensions().getExtraProperties().set(configuredMarker, true);
		final Path output = output(project, minecraftProvider, identity);
		final TaskProvider<GenerateLayeredMappingsTask> task = getOrRegisterTask(project, identity);
		final Path intermediaryMappings = useIntermediateMappings
				? IntermediateMappingsService.registerPreparationTask(project, minecraftProvider)
				: null;

		task.configure(generateTask -> {
			generateTask.getLayerFiles().setFrom(Collections.emptyList());
			generateTask.getLayerFilePaths().set(Collections.emptyList());
			final List<String> layers = new ArrayList<>();
			final int[] fileIndex = {0};

			for (MappingsSpec<?> layer : spec.layers()) {
				layers.add(encodeLayer(project, generateTask, layer, useIntermediateMappings, extension.getProductionNamespace().get(), fileIndex));
			}

			generateTask.setDescription("Generates the configured layered Minecraft mappings.");
			generateTask.setGroup(Constants.TaskGroup.FABRIC);
			generateTask.getMinecraftMetadata().fileValue(minecraftProvider.getMinecraftMetadataPath().toFile());

			if (intermediaryMappings != null) {
				generateTask.getIntermediaryMappings().fileValue(intermediaryMappings.toFile());
			}

			generateTask.getLayers().set(layers);
			generateTask.getUseIntermediateMappings().set(useIntermediateMappings);
			generateTask.getDropNonIntermediateRootMethods().set(GradleUtils.getBooleanProperty(project, Constants.Properties.DROP_NON_INTERMEDIATE_ROOT_METHODS));
			generateTask.getOffline().set(project.getGradle().getStartParameter().isOffline());
			generateTask.getRefresh().set(extension.refreshDeps());
			generateTask.getOutputMappings().fileValue(output.toFile());
		});

		final MinecraftTaskGraph taskGraph = MinecraftTaskGraph.get(project);
		taskGraph.dependsOn(task, minecraftProvider.getMinecraftMetadataPath());

		if (intermediaryMappings != null) {
			taskGraph.dependsOn(task, intermediaryMappings);
		}

		if (!taskGraph.hasProducer(output)) {
			taskGraph.registerOutput(output, task);
		}
	}

	private String encodeLayer(Project project, GenerateLayeredMappingsTask task, MappingsSpec<?> layer, boolean useIntermediateMappings, String productionNamespace, int[] fileIndex) {
		return switch (layer) {
		case IntermediaryMappingsSpec ignored -> GenerateLayeredMappingsTask.intermediaryLayer();
		case MojangMappingsSpec mojang -> GenerateLayeredMappingsTask.mojangLayer(mojang.nameSyntheticMembers());
		case ParchmentMappingsSpec parchment -> GenerateLayeredMappingsTask.parchmentLayer(addFileSource(project, task, parchment.fileSpec(), fileIndex), parchment.removePrefix());
		case FileMappingsSpec file -> GenerateLayeredMappingsTask.fileLayer(
				addFileSource(project, task, file.fileSpec(), fileIndex),
				file.mappingPath(),
				file.fallbackSourceNamespace().orElse(productionNamespace),
				file.fallbackTargetNamespace(),
				file.enigma(),
				file.unpick(),
				file.annotations(),
				file.mergeNamespace().orElse(useIntermediateMappings ? MappingsNamespace.INTERMEDIARY.toString() : MappingsNamespace.OFFICIAL.toString()),
				file.unpick() ? file.fallbackUnpickConstants().orElse(null) : null
		);
		case SignatureFixesSpec signatureFixes -> GenerateLayeredMappingsTask.signatureFixLayer(addFileSource(project, task, signatureFixes.fileSpec(), fileIndex));
		default -> throw unsupported(layer);
		};
	}

	private String addFileSource(Project project, GenerateLayeredMappingsTask task, FileSpec fileSpec, int[] fileIndex) {
		if (fileSpec instanceof URLFileSpec urlFileSpec) {
			return GenerateLayeredMappingsTask.urlSource(urlFileSpec.url());
		}

		final FileSource source = resolveFileSource(project, fileSpec);
		final int index = fileIndex[0]++;
		task.getLayerFiles().from(source.files());
		task.getLayerFilePaths().add(source.file().map(File::getAbsolutePath));
		task.getLayerFileOrder().add(source.file().map(file -> Checksum.of(file).sha256().hex()));
		return GenerateLayeredMappingsTask.fileSource(index);
	}

	private FileSource resolveFileSource(Project project, FileSpec fileSpec) {
		if (fileSpec instanceof LocalFileSpec localFileSpec) {
			final Provider<File> file = project.provider(localFileSpec::file);
			return new FileSource(project.files(file), file);
		}

		if (fileSpec instanceof ProviderFileSpec providerFileSpec) {
			final Provider<File> file = providerFileSpec.provider().map(LayeredMappingsFactory::providerValueToFile);
			return new FileSource(project.files(file), file);
		}

		if (fileSpec instanceof MavenFileSpec mavenFileSpec) {
			return resolveDependency(project, project.getDependencies().create(mavenFileSpec.dependencyNotation()));
		}

		if (fileSpec instanceof MinimalExternalModuleDependencyFileSpec moduleFileSpec) {
			return resolveDependency(project, project.getDependencies().create(moduleFileSpec.dependency()));
		}

		if (fileSpec instanceof DependencyFileSpec dependencyFileSpec) {
			return resolveDependency(project, dependencyFileSpec.dependency());
		}

		throw new UnsupportedOperationException("Task-backed layered mappings do not support FileSpec implementation " + fileSpec.getClass().getName());
	}

	private static File providerValueToFile(Object value) {
		return switch (value) {
		case File file -> file;
		case Path path -> path.toFile();
		case FileSystemLocation location -> location.getAsFile();
		default -> throw new UnsupportedOperationException(
				"A provider-backed layered mappings input must resolve to a File, Path, or FileSystemLocation, not " + value.getClass().getName()
		);
		};
	}

	private FileSource resolveDependency(Project project, Dependency dependency) {
		final FileCollection files;

		if (dependency instanceof FileCollectionDependency fileDependency) {
			files = fileDependency.getFiles();
		} else {
			final Configuration configuration = project.getConfigurations().detachedConfiguration(dependency);
			configuration.resolutionStrategy(ResolutionStrategy::failOnNonReproducibleResolution);
			files = configuration;
		}

		final Provider<File> file = files.getElements().map(elements -> {
			if (elements.size() != 1) {
				throw new IllegalStateException("Expected exactly one layered mappings input for " + dependency + ", but found " + elements.size());
			}

			return elements.iterator().next().getAsFile();
		});
		return new FileSource(files, file);
	}

	private TaskProvider<GenerateLayeredMappingsTask> getOrRegisterTask(Project project, String identity) {
		final String name = TASK_NAME_PREFIX + identity.substring(0, 16);
		final TaskContainer tasks = project.getTasks();

		if (tasks.getNames().contains(name)) {
			return tasks.named(name, GenerateLayeredMappingsTask.class);
		}

		return tasks.register(name, GenerateLayeredMappingsTask.class, task -> task.getOutputMappings().fileProvider(
				project.provider(() -> output(project, LoomGradleExtension.get(project).getMinecraftProvider(), identity).toFile())
		));
	}

	private String identity() {
		final StringBuilder builder = new StringBuilder();

		for (MappingsSpec<?> layer : spec.layers()) {
			builder.append(describeLayer(layer)).append('\n');
		}

		return Checksum.of(builder.toString()).sha256().hex();
	}

	private String describeLayer(MappingsSpec<?> layer) {
		return switch (layer) {
		case IntermediaryMappingsSpec ignored -> "intermediary";
		case MojangMappingsSpec mojang -> "mojang:" + mojang.nameSyntheticMembers();
		case ParchmentMappingsSpec parchment -> "parchment:" + describeFile(parchment.fileSpec()) + ':' + parchment.removePrefix();
		case FileMappingsSpec file -> "file:" + describeFile(file.fileSpec()) + ':' + file.mappingPath() + ':'
				+ file.fallbackSourceNamespace() + ':' + file.fallbackTargetNamespace() + ':' + file.enigma() + ':'
				+ file.unpick() + ':' + file.annotations() + ':' + file.mergeNamespace() + ':' + file.fallbackUnpickConstants();
		case SignatureFixesSpec signatureFixes -> "signature-fix:" + describeFile(signatureFixes.fileSpec());
		default -> throw unsupported(layer);
		};
	}

	private String describeFile(FileSpec fileSpec) {
		return switch (fileSpec) {
		case LocalFileSpec local -> "local:" + local.file().toPath().toAbsolutePath().normalize();
		case MavenFileSpec maven -> "maven:" + maven.dependencyNotation();
		case MinimalExternalModuleDependencyFileSpec module -> "minimal-module:" + module.dependency();
		case DependencyFileSpec dependency -> "dependency:" + dependency.dependency();
		case ProviderFileSpec ignored -> "provider-declaration:" + declarationIndex;
		case URLFileSpec url -> "url:" + url.url();
		default -> throw new UnsupportedOperationException("Task-backed layered mappings do not support FileSpec implementation " + fileSpec.getClass().getName());
		};
	}

	private UnsupportedOperationException unsupported(MappingsSpec<?> layer) {
		return new UnsupportedOperationException("Task-backed layered mappings only support Loom's built-in mappings specs; "
				+ layer.getClass().getName() + " cannot be serialized for task execution");
	}

	private static Path output(Project project, MinecraftProvider minecraftProvider, String identity) {
		return LoomGradleExtension.get(project).getFiles().getProjectPersistentCache().toPath()
				.resolve("layered")
				.resolve(minecraftProvider.minecraftVersion())
				.resolve(identity)
				.resolve("mappings.jar");
	}

	private record FileSource(FileCollection files, Provider<File> file) {
		private FileSource {
			Objects.requireNonNull(files, "files");
			Objects.requireNonNull(file, "file");
		}
	}
}
