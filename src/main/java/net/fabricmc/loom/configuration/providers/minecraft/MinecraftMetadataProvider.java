/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2023 FabricMC
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

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencySet;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.util.Constants;

public final class MinecraftMetadataProvider {
	private final Options options;

	private MinecraftVersionMeta versionMeta;

	private MinecraftMetadataProvider(Options options) {
		this.options = options;
	}

	public static MinecraftMetadataProvider create(ConfigContext configContext) {
		final String minecraftVersion = resolveMinecraftVersion(configContext.project());

		return new MinecraftMetadataProvider(MinecraftMetadataProvider.Options.create(minecraftVersion, configContext.project()));
	}

	private static String resolveMinecraftVersion(Project project) {
		final DependencySet dependencies = project.getConfigurations().getByName(Constants.Configurations.MINECRAFT).getDependencies();

		if (dependencies.size() != 1) {
			throw new IllegalArgumentException("Configuration '%s' must have exactly one dependency".formatted(Constants.Configurations.MINECRAFT));
		}

		final Dependency dependency = dependencies.iterator().next();
		return Objects.requireNonNull(dependency.getVersion(), "Task-backed Minecraft requires a dependency with a declared version");
	}

	public String getMinecraftVersion() {
		return options.minecraftVersion();
	}

	public MinecraftVersionMeta getVersionMeta() {
		if (versionMeta == null) {
			try {
				versionMeta = readVersionMeta();
			} catch (IOException e) {
				throw new UncheckedIOException(e.getMessage(), e);
			}
		}

		return versionMeta;
	}

	public boolean isUnobfuscated() {
		return getVersionMeta().isVersionOrNewer(Constants.RELEASE_TIME_1_21_11_UNOBFUSCATED_SNAPSHOTS)
				&& !getVersionMeta().downloads().containsKey("client_mappings");
	}

	private MinecraftVersionMeta readVersionMeta() throws IOException {
		final Path metadata = options.workingDir().resolve("minecraft-metadata.json");

		if (Files.notExists(metadata)) {
			throw new IOException("Minecraft metadata has not been produced yet: " + metadata);
		}

		try (Reader reader = Files.newBufferedReader(metadata, StandardCharsets.UTF_8)) {
			final MinecraftVersionMeta value = LoomGradlePlugin.GSON.fromJson(reader, MinecraftVersionMeta.class);

			if (value == null) {
				throw new IOException("Minecraft metadata is empty: " + metadata);
			}

			return value;
		}
	}

	public record Options(String minecraftVersion, Path workingDir) {
		public static Options create(String minecraftVersion, Project project) {
			final Path workingDir = MinecraftProvider.minecraftWorkingDirectory(project, minecraftVersion).toPath();
			return new Options(minecraftVersion, workingDir);
		}
	}
}
