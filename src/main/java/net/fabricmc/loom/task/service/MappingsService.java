/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
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

package net.fabricmc.loom.task.service;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.gradle.api.Project;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.SourceSet;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.build.mixin.AnnotationProcessorInvoker;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.gradle.GradleUtils;
import net.fabricmc.loom.util.gradle.SourceSetHelper;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.IMappingProvider;

public final class MappingsService implements AutoCloseable {
	public interface Mappings {
		RegularFileProperty mappingsFile();
		Property<String> getFrom();
		Property<String> getTo();
		Property<Boolean> getRemapLocals();

		private static Mappings createMappings(Project project, String from, String to, Path mappingsFile) {
			Mappings mappings = project.getObjects().newInstance(Mappings.class);
			mappings.mappingsFile().set(mappingsFile.toFile());
			mappings.getFrom().set(from);
			mappings.getTo().set(to);
			mappings.getRemapLocals().set(false);
			return mappings;
		}

		static Mappings getDefaultMappings(Project project, String from, String to) {
			final MappingConfiguration mappingConfiguration = LoomGradleExtension.get(project).getMappingConfiguration();
			return createMappings(project, from, to, mappingConfiguration.tinyMappings);
		}

		// Returns a list of mixin mappings from other projects that are using the same mapping id.
		static List<Mappings> getMixinMappings(Project project, String from, String to) {
			LoomGradleExtension extension = LoomGradleExtension.get(project);
			String mappingId = extension.getMappingConfiguration().mappingsIdentifier;

			List<Mappings> params = new ArrayList<>();

			GradleUtils.allLoomProjects(project.getGradle(), otherProject -> {
				if (!mappingId.equals(LoomGradleExtension.get(otherProject).getMappingConfiguration().mappingsIdentifier)) {
					// Only find mixin mappings that are from other projects with the same mapping id.
					return;
				}

				for (SourceSet sourceSet : SourceSetHelper.getSourceSets(otherProject)) {
					final File mixinMappings = AnnotationProcessorInvoker.getMixinMappingsForSourceSet(otherProject, sourceSet);

					if (!mixinMappings.exists()) {
						continue;
					}

					params.add(Mappings.createMappings(otherProject, from, to, mixinMappings.toPath()));
				}
			});

			return params;
		}
	}

	private final Mappings options;

	public MappingsService(Mappings options) {
		this.options = options;
	}

	private IMappingProvider mappingProvider = null;
	private MemoryMappingTree memoryMappingTree = null;

	public synchronized IMappingProvider getMappingsProvider() {
		if (mappingProvider == null) {
			try {
				mappingProvider = TinyRemapperHelper.create(
						options.mappingsFile().getAsFile().get().toPath(),
						options.getFrom().get(),
						options.getTo().get(),
						options.getRemapLocals().get()
				);
			} catch (IOException e) {
				throw new UncheckedIOException("Failed to read mappings from: " + options.mappingsFile(), e);
			}
		}

		return mappingProvider;
	}

	public synchronized MemoryMappingTree getMemoryMappingTree() {
		if (memoryMappingTree == null) {
			memoryMappingTree = new MemoryMappingTree();

			try {
				MappingReader.read(options.mappingsFile().get().getAsFile().toPath(), memoryMappingTree);
			} catch (IOException e) {
				throw new UncheckedIOException("Failed to read mappings from: " + options.mappingsFile(), e);
			}
		}

		return memoryMappingTree;
	}

	public String getFromNamespace() {
		return options.getFrom().get();
	}

	public String getToNamespace() {
		return options.getTo().get();
	}

	@Override
	public void close() {
		mappingProvider = null;
		memoryMappingTree = null;
	}
}
