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

package net.fabricmc.loom.configuration.ide;

import java.io.File;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.gradle.api.Project;
import org.gradle.api.specs.Spec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.RunConfiguration;
import net.fabricmc.loom.task.DownloadMinecraftLibrariesTask;
import net.fabricmc.loom.util.Constants;

public class RuntimeLibraries {
	private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeLibraries.class);

	public static List<String> getExcludedLibraryPaths(Project project, RunConfiguration runConfiguration) {
		if (!isServer(runConfiguration)) {
			return Collections.emptyList();
		}

		final LibraryPaths paths = getLibraryPaths(project);

		// This is called from the lazily evaluated ideaSyncTask input. Resolving the configuration here
		// happens after the library task has run, rather than reading task outputs during configuration.
		return project.getConfigurations().getByName(Constants.Configurations.MINECRAFT_CLIENT_RUNTIME_LIBRARIES).getFiles().stream()
				.map(file -> file.toPath().toAbsolutePath().normalize())
				.filter(paths::isClientOnly)
				.map(Path::toString)
				.sorted()
				.toList();
	}

	public static Spec<File> createLibraryFilter(Project project, RunConfiguration runConfiguration, String configName) {
		final LibraryPaths paths = getLibraryPaths(project);
		return new ServerLibraryFilter(
				isServer(runConfiguration),
				paths.clientRuntime().toString(),
				paths.clientRuntimeNatives().toString(),
				configName
		);
	}

	private static boolean isServer(RunConfiguration runConfiguration) {
		return runConfiguration.getRuntimeEnvironment().get().toLowerCase(Locale.ROOT).equals("server");
	}

	private static LibraryPaths getLibraryPaths(Project project) {
		final Path output = LoomGradleExtension.get(project).getMinecraftProvider().path("libraries").toAbsolutePath().normalize();
		return new LibraryPaths(
				output.resolve(DownloadMinecraftLibrariesTask.CLIENT_RUNTIME_DIRECTORY),
				output.resolve(DownloadMinecraftLibrariesTask.CLIENT_RUNTIME_NATIVES_DIRECTORY)
		);
	}

	private record LibraryPaths(Path clientRuntime, Path clientRuntimeNatives) {
		private boolean isClientOnly(Path path) {
			return path.startsWith(clientRuntime) || path.startsWith(clientRuntimeNatives);
		}
	}

	private static final class ServerLibraryFilter implements Spec<File>, Serializable {
		@Serial
		private static final long serialVersionUID = 1L;

		private final boolean server;
		private final String clientRuntime;
		private final String clientRuntimeNatives;
		private final String configName;

		private ServerLibraryFilter(boolean server, String clientRuntime, String clientRuntimeNatives, String configName) {
			this.server = server;
			this.clientRuntime = clientRuntime;
			this.clientRuntimeNatives = clientRuntimeNatives;
			this.configName = configName;
		}

		@Override
		public boolean isSatisfiedBy(File element) {
			if (!server) {
				return true;
			}

			final Path elementPath = element.toPath().toAbsolutePath().normalize();
			final LibraryPaths paths = new LibraryPaths(Path.of(clientRuntime), Path.of(clientRuntimeNatives));

			if (!paths.isClientOnly(elementPath)) {
				return true;
			}

			LOGGER.debug("Excluding library {} from {} run config", element.getName(), configName);
			return false;
		}
	}
}
