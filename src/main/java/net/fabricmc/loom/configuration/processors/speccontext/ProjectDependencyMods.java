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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.gradle.api.artifacts.component.ComponentIdentifier;

import net.fabricmc.loom.api.processor.SpecContext;
import net.fabricmc.loom.util.fmj.FabricModJson;

record ProjectDependencyMods(List<FabricModJson> all, List<FabricModJson> transforming, List<FabricModJson> clientOnly) {
	static ProjectDependencyMods create(ProjectView projectView) {
		if (projectView.disableProjectDependantMods()) {
			return new ProjectDependencyMods(List.of(), List.of(), List.of());
		}

		List<ProjectView.ProjectMod> mainCompile = projectView.getProjectMods(ProjectView.ArtifactUsage.COMPILE, DebofConfiguration.TargetSourceSet.MAIN);
		List<ProjectView.ProjectMod> mainRuntime = projectView.getProjectMods(ProjectView.ArtifactUsage.RUNTIME, DebofConfiguration.TargetSourceSet.MAIN);
		Map<ComponentIdentifier, FabricModJson> all = new LinkedHashMap<>();
		add(all, mainCompile);
		add(all, mainRuntime);
		Map<ComponentIdentifier, FabricModJson> common = intersection(mainCompile, mainRuntime);
		Map<ComponentIdentifier, FabricModJson> client = new LinkedHashMap<>();

		if (projectView.areEnvironmentSourceSetsSplit()) {
			List<ProjectView.ProjectMod> clientCompile = projectView.getProjectMods(ProjectView.ArtifactUsage.COMPILE, DebofConfiguration.TargetSourceSet.CLIENT);
			List<ProjectView.ProjectMod> clientRuntime = projectView.getProjectMods(ProjectView.ArtifactUsage.RUNTIME, DebofConfiguration.TargetSourceSet.CLIENT);
			add(all, clientCompile);
			add(all, clientRuntime);
			client.putAll(intersection(clientCompile, clientRuntime));
			client.keySet().removeAll(common.keySet());
		}

		List<FabricModJson> transforming = new ArrayList<>(common.values());
		transforming.addAll(client.values());
		return new ProjectDependencyMods(
				SpecContext.distinctSorted(new ArrayList<>(all.values())),
				SpecContext.distinctSorted(transforming),
				SpecContext.distinctSorted(new ArrayList<>(client.values()))
		);
	}

	private static void add(Map<ComponentIdentifier, FabricModJson> mods, List<ProjectView.ProjectMod> dependencies) {
		for (ProjectView.ProjectMod dependency : dependencies) {
			mods.putIfAbsent(dependency.component(), dependency.mod());
		}
	}

	private static Map<ComponentIdentifier, FabricModJson> intersection(List<ProjectView.ProjectMod> compile, List<ProjectView.ProjectMod> runtime) {
		Map<ComponentIdentifier, FabricModJson> mods = new LinkedHashMap<>();
		add(mods, compile);
		Map<ComponentIdentifier, FabricModJson> runtimeMods = new LinkedHashMap<>();
		add(runtimeMods, runtime);
		mods.keySet().retainAll(runtimeMods.keySet());
		return mods;
	}
}
