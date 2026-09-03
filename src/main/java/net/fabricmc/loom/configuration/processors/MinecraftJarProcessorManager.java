/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2023 FabricMC
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

package net.fabricmc.loom.configuration.processors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactView;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.RemapConfigurationSettings;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.api.processor.MappingProcessorContext;
import net.fabricmc.loom.api.processor.MarkdownJavadocOption;
import net.fabricmc.loom.api.processor.MinecraftJarProcessor;
import net.fabricmc.loom.api.processor.ProcessorContext;
import net.fabricmc.loom.api.processor.SpecContext;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerJarProcessor;
import net.fabricmc.loom.configuration.accesswidener.LocalAccessWidenerEntry;
import net.fabricmc.loom.configuration.accesswidener.ModAccessWidenerEntry;
import net.fabricmc.loom.configuration.accesswidener.ProcessMinecraftAccessWidenersTask;
import net.fabricmc.loom.configuration.ifaceinject.InterfaceInjectionProcessor;
import net.fabricmc.loom.configuration.ifaceinject.InterfaceInjectionProcessor.InjectedInterface;
import net.fabricmc.loom.configuration.ifaceinject.ProcessMinecraftInterfaceInjectionsTask;
import net.fabricmc.loom.configuration.processors.AnalyzeMinecraftJarProcessorTask.ProcessorType;
import net.fabricmc.loom.configuration.processors.speccontext.DebofConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftSourceSets;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftTaskGraph;
import net.fabricmc.loom.task.PrepareSourceMappingsTask;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.fmj.FabricModJsonSource;
import net.fabricmc.loom.util.gradle.SourceSetHelper;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public final class MinecraftJarProcessorManager {
	private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftJarProcessorManager.class);
	private static final String EXTENSION_NAME = "loomMinecraftJarProcessorManager";
	private static final String ACCESS_WIDENER_NAME = "fabric-loom:access-widener";
	private static final String MOD_JAVADOC_NAME = "fabric-loom:mod-javadoc";
	private static final String INTERFACE_INJECTION_NAME = "fabric-loom:interface-inject";
	private static final String JSR_ANNOTATIONS_NAME = "fabric-loom:jsr-annotations";
	private static final String TASK_CACHE_VALUE = String.join("::", ACCESS_WIDENER_NAME, MOD_JAVADOC_NAME, INTERFACE_INJECTION_NAME, JSR_ANNOTATIONS_NAME);

	private final List<ProcessorEntry<?>> jarProcessors;
	private final @Nullable List<JarProcessorTaskConfiguration> taskConfigurations;
	private final @Nullable SourceMappingsTaskConfiguration sourceMappingsTaskConfiguration;
	private final @Nullable String taskCacheValue;

	private MinecraftJarProcessorManager(List<ProcessorEntry<?>> jarProcessors) {
		this.jarProcessors = Collections.unmodifiableList(jarProcessors);
		this.taskConfigurations = null;
		this.sourceMappingsTaskConfiguration = null;
		this.taskCacheValue = null;
	}

	private MinecraftJarProcessorManager(List<JarProcessorTaskConfiguration> taskConfigurations, SourceMappingsTaskConfiguration sourceMappingsTaskConfiguration, String taskCacheValue) {
		this.jarProcessors = List.of();
		this.taskConfigurations = List.copyOf(taskConfigurations);
		this.sourceMappingsTaskConfiguration = sourceMappingsTaskConfiguration;
		this.taskCacheValue = taskCacheValue;
	}

	public static MinecraftJarProcessorManager create(Project project) {
		final Object existing = project.getExtensions().findByName(EXTENSION_NAME);

		if (existing != null) {
			return (MinecraftJarProcessorManager) existing;
		}

		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final MinecraftJarProcessorManager manager = createTaskBacked(project, extension);
		project.getExtensions().add(EXTENSION_NAME, manager);
		return manager;
	}

	private static MinecraftJarProcessorManager createTaskBacked(Project project, LoomGradleExtension extension) {
		final ProcessorInputs inputs = ProcessorInputs.create(project, extension);
		final Provider<List<String>> unsupportedProcessors = extension.getMinecraftJarProcessors()
				.map(processors -> processors.stream()
						.map(processor -> "%s (%s)".formatted(processor.getName(), processor.getClass().getName()))
						.toList())
				.zip(extension.getGameJarProcessors().map(processors -> processors.stream()
						.map(processor -> processor.getClass().getName())
						.toList()), (processors, legacyProcessors) -> {
						final List<String> result = new ArrayList<>(processors);
						result.addAll(legacyProcessors);
						return result;
					});

		final AnalysisConfiguration accessWideners = registerAnalysisTask(
				project,
				extension,
				0,
				ProcessorType.ACCESS_WIDENER,
				extension.getEnableTransitiveAccessWideners(),
				inputs,
				unsupportedProcessors
		);
		final AnalysisConfiguration modJavadocs = registerAnalysisTask(
				project,
				extension,
				1,
				ProcessorType.MOD_JAVADOC,
				extension.getEnableModProvidedJavadoc(),
				inputs,
				project.provider(Collections::<String>emptyList)
		);
		final AnalysisConfiguration interfaceInjections = registerAnalysisTask(
				project,
				extension,
				2,
				ProcessorType.INTERFACE_INJECTION,
				extension.getInterfaceInjection().getEnableDependencyInterfaceInjection(),
				inputs,
				project.provider(Collections::<String>emptyList)
		);
		final List<JarProcessorTaskConfiguration> configurations = List.of(
				new AccessWidenerTaskConfiguration(0, ACCESS_WIDENER_NAME, List.of(), List.of(), accessWideners.output(), accessWideners.sources()),
				new InterfaceInjectionTaskConfiguration(2, INTERFACE_INJECTION_NAME, List.of(), Set.of(), interfaceInjections.output(), interfaceInjections.sources()),
				new JsrAnnotationTaskConfiguration(
						3,
						JSR_ANNOTATIONS_NAME,
						JsrAnnotationRemapperProcessor.annotationMappings(),
						extension.getRemapJsrAnnotationsToJetBrains().map(remapToJetBrains -> !remapToJetBrains)
				)
		);
		final ConfigurableFileCollection sourceInputs = project.files();
		sourceInputs.from(accessWideners.sources(), modJavadocs.sources(), interfaceInjections.sources());
		final SourceMappingsTaskConfiguration sourceMappings = new SourceMappingsTaskConfiguration(
				List.of(),
				List.of(),
				List.of(),
				List.of(accessWideners.output(), modJavadocs.output(), interfaceInjections.output()),
				sourceInputs
		);
		return new MinecraftJarProcessorManager(configurations, sourceMappings, getTaskCacheValue(extension, inputs));
	}

	private static String getTaskCacheValue(LoomGradleExtension extension, ProcessorInputs inputs) {
		// Output paths are fixed during configuration, so use declared settings and paths here. File contents remain task inputs.
		final List<String> values = new ArrayList<>();
		values.add(TASK_CACHE_VALUE);
		values.add("accessWidener=" + configuredPath(extension.getAccessWidenerPath()));
		values.add("transitiveAccessWideners=" + extension.getEnableTransitiveAccessWideners().get());
		values.add("modProvidedJavadoc=" + extension.getEnableModProvidedJavadoc().get());
		values.add("interfaceInjection=" + extension.getInterfaceInjection().getIsEnabled().get());
		values.add("dependencyInterfaceInjection=" + extension.getInterfaceInjection().getEnableDependencyInterfaceInjection().get());
		values.add("fabricModJson=" + configuredPath(extension.getFabricModJsonPath()));
		values.add("localResourcePaths=" + encodeStrings(inputs.localResourcePaths()));
		values.add("localResourceOrder=" + encodeStrings(inputs.localResourceOrder()));
		values.add("remapJsrAnnotationsToJetBrains=" + extension.getRemapJsrAnnotationsToJetBrains().get());
		values.add("disableObfuscation=" + extension.disableObfuscation());
		values.add("productionNamespace=" + extension.getProductionNamespace().get());

		if (!extension.disableObfuscation()) {
			values.add("knownIndyBsms=" + encodeStrings(extension.getKnownIndyBsms().get().stream().sorted().toList()));
		}

		return values.stream()
				.map(value -> value.length() + ":" + value)
				.collect(Collectors.joining());
	}

	private static String encodeStrings(List<String> values) {
		return values.stream()
				.map(value -> value.length() + ":" + value)
				.collect(Collectors.joining());
	}

	private static String configuredPath(RegularFileProperty property) {
		if (!property.isPresent()) {
			return "<absent>";
		}

		return property.get().getAsFile().toPath().toAbsolutePath().normalize().toString();
	}

	private static AnalysisConfiguration registerAnalysisTask(Project project, LoomGradleExtension extension, int processorIndex, ProcessorType processorType, Provider<Boolean> includeDependencies, ProcessorInputs inputs, Provider<List<String>> unsupportedProcessors) {
		final Provider<Boolean> enabled = switch (processorType) {
		case ACCESS_WIDENER -> project.provider(() -> true);
		case MOD_JAVADOC -> extension.getEnableModProvidedJavadoc();
		case INTERFACE_INJECTION -> extension.getInterfaceInjection().getIsEnabled();
		};
		final Provider<Boolean> dependencyInputsEnabled = enabled.zip(includeDependencies, (processorEnabled, include) -> processorEnabled && include);
		final FileCollection commonCompile = conditionalFiles(project, dependencyInputsEnabled, inputs.commonCompile());
		final FileCollection commonRuntime = conditionalFiles(project, dependencyInputsEnabled, inputs.commonRuntime());
		final FileCollection clientCompile = conditionalFiles(project, dependencyInputsEnabled, inputs.clientCompile());
		final FileCollection clientRuntime = conditionalFiles(project, dependencyInputsEnabled, inputs.clientRuntime());
		final FileCollection localResources = processorType == ProcessorType.INTERFACE_INJECTION
				? conditionalFiles(project, enabled, inputs.localResources())
				: project.files();
		final ConfigurableFileCollection sources = project.files();
		sources.from(commonCompile, commonRuntime, clientCompile, clientRuntime, localResources);

		if (processorType == ProcessorType.ACCESS_WIDENER) {
			sources.from(extension.getAccessWidenerPath().map(file -> List.of(file)).orElse(List.of()));
		} else if (processorType == ProcessorType.INTERFACE_INJECTION) {
			sources.from(extension.getFabricModJsonPath().map(file -> List.of(file)).orElse(List.of()));
		}

		final String taskName = switch (processorType) {
		case ACCESS_WIDENER -> "analyzeMinecraftAccessWideners";
		case MOD_JAVADOC -> "analyzeMinecraftModJavadocs";
		case INTERFACE_INJECTION -> "analyzeMinecraftInterfaceInjections";
		};
		final String fileName = "%02d-%s.json".formatted(processorIndex, processorType.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
		final Path output = extension.getFiles().getProjectPersistentCache().toPath()
				.resolve("minecraft")
				.resolve("processor-analysis")
				.resolve(fileName);
		final TaskProvider<AnalyzeMinecraftJarProcessorTask> taskProvider = project.getTasks().register(taskName, AnalyzeMinecraftJarProcessorTask.class, task -> {
			task.setDescription("Analyzes inputs for the %s Minecraft processor.".formatted(processorType.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')));
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.getProcessorType().set(processorType);
			task.getProcessorEnabled().set(enabled);
			task.getIncludeDependencies().set(includeDependencies);
			task.getProductionNamespace().set(extension.getProductionNamespaceEnum().map(MappingsNamespace::toString));
			task.getMarkdownJavadocOption().set(extension.disableObfuscation() ? MarkdownJavadocOption.REQUIRED.name() : MarkdownJavadocOption.UNSUPPORTED.name());
			task.getCommonCompileMods().from(commonCompile);
			task.getCommonRuntimeMods().from(commonRuntime);
			task.getClientCompileMods().from(clientCompile);
			task.getClientRuntimeMods().from(clientRuntime);
			task.getLocalResources().from(localResources);
			task.getLocalResourcePaths().set(inputs.localResourcePaths());
			task.getLocalResourceOrder().set(inputs.localResourceOrder());
			task.getUnsupportedProcessors().set(unsupportedProcessors);
			task.getOutputAnalysis().fileValue(output.toFile());

			if (processorType == ProcessorType.ACCESS_WIDENER) {
				task.getLocalAccessWidener().set(extension.getAccessWidenerPath());
			} else if (processorType == ProcessorType.INTERFACE_INJECTION) {
				task.getLocalFabricModJson().set(extension.getFabricModJsonPath());
			}
		});
		MinecraftTaskGraph.get(project).registerOutput(output, taskProvider);
		return new AnalysisConfiguration(output, sources);
	}

	private static FileCollection conditionalFiles(Project project, Provider<Boolean> enabled, FileCollection files) {
		return project.files(enabled.map(value -> value ? files : List.of()));
	}

	@Nullable
	public static MinecraftJarProcessorManager create(List<MinecraftJarProcessor<?>> processors, SpecContext context) {
		List<ProcessorEntry<?>> entries = new ArrayList<>();

		for (MinecraftJarProcessor<?> processor : processors) {
			LOGGER.debug("Building processor spec for {}", processor.getName());
			MinecraftJarProcessor.Spec spec = processor.buildSpec(context);

			if (spec != null) {
				LOGGER.debug("Adding processor entry for {}", processor.getName());
				entries.add(new ProcessorEntry<>(processor, spec));
			}
		}

		if (entries.isEmpty()) {
			LOGGER.debug("No processor entries");
			return null;
		}

		return new MinecraftJarProcessorManager(entries);
	}

	private String getCacheValue() {
		if (taskConfigurations != null) {
			return Objects.requireNonNull(taskCacheValue);
		}

		return jarProcessors.stream()
				.map(ProcessorEntry::cacheValue)
				.collect(Collectors.joining("::"));
	}

	private String getDebugString() {
		final var sj = new StringJoiner("\n");

		for (ProcessorEntry<?> jarProcessor : jarProcessors) {
			sj.add(jarProcessor.name() + ":");
			sj.add("\tHash: " + jarProcessor.hashCode());
			sj.add("\tStr: " + jarProcessor.cacheValue());
		}

		return sj.toString();
	}

	public String getJarHash() {
		//fabric-loom:mod-javadoc:-1289977000
		return Checksum.of(getCacheValue()).sha1().hex(10);
	}

	public String getSourceMappingsHash() {
		return Checksum.of(getCacheValue()).sha1().hex();
	}

	public List<JarProcessorTaskConfiguration> getJarProcessorTaskConfigurations() throws IOException {
		if (taskConfigurations != null) {
			return taskConfigurations;
		}

		final List<JarProcessorTaskConfiguration> configurations = new ArrayList<>();

		for (int processorIndex = 0; processorIndex < jarProcessors.size(); processorIndex++) {
			final ProcessorEntry<?> entry = jarProcessors.get(processorIndex);

			if (entry.processor() instanceof AccessWidenerJarProcessor) {
				configurations.add(getAccessWidenerTaskConfiguration(processorIndex, entry));
			} else if (entry.processor() instanceof InterfaceInjectionProcessor) {
				final InterfaceInjectionProcessor.Spec spec = (InterfaceInjectionProcessor.Spec) entry.spec();
				configurations.add(new InterfaceInjectionTaskConfiguration(
						processorIndex,
						entry.name(),
						spec.injectedInterfaces().stream()
								.map(injectedInterface -> ProcessMinecraftInterfaceInjectionsTask.encodeInjectedInterface(
										injectedInterface.modId(),
										injectedInterface.className(),
										injectedInterface.ifaceName(),
										injectedInterface.generics()
								))
								.toList(),
						spec.clientOnlyModIds()
				));
			} else if (entry.processor() instanceof JsrAnnotationRemapperProcessor) {
				final JsrAnnotationRemapperProcessor.Spec spec = (JsrAnnotationRemapperProcessor.Spec) entry.spec();
				configurations.add(new JsrAnnotationTaskConfiguration(processorIndex, entry.name(), spec.annotationMapping()));
			} else if (!(entry.processor() instanceof ModJavadocProcessor)) {
				throw new UnsupportedOperationException("Task-backed Minecraft jar processing does not support processor %s (%s)"
						.formatted(entry.name(), entry.processor().getClass().getName()));
			}
		}

		return List.copyOf(configurations);
	}

	private AccessWidenerTaskConfiguration getAccessWidenerTaskConfiguration(int processorIndex, ProcessorEntry<?> entry) throws IOException {
		final List<String> descriptors = new ArrayList<>();
		final Map<Path, Integer> sourceIndices = new LinkedHashMap<>();
		final AccessWidenerJarProcessor.Spec spec = (AccessWidenerJarProcessor.Spec) entry.spec();

		for (var accessWidener : spec.accessWideners()) {
			final boolean local;
			final boolean transitiveOnly;
			final AccessWidenerSource source;

			if (accessWidener instanceof LocalAccessWidenerEntry localEntry) {
				local = true;
				transitiveOnly = false;
				source = new AccessWidenerSource(localEntry.path(), "");
			} else if (accessWidener instanceof ModAccessWidenerEntry modEntry) {
				local = false;
				transitiveOnly = modEntry.transitiveOnly();
				source = getAccessWidenerSource(modEntry);
			} else {
				throw new IllegalStateException("Unsupported access widener entry type: " + accessWidener.getClass().getName());
			}

			final Path sourcePath = source.path().toAbsolutePath().normalize();
			final int sourceIndex = sourceIndices.computeIfAbsent(sourcePath, ignored -> sourceIndices.size());
			descriptors.add(ProcessMinecraftAccessWidenersTask.encodeAccessWidener(
						0,
						accessWidener.environment(),
						local,
						transitiveOnly,
						sourceIndex,
						source.pathWithinJar()
				));
		}

		return new AccessWidenerTaskConfiguration(processorIndex, entry.name(), descriptors, List.copyOf(sourceIndices.keySet()));
	}

	public SourceMappingsTaskConfiguration getSourceMappingsTaskConfiguration() throws IOException {
		if (sourceMappingsTaskConfiguration != null) {
			return sourceMappingsTaskConfiguration;
		}

		final List<String> transformations = new ArrayList<>();
		final Map<Path, Integer> sourceIndices = new LinkedHashMap<>();
		final List<String> unsupportedProcessors = new ArrayList<>();

		for (ProcessorEntry<?> entry : jarProcessors) {
			if (entry.mappingsProcessor() == null) {
				continue;
			}

			if (entry.processor() instanceof AccessWidenerJarProcessor) {
				addAccessWidenerSourceMappings(entry, transformations, sourceIndices);
			} else if (entry.processor() instanceof InterfaceInjectionProcessor) {
				addInterfaceInjectionSourceMappings(entry, transformations);
			} else if (entry.processor() instanceof ModJavadocProcessor) {
				addJavadocSourceMappings(entry, transformations);
			} else {
				unsupportedProcessors.add("%s (%s)".formatted(entry.name(), entry.processor().getClass().getName()));
			}
		}

		return new SourceMappingsTaskConfiguration(
				transformations,
				List.copyOf(sourceIndices.keySet()),
				unsupportedProcessors
		);
	}

	private static void addAccessWidenerSourceMappings(ProcessorEntry<?> entry, List<String> transformations, Map<Path, Integer> sourceIndices) throws IOException {
		final AccessWidenerJarProcessor.Spec spec = (AccessWidenerJarProcessor.Spec) entry.spec();

		for (var accessWidener : spec.accessWideners()) {
			if (accessWidener.mappingId() == null) {
				continue;
			}

			if (!(accessWidener instanceof ModAccessWidenerEntry modEntry)) {
				throw new IllegalStateException("Unsupported source mappings access widener entry: " + accessWidener.getClass().getName());
			}

			final AccessWidenerSource source = getAccessWidenerSource(modEntry);
			final Path sourcePath = source.path().toAbsolutePath().normalize();
			final int sourceIndex = sourceIndices.computeIfAbsent(sourcePath, ignored -> sourceIndices.size());
			transformations.add(PrepareSourceMappingsTask.encodeAccessWidener(
					accessWidener.mappingId(),
					modEntry.transitiveOnly(),
					sourceIndex,
					source.pathWithinJar()
			));
		}
	}

	private static void addInterfaceInjectionSourceMappings(ProcessorEntry<?> entry, List<String> transformations) {
		final InterfaceInjectionProcessor.Spec spec = (InterfaceInjectionProcessor.Spec) entry.spec();

		for (InjectedInterface injectedInterface : spec.injectedInterfaces()) {
			transformations.add(PrepareSourceMappingsTask.encodeInterfaceInjection(
					injectedInterface.modId(),
					injectedInterface.className(),
					injectedInterface.ifaceName()
			));
		}
	}

	private static void addJavadocSourceMappings(ProcessorEntry<?> entry, List<String> transformations) {
		final ModJavadocProcessor.Spec spec = (ModJavadocProcessor.Spec) entry.spec();

		for (ModJavadocProcessor.ModJavadoc javadoc : spec.javadocs()) {
			final MemoryMappingTree mappings = javadoc.mappingTree();
			final String namespace = mappings.getSrcNamespace();

			for (MappingTree.ClassMapping classMapping : mappings.getClasses()) {
				addJavadocTransformation(transformations, javadoc.modId(), namespace, "class", classMapping.getSrcName(), null, null, classMapping.getComment());

				for (MappingTree.FieldMapping fieldMapping : classMapping.getFields()) {
					addJavadocTransformation(transformations, javadoc.modId(), namespace, "field", classMapping.getSrcName(), fieldMapping.getSrcName(), fieldMapping.getSrcDesc(), fieldMapping.getComment());
				}

				for (MappingTree.MethodMapping methodMapping : classMapping.getMethods()) {
					addJavadocTransformation(transformations, javadoc.modId(), namespace, "method", classMapping.getSrcName(), methodMapping.getSrcName(), methodMapping.getSrcDesc(), methodMapping.getComment());
				}
			}
		}
	}

	private static void addJavadocTransformation(List<String> transformations, String modId, String namespace, String kind, String owner, @Nullable String name, @Nullable String descriptor, @Nullable String comment) {
		if (comment == null) {
			LOGGER.warn("Mod {} provided javadoc has mapping for {} {}, without comment", modId, kind, name != null ? name : owner);
			return;
		}

		transformations.add(PrepareSourceMappingsTask.encodeJavadoc(modId, namespace, kind, owner, name, descriptor, comment));
	}

	private static AccessWidenerSource getAccessWidenerSource(ModAccessWidenerEntry entry) throws IOException {
		final FabricModJsonSource source = entry.mod().getSource();

		if (source instanceof FabricModJsonSource.ZipSource zipSource) {
			return new AccessWidenerSource(zipSource.zipPath(), entry.path());
		}

		if (source instanceof FabricModJsonSource.DirectorySource directorySource) {
			return new AccessWidenerSource(directorySource.directoryPath().resolve(entry.path()), "");
		}

		if (source instanceof FabricModJsonSource.SourceSetSource sourceSetSource) {
			return new AccessWidenerSource(sourceSetSource.findFile(entry.path()).toPath(), "");
		}

		throw new IllegalStateException("Unsupported mod source for task-backed access widener: " + source.getClass().getName());
	}

	public List<String> getProcessorDescriptions() {
		return jarProcessors.stream()
				.map(entry -> "%s (%s)".formatted(entry.name(), entry.processor().getClass().getName()))
				.toList();
	}

	public interface JarProcessorTaskConfiguration {
		int processorIndex();

		String name();
	}

	public record AccessWidenerTaskConfiguration(int processorIndex, String name, List<String> descriptors, List<Path> sources, @Nullable Path analysis, @Nullable FileCollection processorSources) implements JarProcessorTaskConfiguration {
		private AccessWidenerTaskConfiguration(int processorIndex, String name, List<String> descriptors, List<Path> sources) {
			this(processorIndex, name, descriptors, sources, null, null);
		}

		public AccessWidenerTaskConfiguration {
			descriptors = List.copyOf(descriptors);
			sources = List.copyOf(sources);
		}
	}

	public record InterfaceInjectionTaskConfiguration(int processorIndex, String name, List<String> injectedInterfaces, Set<String> clientOnlyModIds, @Nullable Path analysis, @Nullable FileCollection processorSources) implements JarProcessorTaskConfiguration {
		private InterfaceInjectionTaskConfiguration(int processorIndex, String name, List<String> injectedInterfaces, Set<String> clientOnlyModIds) {
			this(processorIndex, name, injectedInterfaces, clientOnlyModIds, null, null);
		}

		public InterfaceInjectionTaskConfiguration {
			injectedInterfaces = List.copyOf(injectedInterfaces);
			clientOnlyModIds = Set.copyOf(clientOnlyModIds);
		}
	}

	public record JsrAnnotationTaskConfiguration(int processorIndex, String name, Map<String, String> annotationMappings, @Nullable Provider<Boolean> enabled) implements JarProcessorTaskConfiguration {
		private JsrAnnotationTaskConfiguration(int processorIndex, String name, Map<String, String> annotationMappings) {
			this(processorIndex, name, annotationMappings, null);
		}

		public JsrAnnotationTaskConfiguration {
			annotationMappings = Map.copyOf(annotationMappings);
		}
	}

	public record SourceMappingsTaskConfiguration(List<String> transformations, List<Path> sources, List<String> unsupportedProcessors, List<Path> analyses, @Nullable FileCollection processorSources) {
		public SourceMappingsTaskConfiguration(List<String> transformations, List<Path> sources, List<String> unsupportedProcessors) {
			this(transformations, sources, unsupportedProcessors, List.of(), null);
		}

		public SourceMappingsTaskConfiguration {
			transformations = List.copyOf(transformations);
			sources = List.copyOf(sources);
			unsupportedProcessors = List.copyOf(unsupportedProcessors);
			analyses = List.copyOf(analyses);
		}
	}

	private record AnalysisConfiguration(Path output, FileCollection sources) {
	}

	private record ProcessorInputs(FileCollection commonCompile, FileCollection commonRuntime, FileCollection clientCompile, FileCollection clientRuntime, FileCollection localResources, List<String> localResourcePaths, List<String> localResourceOrder) {
		private static ProcessorInputs create(Project project, LoomGradleExtension extension) {
			final ConfigurableFileCollection commonCompile = project.files();
			final ConfigurableFileCollection commonRuntime = project.files();
			final ConfigurableFileCollection clientCompile = project.files();
			final ConfigurableFileCollection clientRuntime = project.files();

			if (extension.disableObfuscation()) {
				commonCompile.from(DebofConfiguration.COMPILE.getConfiguration(project, DebofConfiguration.TargetSourceSet.MAIN));
				commonRuntime.from(DebofConfiguration.RUNTIME.getConfiguration(project, DebofConfiguration.TargetSourceSet.MAIN));

				if (extension.areEnvironmentSourceSetsSplit()) {
					clientCompile.from(DebofConfiguration.COMPILE.getConfiguration(project, DebofConfiguration.TargetSourceSet.CLIENT));
					clientRuntime.from(DebofConfiguration.RUNTIME.getConfiguration(project, DebofConfiguration.TargetSourceSet.CLIENT));
				}
			} else {
				for (RemapConfigurationSettings settings : extension.getRemapConfigurations()) {
					final Configuration source = project.getConfigurations().getByName(settings.getName());
					final FileCollection compile = artifactsForUsage(project, source, Usage.JAVA_API);
					final FileCollection runtime = artifactsForUsage(project, source, Usage.JAVA_RUNTIME);
					final Provider<Boolean> isClient = settings.getSourceSet()
							.map(sourceSet -> MinecraftSourceSets.Split.CLIENT_ONLY_SOURCE_SET_NAME.equals(sourceSet.getName()));
					final Provider<Boolean> useCompile = settings.getApplyDependencyTransforms()
							.zip(settings.getOnCompileClasspath(), (apply, onClasspath) -> apply && onClasspath);
					final Provider<Boolean> useRuntime = settings.getApplyDependencyTransforms()
							.zip(settings.getOnRuntimeClasspath(), (apply, onClasspath) -> apply && onClasspath);
					commonCompile.from(useCompile.zip(isClient, (use, client) -> use && !client)
							.map(use -> use ? compile : List.of()));
					commonRuntime.from(useRuntime.zip(isClient, (use, client) -> use && !client)
							.map(use -> use ? runtime : List.of()));
					clientCompile.from(useCompile.zip(isClient, (use, client) -> use && client)
							.map(use -> use ? compile : List.of()));
					clientRuntime.from(useRuntime.zip(isClient, (use, client) -> use && client)
							.map(use -> use ? runtime : List.of()));
				}
			}

			final List<Path> localResourcePaths = new ArrayList<>();
			final List<String> localResourceOrder = new ArrayList<>();
			final ConfigurableFileCollection localResources = project.files();
			addLocalResources(project, SourceSetHelper.getMainSourceSet(project), localResources, localResourcePaths, localResourceOrder);

			if (extension.areEnvironmentSourceSetsSplit()) {
				addLocalResources(
						project,
						SourceSetHelper.getSourceSetByName(MinecraftSourceSets.Split.CLIENT_ONLY_SOURCE_SET_NAME, project),
						localResources,
						localResourcePaths,
						localResourceOrder
				);
			}

			return new ProcessorInputs(
					commonCompile,
					commonRuntime,
					clientCompile,
					clientRuntime,
					localResources,
					localResourcePaths.stream().map(Path::toString).toList(),
					localResourceOrder
			);
		}

		private static FileCollection artifactsForUsage(Project project, Configuration source, String usageName) {
			final ArtifactView view = source.getIncoming().artifactView(configuration -> {
				configuration.withVariantReselection();
				configuration.attributes(attributes -> attributes.attribute(
						Usage.USAGE_ATTRIBUTE,
						project.getObjects().named(Usage.class, usageName)
				));
			});
			return view.getFiles();
		}

		private static void addLocalResources(Project project, SourceSet sourceSet, ConfigurableFileCollection resources, List<Path> paths, List<String> order) {
			resources.from(sourceSet.getResources().getSourceDirectories());

			for (var file : sourceSet.getResources().getSrcDirs()) {
				paths.add(file.toPath().toAbsolutePath().normalize());
				order.add(sourceSet.getName() + ":" + project.relativePath(file));
			}
		}
	}

	private record AccessWidenerSource(Path path, String pathWithinJar) {
	}

	public boolean requiresProcessingJar(Path jar) {
		Objects.requireNonNull(jar);

		if (Files.notExists(jar)) {
			LOGGER.debug("{} does not exist, generating", jar);
			return true;
		}

		return false;
	}

	public void processJar(Path jar, ProcessorContext context) throws IOException {
		for (ProcessorEntry<?> entry : jarProcessors) {
			try {
				entry.processJar(jar, context);
			} catch (IOException e) {
				try {
					Files.delete(jar);
				} catch (IOException ioe) {
					LOGGER.error("Failed to delete jar after failed processing: {}", jar, ioe);
				}

				throw new IOException("Failed to process jar when running jar processor: %s - %s".formatted(entry.name(), e.getMessage()), e);
			}
		}
	}

	public boolean processMappings(MemoryMappingTree mappings, MappingProcessorContext context) {
		boolean transformed = false;

		for (ProcessorEntry<?> entry : jarProcessors) {
			if (entry.processMappings(mappings, context)) {
				transformed = true;
			}
		}

		return transformed;
	}

	record ProcessorEntry<S extends MinecraftJarProcessor.Spec>(S spec, MinecraftJarProcessor<S> processor, MinecraftJarProcessor.@Nullable MappingsProcessor<S> mappingsProcessor) {
		@SuppressWarnings("unchecked")
		ProcessorEntry(MinecraftJarProcessor<?> processor, MinecraftJarProcessor.Spec spec) {
			this((S) Objects.requireNonNull(spec), (MinecraftJarProcessor<S>) processor, (MinecraftJarProcessor.MappingsProcessor<S>) processor.processMappings());
		}

		private void processJar(Path jar, ProcessorContext context) throws IOException {
			processor().processJar(jar, spec, context);
		}

		private boolean processMappings(MemoryMappingTree mappings, MappingProcessorContext context) {
			if (mappingsProcessor() == null) {
				return false;
			}

			return mappingsProcessor().transform(mappings, spec, context);
		}

		private String name() {
			return processor.getName();
		}

		private String cacheValue() {
			return processor.getName() + ":" + spec.hashCode();
		}
	}
}
