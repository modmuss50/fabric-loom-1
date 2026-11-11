/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022 FabricMC
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

package net.fabricmc.loom.configuration.decompile;

import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.MappedMinecraftProvider;
import net.fabricmc.loom.task.GenerateSourcesTask;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.gradle.GradleUtils;

public abstract class DecompileConfiguration<T extends MappedMinecraftProvider> {
	static final String DEFAULT_DECOMPILER = "Vineflower";

	protected final Project project;
	protected final T minecraftProvider;
	protected final LoomGradleExtension extension;

	public DecompileConfiguration(Project project, T minecraftProvider) {
		this.project = project;
		this.minecraftProvider = minecraftProvider;
		this.extension = LoomGradleExtension.get(project);
	}

	public abstract String getTaskName(MinecraftJar.Type type);

	public abstract void afterEvaluation();

	public static boolean generateSourcesByDefault(Project project) {
		final String ciProperty = System.getProperty("fabric.loom.ci");
		final boolean ciBuild = ciProperty != null ? Boolean.parseBoolean(ciProperty) : System.getenv("CI") != null;
		return GradleUtils.getBooleanProperty(project, Constants.Properties.MINECRAFT_JAR_PROCESSING_GENERATE_SOURCES, !ciBuild);
	}

	public static String getDefaultTaskName(MinecraftJar.Type type) {
		return switch (type) {
		case MERGED, SERVER, CLIENT -> "genSourcesWith" + DEFAULT_DECOMPILER;
		case COMMON -> "genCommonSourcesWith" + DEFAULT_DECOMPILER;
		case CLIENT_ONLY -> "genClientOnlySourcesWith" + DEFAULT_DECOMPILER;
		};
	}

	protected void configureJar(GenerateSourcesTask task, MinecraftJar minecraftJar, String decompilerName) {
		task.getInputJarName().set(minecraftJar.getName());
		final var processingTasks = extension.getMinecraftJarProcessingTasks(minecraftJar.getType());

		if (processingTasks.isEmpty()) {
			task.getSourcesOutputJar().fileValue(GenerateSourcesTask.getJarFileWithSuffix("-sources.jar", minecraftJar.getPath()));
			return;
		}

		final var finalTask = processingTasks.getLast();
		final String outputSuffix = decompilerName.equals(DEFAULT_DECOMPILER) ? "" : "-" + decompilerName;
		task.useProcessedJar(finalTask.flatMap(processingTask -> processingTask.getOutputJar().getLocationOnly()), outputSuffix);
		task.dependsOn(finalTask);

		if (!generateSourcesByDefault(project)) {
			task.onlyIf(ignored -> {
				throw new IllegalStateException("Minecraft source generation is disabled for task-based jar processing. Set "
						+ Constants.Properties.MINECRAFT_JAR_PROCESSING_GENERATE_SOURCES + "=true to enable it.");
			});
		}
	}
}
