/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2024-2026 FabricMC
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

package net.fabricmc.loom.task.service;

import java.io.UncheckedIOException;
import java.nio.file.Path;

import org.gradle.api.Project;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.processors.MinecraftJarProcessorManager;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.RemapMappingConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.tiny.TinyJarInfo;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftTaskGraph;
import net.fabricmc.loom.api.decompilers.JavadocStyle;
import net.fabricmc.loom.task.GenerateSourcesTask;
import net.fabricmc.loom.task.PrepareSourceMappingsTask;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;

/// Provides mappings for decompilation (MC *source* code).
/// This also works in projects with disabled obfuscation
/// where the mappings are just based on javadocs.
public class SourceMappingsService extends Service<SourceMappingsService.Options> {
	public static final ServiceType<Options, SourceMappingsService> TYPE = new ServiceType<>(Options.class, SourceMappingsService.class);
	public static final String PREPARE_SOURCE_MAPPINGS_TASK = "prepareMinecraftSourceMappings";
	private static final String EMPTY_MAPPINGS = "tiny\t2\t0\tofficial\n";

	public interface Options extends Service.Options {
		@InputFile
		@PathSensitive(PathSensitivity.NONE)
		RegularFileProperty getMappings();

		@Input
		@Optional
		Property<String> getProcessorHash(); // the hash of the processors applied to the mappings

		@Input
		Property<JavadocStyle> getJavadocStyle();
	}

	public static Provider<Options> create(GenerateSourcesTask generateSourcesTask) {
		final Project project = generateSourcesTask.getProject();
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final MinecraftJarProcessorManager jarProcessor = MinecraftJarProcessorManager.create(project);
		final Path dir = extension.getFiles().getProjectPersistentCache().toPath().resolve("source_mappings");
		final boolean disableObf = extension.disableObfuscation();
		final MappingConfiguration mappingConfiguration = extension.getMappingConfigurationOrNull();

		if (!disableObf && mappingConfiguration == null) {
			throw new IllegalStateException("Mappings have not been configured");
		}

		final String processorHash = jarProcessor != null ? jarProcessor.getSourceMappingsHash() : "none";
		final String mappingsHash = mappingConfiguration != null
				? mappingConfiguration.getMappingsHash()
				: Checksum.of(EMPTY_MAPPINGS).sha256().hex();
		final String hash = Checksum.of(processorHash + ":" + mappingsHash).sha1().hex();
		final Path outputMappings = dir.resolve(hash + ".tiny");
		final TaskProvider<PrepareSourceMappingsTask> prepareTask = registerPreparationTask(
				project,
				extension,
				mappingConfiguration,
				jarProcessor,
				outputMappings
		);
		generateSourcesTask.dependsOn(prepareTask);

		return TYPE.create(project, options -> {
			options.getMappings().set(prepareTask.flatMap(PrepareSourceMappingsTask::getOutputMappings));
			options.getProcessorHash().set(hash);
			options.getJavadocStyle().set(mappingConfiguration != null ? mappingConfiguration.getJavadocStyle() : JavadocStyle.HTML);
		});
	}

