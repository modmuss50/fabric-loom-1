/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2022 FabricMC
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.TaskProvider;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.configuration.providers.mappings.IntermediaryMappingsProvider;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.NoRemapMappingConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.RemapMappingConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftSourceSets;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftTaskGraph;
import net.fabricmc.loom.configuration.providers.minecraft.SingleJarMinecraftProvider;
import net.fabricmc.loom.task.ApplyMinecraftAnnotationsTask;
import net.fabricmc.loom.task.ProcessMinecraftJarTask;
import net.fabricmc.loom.task.RemapMinecraftJarTask;
import net.fabricmc.loom.util.Constants;

public abstract class AbstractMappedMinecraftProvider<M extends MinecraftProvider> implements MappedMinecraftProvider.ProviderImpl {
	protected final M minecraftProvider;
	private final Project project;
	protected final LoomGradleExtension extension;

	public AbstractMappedMinecraftProvider(Project project, M minecraftProvider) {
		this.minecraftProvider = minecraftProvider;
		this.project = project;
		this.extension = LoomGradleExtension.get(project);
	}

	public abstract MappingsNamespace getTargetNamespace();

	/**
	 * @return A list of jars that should be remapped
	 */
	public abstract List<RemappedJars> getRemappedJars();

	/**
	 * @return A list of output jars that this provider generates
	 */
	public List<? extends OutputJar> getOutputJars() {
		return getRemappedJars();
	}

	// Returns a list of MinecraftJar.Type's that this provider exports to be used as a dependency
	public List<MinecraftJar.Type> getDependencyTypes() {
		return Collections.emptyList();
	}

	public List<MinecraftJar> provide(ProvideContext context) throws Exception {
		final List<RemappedJars> remappedJars = getRemappedJars();
		final List<MinecraftJar> minecraftJars = remappedJars.stream()
				.map(RemappedJars::outputJar)
				.toList();

		if (remappedJars.isEmpty()) {
			throw new IllegalStateException("No remapped jars provided");
		}

		registerRemapTasks(remappedJars);

		if (context.applyDependencies()) {
			final List<MinecraftJar.Type> dependencyTargets = getDependencyTypes();

			if (!dependencyTargets.isEmpty()) {
				final MinecraftTaskGraph taskGraph = MinecraftTaskGraph.get(getProject());
				MinecraftSourceSets.get(getProject()).applyDependencies(
						(configuration, type) -> getProject().getDependencies().add(configuration, taskGraph.files(getJar(type))),
						dependencyTargets
				);
			}
		}

		return minecraftJars;
	}

	private void registerRemapTasks(List<RemappedJars> remappedJars) {
		final MinecraftTaskGraph taskGraph = MinecraftTaskGraph.get(getProject());
		final List<Path> mappingsRemapClasspath = !extension.disableObfuscation() && getTargetNamespace() == MappingsNamespace.NAMED
				? extension.getMinecraftJars(MappingsNamespace.INTERMEDIARY)
				: List.of();

		for (RemappedJars remappedJar : remappedJars) {
			final String taskSuffix = taskSuffix(remappedJar.type()) + "To" + taskSuffix(getTargetNamespace().toString());
			final TaskProvider<? extends Task> remapTask;
			final MappingConfiguration mappingConfiguration = extension.getMappingConfigurationOrNull();

			if (extension.disableObfuscation() && mappingConfiguration instanceof NoRemapMappingConfiguration annotations) {
				remapTask = getProject().getTasks().register("annotateMinecraft" + taskSuffix, ApplyMinecraftAnnotationsTask.class, task -> {
					task.setDescription("Applies annotations to the %s Minecraft jar.".formatted(remappedJar.name()));
					task.setGroup(Constants.TaskGroup.FABRIC);
					task.getInputJar().fileValue(remappedJar.inputJar().toFile());
					task.getAnnotationsJar().fileValue(annotations.getInputJar().toFile());
					task.getOutputJar().fileValue(remappedJar.outputJarPath().toFile());
				});
			} else if (extension.disableObfuscation()) {
				remapTask = getProject().getTasks().register("copyMinecraft" + taskSuffix, ProcessMinecraftJarTask.class, task -> {
					task.setDescription("Copies the %s Minecraft jar to %s.".formatted(remappedJar.name(), getTargetNamespace()));
					task.setGroup(Constants.TaskGroup.FABRIC);
					task.getInputJar().fileValue(remappedJar.inputJar().toFile());
					task.getOutputJar().fileValue(remappedJar.outputJarPath().toFile());
				});
			} else {
				final RemapMappingConfiguration remapMappingConfiguration = extension.getMappingConfiguration();
				remapTask = getProject().getTasks().register("remapMinecraft" + taskSuffix, RemapMinecraftJarTask.class, task -> {
					task.setDescription("Remaps the %s Minecraft jar to %s.".formatted(remappedJar.name(), getTargetNamespace()));
					task.setGroup(Constants.TaskGroup.FABRIC);
					task.getInputJar().fileValue(remappedJar.inputJar().toFile());
					task.getMappingsFile().fileValue(remapMappingConfiguration.tinyMappings.toFile());
					task.getMappingsExtrasJar().fileValue(remapMappingConfiguration.tinyMappingsJar.toFile());
					task.getMinecraftMetadata().fileValue(minecraftProvider.getMinecraftMetadataPath().toFile());
					task.getRemapClasspath().from(List.of(remappedJar.remapClasspath()).stream().map(Path::toFile).toList());
					task.getMappingsRemapClasspath().from(mappingsRemapClasspath.stream().map(Path::toFile).toList());
					task.getSourceNamespace().set(remappedJar.sourceNamespace().toString());

					if (minecraftProvider instanceof SingleJarMinecraftProvider singleJarMinecraftProvider) {
						task.getLegacySourceNamespace().set(singleJarMinecraftProvider.getLegacyOfficialNamespace().toString());
					}

					task.getTargetNamespace().set(getTargetNamespace().toString());
					task.getApplyClientOnlyAnnotation().set(remappedJar.outputJar().getType() == MinecraftJar.Type.CLIENT_ONLY);
					task.getKnownIndyBsms().set(extension.getKnownIndyBsms());
					task.getOutputJar().fileValue(remappedJar.outputJarPath().toFile());
				});
			}

			taskGraph.dependsOn(remapTask, remappedJar.inputJar());

			if (mappingConfiguration instanceof NoRemapMappingConfiguration) {
				taskGraph.dependsOn(remapTask, mappingConfiguration.getInputJar());
			}

			if (!extension.disableObfuscation()) {
				final RemapMappingConfiguration remapMappingConfiguration = extension.getMappingConfiguration();
				taskGraph.dependsOn(
						remapTask,
						remapMappingConfiguration.tinyMappings,
						remapMappingConfiguration.tinyMappingsJar,
						minecraftProvider.getMinecraftMetadataPath()
				);
			}

			for (Path classpath : remappedJar.remapClasspath()) {
				if (taskGraph.hasProducer(classpath)) {
					taskGraph.dependsOn(remapTask, classpath);
				}
			}

			for (Path classpath : mappingsRemapClasspath) {
				if (taskGraph.hasProducer(classpath)) {
					taskGraph.dependsOn(remapTask, classpath);
				}
			}

			taskGraph.registerOutput(remappedJar.outputJarPath(), remapTask);

			if (requiresBackupJars()) {
				final Path backupPath = getBackupJarPath(remappedJar.outputJar());
				final TaskProvider<ProcessMinecraftJarTask> backupTask = getProject().getTasks().register("backupMinecraft" + taskSuffix, ProcessMinecraftJarTask.class, task -> {
					task.setDescription("Backs up the %s Minecraft jar.".formatted(remappedJar.name()));
					task.setGroup(Constants.TaskGroup.FABRIC);
					task.getInputJar().fileValue(remappedJar.outputJarPath().toFile());
					task.getOutputJar().fileValue(backupPath.toFile());
				});
				taskGraph.dependsOn(backupTask, remappedJar.outputJarPath());
				taskGraph.registerOutput(backupPath, backupTask);
			}
		}
	}

