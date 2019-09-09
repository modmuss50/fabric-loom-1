/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
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

package net.fabricmc.loom.util;

import net.fabricmc.loom.LoomGradleExtension;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.query.ArtifactResolutionQuery;
import org.gradle.api.artifacts.result.ArtifactResult;
import org.gradle.api.artifacts.result.ComponentArtifactsResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.jvm.JvmLibrary;
import org.gradle.language.base.artifact.SourcesArtifact;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * This is based of work from covers1624 and adapted to work on loom 0.2.5
 */
@SuppressWarnings("UnstableApiUsage")
public class ModCompileRemapper {

	final Project project;
	final LoomGradleExtension extension;

	public ModCompileRemapper(Project project) {
		this.project = project;
		this.extension = project.getExtensions().getByType(LoomGradleExtension.class);
	}

	public void setup(Configuration modCompile, Configuration modCompileRemapped, Configuration regularCompile, Consumer<Runnable> postPopulationScheduler) {
		DependencyHandler dependencies = project.getDependencies();

		for (ResolvedArtifactResult artifact : modCompile.getIncoming().getArtifacts().getArtifacts()) {
			boolean maven = artifact.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier;

			MavenNotation notation;
			if(maven){
				notation = MavenNotation.parse(artifact.getId().getComponentIdentifier().getDisplayName());
			} else {
				//support anything! Hash the file and use that as its group, filename as name, and 1.0.0 as version. Alternatively parse version and name from artifact.
				try {
					notation = MavenNotation.parse(String.format("%s:%s:1.0.0", Checksum.get(artifact.getFile()) , artifact.getFile().getName()));
				} catch (IOException e) {
					throw new RuntimeException("Failed to create maven notation for file", e);
				}
			}

			File classes = artifact.getFile();
			AtomicBoolean isFabricMod = new AtomicBoolean(false);
			project.zipTree(classes).visit(f -> {
				if (f.getName().endsWith("fabric.mod.json")) {
					project.getLogger().info("Found Fabric mod in modCompile: {}", notation.toString());
					isFabricMod.set(true);
					f.stopVisiting();
				}
			});

			if (!isFabricMod.get()) {
				project.getLogger().info("Adding '{}' does not contain 'fabric.mod.json', skipping remapping", notation.toString());
				Dependency dep = dependencies.module(notation.toString());
				dependencies.add(regularCompile.getName(), dep);
				continue;
			}

			AtomicReference<File> sources = new AtomicReference<>();
			@SuppressWarnings("unchecked") ArtifactResolutionQuery query = dependencies.createArtifactResolutionQuery()//
				.forComponents(artifact.getId().getComponentIdentifier())//
				.withArtifacts(JvmLibrary.class, SourcesArtifact.class);
			outer:
			for (ComponentArtifactsResult result : query.execute().getResolvedComponents()) {
				for (ArtifactResult srcArtifact : result.getArtifacts(SourcesArtifact.class)) {
					if (srcArtifact instanceof ResolvedArtifactResult) {
						sources.set(((ResolvedArtifactResult) srcArtifact).getFile());
						break outer;
					}
				}
			}

			MavenNotation remappedNotation = remap(notation);

			remapArtifact(modCompileRemapped, artifact, remappedNotation.toFile(extension.getRemappedModCache()));
			if (sources.get() != null) {
				scheduleSourcesRemapping(postPopulationScheduler, sources.get(), remappedNotation.toString(), remappedNotation.withClassifier("sources").toFile(extension.getRemappedModCache()));
			}
			dependencies.add(modCompileRemapped.getName(), remappedNotation.toString());
		}
	}

	private void remapArtifact(Configuration config, ResolvedArtifactResult artifact, File output) {
		File input = artifact.getFile();
		if (!output.exists() || input.lastModified() <= 0 || input.lastModified() > output.lastModified()) {
			//If the output doesn't exist, or appears to be outdated compared to the input we'll remap it
			try {
				ModProcessor.processMod(input, output, project, config);
			} catch (IOException e) {
				throw new RuntimeException("Failed to remap mod", e);
			}

			if (!output.exists()) {
				throw new RuntimeException("Failed to remap mod");
			}

			output.setLastModified(input.lastModified());
		} else {
			project.getLogger().info(output.getName() + " is up to date with " + input.getName());
		}

		ModProcessor.acknowledgeMod(input, output, project, config);
	}

	private void scheduleSourcesRemapping(Consumer<Runnable> postPopulationScheduler, File sources, String remappedLog, File remappedSources) {
		postPopulationScheduler.accept(() -> {
			project.getLogger().lifecycle(":providing " + remappedLog + " sources");

			if (!remappedSources.exists() || sources.lastModified() <= 0 || sources.lastModified() > remappedSources.lastModified()) {
				try {
					SourceRemapper.remapSources(project, sources, remappedSources, true);

					//Set the remapped sources creation date to match the sources if we're likely succeeded in making it
					remappedSources.setLastModified(sources.lastModified());
				} catch (Exception e) {
					e.printStackTrace();
				}
			} else {
				project.getLogger().info(remappedSources.getName() + " is up to date with " + sources.getName());
			}
		});
	}

	public MavenNotation remap(MavenNotation notation) {
		String mappings = extension.getMappingsProvider().mappingsName + "-" + extension.getMappingsProvider().version.replaceAll("\\.", "-");
		return notation.withGroup(mappings + "." + notation.group);
	}

}
