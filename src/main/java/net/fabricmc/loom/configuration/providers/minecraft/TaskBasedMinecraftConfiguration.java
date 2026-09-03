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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gradle.api.Project;
import org.gradle.plugins.ide.eclipse.model.Classpath;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.plugins.ide.eclipse.model.Library;
import org.jetbrains.annotations.ApiStatus;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.util.Checksum;

@ApiStatus.Internal
public final class TaskBasedMinecraftConfiguration {
	public static final String PROCESS_MINECRAFT_JARS_TASK = "processMinecraftJars";
	public static final String PROCESS_MINECRAFT_MERGED_JAR_TASK = "processMinecraftMergedJar";
	public static final String PROCESS_MINECRAFT_SERVER_JAR_TASK = "processMinecraftServerJar";
	public static final String PROCESS_MINECRAFT_CLIENT_JAR_TASK = "processMinecraftClientJar";
	public static final String PROCESS_MINECRAFT_COMMON_JAR_TASK = "processMinecraftCommonJar";
	public static final String PROCESS_MINECRAFT_CLIENT_ONLY_JAR_TASK = "processMinecraftClientOnlyJar";
	private static final Set<String> IDE_TASKS = Set.of("eclipse", "eclipseClasspath", "genEclipseRuns", "vscode");

	private TaskBasedMinecraftConfiguration() {
	}

	public static String getProcessTaskName(MinecraftJar.Type type) {
		return switch (type) {
		case MERGED -> PROCESS_MINECRAFT_MERGED_JAR_TASK;
		case SERVER -> PROCESS_MINECRAFT_SERVER_JAR_TASK;
		case CLIENT -> PROCESS_MINECRAFT_CLIENT_JAR_TASK;
		case COMMON -> PROCESS_MINECRAFT_COMMON_JAR_TASK;
		case CLIENT_ONLY -> PROCESS_MINECRAFT_CLIENT_ONLY_JAR_TASK;
		};
	}

	public static MinecraftJar getOutputJar(Project project, MinecraftJar inputJar) {
		return inputJar.forPath(getOutputPath(project, inputJar));
	}

	public static Path getOutputPath(Project project, MinecraftJar inputJar) {
		final String projectHash = Checksum.of(project).sha1().hex();

		return LoomGradleExtension.get(project).getFiles().getUserCache().toPath()
				.resolve("taskBasedMinecraft")
				.resolve(projectHash)
				.resolve(getInputJarIdentity(inputJar))
				.resolve(inputJar.getType().toString())
				.resolve("minecraft-%s.jar".formatted(inputJar.getType()));
	}

	public static Path getLineMappedPath(Project project, MinecraftJar inputJar) {
		return LoomGradleExtension.get(project).getFiles().getProjectPersistentCache().toPath()
				.resolve("taskBasedMinecraft")
				.resolve("generatedSources")
				.resolve(getInputJarIdentity(inputJar))
				.resolve(inputJar.getType().toString())
				.resolve("minecraft-%s-line-mapped.jar".formatted(inputJar.getType()));
	}

	public static Path getLineMapPath(Project project, MinecraftJar inputJar) {
		final Path lineMappedJar = getLineMappedPath(project, inputJar);
		return lineMappedJar.resolveSibling(lineMappedJar.getFileName() + ".linemap.txt");
	}

	public static Path getLineMappedInputHashPath(Project project, MinecraftJar inputJar) {
		final Path lineMappedJar = getLineMappedPath(project, inputJar);
		return lineMappedJar.resolveSibling(lineMappedJar.getFileName() + ".input.sha256");
	}

	public static Path getSourcesPath(Project project, MinecraftJar inputJar) {
		return getSourcesPath(getOutputPath(project, inputJar));
	}

	public static Path getSourcesWorkPath(Project project, MinecraftJar inputJar) {
		final Path lineMappedJar = getLineMappedPath(project, inputJar);
		return lineMappedJar.resolveSibling("minecraft-%s-sources.jar".formatted(inputJar.getType()));
	}

	public static Path getSourcesPath(Path outputJar) {
		final String fileName = outputJar.getFileName().toString();

		if (!fileName.endsWith(".jar")) {
			throw new IllegalArgumentException("Expected a jar path: " + outputJar);
		}

		return outputJar.resolveSibling(fileName.substring(0, fileName.length() - 4) + "-sources.jar");
	}

	public static void configureIde(Project project) {
		project.getTasks().matching(task -> IDE_TASKS.contains(task.getName())).configureEach(task -> task.dependsOn(PROCESS_MINECRAFT_JARS_TASK));
		project.getPluginManager().withPlugin("eclipse", plugin -> {
			final EclipseModel eclipseModel = project.getExtensions().getByType(EclipseModel.class);
			eclipseModel.synchronizationTasks(PROCESS_MINECRAFT_JARS_TASK);
		});
	}

	public static void configureEclipseSources(Project project, List<MinecraftJar> minecraftJars) {
		final Map<Path, Path> sourcesByOutput = new HashMap<>();

		minecraftJars.forEach(minecraftJar -> {
			final Path output = normalize(getOutputPath(project, minecraftJar));
			sourcesByOutput.put(output, getSourcesPath(output));
		});

		project.getPluginManager().withPlugin("eclipse", plugin -> {
			final EclipseModel eclipseModel = project.getExtensions().getByType(EclipseModel.class);
			eclipseModel.getClasspath().getFile().whenMerged(model -> attachSources(sourcesByOutput, (Classpath) model));
		});
	}

	private static void attachSources(Map<Path, Path> sourcesByOutput, Classpath classpath) {
		for (var entry : classpath.getEntries()) {
			if (entry instanceof Library library) {
				final Path sources = sourcesByOutput.get(normalize(library.getLibrary().getFile().toPath()));

				if (sources != null && Files.isRegularFile(sources)) {
					library.setSourcePath(classpath.fileReference(sources.toFile()));
				}
			}
		}
	}

	private static Path normalize(Path path) {
		return path.toAbsolutePath().normalize();
	}

	private static String getInputJarIdentity(MinecraftJar inputJar) {
		// Mapped input paths include the Minecraft and mappings identities, plus the processor hash when applicable.
		return Checksum.of(inputJar.getPath().toAbsolutePath().normalize().toString()).sha1().hex();
	}
}
