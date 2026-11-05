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

package net.fabricmc.loom.test.unit.fmj

import java.nio.charset.StandardCharsets
import java.nio.file.Path

import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.util.fmj.FabricModJsonFactory
import net.fabricmc.loom.util.fmj.FabricModJsonHelpers
import net.fabricmc.loom.util.fmj.FabricModJsonSource
import net.fabricmc.loom.util.gradle.SourceSetHelper

// Source metadata must preserve literal version placeholders.
@SuppressWarnings("GStringExpressionWithinString")
class FabricModJsonSourceTest extends Specification {
	@TempDir
	Path projectDir

	def "source resources preserve main and client order without retaining source sets"() {
		given:
		def project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build()
		project.pluginManager.apply("java")
		def extension = Stub(LoomGradleExtension) {
			isConfigurationCacheActive() >> configurationCache
		}
		project.extensions.add("loom", extension)
		def sourceSets = SourceSetHelper.getSourceSets(project)
		def main = sourceSets.getByName("main")
		def client = sourceSets.create("client")
		main.resources.setSrcDirs(["custom/main", "custom/extra"])
		client.resources.setSrcDirs(["custom/client"])
		def metadata = writeFile("custom/main/fabric.mod.json", MOD_JSON)
		writeFile("custom/client/fabric.mod.json", MOD_JSON.replace("example", "client"))
		writeFile("custom/main/shared.classtweaker", "main")
		writeFile("custom/extra/shared.classtweaker", "extra")
		writeFile("custom/client/shared.classtweaker", "client")
		writeFile("custom/extra/docs.json", "main docs")
		writeFile("custom/client/docs.json", "client docs")
		writeFile("custom/client/client.classtweaker", "client only")

		when:
		def mod = FabricModJsonFactory.createFromSourceSetsNullable(project, main, client)
		main.resources.setSrcDirs(["changed/main"])
		client.resources.setSrcDirs(["changed/client"])

		then:
		mod.id == "example"
		mod.modVersion == '${version}'
		mod.source instanceof FabricModJsonSource.SourceSetSource
		mod.source.fabricModJsonPath() == metadata.toRealPath()
		mod.source.resourceRoots() == [
			projectDir.resolve("custom/main").toRealPath(),
			projectDir.resolve("custom/extra").toRealPath(),
			projectDir.resolve("custom/client").toRealPath()
		]
		read(mod.source, "fabric.mod.json") == MOD_JSON
		read(mod.source, "shared.classtweaker") == "main"
		read(mod.source, "docs.json") == "main docs"
		read(mod.source, "client.classtweaker") == "client only"

		where:
		configurationCache << [false, true]
	}

	def "client metadata stays selected when main metadata appears later"() {
		given:
		def project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build()
		project.pluginManager.apply("java")
		project.extensions.add("loom", Stub(LoomGradleExtension))
		def sourceSets = SourceSetHelper.getSourceSets(project)
		def main = sourceSets.getByName("main")
		def client = sourceSets.create("client")
		writeFile("src/client/resources/fabric.mod.json", MOD_JSON)
		writeFile("src/main/resources/docs.json", "main docs")

		when:
		def mod = FabricModJsonFactory.createFromSourceSetsNullable(project, main, client)
		writeFile("src/main/resources/fabric.mod.json", MOD_JSON.replace("example", "main"))

		then:
		mod.id == "example"
		read(mod.source, "fabric.mod.json") == MOD_JSON
		read(mod.source, "docs.json") == "main docs"
	}

	def "metadata override resolves referenced files relative to its directory"() {
		given:
		def project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build()
		def metadata = writeFile("metadata/override.json", MOD_JSON)
		writeFile("metadata/shared.classtweaker", "override")
		writeFile("metadata/docs.json", "override docs")
		writeFile("src/main/resources/shared.classtweaker", "main")
		def overridePath = project.objects.fileProperty().fileValue(metadata.toFile())
		project.extensions.add("loom", Stub(LoomGradleExtension) {
			getFabricModJsonPath() >> overridePath
		})

		when:
		def mod = FabricModJsonHelpers.getModsInProject(project).getFirst()

		then:
		mod.modVersion == '${version}'
		read(mod.source, "shared.classtweaker") == "override"
		read(mod.source, "docs.json") == "override docs"
	}

	def "source reports missing resources"() {
		given:
		def source = new FabricModJsonSource.SourceSetSource(projectDir.resolve("fabric.mod.json"), [projectDir])

		when:
		source.read("missing.classtweaker")

		then:
		def exception = thrown(FileNotFoundException)
		exception.message == "Could not find: missing.classtweaker"
	}

	private Path writeFile(String path, String contents) {
		def file = projectDir.resolve(path).toFile()
		file.parentFile.mkdirs()
		file.setText(contents, "UTF-8")
		return file.toPath()
	}

	private static String read(FabricModJsonSource source, String path) {
		return new String(source.read(path), StandardCharsets.UTF_8)
	}

	private static final String MOD_JSON = '''{
	"schemaVersion": 1,
	"id": "example",
	"version": "${version}",
	"accessWidener": "shared.classtweaker",
	"custom": {
		"loom:provided_javadoc": "docs.json"
	}
}'''
}
