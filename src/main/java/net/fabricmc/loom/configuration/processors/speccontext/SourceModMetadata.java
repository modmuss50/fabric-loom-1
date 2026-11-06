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

package net.fabricmc.loom.configuration.processors.speccontext;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.attributes.Usage;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskDependency;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.fmj.FabricModJson;
import net.fabricmc.loom.util.fmj.FabricModJsonFactory;
import net.fabricmc.loom.util.fmj.FabricModJsonSource;
import net.fabricmc.loom.util.gradle.SourceSetHelper;

/**
 * Exchanges existing source files without scheduling producer tasks. Generated metadata is not supported.
 */
public final class SourceModMetadata {
	private static final String CONFIGURATION = "loomSourceModMetadata";
	private static final String USAGE = "fabric-loom-source-mod-metadata";
	private static final String METADATA = "loom-mod-json";
	private static final String RESOURCE_ROOT = "loom-resource-root";

	private SourceModMetadata() {
	}

	public static void register(Project project) {
		project.getConfigurations().consumable(CONFIGURATION, configuration -> {
			configuration.attributes(attributes -> attributes.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, USAGE)));
			configuration.getOutgoing().getArtifacts().addAllLater(project.provider(() -> sourceArtifacts(project)));
		});
	}

	private static List<PublishArtifact> sourceArtifacts(Project project) {
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		List<Path> roots = new ArrayList<>();
		File metadata;

		if (extension.getFabricModJsonPath().isPresent()) {
			metadata = extension.getFabricModJsonPath().get().getAsFile();
			roots.add(metadata.toPath().getParent());
		} else {
			List<SourceSet> sourceSets = new ArrayList<>();
			sourceSets.add(extension.getMinecraftSourceSet());

			if (extension.areEnvironmentSourceSetsSplit()) {
				sourceSets.add(extension.getMinecraftClientSourceSet());
			}

			metadata = SourceSetHelper.findFirstFileInResource(FabricModJsonFactory.FABRIC_MOD_JSON, project, sourceSets.toArray(SourceSet[]::new));

			if (metadata == null) {
				return List.of();
			}

			for (SourceSet sourceSet : sourceSets) {
				for (File root : sourceSet.getResources().getSrcDirs()) {
					roots.add(root.toPath());
				}
			}
		}

		List<PublishArtifact> artifacts = new ArrayList<>();
		artifacts.add(new SourceArtifact(metadata, METADATA, "metadata"));

		for (int index = 0; index < roots.size(); index++) {
			artifacts.add(new SourceArtifact(roots.get(index).toFile(), RESOURCE_ROOT, Integer.toString(index)));
		}

		return artifacts;
	}

	public static List<ProjectView.ProjectMod> resolve(Project project, String configurationName, Map<ComponentIdentifier, FabricModJson> cache) {
		Configuration source = project.getConfigurations().getByName(configurationName);
		Configuration metadata = project.getConfigurations().detachedConfiguration();
		metadata.setTransitive(false);
		metadata.attributes(attributes -> attributes.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, USAGE)));

		// Module dependencies can select projects through composite-build substitution.
		for (ModuleDependency dependency : source.getAllDependencies().withType(ModuleDependency.class)) {
			ModuleDependency copy = dependency.copy();
			copy.setTargetConfiguration(null);
			metadata.getDependencies().add(copy);
		}

		// Non-Loom projects have no metadata variant. Keep discovery direct, as with the previous project walk.
		Set<ResolvedArtifact> artifacts = metadata.getResolvedConfiguration().getLenientConfiguration().getArtifacts();
		Map<ComponentIdentifier, List<ResolvedArtifact>> components = new LinkedHashMap<>();

		for (ResolvedArtifact artifact : artifacts) {
			ComponentIdentifier component = artifact.getId().getComponentIdentifier();

			if (component instanceof ProjectComponentIdentifier) {
				components.computeIfAbsent(component, key -> new ArrayList<>()).add(artifact);
			}
		}

		List<ProjectView.ProjectMod> mods = new ArrayList<>();

		for (var entry : components.entrySet()) {
			ResolvedArtifact modJson = entry.getValue().stream().filter(artifact -> METADATA.equals(artifact.getType())).findFirst().orElse(null);

			if (modJson == null) {
				continue;
			}

			FabricModJson mod = cache.computeIfAbsent(entry.getKey(), key -> read(project, modJson, entry.getValue()));
			mods.add(new ProjectView.ProjectMod(entry.getKey(), mod));
		}

		return List.copyOf(mods);
	}

	private static FabricModJson read(Project project, ResolvedArtifact modJson, List<ResolvedArtifact> artifacts) {
		Path metadata = modJson.getFile().toPath();
		List<Path> roots = artifacts.stream()
				.filter(artifact -> RESOURCE_ROOT.equals(artifact.getType()))
				.sorted(Comparator.comparingInt(artifact -> Integer.parseInt(artifact.getClassifier())))
				.map(artifact -> artifact.getFile().toPath())
				.toList();
		var source = new FabricModJsonSource.SourceSetSource(metadata, roots);
		String json = new String(readInput(project, metadata), StandardCharsets.UTF_8);
		FabricModJson mod = FabricModJsonFactory.create(LoomGradlePlugin.GSON.fromJson(json, JsonObject.class), source);
		List<String> referencedFiles = new ArrayList<>(mod.getClassTweakers().keySet());
		JsonElement javadoc = mod.getCustom(Constants.CustomModJsonKeys.PROVIDED_JAVADOC);

		if (javadoc != null) {
			referencedFiles.add(javadoc.getAsString());
		}

		for (String referencedFile : referencedFiles) {
			try {
				readInput(project, source.resolve(referencedFile));
			} catch (IOException e) {
				throw new UncheckedIOException("Failed to read source metadata resource " + referencedFile + " in " + metadata, e);
			}
		}

		return mod;
	}

	private static byte[] readInput(Project project, Path path) {
		return project.getProviders().fileContents(project.getLayout().file(project.provider(path::toFile))).getAsBytes().get();
	}

	private record SourceArtifact(File file, String type, String classifier) implements PublishArtifact {
		@Override
		public String getName() {
			return file.getName();
		}

		@Override
		public String getExtension() {
			return "";
		}

		@Override
		public String getType() {
			return type;
		}

		@Override
		public String getClassifier() {
			return classifier;
		}

		@Override
		public File getFile() {
			return file;
		}

		@Override
		public Date getDate() {
			return new Date(0);
		}

		@Override
		public TaskDependency getBuildDependencies() {
			return task -> Set.of();
		}
	}
}
