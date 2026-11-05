/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2025 FabricMC
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

package net.fabricmc.loom.test.unit

import java.util.function.Function
import java.util.stream.Stream

import com.google.gson.JsonObject
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

import net.fabricmc.loom.api.RemapConfigurationSettings
import net.fabricmc.loom.configuration.processors.speccontext.DebofConfiguration
import net.fabricmc.loom.configuration.processors.speccontext.DeobfProjectView
import net.fabricmc.loom.configuration.processors.speccontext.DeobfSpecContext
import net.fabricmc.loom.configuration.processors.speccontext.ProjectView
import net.fabricmc.loom.configuration.processors.speccontext.RemappedProjectView
import net.fabricmc.loom.configuration.processors.speccontext.RemappedSpecContext
import net.fabricmc.loom.util.fmj.FabricModJsonFactory
import net.fabricmc.loom.util.fmj.FabricModJsonSource

import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when

class ProjectDependencySpecContextTest extends Specification {
	def "project metadata requires compile and runtime in the same source set"() {
		given:
		def project = ProjectBuilder.builder().build()
		def view = mock(remapped ? RemappedProjectView : DeobfProjectView)
		def common = projectMod("common")
		def client = projectMod("client")
		def compileOnly = projectMod("compile-only")
		def runtimeOnly = projectMod("runtime-only")
		def mixed = projectMod("mixed")
		when(view.getProjectMods(ProjectView.ArtifactUsage.COMPILE, DebofConfiguration.TargetSourceSet.MAIN)).thenReturn([common, compileOnly, mixed])
		when(view.getProjectMods(ProjectView.ArtifactUsage.RUNTIME, DebofConfiguration.TargetSourceSet.MAIN)).thenReturn([common, runtimeOnly])
		when(view.getProjectMods(ProjectView.ArtifactUsage.COMPILE, DebofConfiguration.TargetSourceSet.CLIENT)).thenReturn([common, client, compileOnly])
		when(view.getProjectMods(ProjectView.ArtifactUsage.RUNTIME, DebofConfiguration.TargetSourceSet.CLIENT)).thenReturn([
			common,
			client,
			runtimeOnly,
			mixed
		])
		when(view.areEnvironmentSourceSetsSplit()).thenReturn(true)

		if (remapped) {
			when(view.getRemapConfigurations()).thenReturn(project.objects.namedDomainObjectList(RemapConfigurationSettings))
			for (def usage : ProjectView.ArtifactUsage.values()) {
				when(view.resolveArtifacts(usage)).thenReturn({ settings ->
					Stream.empty()
				} as Function)
			}
		} else {
			for (def configuration : DebofConfiguration.ALL) {
				for (def target : DebofConfiguration.TargetSourceSet.values()) {
					when(view.getDependencies(configuration, target)).thenReturn(project.files())
				}
			}
		}

		when:
		def context = remapped ? RemappedSpecContext.create(view) : DeobfSpecContext.create(view)

		then:
		context.modDependencies()*.id == [
			"client",
			"common",
			"compile-only",
			"mixed",
			"runtime-only"
		]
		context.modDependenciesCompileRuntime()*.id.sort() == ["client", "common"]
		context.modDependenciesCompileRuntimeClient()*.id == ["client"]

		where:
		remapped << [false, true]
	}

	def "matching mod ids from different components do not form an intersection"() {
		given:
		def project = ProjectBuilder.builder().build()
		def view = mock(DeobfProjectView)
		for (def configuration : DebofConfiguration.ALL) {
			when(view.getDependencies(configuration, DebofConfiguration.TargetSourceSet.MAIN)).thenReturn(project.files())
		}
		when(view.getProjectMods(ProjectView.ArtifactUsage.COMPILE, DebofConfiguration.TargetSourceSet.MAIN)).thenReturn([projectMod("duplicate")])
		when(view.getProjectMods(ProjectView.ArtifactUsage.RUNTIME, DebofConfiguration.TargetSourceSet.MAIN)).thenReturn([projectMod("duplicate")])

		when:
		def context = DeobfSpecContext.create(view)

		then:
		context.modDependenciesCompileRuntime().empty
	}

	private ProjectView.ProjectMod projectMod(String id) {
		def json = new JsonObject()
		json.addProperty("schemaVersion", 1)
		json.addProperty("id", id)
		json.addProperty("version", "1.0.0")
		def component = Mock(ComponentIdentifier)
		def metadata = FabricModJsonFactory.create(json, Mock(FabricModJsonSource))
		return new ProjectView.ProjectMod(component, metadata)
	}
}
