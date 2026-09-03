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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import org.apache.tools.ant.util.StringUtils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.api.decompilers.JavadocStyle;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftTaskGraph;
import net.fabricmc.loom.task.PrepareMinecraftMappingsTask;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public final class RemapMappingConfiguration extends MappingConfiguration {
	public static final String PREPARE_MAPPINGS_TASK = "prepareMinecraftMappings";
	public final Path tinyMappings;
	public final Path tinyMappingsJar;

	private RemapMappingConfiguration(String mappingsIdentifier, Path mappingsWorkingDir) {
		super(mappingsIdentifier, mappingsWorkingDir.resolve("mappings.jar"));
		this.tinyMappings = mappingsWorkingDir.resolve("mappings.tiny");
		this.tinyMappingsJar = mappingsWorkingDir.resolve("mappings.jar");
	}

	public static RemapMappingConfiguration create(Project project, ServiceFactory serviceFactory, DependencyInfo dependency, MinecraftProvider minecraftProvider) {
		final String layeredIdentity = getLayeredIdentity(dependency);
		final String version = layeredIdentity != null ? layeredIdentity : Objects.requireNonNullElse(dependency.getDependency().getVersion(), "unspecified");
		final String mappingsName = layeredIdentity != null
				? "loom.layered"
				: StringUtils.removeSuffix(dependency.getDependency().getGroup() + "." + dependency.getDependency().getName(), "-unmerged");
		final String mappingsIdentifier = createMappingsIdentifier(mappingsName, version, getDeclaredClassifier(dependency), minecraftProvider.minecraftVersion());
		final Path workingDir = minecraftProvider.file(mappingsIdentifier).toPath();
		final var mappingConfiguration = new RemapMappingConfiguration(mappingsIdentifier, workingDir);
		mappingConfiguration.registerPreparationTask(project, dependency, minecraftProvider);
		return mappingConfiguration;
	}

	@Nullable
	private static String getLayeredIdentity(DependencyInfo dependency) {
		if (!(dependency.getDependency() instanceof FileCollectionDependency)) {
			return null;
		}

		final String reason = dependency.getDependency().getReason();
		return reason != null && reason.startsWith(LayeredMappingsFactory.DEPENDENCY_REASON_PREFIX)
				? reason.substring(LayeredMappingsFactory.DEPENDENCY_REASON_PREFIX.length())
				: null;
	}

	private void registerPreparationTask(Project project, DependencyInfo dependency, MinecraftProvider minecraftProvider) {
		final boolean useIntermediateMappings = net.fabricmc.loom.LoomGradleExtension.get(project).getUseIntermediateMappings().get();
		final Path intermediaryMappings = useIntermediateMappings
				? IntermediateMappingsService.registerPreparationTask(project, minecraftProvider)
				: null;
		final var prepareTask = project.getTasks().register(PREPARE_MAPPINGS_TASK, PrepareMinecraftMappingsTask.class, task -> {
			task.setDescription("Prepares the configured Minecraft mappings.");
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.getMappingsJar().fileProvider(resolveMappingsJar(project, dependency));

			if (intermediaryMappings != null) {
				task.getIntermediaryMappings().fileValue(intermediaryMappings.toFile());
			}

			final var minecraftJars = minecraftProvider.getMinecraftJars();

			if (minecraftJars.size() == 1) {
				task.getOfficialMinecraftJar().fileValue(minecraftJars.getFirst().toFile());
			}

			task.getMinecraftMetadata().fileValue(minecraftProvider.getMinecraftMetadataPath().toFile());
			task.getUseIntermediateMappings().set(useIntermediateMappings);
			task.getOutputMappings().fileValue(tinyMappings.toFile());
			task.getOutputMappingsJar().fileValue(tinyMappingsJar.toFile());
		});
		final MinecraftTaskGraph taskGraph = MinecraftTaskGraph.get(project);
		taskGraph.dependsOn(prepareTask, minecraftProvider.getMinecraftMetadataPath());

		if (intermediaryMappings != null) {
			taskGraph.dependsOn(prepareTask, intermediaryMappings);
		}

		final var minecraftJars = minecraftProvider.getMinecraftJars();

		if (minecraftJars.size() == 1 && taskGraph.hasProducer(minecraftJars.getFirst())) {
			taskGraph.dependsOn(prepareTask, minecraftJars.getFirst());
		}

		taskGraph.registerOutput(tinyMappings, prepareTask);
		taskGraph.registerOutput(tinyMappingsJar, prepareTask);
	}

	private static Provider<File> resolveMappingsJar(Project project, DependencyInfo dependency) {
		final FileCollection files;
		final String dependencyNotation = dependency.getDepString();

		if (dependency.getDependency() instanceof FileCollectionDependency fileDependency) {
			files = fileDependency.getFiles();
		} else {
			final String group = dependency.getDependency().getGroup();
			final String name = dependency.getDependency().getName();
			files = dependency.getSourceConfiguration().getIncoming().artifactView(view -> view.componentFilter(identifier ->
					identifier instanceof ModuleComponentIdentifier moduleIdentifier
							&& Objects.equals(group, moduleIdentifier.getGroup())
							&& name.equals(moduleIdentifier.getModule())
			)).getFiles();
		}

		return files.getElements().map(elements -> {
			if (elements.size() != 1) {
				throw new IllegalStateException("Expected exactly one mappings artifact for " + dependencyNotation + ", but found " + elements.size());
			}

			return elements.iterator().next().getAsFile();
		});
	}

	private static String getDeclaredClassifier(DependencyInfo dependency) {
		if (!(dependency.getDependency() instanceof ModuleDependency moduleDependency)) {
			return "";
		}

		return moduleDependency.getArtifacts().stream()
				.map(artifact -> artifact.getClassifier())
				.filter(Objects::nonNull)
				.filter(classifier -> !classifier.isEmpty())
				.findFirst()
				.map(classifier -> "-" + classifier)
				.orElse("");
	}

	private static void validateMappings(MemoryMappingTree mappingTree) throws IOException {
		if (!mappingTree.getMetadata(MARKDOWN_METADATA_KEY).isEmpty()) {
			LOGGER.warn("Markdown comments are currently not supported for remapped Minecraft. Reinterpreting comments as HTML.");
		}
	}

	@Override
	public TinyMappingsService getMappingsService(Project project, ServiceFactory serviceFactory) {
		TinyMappingsService mappingsService = super.getMappingsService(project, serviceFactory);

		try {
			validateMappings(mappingsService.getMappingTree());
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to validate mappings", e);
		}

		return mappingsService;
	}

	@Override
	public Provider<TinyMappingsService.Options> getMappingsServiceOptions(Project project) {
		return TinyMappingsService.createOptions(project, tinyMappings);
	}

	@Override
	public String getMappingsHash() {
		return Checksum.of(mappingsIdentifier()).sha256().hex();
	}

	@Override
	public MappingsNamespace getRuntimeNamespace() {
		return MappingsNamespace.NAMED;
	}

	@Override
	public JavadocStyle getJavadocStyle() {
		return JavadocStyle.HTML;
	}

	@Override
	public void applyToProject(Project project, DependencyInfo dependency) {
		super.applyToProject(project, dependency);
		project.getDependencies().add(Constants.Configurations.MAPPINGS_FINAL, MinecraftTaskGraph.get(project).files(tinyMappingsJar));
	}

	@Nullable
	public Map<String, String> getSignatureFixes() {
		return null;
	}
}
