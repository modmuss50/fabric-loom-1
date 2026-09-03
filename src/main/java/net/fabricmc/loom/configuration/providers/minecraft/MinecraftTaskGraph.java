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

package net.fabricmc.loom.configuration.providers.minecraft;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.TaskProvider;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class MinecraftTaskGraph {
	private static final String EXTENSION_NAME = "loomMinecraftTaskGraph";

	private final Project project;
	private final Map<Path, TaskProvider<? extends Task>> producers = new LinkedHashMap<>();

	private MinecraftTaskGraph(Project project) {
		this.project = project;
	}

	public static MinecraftTaskGraph create(Project project) {
		final MinecraftTaskGraph taskGraph = new MinecraftTaskGraph(project);
		project.getExtensions().add(EXTENSION_NAME, taskGraph);
		return taskGraph;
	}

	public static MinecraftTaskGraph get(Project project) {
		return (MinecraftTaskGraph) project.getExtensions().getByName(EXTENSION_NAME);
	}

	public void registerOutput(Path output, TaskProvider<? extends Task> producer) {
		final Path normalizedOutput = normalize(output);
		final TaskProvider<? extends Task> previous = producers.putIfAbsent(normalizedOutput, producer);

		if (previous != null && previous != producer) {
			throw new IllegalStateException("Minecraft output already has a producer: " + normalizedOutput);
		}
	}

	public TaskProvider<? extends Task> getProducer(Path output) {
		final Path normalizedOutput = normalize(output);
		final TaskProvider<? extends Task> producer = producers.get(normalizedOutput);

		if (producer == null) {
			throw new IllegalStateException("Minecraft output does not have a producer: " + normalizedOutput);
		}

		return producer;
	}

	public boolean hasProducer(Path output) {
		return producers.containsKey(normalize(output));
	}

	public void dependsOn(TaskProvider<? extends Task> task, Path... inputs) {
		for (Path input : inputs) {
			task.configure(configuredTask -> configuredTask.dependsOn(getProducer(input)));
		}
	}

	public ConfigurableFileCollection files(Path output) {
		return project.files(output.toFile()).builtBy(getProducer(output));
	}

	private static Path normalize(Path path) {
		return path.toAbsolutePath().normalize();
	}
}
