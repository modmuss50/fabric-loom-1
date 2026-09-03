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

package net.fabricmc.loom.test.integration

import java.nio.file.Files

import org.gradle.testkit.runner.BuildResult
import spock.lang.Specification
import spock.lang.Unroll
import spock.util.environment.RestoreSystemProperties

import net.fabricmc.loom.test.LoomTestVersions
import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

class TaskBasedMinecraftTest extends Specification implements GradleProjectTestTrait {
	private static final String PROCESS_MINECRAFT_JARS = "processMinecraftJars"
	private static final List<String> PROCESS_TASKS = [
		"processMinecraftMergedJar",
		"processMinecraftServerJar",
		"processMinecraftClientJar",
		"processMinecraftCommonJar",
		"processMinecraftClientOnlyJar"
	]

	def "task based Minecraft is opt-in"() {
		setup:
		def gradle = gradleProject(project: "accesswidener")

		when:
		def result = gradle.run(tasks: ["tasks", "--all"])
		def localMinecraftMaven = new File(gradle.projectDir, ".gradle/loom-cache/minecraftMaven/net/minecraft")
		def localMinecraftFiles = filesUnder(localMinecraftMaven)

		then:
		result.task(":tasks").outcome == SUCCESS
		!result.output.contains(PROCESS_MINECRAFT_JARS)
		PROCESS_TASKS.every { !result.output.contains(it) }
		localMinecraftFiles.any { it.name.endsWith(".jar") }
		localMinecraftFiles.any { it.name.endsWith(".pom") }
	}

	def "compile uses the lazily processed merged Minecraft jar"() {
		setup:
		def gradle = gradleProject(project: "minimalBase")
		enableTaskBasedMinecraft(gradle)
		configureMinecraft(gradle, "", true)
		gradle.buildGradle << '''
            afterEvaluate {
                def processMinecraftOutput = tasks.named("processMinecraftMergedJar", net.fabricmc.loom.task.ProcessMinecraftJarTask)
                println("PROCESS_MINECRAFT_OUTPUT=" + processMinecraftOutput.get().outputJar.get().asFile.absolutePath)
            }
        '''

		when:
		def helpResult = gradle.run(task: "help")
		def outputJar = processMinecraftOutput(helpResult)

		then:
		helpResult.task(":help").outcome == SUCCESS
		helpResult.task(":processMinecraftMergedJar") == null
		!outputJar.exists()

		when:
		def firstCompile = gradle.run(task: "compileJava")
		def secondCompile = gradle.run(task: "compileJava")

		then:
		firstCompile.task(":processMinecraftMergedJar").outcome == SUCCESS
		firstCompile.task(":compileJava").outcome == SUCCESS
		outputJar.isFile()
		secondCompile.task(":processMinecraftMergedJar").outcome == UP_TO_DATE
		secondCompile.output.contains("Reusing configuration cache.")
	}

	@Unroll
	def "registers process tasks for the #configurationName jar configuration"() {
		setup:
		def gradle = gradleProject(project: "minimalBase")
		enableTaskBasedMinecraft(gradle)
		configureMinecraft(gradle, loomConfiguration, true)

		when:
		def result = gradle.run(tasks: ["tasks", "--all"])
		def firstCompile = gradle.run(task: "compileJava")
		def secondCompile = gradle.run(task: "compileJava")

		then:
		result.task(":tasks").outcome == SUCCESS
		result.output.contains(PROCESS_MINECRAFT_JARS)
		expectedTasks.every { result.output.contains(it) }
		(PROCESS_TASKS - expectedTasks).every { !result.output.contains(it) }
		expectedTasks.every { firstCompile.task(":" + it).outcome == SUCCESS }
		(PROCESS_TASKS - expectedTasks).every { firstCompile.task(":" + it) == null }
		firstCompile.task(":compileJava").outcome == SUCCESS
		expectedTasks.every { secondCompile.task(":" + it).outcome == UP_TO_DATE }

		where:
		configurationName | loomConfiguration          | expectedTasks
		"server"          | "serverOnlyMinecraftJar()" | ["processMinecraftServerJar"]
		"client"          | "clientOnlyMinecraftJar()" | ["processMinecraftClientJar"]
		"split"           | "splitMinecraftJar()"      | [
			"processMinecraftCommonJar",
			"processMinecraftClientOnlyJar"
		]
	}

	def "split source sets compile with their matching Minecraft jars"() {
		setup:
		def gradle = gradleProject(project: "splitSources")
		enableTaskBasedMinecraft(gradle)

		when:
		def commonCompile = gradle.run(task: "compileJava")
		def clientCompile = gradle.run(task: "compileClientJava")

		then:
		commonCompile.task(":processMinecraftCommonJar").outcome == SUCCESS
		commonCompile.task(":processMinecraftClientOnlyJar") == null
		commonCompile.task(":compileJava").outcome == SUCCESS
		clientCompile.task(":processMinecraftCommonJar").outcome == UP_TO_DATE
		clientCompile.task(":processMinecraftClientOnlyJar").outcome == SUCCESS
		clientCompile.task(":compileClientJava").outcome == SUCCESS
	}

