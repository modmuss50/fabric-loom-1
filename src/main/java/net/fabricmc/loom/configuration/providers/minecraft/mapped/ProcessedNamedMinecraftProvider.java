/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2023 FabricMC
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

package net.fabricmc.loom.configuration.providers.minecraft.mapped;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.gradle.api.Task;
import org.gradle.api.tasks.TaskProvider;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.accesswidener.ProcessMinecraftAccessWidenersTask;
import net.fabricmc.loom.configuration.ifaceinject.ProcessMinecraftInterfaceInjectionsTask;
import net.fabricmc.loom.configuration.processors.MinecraftJarProcessorManager;
import net.fabricmc.loom.configuration.processors.ProcessMinecraftJsrAnnotationsTask;
import net.fabricmc.loom.configuration.providers.minecraft.LegacyMergedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MergedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftSourceSets;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftTaskGraph;
import net.fabricmc.loom.configuration.providers.minecraft.SingleJarEnvType;
import net.fabricmc.loom.configuration.providers.minecraft.SingleJarMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.SplitMinecraftProvider;
import net.fabricmc.loom.task.ProcessMinecraftJarTask;
import net.fabricmc.loom.util.Constants;

public abstract class ProcessedNamedMinecraftProvider<M extends MinecraftProvider, P extends NamedMinecraftProvider<M>> extends NamedMinecraftProvider<M> {
	private final P parentMinecraftProvider;
	private final MinecraftJarProcessorManager jarProcessorManager;

	public ProcessedNamedMinecraftProvider(P parentMinecraftProvide, MinecraftJarProcessorManager jarProcessorManager) {
		super(parentMinecraftProvide.getProject(), parentMinecraftProvide.getMinecraftProvider());
		this.parentMinecraftProvider = parentMinecraftProvide;
		this.jarProcessorManager = Objects.requireNonNull(jarProcessorManager);
	}

	@Override
	public List<MinecraftJar> provide(ProvideContext context) throws Exception {
		parentMinecraftProvider.provide(context.withApplyDependencies(false));
		final List<MinecraftJar> parentMinecraftJars = parentMinecraftProvider.getMinecraftJars();
		final Map<MinecraftJar, MinecraftJar> minecraftJarOutputMap = parentMinecraftJars.stream()
				.collect(Collectors.toMap(Function.identity(), this::getProcessedJar));
		final List<MinecraftJarProcessorManager.JarProcessorTaskConfiguration> processorConfigurations = jarProcessorManager.getJarProcessorTaskConfigurations();

		registerProcessorTasks(minecraftJarOutputMap, processorConfigurations);

		if (context.applyDependencies()) {
			applyTaskDependencies();
		}

		return List.copyOf(minecraftJarOutputMap.values());
	}