	private static String taskSuffix(Object value) {
		final String string = value.toString();
		return Character.toUpperCase(string.charAt(0)) + string.substring(1);
	}

	// Create two copies of the remapped jar, the backup jar is used as the input of genSources
	public static Path getBackupJarPath(MinecraftJar minecraftJar) {
		final Path outputJarPath = minecraftJar.getPath();
		return outputJarPath.resolveSibling(outputJarPath.getFileName() + ".backup");
	}

	protected boolean requiresBackupJars() {
		return true;
	}

	public record ProvideContext(boolean applyDependencies, boolean refreshOutputs, ConfigContext configContext) {
		ProvideContext withApplyDependencies(boolean applyDependencies) {
			return new ProvideContext(applyDependencies, refreshOutputs(), configContext());
		}
	}

	@Override
	public Path getJar(MinecraftJar.Type type) {
		return extension.getFiles().getProjectPersistentCache().toPath()
				.resolve("minecraft")
				.resolve("mapped")
				.resolve(getVersion())
				.resolve(getName(type))
				.resolve("minecraft-%s.jar".formatted(type));
	}

	protected String getName(MinecraftJar.Type type) {
		var sj = new StringJoiner("-");
		sj.add("minecraft");
		sj.add(type.toString());

		if (!extension.disableObfuscation()) {
			// Include the intermediate mapping name if it's not the default intermediary
			final String intermediateName = extension.getIntermediateMappingsProvider().getName();

			if (!intermediateName.equals(IntermediaryMappingsProvider.NAME)) {
				sj.add(intermediateName);
			}
		} else {
			sj.add("deobf");
		}

		if (getTargetNamespace() != MappingsNamespace.NAMED) {
			sj.add(getTargetNamespace().name());
		}

		return sj.toString().toLowerCase(Locale.ROOT);
	}

	protected String getVersion() {
		if (extension.disableObfuscation()) {
			MappingConfiguration mappingConfiguration = extension.getMappingConfigurationOrNull();

			if (mappingConfiguration == null) {
				return extension.getMinecraftProvider().minecraftVersion();
			}

			return "%s-%s".formatted(extension.getMinecraftProvider().minecraftVersion(), mappingConfiguration.mappingsIdentifier());
		}

		return "%s-%s".formatted(extension.getMinecraftProvider().minecraftVersion(), extension.getMappingConfiguration().mappingsIdentifier());
	}

	public Project getProject() {
		return project;
	}

	public M getMinecraftProvider() {
		return minecraftProvider;
	}

	public sealed interface OutputJar permits RemappedJars, SimpleOutputJar {
		MinecraftJar outputJar();

		default MinecraftJar.Type type() {
			return outputJar().getType();
		}
	}

	public record RemappedJars(Path inputJar, MinecraftJar outputJar, MappingsNamespace sourceNamespace, Path... remapClasspath) implements OutputJar {
		public Path outputJarPath() {
			return outputJar().getPath();
		}

		public String name() {
			return outputJar().getName();
		}
	}

	public record SimpleOutputJar(MinecraftJar outputJar) implements OutputJar {
	}
}
