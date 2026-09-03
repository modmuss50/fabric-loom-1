/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2023 FabricMC
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

import static net.fabricmc.loom.util.Constants.Configurations;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.AbstractCopyTask;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.api.tasks.testing.Test;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.build.mixin.GroovyApInvoker;
import net.fabricmc.loom.build.mixin.JavaApInvoker;
import net.fabricmc.loom.build.mixin.KaptApInvoker;
import net.fabricmc.loom.build.mixin.ScalaApInvoker;
import net.fabricmc.loom.configuration.processors.MinecraftJarProcessorManager;
import net.fabricmc.loom.configuration.processors.speccontext.DebofConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.LayeredMappingsFactory;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.NoRemapMappingConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.RemapMappingConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftMetadataProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftSourceSets;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftTaskGraph;
import net.fabricmc.loom.configuration.providers.minecraft.TaskBasedMinecraftConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.AbstractMappedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.IntermediaryMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.NamedMinecraftProvider;
import net.fabricmc.loom.extension.MixinExtension;
import net.fabricmc.loom.task.ProcessMinecraftJarTask;
import net.fabricmc.loom.task.service.ClasspathGroupService;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ExceptionUtil;
import net.fabricmc.loom.util.gradle.GradleUtils;
import net.fabricmc.loom.util.gradle.SourceSetHelper;
import net.fabricmc.loom.util.gradle.daemon.DaemonUtils;
import net.fabricmc.loom.util.service.ScopedServiceFactory;
import net.fabricmc.loom.util.service.ServiceFactory;

public abstract class CompileConfiguration implements Runnable {
	@Inject
	protected abstract Project getProject();

	@Inject
	protected abstract TaskContainer getTasks();

	@Override
	public void run() {
		LoomGradleExtension extension = LoomGradleExtension.get(getProject());
		MinecraftTaskGraph.create(getProject());
		InstallerDataTaskConfiguration.register(getProject());
		getTasks().register(TaskBasedMinecraftConfiguration.PROCESS_MINECRAFT_JARS_TASK, task -> {
			task.setDescription("Process Minecraft jars lazily.");
			task.setGroup(Constants.TaskGroup.FABRIC);
		});
		TaskBasedMinecraftConfiguration.configureIde(getProject());

		getTasks().named(JavaPlugin.JAVADOC_TASK_NAME, Javadoc.class).configure(javadoc -> {
			final SourceSet main = SourceSetHelper.getMainSourceSet(getProject());
			javadoc.setClasspath(main.getOutput().plus(main.getCompileClasspath()));
		});

		afterEvaluationWithService((serviceFactory) -> {
			final ConfigContext configContext = new ConfigContextImpl(getProject(), serviceFactory, extension);

			if (extension.disableObfuscation()) {
				DebofConfiguration.create(getProject());
			}

			MinecraftSourceSets.get(getProject()).afterEvaluate(getProject());

			try {
				final MinecraftMetadataProvider metadataProvider = MinecraftMetadataProvider.create(configContext);
				extension.setMetadataProvider(metadataProvider);
				final MinecraftProvider minecraftProvider = createMinecraftProvider(configContext, metadataProvider);
				final MappingConfigurationSetup mappingConfigurationSetup = provideMinecraft(configContext, minecraftProvider);

				if (mappingConfigurationSetup != null) {
					extension.setMappingConfiguration(mappingConfigurationSetup.mappingConfiguration());
					mappingConfigurationSetup.mappingConfiguration().applyToProject(getProject(), mappingConfigurationSetup.dependencyInfo());
				}

				createMappedMinecraftProviders(configContext);
				final MinecraftJarProcessorManager minecraftJarProcessorManager = MinecraftJarProcessorManager.create(getProject());
				createProcessedNamedMinecraftProvider(configContext, minecraftJarProcessorManager);

				provideMappedMinecraft(configContext);

				var dependencyManager = new LoomDependencyManager(getProject(), serviceFactory, extension);
				dependencyManager.handleDependencies();
			} catch (Exception e) {
				ExceptionUtil.processException(e, DaemonUtils.Context.fromProject(getProject()));
				throw ExceptionUtil.createDescriptiveWrapper(RuntimeException::new, "Failed to setup Minecraft", e);
			}

			MixinExtension mixin = LoomGradleExtension.get(getProject()).getMixin();

			if (mixin.getUseLegacyMixinAp().get()) {
				setupMixinAp(mixin);
			}

			configureDecompileTasks(configContext);
			configureTestTask();
		});

		finalizedBy("eclipse", "genEclipseRuns");

		if (!extension.disableObfuscation()) {
			// Add the "dev" jar to the "namedElements" configuration
			getProject().artifacts(artifactHandler -> artifactHandler.add(Configurations.NAMED_ELEMENTS, getTasks().named("jar")));
		}

		// Ensure that the encoding is set to UTF-8, no matter what the system default is
		// this fixes some edge cases with special characters not displaying correctly
		// see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
		getTasks().withType(AbstractCopyTask.class).configureEach(abstractCopyTask -> abstractCopyTask.setFilteringCharset(StandardCharsets.UTF_8.name()));
		getTasks().withType(JavaCompile.class).configureEach(javaCompile -> javaCompile.getOptions().setEncoding(StandardCharsets.UTF_8.name()));

		if (getProject().getPluginManager().hasPlugin("org.jetbrains.kotlin.kapt")) {
			// If loom is applied after kapt, then kapt will use the AP arguments too early for loom to pass the arguments we need for mixin.
			throw new IllegalArgumentException("fabric-loom must be applied BEFORE kapt in the plugins { } block.");
		}
	}