	private void registerProcessorTasks(Map<MinecraftJar, MinecraftJar> minecraftJarOutputMap, List<MinecraftJarProcessorManager.JarProcessorTaskConfiguration> processorConfigurations) {
		final MinecraftTaskGraph taskGraph = MinecraftTaskGraph.get(getProject());
		final boolean disableObfuscation = extension.disableObfuscation();
		final String productionNamespace = extension.getProductionNamespaceEnum().get().toString();
		final List<Path> remapClasspath = disableObfuscation
				? List.of()
				: extension.getProductionNamespaceEnum().get() == MappingsNamespace.NAMED
						? parentMinecraftProvider.getMinecraftJarPaths()
						: extension.getMinecraftJars(extension.getProductionNamespaceEnum().get());
		final Path mappingsFile = disableObfuscation ? null : extension.getMappingConfiguration().tinyMappings;

		for (Map.Entry<MinecraftJar, MinecraftJar> entry : minecraftJarOutputMap.entrySet()) {
			final MinecraftJar inputJar = entry.getKey();
			final MinecraftJar outputJar = entry.getValue();
			final String taskSuffix = Character.toUpperCase(outputJar.getName().charAt(0)) + outputJar.getName().substring(1);
			Path stageInput = inputJar.getPath();

			for (MinecraftJarProcessorManager.JarProcessorTaskConfiguration processorConfiguration : processorConfigurations) {
				final Path stageOutput = getProcessorStagePath(outputJar, processorConfiguration.processorIndex());
				final TaskProvider<? extends Task> stageTask = registerProcessorTask(
						inputJar,
						taskSuffix,
						stageInput,
						stageOutput,
						processorConfiguration,
						disableObfuscation,
						productionNamespace,
						mappingsFile,
						remapClasspath
				);
				taskGraph.dependsOn(stageTask, stageInput);

				if (processorConfiguration instanceof MinecraftJarProcessorManager.AccessWidenerTaskConfiguration accessWideners
						&& accessWideners.analysis() != null) {
					taskGraph.dependsOn(stageTask, accessWideners.analysis());
				} else if (processorConfiguration instanceof MinecraftJarProcessorManager.InterfaceInjectionTaskConfiguration interfaceInjections
						&& interfaceInjections.analysis() != null) {
					taskGraph.dependsOn(stageTask, interfaceInjections.analysis());
				}

				if (processorConfiguration instanceof MinecraftJarProcessorManager.AccessWidenerTaskConfiguration
						|| processorConfiguration instanceof MinecraftJarProcessorManager.InterfaceInjectionTaskConfiguration) {
					registerRemappingDependencies(taskGraph, stageTask, mappingsFile, remapClasspath);
				}

				taskGraph.registerOutput(stageOutput, stageTask);
				stageInput = stageOutput;
			}

			final Path processedInput = stageInput;
			final TaskProvider<ProcessMinecraftJarTask> processorTask = getProject().getTasks().register("applyMinecraftProcessors" + taskSuffix, ProcessMinecraftJarTask.class, task -> {
				task.setDescription("Finalizes the processed %s Minecraft jar.".formatted(outputJar.getName()));
				task.setGroup(Constants.TaskGroup.FABRIC);
				task.getInputJar().fileValue(processedInput.toFile());
				task.getOutputJar().fileValue(outputJar.toFile());
			});
			taskGraph.dependsOn(processorTask, processedInput);
			taskGraph.registerOutput(outputJar.getPath(), processorTask);

			final Path backupPath = getBackupJarPath(outputJar);
			final TaskProvider<ProcessMinecraftJarTask> backupTask = getProject().getTasks().register("backupProcessedMinecraft" + taskSuffix, ProcessMinecraftJarTask.class, task -> {
				task.setDescription("Backs up the processed %s Minecraft jar.".formatted(outputJar.getName()));
				task.setGroup(Constants.TaskGroup.FABRIC);
				task.getInputJar().fileValue(outputJar.toFile());
				task.getOutputJar().fileValue(backupPath.toFile());
			});
			taskGraph.dependsOn(backupTask, outputJar.getPath());
			taskGraph.registerOutput(backupPath, backupTask);
		}
	}

	private TaskProvider<? extends Task> registerProcessorTask(MinecraftJar minecraftJar, String taskSuffix, Path input, Path output, MinecraftJarProcessorManager.JarProcessorTaskConfiguration configuration, boolean disableObfuscation, String productionNamespace, Path mappingsFile, List<Path> remapClasspath) {
		final String taskName = "applyMinecraft" + taskSuffix + "Processor" + configuration.processorIndex();

		if (configuration instanceof MinecraftJarProcessorManager.AccessWidenerTaskConfiguration accessWideners) {
			return getProject().getTasks().register(taskName, ProcessMinecraftAccessWidenersTask.class, task -> {
				configureProcessorTask(task, configuration, input, output);
				task.getAccessWideners().set(accessWideners.descriptors());
				task.getAccessWidenerSources().from(accessWideners.sources().stream().map(Path::toFile).toList());
				task.getAccessWidenerSourcePaths().set(accessWideners.sources().stream().map(Path::toString).toList());

				if (accessWideners.analysis() != null) {
					task.getProcessorAnalysis().fileValue(accessWideners.analysis().toFile());
				}

				if (accessWideners.processorSources() != null) {
					task.getAccessWidenerSources().from(accessWideners.processorSources());
				}

				task.getIncludesClient().set(minecraftJar.includesClient());
				task.getIncludesServer().set(minecraftJar.includesServer());
				task.getDisableObfuscation().set(disableObfuscation);
				task.getProductionNamespace().set(productionNamespace);

				if (!disableObfuscation) {
					task.getKnownIndyBsms().set(extension.getKnownIndyBsms());
					task.getMappingsFile().fileValue(Objects.requireNonNull(mappingsFile).toFile());
					task.getRemapClasspath().from(remapClasspath.stream().map(Path::toFile).toList());
				}
			});
		}

		if (configuration instanceof MinecraftJarProcessorManager.InterfaceInjectionTaskConfiguration interfaceInjections) {
			return getProject().getTasks().register(taskName, ProcessMinecraftInterfaceInjectionsTask.class, task -> {
				configureProcessorTask(task, configuration, input, output);
				task.getInjectedInterfaces().set(interfaceInjections.injectedInterfaces());
				task.getClientOnlyModIds().set(interfaceInjections.clientOnlyModIds());

				if (interfaceInjections.analysis() != null) {
					task.getProcessorAnalysis().fileValue(interfaceInjections.analysis().toFile());
				}

				if (interfaceInjections.processorSources() != null) {
					task.getProcessorSources().from(interfaceInjections.processorSources());
				}

				task.getIncludesClient().set(minecraftJar.includesClient());
				task.getDisableObfuscation().set(disableObfuscation);
				task.getProductionNamespace().set(productionNamespace);

				if (!disableObfuscation) {
					task.getKnownIndyBsms().set(extension.getKnownIndyBsms());
					task.getMappingsFile().fileValue(Objects.requireNonNull(mappingsFile).toFile());
					task.getRemapClasspath().from(remapClasspath.stream().map(Path::toFile).toList());
				}
			});
		}

		if (configuration instanceof MinecraftJarProcessorManager.JsrAnnotationTaskConfiguration jsrAnnotations) {
			return getProject().getTasks().register(taskName, ProcessMinecraftJsrAnnotationsTask.class, task -> {
				configureProcessorTask(task, configuration, input, output);
				task.getAnnotationMappings().set(jsrAnnotations.annotationMappings());

				if (jsrAnnotations.enabled() != null) {
					task.getProcessorEnabled().set(jsrAnnotations.enabled());
				}
			});
		}

		throw new IllegalArgumentException("Unsupported Minecraft processor task configuration: " + configuration.getClass().getName());
	}