	private static TaskProvider<PrepareSourceMappingsTask> registerPreparationTask(Project project, LoomGradleExtension extension, @Nullable MappingConfiguration mappingConfiguration, @Nullable MinecraftJarProcessorManager jarProcessor, Path outputMappings) {
		final MinecraftTaskGraph taskGraph = MinecraftTaskGraph.get(project);

		if (taskGraph.hasProducer(outputMappings)) {
			return project.getTasks().named(PREPARE_SOURCE_MAPPINGS_TASK, PrepareSourceMappingsTask.class);
		}

		final MinecraftJarProcessorManager.SourceMappingsTaskConfiguration processorConfiguration;

		try {
			processorConfiguration = jarProcessor != null
					? jarProcessor.getSourceMappingsTaskConfiguration()
					: new MinecraftJarProcessorManager.SourceMappingsTaskConfiguration(java.util.List.of(), java.util.List.of(), java.util.List.of());
		} catch (java.io.IOException e) {
			throw new UncheckedIOException("Failed to configure source mappings task", e);
		}

		final Path inputMappings;
		final String inputMappingsEntry;

		if (mappingConfiguration instanceof RemapMappingConfiguration remapMappingConfiguration) {
			inputMappings = remapMappingConfiguration.tinyMappings;
			inputMappingsEntry = null;
		} else if (mappingConfiguration != null) {
			inputMappings = mappingConfiguration.getInputJar();
			inputMappingsEntry = TinyJarInfo.MAPPINGS_PATH;
		} else {
			inputMappings = null;
			inputMappingsEntry = null;
		}

		final String mappingsSourceNamespace = !extension.disableObfuscation() && extension.getUseIntermediateMappings().get()
				? MappingsNamespace.INTERMEDIARY.toString()
				: MappingsNamespace.OFFICIAL.toString();
		final String productionNamespace = extension.getProductionNamespace().get();
		final String outputNamespace = extension.disableObfuscation()
				? MappingsNamespace.OFFICIAL.toString()
				: MappingsNamespace.NAMED.toString();
		final boolean disableObfuscation = extension.disableObfuscation();
		final boolean normalizeNamespaces = !(jarProcessor == null && mappingConfiguration instanceof RemapMappingConfiguration);
		final java.util.List<String> mappingTransformations = processorConfiguration.transformations();
		final java.util.List<java.io.File> transformationSources = processorConfiguration.sources().stream().map(Path::toFile).toList();
		final java.util.List<String> transformationSourcePaths = processorConfiguration.sources().stream().map(Path::toString).toList();
		final java.util.List<String> unsupportedProcessors = processorConfiguration.unsupportedProcessors();
		final java.util.List<Path> processorAnalyses = processorConfiguration.analyses();

		final TaskProvider<PrepareSourceMappingsTask> prepareTask = project.getTasks().register(PREPARE_SOURCE_MAPPINGS_TASK, PrepareSourceMappingsTask.class, task -> {
			task.setDescription("Prepares mappings used to decompile Minecraft sources.");
			task.setGroup(Constants.TaskGroup.FABRIC);

			if (inputMappings != null) {
				task.getInputMappings().fileValue(inputMappings.toFile());
			}

			if (inputMappingsEntry != null) {
				task.getInputMappingsEntry().set(inputMappingsEntry);
			}

			task.getMappingsSourceNamespace().set(mappingsSourceNamespace);
			task.getProductionNamespace().set(productionNamespace);
			task.getOutputNamespace().set(outputNamespace);
			task.getDisableObfuscation().set(disableObfuscation);
			task.getNormalizeNamespaces().set(normalizeNamespaces);
			task.getMappingTransformations().set(mappingTransformations);
			task.getProcessorAnalyses().from(processorAnalyses.stream().map(Path::toFile).toList());
			task.getProcessorAnalysisPaths().set(processorAnalyses.stream().map(Path::toString).toList());
			task.getTransformationSources().from(transformationSources);

			if (processorConfiguration.processorSources() != null) {
				task.getTransformationSources().from(processorConfiguration.processorSources());
			}

			task.getTransformationSourcePaths().set(transformationSourcePaths);
			task.getUnsupportedProcessors().set(unsupportedProcessors);
			task.getOutputMappings().fileValue(outputMappings.toFile());
		});

		if (inputMappings != null && taskGraph.hasProducer(inputMappings)) {
			taskGraph.dependsOn(prepareTask, inputMappings);
		}

		for (Path processorAnalysis : processorAnalyses) {
			taskGraph.dependsOn(prepareTask, processorAnalysis);
		}

		taskGraph.registerOutput(outputMappings, prepareTask);
		return prepareTask;
	}

	public SourceMappingsService(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
	}

	public Path getMappingsFile() {
		return getOptions().getMappings().getAsFile().get().toPath();
	}

	public @Nullable String getProcessorHash() {
		return Checksum.of(getMappingsFile()).sha256().hex();
	}

	public JavadocStyle getJavadocStyle() {
		return getOptions().getJavadocStyle().get();
	}
}