	private MinecraftProvider createMinecraftProvider(ConfigContext configContext, MinecraftMetadataProvider metadataProvider) {
		final LoomGradleExtension extension = configContext.extension();
		final MinecraftProvider minecraftProvider = extension.getMinecraftJarConfiguration().get().createMinecraftProvider(metadataProvider, configContext);
		extension.setMinecraftProvider(minecraftProvider);
		return minecraftProvider;
	}

	@Nullable
	private MappingConfigurationSetup provideMinecraft(ConfigContext configContext, MinecraftProvider minecraftProvider) throws Exception {
		final Project project = configContext.project();
		final LoomGradleExtension extension = configContext.extension();

		// Provide the vanilla mc jars
		minecraftProvider.provide();

		if (!extension.disableObfuscation()) {
			// Realise the dependencies without actually resolving them, this forces any lazy providers to be created, populating the layered mapping factories.
			project.getConfigurations().getByName(Configurations.MAPPINGS).getDependencies().toArray();

			// Created any layered mapping files.
			LayeredMappingsFactory.afterEvaluate(configContext);

			// Describe the configured mapping dependency without resolving task-backed files.
			final DependencyInfo mappingsDep = DependencyInfo.createForMappings(getProject(), Configurations.MAPPINGS);
			final MappingConfiguration mappingConfiguration = RemapMappingConfiguration.create(getProject(), configContext.serviceFactory(), mappingsDep, minecraftProvider);
			return new MappingConfigurationSetup(mappingConfiguration, mappingsDep);
		} else {
			var annotations = project.getConfigurations().getByName(Configurations.ANNOTATIONS);

			if (!annotations.getDependencies().isEmpty()) {
				final DependencyInfo annotationsDep = DependencyInfo.createForMappings(getProject(), annotations);
				final MappingConfiguration mappingConfiguration = NoRemapMappingConfiguration.create(getProject(), annotationsDep, minecraftProvider);
				return new MappingConfigurationSetup(mappingConfiguration, annotationsDep);
			}
		}

		return null;
	}

	private record MappingConfigurationSetup(MappingConfiguration mappingConfiguration, DependencyInfo dependencyInfo) {
	}

	private void createMappedMinecraftProviders(ConfigContext configContext) {
		final Project project = configContext.project();
		final LoomGradleExtension extension = configContext.extension();
		final var jarConfiguration = extension.getMinecraftJarConfiguration().get();
		IntermediaryMinecraftProvider<?> intermediaryMinecraftProvider = extension.disableObfuscation() ? null : jarConfiguration.createIntermediaryMinecraftProvider(project);
		NamedMinecraftProvider<?> namedMinecraftProvider = jarConfiguration.createNamedMinecraftProvider(project);

		if (intermediaryMinecraftProvider != null) {
			extension.setIntermediaryMinecraftProvider(intermediaryMinecraftProvider);
		}

		extension.setNamedMinecraftProvider(namedMinecraftProvider);
	}