	private static void configureProcessorTask(ProcessMinecraftAccessWidenersTask task, MinecraftJarProcessorManager.JarProcessorTaskConfiguration configuration, Path input, Path output) {
		configureProcessorTask((Task) task, configuration);
		task.getInputJar().fileValue(input.toFile());
		task.getOutputJar().fileValue(output.toFile());
	}

	private static void configureProcessorTask(ProcessMinecraftInterfaceInjectionsTask task, MinecraftJarProcessorManager.JarProcessorTaskConfiguration configuration, Path input, Path output) {
		configureProcessorTask((Task) task, configuration);
		task.getInputJar().fileValue(input.toFile());
		task.getOutputJar().fileValue(output.toFile());
	}

	private static void configureProcessorTask(ProcessMinecraftJsrAnnotationsTask task, MinecraftJarProcessorManager.JarProcessorTaskConfiguration configuration, Path input, Path output) {
		configureProcessorTask((Task) task, configuration);
		task.getInputJar().fileValue(input.toFile());
		task.getOutputJar().fileValue(output.toFile());
	}

	private static void configureProcessorTask(Task task, MinecraftJarProcessorManager.JarProcessorTaskConfiguration configuration) {
		task.setDescription("Applies the %s processor to a Minecraft jar.".formatted(configuration.name()));
		task.setGroup(Constants.TaskGroup.FABRIC);
	}

	private static void registerRemappingDependencies(MinecraftTaskGraph taskGraph, TaskProvider<? extends Task> task, Path mappingsFile, List<Path> remapClasspath) {
		if (mappingsFile != null && taskGraph.hasProducer(mappingsFile)) {
			taskGraph.dependsOn(task, mappingsFile);
		}

		for (Path classpath : remapClasspath) {
			if (taskGraph.hasProducer(classpath)) {
				taskGraph.dependsOn(task, classpath);
			}
		}
	}

	private static Path getProcessorStagePath(MinecraftJar outputJar, int processorIndex) {
		return outputJar.getPath().getParent()
				.resolve("processor-stages")
				.resolve("%02d.jar".formatted(processorIndex));
	}

	private void applyTaskDependencies() {
		final List<MinecraftJar.Type> dependencyTargets = getDependencyTypes();

		if (dependencyTargets.isEmpty()) {
			return;
		}

		final MinecraftTaskGraph taskGraph = MinecraftTaskGraph.get(getProject());
		MinecraftSourceSets.get(getProject()).applyDependencies(
				(configuration, type) -> getProject().getDependencies().add(configuration, taskGraph.files(getProcessedPath(getMinecraftJar(type)))),
				dependencyTargets
		);
	}

	private MinecraftJar getMinecraftJar(MinecraftJar.Type type) {
		return parentMinecraftProvider.getMinecraftJars().stream()
				.filter(jar -> jar.getType() == type)
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Missing Minecraft jar for type: " + type));
	}

	@Override
	public List<? extends OutputJar> getOutputJars() {
		return parentMinecraftProvider.getMinecraftJars().stream()
				.map(this::getProcessedJar)
				.map(SimpleOutputJar::new)
				.toList();
	}

	@Override
	public List<MinecraftJar.Type> getDependencyTypes() {
		return parentMinecraftProvider.getDependencyTypes();
	}

