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
import java.util.Objects;

import org.gradle.api.Project;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.api.decompilers.JavadocStyle;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.configuration.providers.mappings.tiny.TinyJarInfo;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftTaskGraph;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public final class NoRemapMappingConfiguration extends MappingConfiguration {
	private static final Logger LOGGER = LoggerFactory.getLogger(NoRemapMappingConfiguration.class);
	private static final String PREPARE_ANNOTATIONS_TASK = "prepareMinecraftAnnotations";
	private final Provider<File> inputJarProvider;

	private NoRemapMappingConfiguration(String mappingsIdentifier, Path inputJar, Provider<File> inputJarProvider) {
		super(mappingsIdentifier, inputJar);
		this.inputJarProvider = inputJarProvider;
	}

	public static NoRemapMappingConfiguration create(Project project, DependencyInfo dependency, MinecraftProvider minecraftProvider) {
		final String version = Objects.requireNonNullElse(dependency.getDependency().getVersion(), "unspecified");
		final String group = Objects.requireNonNullElse(dependency.getDependency().getGroup(), "local");
		final String name = Objects.requireNonNullElse(dependency.getDependency().getName(), "annotations");
		final String classifier = getDeclaredClassifier(dependency);
		final String declaration = "%s:%s:%s%s".formatted(group, name, version, classifier);
		final String mappingsName = "annotations.%s.%s.%s".formatted(group, name, Checksum.of(declaration).sha256().hex(12));
		final String mappingsIdentifier = createMappingsIdentifier(mappingsName, version, classifier, minecraftProvider.minecraftVersion());
		final Path outputJar = minecraftProvider.path(mappingsIdentifier).resolve("annotations.jar");
		final Provider<File> input = resolveAnnotationsJar(project, dependency);
		final TaskProvider<PrepareMinecraftAnnotationsTask> task = project.getTasks().register(PREPARE_ANNOTATIONS_TASK, PrepareMinecraftAnnotationsTask.class, prepareTask -> {
			prepareTask.setDescription("Prepares the configured Minecraft annotations.");
			prepareTask.setGroup(Constants.TaskGroup.FABRIC);
			prepareTask.getInputJar().fileProvider(input);
			prepareTask.getMinecraftVersion().set(minecraftProvider.minecraftVersion());
			prepareTask.getOutputJar().fileValue(outputJar.toFile());
		});
		MinecraftTaskGraph.get(project).registerOutput(outputJar, task);
		return new NoRemapMappingConfiguration(mappingsIdentifier, outputJar, task.flatMap(PrepareMinecraftAnnotationsTask::getOutputJar).map(file -> file.getAsFile()));
	}

	private static Provider<File> resolveAnnotationsJar(Project project, DependencyInfo dependency) {
		final FileCollection files;

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
				throw new IllegalStateException("Expected exactly one annotations artifact for " + dependency.getDepString() + ", but found " + elements.size());
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

	static void validateMappings(Path mappings) throws IOException {
		MemoryMappingTree mappingTree = new MemoryMappingTree();
		MappingReader.read(mappings, mappingTree);
		validateMappings(mappingTree);
	}

	private static void validateMappings(MemoryMappingTree mappingTree) throws IOException {
		if (!MappingsNamespace.OFFICIAL.toString().equals(mappingTree.getSrcNamespace()) || !mappingTree.getDstNamespaces().isEmpty()) {
			throw new IOException("Annotations mappings must contain only the official namespace");
		}

		if (mappingTree.getMetadata(MARKDOWN_METADATA_KEY).isEmpty()) {
			LOGGER.warn("Annotations mappings should have the " + MARKDOWN_METADATA_KEY + " metadata entry. Comments are still assumed to be in Markdown.");
		}
	}

	@Override
	public TinyMappingsService getMappingsService(Project project, ServiceFactory serviceFactory) {
		TinyMappingsService mappingsService = super.getMappingsService(project, serviceFactory);

		try {
			validateMappings(mappingsService.getMappingTree());
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to validate annotations mappings", e);
		}

		return mappingsService;
	}

	@Override
	public Provider<TinyMappingsService.Options> getMappingsServiceOptions(Project project) {
		return TinyMappingsService.createOptions(project, inputJarProvider, TinyJarInfo.MAPPINGS_PATH);
	}

	@Override
	public String getMappingsHash() {
		return Checksum.of(mappingsIdentifier()).sha256().hex();
	}

	@Override
	public MappingsNamespace getRuntimeNamespace() {
		return MappingsNamespace.OFFICIAL;
	}

	@Override
	public JavadocStyle getJavadocStyle() {
		return JavadocStyle.MARKDOWN;
	}
}