	private void createProcessedNamedMinecraftProvider(ConfigContext configContext, @Nullable MinecraftJarProcessorManager minecraftJarProcessorManager) {
		if (minecraftJarProcessorManager == null) {
			return;
		}

		final LoomGradleExtension extension = configContext.extension();
		final var jarConfiguration = extension.getMinecraftJarConfiguration().get();
		final NamedMinecraftProvider<?> namedMinecraftProvider = jarConfiguration.createProcessedNamedMinecraftProvider(extension.getNamedMinecraftProvider(), minecraftJarProcessorManager);
		extension.setNamedMinecraftProvider(namedMinecraftProvider);
	}

	private void provideMappedMinecraft(ConfigContext configContext) throws Exception {
		final LoomGradleExtension extension = configContext.extension();
		final IntermediaryMinecraftProvider<?> intermediaryMinecraftProvider = extension.disableObfuscation() ? null : extension.getIntermediaryMinecraftProvider();
		final NamedMinecraftProvider<?> namedMinecraftProvider = extension.getNamedMinecraftProvider();
		final var provideContext = new AbstractMappedMinecraftProvider.ProvideContext(true, extension.refreshDeps(), configContext);

		if (intermediaryMinecraftProvider != null) {
			intermediaryMinecraftProvider.provide(provideContext);
		}

		final List<MinecraftJar> minecraftJars = namedMinecraftProvider.provide(
				new AbstractMappedMinecraftProvider.ProvideContext(false, extension.refreshDeps(), configContext)
		);
		applyTaskBasedMinecraftDependencies(namedMinecraftProvider, minecraftJars);
	}

	private void applyTaskBasedMinecraftDependencies(NamedMinecraftProvider<?> namedMinecraftProvider, List<MinecraftJar> minecraftJars) {
		final TaskProvider<Task> aggregateTask = getTasks().named(TaskBasedMinecraftConfiguration.PROCESS_MINECRAFT_JARS_TASK);
		final Map<MinecraftJar.Type, TaskProvider<ProcessMinecraftJarTask>> processTasks = new EnumMap<>(MinecraftJar.Type.class);
		final MinecraftTaskGraph taskGraph = MinecraftTaskGraph.get(getProject());

		for (MinecraftJar minecraftJar : minecraftJars) {
			final MinecraftJar outputJar = TaskBasedMinecraftConfiguration.getOutputJar(getProject(), minecraftJar);
			final Path lineMappedJar = TaskBasedMinecraftConfiguration.getLineMappedPath(getProject(), minecraftJar);
			final Path lineMappedInputHash = TaskBasedMinecraftConfiguration.getLineMappedInputHashPath(getProject(), minecraftJar);
			final Path sourcesWorkJar = TaskBasedMinecraftConfiguration.getSourcesWorkPath(getProject(), minecraftJar);
			final Path sourcesOutputJar = TaskBasedMinecraftConfiguration.getSourcesPath(getProject(), minecraftJar);
			final String taskName = TaskBasedMinecraftConfiguration.getProcessTaskName(minecraftJar.getType());
			final TaskProvider<ProcessMinecraftJarTask> processTask = getTasks().register(taskName, ProcessMinecraftJarTask.class, task -> {
				task.setDescription("Process the %s Minecraft jar.".formatted(minecraftJar.getType()));
				task.setGroup(Constants.TaskGroup.FABRIC);
				task.getInputJar().fileValue(minecraftJar.toFile());
				task.getLineMappedInputCandidates().from(lineMappedJar.toFile());
				task.getLineMappedInputHashes().from(lineMappedInputHash.toFile());
				task.getSourcesInputCandidates().from(sourcesWorkJar.toFile());
				task.getOutputJar().fileValue(outputJar.toFile());
				task.getOutputSourcesJars().from(sourcesOutputJar.toFile());
			});

			aggregateTask.configure(task -> task.dependsOn(processTask));
			taskGraph.registerOutput(outputJar.getPath(), processTask);

			if (taskGraph.hasProducer(minecraftJar.getPath())) {
				taskGraph.dependsOn(processTask, minecraftJar.getPath());
			}

			processTasks.put(minecraftJar.getType(), processTask);
		}

		MinecraftSourceSets.get(getProject()).applyDependencies((configuration, type) -> {
			final TaskProvider<ProcessMinecraftJarTask> processTask = processTasks.get(type);

			if (processTask == null) {
				throw new IllegalStateException("Missing process task for Minecraft jar type: " + type);
			}

			final var outputFiles = getProject().files(processTask.flatMap(ProcessMinecraftJarTask::getOutputJar));
			outputFiles.builtBy(processTask);
			getProject().getDependencies().add(configuration, outputFiles);
		}, namedMinecraftProvider.getDependencyTypes());

		TaskBasedMinecraftConfiguration.configureEclipseSources(getProject(), minecraftJars);
	}