	@Override
	protected String getName(MinecraftJar.Type type) {
		// Hash the cache value so that we don't have to process the same JAR multiple times for many projects
		return "minecraft-%s-%s".formatted(type.toString(), jarProcessorManager.getJarHash());
	}

	@Override
	public Path getJar(MinecraftJar.Type type) {
		// Something has gone wrong if this gets called.
		throw new UnsupportedOperationException();
	}

	@Override
	public List<RemappedJars> getRemappedJars() {
		throw new UnsupportedOperationException();
	}

	@Override
	public List<MinecraftJar> getMinecraftJars() {
		return getParentMinecraftProvider().getMinecraftJars().stream()
				.map(this::getProcessedJar)
				.toList();
	}

	public P getParentMinecraftProvider() {
		return parentMinecraftProvider;
	}

	private Path getProcessedPath(MinecraftJar minecraftJar) {
		return extension.getFiles().getProjectPersistentCache().toPath()
				.resolve("minecraft")
				.resolve("processed")
				.resolve(getVersion())
				.resolve(jarProcessorManager.getJarHash())
				.resolve(minecraftJar.getType().toString())
				.resolve("minecraft-%s.jar".formatted(minecraftJar.getType()));
	}

	public MinecraftJar getProcessedJar(MinecraftJar minecraftJar) {
		return minecraftJar.forPath(getProcessedPath(minecraftJar));
	}

	public static final class MergedImpl extends ProcessedNamedMinecraftProvider<MergedMinecraftProvider, NamedMinecraftProvider.MergedImpl> implements Merged {
		public MergedImpl(NamedMinecraftProvider.MergedImpl parentMinecraftProvide, MinecraftJarProcessorManager jarProcessorManager) {
			super(parentMinecraftProvide, jarProcessorManager);
		}

		@Override
		public MinecraftJar getMergedJar() {
			return getProcessedJar(getParentMinecraftProvider().getMergedJar());
		}
	}

	public static final class LegacyMergedImpl extends ProcessedNamedMinecraftProvider<LegacyMergedMinecraftProvider, NamedMinecraftProvider.LegacyMergedImpl> implements Merged {
		public LegacyMergedImpl(NamedMinecraftProvider.LegacyMergedImpl parentMinecraftProvider, MinecraftJarProcessorManager jarProcessorManager) {
			super(parentMinecraftProvider, jarProcessorManager);
		}

		@Override
		public MinecraftJar getMergedJar() {
			return getProcessedJar(getParentMinecraftProvider().getMergedJar());
		}
	}

	public static final class SplitImpl extends ProcessedNamedMinecraftProvider<SplitMinecraftProvider, NamedMinecraftProvider.SplitImpl> implements Split {
		public SplitImpl(NamedMinecraftProvider.SplitImpl parentMinecraftProvide, MinecraftJarProcessorManager jarProcessorManager) {
			super(parentMinecraftProvide, jarProcessorManager);
		}

		@Override
		public MinecraftJar getCommonJar() {
			return getProcessedJar(getParentMinecraftProvider().getCommonJar());
		}

		@Override
		public MinecraftJar getClientOnlyJar() {
			return getProcessedJar(getParentMinecraftProvider().getClientOnlyJar());
		}
	}

	public static final class SingleJarImpl extends ProcessedNamedMinecraftProvider<SingleJarMinecraftProvider, NamedMinecraftProvider.SingleJarImpl> implements SingleJar {
		private final SingleJarEnvType env;

		private SingleJarImpl(NamedMinecraftProvider.SingleJarImpl parentMinecraftProvide, MinecraftJarProcessorManager jarProcessorManager, SingleJarEnvType env) {
			super(parentMinecraftProvide, jarProcessorManager);
			this.env = env;
		}

		public static ProcessedNamedMinecraftProvider.SingleJarImpl server(NamedMinecraftProvider.SingleJarImpl parentMinecraftProvide, MinecraftJarProcessorManager jarProcessorManager) {
			return new ProcessedNamedMinecraftProvider.SingleJarImpl(parentMinecraftProvide, jarProcessorManager, SingleJarEnvType.SERVER);
		}

		public static ProcessedNamedMinecraftProvider.SingleJarImpl client(NamedMinecraftProvider.SingleJarImpl parentMinecraftProvide, MinecraftJarProcessorManager jarProcessorManager) {
			return new ProcessedNamedMinecraftProvider.SingleJarImpl(parentMinecraftProvide, jarProcessorManager, SingleJarEnvType.CLIENT);
		}

		@Override
		public MinecraftJar getEnvOnlyJar() {
			return getProcessedJar(getParentMinecraftProvider().getEnvOnlyJar());
		}

		@Override
		public SingleJarEnvType env() {
			return env;
		}
	}
}
