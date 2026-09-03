/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2021 FabricMC
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

import java.io.File;
import java.util.Optional;
import java.util.Set;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;

import net.fabricmc.loom.configuration.providers.mappings.LayeredMappingsFactory;
import net.fabricmc.loom.util.Checksum;

public class DependencyInfo {
	final Project project;
	final Dependency dependency;
	final Configuration sourceConfiguration;

	private String resolvedVersion = null;

	public static DependencyInfo create(Project project, String configuration) {
		return create(project, project.getConfigurations().getByName(configuration));
	}

	public static DependencyInfo create(Project project, Configuration configuration) {
		return create(project, getSingleDependency(configuration), configuration);
	}

	public static DependencyInfo createForMappings(Project project, String configuration) {
		return createForMappings(project, project.getConfigurations().getByName(configuration));
	}

	public static DependencyInfo createForMappings(Project project, Configuration configuration) {
		final Dependency dependency = getSingleDependency(configuration);

		if (dependency instanceof FileCollectionDependency fileDependency) {
			final String layeredIdentity = getLayeredIdentity(dependency);
			final String identity = layeredIdentity != null ? layeredIdentity : getMappingFileIdentity(project, configuration);
			final String notation = layeredIdentity != null ? "loom:layered:" + identity : "loom:file-mappings:" + identity;
			return new TaskBackedFileDependencyInfo(project, fileDependency, configuration, identity, notation);
		}

		return new DependencyInfo(project, dependency, configuration);
	}

	public static DependencyInfo create(Project project, Dependency dependency, Configuration sourceConfiguration) {
		if (dependency instanceof FileCollectionDependency fileCollectionDependency) {
			final String layeredIdentity = getLayeredIdentity(dependency);

			if (layeredIdentity != null) {
				return new TaskBackedFileDependencyInfo(project, fileCollectionDependency, sourceConfiguration, layeredIdentity, "loom:layered:" + layeredIdentity);
			}

			return new FileDependencyInfo(project, fileCollectionDependency, sourceConfiguration);
		} else {
			return new DependencyInfo(project, dependency, sourceConfiguration);
		}
	}

	private static Dependency getSingleDependency(Configuration configuration) {
		final DependencySet dependencies = configuration.getDependencies();

		if (dependencies.isEmpty()) {
			throw new IllegalArgumentException(String.format("Configuration '%s' has no dependencies", configuration.getName()));
		}

		if (dependencies.size() != 1) {
			throw new IllegalArgumentException(String.format("Configuration '%s' must only have 1 dependency", configuration.getName()));
		}

		return dependencies.iterator().next();
	}

	private static String getLayeredIdentity(Dependency dependency) {
		final String reason = dependency.getReason();
		return reason != null && reason.startsWith(LayeredMappingsFactory.DEPENDENCY_REASON_PREFIX)
				? reason.substring(LayeredMappingsFactory.DEPENDENCY_REASON_PREFIX.length())
				: null;
	}

	private static String getMappingFileIdentity(Project project, Configuration configuration) {
		final String declaration = project.getProjectDir().getAbsolutePath() + '\0' + project.getPath() + '\0' + configuration.getName();
		return Checksum.of(declaration).sha256().hex();
	}

	DependencyInfo(Project project, Dependency dependency, Configuration sourceConfiguration) {
		this.project = project;
		this.dependency = dependency;
		this.sourceConfiguration = sourceConfiguration;
	}

	public Dependency getDependency() {
		return dependency;
	}

	public String getResolvedVersion() {
		if (resolvedVersion != null) {
			return resolvedVersion;
		}

		for (ResolvedDependency rd : sourceConfiguration.getResolvedConfiguration().getFirstLevelModuleDependencies()) {
			if (rd.getModuleGroup().equals(dependency.getGroup()) && rd.getModuleName().equals(dependency.getName())) {
				resolvedVersion = rd.getModuleVersion();
				return resolvedVersion;
			}
		}

		resolvedVersion = dependency.getVersion();
		return resolvedVersion;
	}

	public Configuration getSourceConfiguration() {
		return sourceConfiguration;
	}

	private boolean matches(ComponentIdentifier identifier) {
		if (identifier instanceof ModuleComponentIdentifier moduleComponentIdentifier) {
			return moduleComponentIdentifier.getGroup().equals(dependency.getGroup())
					&& moduleComponentIdentifier.getModule().equals(dependency.getName())
					&& moduleComponentIdentifier.getVersion().equals(dependency.getVersion());
		}

		return false;
	}

	public Set<File> resolve() {
		return sourceConfiguration.getIncoming()
				.artifactView(view -> view.componentFilter(this::matches))
				.getFiles()
				.getFiles();
	}

	public Optional<File> resolveFile() {
		Set<File> files = resolve();

		if (files.isEmpty()) {
			return Optional.empty();
		} else if (files.size() > 1) {
			StringBuilder builder = new StringBuilder(this.toString());
			builder.append(" resolves to more than one file:");

			for (File f : files) {
				builder.append("\n\t-").append(f.getAbsolutePath());
			}

			throw new RuntimeException(builder.toString());
		} else {
			return files.stream().findFirst();
		}
	}

	@Override
	public String toString() {
		return getDepString();
	}

	public String getDepString() {
		return dependency.getGroup() + ":" + dependency.getName() + ":" + dependency.getVersion();
	}

	public String getResolvedDepString() {
		return dependency.getGroup() + ":" + dependency.getName() + ":" + getResolvedVersion();
	}

	private static final class TaskBackedFileDependencyInfo extends DependencyInfo {
		private final FileCollectionDependency fileDependency;
		private final String identity;
		private final String notation;

		private TaskBackedFileDependencyInfo(Project project, FileCollectionDependency dependency, Configuration sourceConfiguration, String identity, String notation) {
			super(project, dependency, sourceConfiguration);
			this.fileDependency = dependency;
			this.identity = identity;
			this.notation = notation;
		}

		@Override
		public String getResolvedVersion() {
			return identity;
		}

		@Override
		public String getDepString() {
			return notation;
		}

		@Override
		public String getResolvedDepString() {
			return getDepString();
		}

		@Override
		public Set<File> resolve() {
			return fileDependency.getFiles().getFiles();
		}
	}
}