	private void setupMixinAp(MixinExtension mixin) {
		mixin.init();

		// Disable some things used by log4j via the mixin AP that prevent it from being garbage collected
		System.setProperty("log4j2.disable.jmx", "true");
		System.setProperty("log4j.shutdownHookEnabled", "false");
		System.setProperty("log4j.skipJansi", "true");

		getProject().getLogger().info("Configuring compiler arguments for Java");

		new JavaApInvoker(getProject()).configureMixin();

		if (getProject().getPluginManager().hasPlugin("scala")) {
			getProject().getLogger().info("Configuring compiler arguments for Scala");
			new ScalaApInvoker(getProject()).configureMixin();
		}

		if (getProject().getPluginManager().hasPlugin("org.jetbrains.kotlin.kapt")) {
			getProject().getLogger().info("Configuring compiler arguments for Kapt plugin");
			new KaptApInvoker(getProject()).configureMixin();
		}

		if (getProject().getPluginManager().hasPlugin("groovy")) {
			getProject().getLogger().info("Configuring compiler arguments for Groovy");
			new GroovyApInvoker(getProject()).configureMixin();
		}
	}

	private void configureDecompileTasks(ConfigContext configContext) {
		final LoomGradleExtension extension = configContext.extension();

		extension.getMinecraftJarConfiguration().get()
				.createDecompileConfiguration(getProject())
				.afterEvaluation();
	}

	private void configureTestTask() {
		final LoomGradleExtension extension = LoomGradleExtension.get(getProject());

		if (extension.getMods().isEmpty()) {
			return;
		}

		getProject().getTasks().named(JavaPlugin.TEST_TASK_NAME, Test.class, test -> {
			Provider<ClasspathGroupService.Options> optionsProvider = ClasspathGroupService.create(getProject());
			test.getInputs().property("LoomClassPathGroups", optionsProvider);
			test.getInputs().files(optionsProvider.map((ClasspathGroupService.Options::getExternalClasspathGroups)));
			test.getInputs().files(optionsProvider.map((ClasspathGroupService.Options::getGeneratedClasspathGroups)));

			test.doFirst(new Action<Task>() {
				@Override
				public void execute(Task task) {
					try (ScopedServiceFactory serviceFactory = new ScopedServiceFactory()) {
						var options = (ClasspathGroupService.Options) task.getInputs().getProperties().get("LoomClassPathGroups");
						ClasspathGroupService classpathGroupService = serviceFactory.get(options);

						if (classpathGroupService.hasGroups()) {
							test.systemProperty("fabric.classPathGroups", classpathGroupService.getClasspathGroupsPropertyValue());
						}
					} catch (IOException e) {
						throw new UncheckedIOException("Failed to get classpath groups", e);
					}
				}
			});
		});
	}

	private void finalizedBy(String a, String b) {
		getTasks().named(a).configure(task -> task.finalizedBy(getTasks().named(b)));
	}

	private void afterEvaluationWithService(Consumer<ServiceFactory> consumer) {
		GradleUtils.afterSuccessfulEvaluation(getProject(), () -> {
			try (var serviceFactory = new ScopedServiceFactory()) {
				consumer.accept(serviceFactory);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}
}
