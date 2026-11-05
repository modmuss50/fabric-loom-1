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

package net.fabricmc.loom.test.integration.noRemap

import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class SourceModMetadataTest extends Specification implements GradleProjectTestTrait {
	def "source metadata works without producer outputs and tracks referenced files"() {
		given:
		def gradle = gradleProject(project: "sourceMetadata")
		verifyContext(gradle, ["producer"], ["producer"], [])
		File resourceDirectory = new File(gradle.projectDir, "producer/extra-resources")

		if (overrideMetadata) {
			resourceDirectory = new File(gradle.projectDir, "producer/overrides")
			resourceDirectory.mkdirs()
			new File(resourceDirectory, "selected.json").text = new File(gradle.projectDir, "producer/metadata/fabric.mod.json").text
			List<String> referencedFiles = [
				"producer.accesswidener",
				"docs.tiny"
			]

			for (String name : referencedFiles) {
				new File(resourceDirectory, name).text = new File(gradle.projectDir, "producer/extra-resources/" + name).text
			}
		}

		def arguments = overrideMetadata ? ["-PoverrideMetadata"] : []
		def options = [task: ":consumer:help", isloatedProjects: isolated, args: arguments]

		when:
		def first = gradle.run(options)

		then:
		first.task(":consumer:help").outcome == SUCCESS
		first.tasks.every {
			!it.path.startsWith(":producer:") && !it.path.startsWith(":indirect:")
		}
		!new File(gradle.projectDir, "producer/build").exists()

		when:
		// Initial Minecraft preparation creates cache artifacts that invalidate the first configuration.
		gradle.run(options)
		def cached = gradle.run(options)

		then:
		cached.output.contains("Reusing configuration cache.")

		when:
		def tweaker = new File(resourceDirectory, "producer.accesswidener")
		tweaker << "# edited class tweaker\n"
		def edited = gradle.run(options)

		then:
		!edited.output.contains("Reusing configuration cache.")

		when:
		gradle.run(options)
		def cachedAfterEdit = gradle.run(options)

		then:
		cachedAfterEdit.output.contains("Reusing configuration cache.")

		when:
		def javadoc = new File(resourceDirectory, "docs.tiny")
		javadoc.text = javadoc.text.replace("Metadata from producer.", "Updated producer documentation.")
		def editedJavadoc = gradle.run(options)

		then:
		!editedJavadoc.output.contains("Reusing configuration cache.")

		when:
		def compiled = gradle.run(task: ":consumer:compileJava", isloatedProjects: isolated, args: arguments)

		then:
		compiled.task(":consumer:compileJava").outcome == SUCCESS

		when:
		tweaker.text = "accessWidener v2 official\n"
		def removedTweaker = gradle.run(task: ":consumer:compileJava", isloatedProjects: isolated, args: arguments, expectFailure: true)

		then:
		removedTweaker.output.contains("path has private access")

		where:
		isolated | overrideMetadata
		false | false
		true | false
		false | true
		true | true
	}

	def "compile-only and runtime-only project dependencies do not apply transforms"() {
		given:
		def gradle = gradleProject(project: "sourceMetadata")
		verifyContext(gradle, ["producer"], [], [])

		when:
		def result = gradle.run(task: ":consumer:compileJava", isloatedProjects: isolated, args: ["-PdependencyScope=" + scope], expectFailure: true)

		then:
		result.output.contains("path has private access")
		result.output.contains("injectedMethod")

		where:
		isolated | scope
		false | "compileOnly"
		true | "compileOnly"
		false | "runtimeOnly"
		true | "runtimeOnly"
	}

	def "client-only dependency metadata keeps its classification"() {
		given:
		def gradle = gradleProject(project: "sourceMetadata")
		verifyContext(gradle, ["producer"], ["producer"], ["producer"])
		def metadata = new File(gradle.projectDir, "producer/metadata/fabric.mod.json")
		metadata.text = metadata.text.replace("net/minecraft/resources/Identifier", "net/minecraft/client/Options")
		new File(gradle.projectDir, "producer/extra-resources/producer.accesswidener").text = """accessWidener v2 official
transitive-accessible field net/minecraft/client/Options serverRenderDistance I
"""
		new File(gradle.projectDir, "consumer/src/main/java/example/Test.java").delete()
		def source = new File(gradle.projectDir, "consumer/src/client/java/example/Test.java")
		source.parentFile.mkdirs()
		source.text = '''package example;
import net.minecraft.client.Options;
public class Test {
	public static int test(Options options) {
		options.injectedMethod();
		return options.serverRenderDistance;
	}
}
'''

		when:
		def result = gradle.run(task: ":consumer:compileClientJava", isloatedProjects: isolated, args: [
			"-Psplit",
			"-PdependencyScope=clientImplementation"
		])

		then:
		result.task(":consumer:compileClientJava").outcome == SUCCESS

		where:
		isolated << [false, true]
	}

	def "discovery stays direct even when transitive project jars exist"() {
		given:
		def gradle = gradleProject(project: "sourceMetadata")
		verifyContext(gradle, ["producer"], ["producer"], [])
		def source = new File(gradle.projectDir, "consumer/src/main/java/example/Test.java")
		source.text = source.text.replace("identifier.path", "identifier.namespace")
		gradle.run(task: ":indirect:jar", isloatedProjects: isolated)

		when:
		def result = gradle.run(task: ":consumer:compileJava", isloatedProjects: isolated, expectFailure: true)

		then:
		result.output.contains("namespace has private access")

		where:
		isolated << [false, true]
	}

	def "explicit project metadata opt-out is retained"() {
		given:
		def gradle = gradleProject(project: "sourceMetadata")
		verifyContext(gradle, [], [], [])

		when:
		def result = gradle.run(task: ":consumer:compileJava", isloatedProjects: true, args: [
			"-Pfabric.loom.disableProjectDependentMods=true"
		], expectFailure: true)

		then:
		result.output.contains("path has private access")
	}

	private static void verifyContext(GradleProject gradle, List<String> all, List<String> transforming, List<String> clientOnly) {
		new File(gradle.projectDir, "consumer/build.gradle") << """
afterEvaluate {
	def context = net.fabricmc.loom.configuration.processors.speccontext.DeobfSpecContext.create(project)
	assert context.modDependencies()*.id == ${all.inspect()}
	assert context.modDependenciesCompileRuntime()*.id == ${transforming.inspect()}
	assert context.modDependenciesCompileRuntimeClient()*.id == ${clientOnly.inspect()}
	assert context.modDependencies().every { it.modVersion == '\$' + '{version}' }
}
"""
	}
}