	def "processed Minecraft jars do not use the project local Minecraft Maven"() {
		setup:
		def gradle = gradleProject(project: "accesswidener")
		enableTaskBasedMinecraft(gradle)
		def localMinecraftMaven = new File(gradle.projectDir, ".gradle/loom-cache/minecraftMaven/net/minecraft")
		def processedMinecraft = new File(gradle.projectDir, ".gradle/loom-cache/minecraft/processed")

		when:
		def help = gradle.run(task: "help")
		def processedFiles = filesUnder(processedMinecraft)

		then:
		help.task(":help").outcome == SUCCESS
		!localMinecraftMaven.exists()
		processedFiles.any { it.name.endsWith(".jar") }
		processedFiles.any { it.name.endsWith(".jar.backup") }
		processedFiles.every { !it.name.endsWith(".pom") }

		when:
		def result = gradle.run(task: "compileJava")

		then:
		result.task(":processMinecraftMergedJar").outcome == SUCCESS
		result.task(":compileJava").outcome == SUCCESS
		!localMinecraftMaven.exists()
	}

	@RestoreSystemProperties
	def "idea sync processes Minecraft without run configurations"() {
		setup:
		System.setProperty("idea.sync.active", "true")
		def gradle = gradleProject(project: "minimalBase")
		enableTaskBasedMinecraft(gradle)
		configureMinecraft(gradle, "runs.clear()")
		new File(gradle.projectDir, ".idea").mkdirs()

		when:
		def result = gradle.run(tasks: [])

		then:
		result.task(":processMinecraftMergedJar").outcome == SUCCESS
		result.task(":processMinecraftJars").outcome == SUCCESS
		result.task(":ideaSyncTask").outcome == SUCCESS
		result.task(":genSourcesWithVineflower") == null
	}

	def "generated sources are adjacent and attached to the Eclipse classpath"() {
		setup:
		def gradle = gradleProject(project: "minimalBase", warningMode: "none")
		enableTaskBasedMinecraft(gradle)
		configureMinecraft(gradle)
		gradle.buildGradle << '''
            afterEvaluate {
                def processMinecraft = tasks.named("processMinecraftMergedJar", net.fabricmc.loom.task.ProcessMinecraftJarTask).get()
                println("PROCESS_MINECRAFT_INPUT=" + processMinecraft.inputJar.get().asFile.absolutePath)
                println("PROCESS_MINECRAFT_OUTPUT=" + processMinecraft.outputJar.get().asFile.absolutePath)
            }
        '''

		when:
		def first = gradle.run(tasks: [
			"genSourcesWithVineflower",
			"eclipse",
			"vscode"
		], configurationCache: false)
		def inputJar = processMinecraftPath(first, "PROCESS_MINECRAFT_INPUT=")
		def outputJar = processMinecraftOutput(first)
		def sourcesJar = new File(outputJar.parentFile, "minecraft-merged-sources.jar")
		def classpath = new groovy.xml.XmlSlurper().parse(new File(gradle.projectDir, ".classpath"))
		def minecraftEntry = classpath.classpathentry.find {
			it.@kind.text() == "lib" && it.@path.text().endsWith("/minecraft-merged.jar")
		}
		def taskBasedCache = new File(gradle.gradleHomeDir, "caches/fabric-loom/taskBasedMinecraft")
		def outputFiles = outputJar.parentFile.listFiles().findAll { it.isFile() }.collect { it.name }.toSet()

		then:
		first.task(":genSourcesWithVineflower").outcome == SUCCESS
		first.task(":processMinecraftMergedJar").outcome == SUCCESS
		first.task(":processMinecraftJars").outcome == SUCCESS
		first.task(":eclipseClasspath").outcome == SUCCESS
		first.task(":eclipse").outcome == SUCCESS
		first.task(":genEclipseRuns").outcome == SUCCESS
		first.task(":vscode").outcome == SUCCESS
		first.tasks*.path.indexOf(":genSourcesWithVineflower") < first.tasks*.path.indexOf(":processMinecraftMergedJar")
		outputJar.isFile()
		sourcesJar.isFile()
		outputJar.canonicalFile.toPath().startsWith(taskBasedCache.canonicalFile.toPath())
		outputFiles == [
			"minecraft-merged.jar",
			"minecraft-merged-sources.jar"
		] as Set
		Files.mismatch(inputJar.toPath(), outputJar.toPath()) == -1
		minecraftEntry.size() == 1
		new File(minecraftEntry.@path.text()).canonicalFile == outputJar.canonicalFile
		new File(minecraftEntry.@sourcepath.text()).canonicalFile == sourcesJar.canonicalFile
	}

	private static void enableTaskBasedMinecraft(GradleProjectTestTrait.GradleProject gradle) {
		gradle.gradleProperties << "\nfabric.loom.experimental.taskBasedMinecraft=true\n"
	}

	private static void configureMinecraft(GradleProjectTestTrait.GradleProject gradle, String loomConfiguration = "", boolean writeCompileSource = false) {
		gradle.buildGradle << """
            loom {
                ${loomConfiguration}
            }

            dependencies {
                minecraft "com.mojang:minecraft:1.18.1"
                mappings "net.fabricmc:yarn:1.18.1+build.18:v2"
                modImplementation "${LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
            }
        """

		if (writeCompileSource) {
			def source = new File(gradle.projectDir, "src/main/java/UsesMinecraft.java")
			source.parentFile.mkdirs()
			source.text = '''
                import net.minecraft.block.Block;

                public final class UsesMinecraft {
                    private Block block;
                }
            '''
		}
	}

	private static File processMinecraftOutput(BuildResult result) {
		return processMinecraftPath(result, "PROCESS_MINECRAFT_OUTPUT=")
	}

	private static File processMinecraftPath(BuildResult result, String prefix) {
		def outputLine = result.output.readLines().find { it.startsWith(prefix) }
		assert outputLine != null
		return new File(outputLine.substring(prefix.length()))
	}

	private static List<File> filesUnder(File root) {
		def files = []

		if (root.exists()) {
			root.eachFileRecurse { file ->
				if (file.isFile()) {
					files.add(file)
				}
			}
		}

		return files
	}
}
