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
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.Unroll
import spock.util.environment.RestoreSystemProperties

import net.fabricmc.loom.test.LoomTestVersions
import net.fabricmc.loom.test.util.GradleProjectTestTrait
import net.fabricmc.loom.util.Checksum

import static net.fabricmc.loom.test.LoomTestConstants.PRE_RELEASE_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
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
	private static final List<String> MERGED_MINECRAFT_TASKS = [
		"downloadMinecraftMetadata",
		"validateMinecraftMetadata",
		"downloadMinecraftLibraries",
		"prepareMinecraftIntermediaryMappings",
		"downloadMinecraftClientJar",
		"verifyMinecraftClientJar",
		"downloadMinecraftServerJar",
		"normalizeMinecraftServerJar",
		"verifyMinecraftServerJar",
		"mergeMinecraftJars",
		"prepareMinecraftMappings",
		"remapMinecraftMergedToIntermediary",
		"remapMinecraftMergedToNamed",
		"processMinecraftMergedJar"
	]
	private static final List<String> INSTALLER_TASKS = [
		"scanInstallerData",
		"downloadInstallerLibraries"
	]
	private static final List<String> PROCESSOR_ANALYSIS_TASKS = [
		"analyzeMinecraftAccessWideners",
		"analyzeMinecraftModJavadocs",
		"analyzeMinecraftInterfaceInjections"
	]
	private static final List<String> MERGED_PROCESSOR_TASKS = [
		"analyzeMinecraftAccessWideners",
		"analyzeMinecraftInterfaceInjections",
		"applyMinecraftMergedProcessor0",
		"applyMinecraftMergedProcessor2",
		"applyMinecraftMergedProcessor3",
		"applyMinecraftProcessorsMerged"
	]
	private static final List<String> RAW_SIDE_TASKS = [
		"downloadMinecraftClientJar",
		"verifyMinecraftClientJar",
		"downloadMinecraftServerJar",
		"normalizeMinecraftServerJar",
		"verifyMinecraftServerJar",
		"mergeMinecraftJars",
		"sanitizeMinecraftServerJar",
		"sanitizeMinecraftClientJar",
		"splitMinecraftJars"
	]
	private static final List<String> SHARED_RAW_TASKS = [
		"downloadMinecraftMetadata",
		"validateMinecraftMetadata",
		"downloadMinecraftLibraries"
	]
	private static final Map<String, List<String>> RAW_TASKS_BY_CONFIGURATION = [
		server: [
			"downloadMinecraftServerJar",
			"normalizeMinecraftServerJar",
			"verifyMinecraftServerJar",
			"sanitizeMinecraftServerJar"
		],
		client: [
			"downloadMinecraftClientJar",
			"verifyMinecraftClientJar",
			"sanitizeMinecraftClientJar"
		],
		split: [
			"downloadMinecraftClientJar",
			"verifyMinecraftClientJar",
			"downloadMinecraftServerJar",
			"normalizeMinecraftServerJar",
			"verifyMinecraftServerJar",
			"splitMinecraftJars"
		]
	]
	private static final Map<String, List<List<String>>> RAW_TASK_ORDER_BY_CONFIGURATION = [
		server: [
			[
				"downloadMinecraftMetadata",
				"validateMinecraftMetadata"
			],
			[
				"validateMinecraftMetadata",
				"downloadMinecraftServerJar"
			],
			[
				"downloadMinecraftServerJar",
				"downloadMinecraftLibraries"
			],
			[
				"downloadMinecraftServerJar",
				"normalizeMinecraftServerJar"
			],
			[
				"normalizeMinecraftServerJar",
				"verifyMinecraftServerJar"
			],
			[
				"verifyMinecraftServerJar",
				"sanitizeMinecraftServerJar"
			],
			[
				"sanitizeMinecraftServerJar",
				"remapMinecraftServerToIntermediary"
			]
		],
		client: [
			[
				"downloadMinecraftMetadata",
				"validateMinecraftMetadata"
			],
			[
				"validateMinecraftMetadata",
				"downloadMinecraftLibraries"
			],
			[
				"validateMinecraftMetadata",
				"downloadMinecraftClientJar"
			],
			[
				"downloadMinecraftClientJar",
				"verifyMinecraftClientJar"
			],
			[
				"verifyMinecraftClientJar",
				"sanitizeMinecraftClientJar"
			],
			[
				"sanitizeMinecraftClientJar",
				"remapMinecraftClientToIntermediary"
			]
		],
		split: [
			[
				"downloadMinecraftMetadata",
				"validateMinecraftMetadata"
			],
			[
				"validateMinecraftMetadata",
				"downloadMinecraftClientJar"
			],
			[
				"downloadMinecraftClientJar",
				"verifyMinecraftClientJar"
			],
			[
				"validateMinecraftMetadata",
				"downloadMinecraftServerJar"
			],
			[
				"downloadMinecraftServerJar",
				"downloadMinecraftLibraries"
			],
			[
				"downloadMinecraftServerJar",
				"normalizeMinecraftServerJar"
			],
			[
				"normalizeMinecraftServerJar",
				"verifyMinecraftServerJar"
			],
			[
				"verifyMinecraftClientJar",
				"splitMinecraftJars"
			],
			[
				"verifyMinecraftServerJar",
				"splitMinecraftJars"
			],
			[
				"splitMinecraftJars",
				"remapMinecraftCommonToIntermediary"
			],
			[
				"splitMinecraftJars",
				"remapMinecraftClientOnlyToIntermediary"
			]
		]
	]
	private static final List<List<String>> MERGED_RAW_TASK_ORDER = [
		[
			"downloadMinecraftMetadata",
			"validateMinecraftMetadata"
		],
		[
			"validateMinecraftMetadata",
			"downloadMinecraftClientJar"
		],
		[
			"downloadMinecraftClientJar",
			"verifyMinecraftClientJar"
		],
		[
			"validateMinecraftMetadata",
			"downloadMinecraftServerJar"
		],
		[
			"downloadMinecraftServerJar",
			"downloadMinecraftLibraries"
		],
		[
			"downloadMinecraftServerJar",
			"normalizeMinecraftServerJar"
		],
		[
			"normalizeMinecraftServerJar",
			"verifyMinecraftServerJar"
		],
		[
			"verifyMinecraftClientJar",
			"mergeMinecraftJars"
		],
		[
			"verifyMinecraftServerJar",
			"mergeMinecraftJars"
		],
		[
			"mergeMinecraftJars",
			"remapMinecraftMergedToIntermediary"
		],
		[
			"mergeMinecraftJars",
			"remapMinecraftMergedToNamed"
		]
	]
	private static final Set<TaskOutcome> FIRST_RUN_OUTCOMES = [SUCCESS, FROM_CACHE] as Set
	private static final Set<TaskOutcome> REUSED_OUTCOMES = [UP_TO_DATE, FROM_CACHE] as Set

	def "Minecraft setup is registered without doing configuration-time work"() {
		setup:
		def gradle = gradleProject(project: "accesswidener")
		def coldOutputRoots = taskOutputRoots(gradle)
		gradle.gradleProperties << "\nfabric.loom.experimental.taskBasedMinecraft=false\n"
		gradle.buildGradle << '''
            dependencies {
                modImplementation "configuration.must.not.resolve:missing-mod:1.0"
            }
        '''

		when:
		def result = gradle.run(tasks: ["tasks", "--all"], args: ["--offline"])

		then:
		result.task(":tasks").outcome == SUCCESS
		result.output.contains(PROCESS_MINECRAFT_JARS)
		result.output.contains("processMinecraftMergedJar")
		(PROCESS_TASKS - "processMinecraftMergedJar").every { !result.output.contains(it) }
		(INSTALLER_TASKS + PROCESSOR_ANALYSIS_TASKS).every { result.output.contains(it) }
		(MERGED_MINECRAFT_TASKS + INSTALLER_TASKS + PROCESSOR_ANALYSIS_TASKS + MERGED_PROCESSOR_TASKS).every {
			result.task(":" + it) == null
		}
		coldOutputRoots.every { filesUnder(it).empty }
		localMinecraftMavenArtifacts(gradle.projectDir).empty
	}

	def "no-remap setup does not require an intermediary Minecraft provider"() {
		setup:
		def gradle = gradleProject(project: "minimalBaseNoRemap", version: PRE_RELEASE_GRADLE)
		gradle.buildGradle << '''
            dependencies {
                minecraft "com.mojang:minecraft:25w45a_unobfuscated"
            }
        '''

		when:
		def result = gradle.run(task: "help")

		then:
		result.task(":help").outcome == SUCCESS
		(MERGED_MINECRAFT_TASKS + INSTALLER_TASKS + PROCESSOR_ANALYSIS_TASKS + MERGED_PROCESSOR_TASKS).every {
			result.task(":" + it) == null
		}
		localMinecraftMavenArtifacts(gradle.projectDir).empty
	}

	def "compile uses the lazily processed merged Minecraft jar"() {
		setup:
		def gradle = gradleProject(project: "minimalBase")
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
		(MERGED_MINECRAFT_TASKS + INSTALLER_TASKS + PROCESSOR_ANALYSIS_TASKS + MERGED_PROCESSOR_TASKS).every {
			helpResult.task(":" + it) == null
		}
		helpResult.task(":remapModImplementationCompileDependencies") == null
		!outputJar.exists()
		taskOutputRoots(gradle).every { filesUnder(it).empty }
		localMinecraftMavenArtifacts(gradle.projectDir).empty

		when:
		def firstCompile = gradle.run(task: "compileJava")
		def secondCompile = gradle.run(task: "compileJava")

		then:
		MERGED_MINECRAFT_TASKS.every { FIRST_RUN_OUTCOMES.contains(firstCompile.task(":" + it)?.outcome) }
		(INSTALLER_TASKS + MERGED_PROCESSOR_TASKS).every { FIRST_RUN_OUTCOMES.contains(firstCompile.task(":" + it)?.outcome) }
		FIRST_RUN_OUTCOMES.contains(firstCompile.task(":remapModImplementationCompileDependencies").outcome)
		firstCompile.task(":compileJava").outcome == SUCCESS
		assertTaskOrder(firstCompile, MERGED_RAW_TASK_ORDER)
		outputJar.isFile()
		localMinecraftMavenArtifacts(gradle.projectDir).empty
		MERGED_MINECRAFT_TASKS.every { REUSED_OUTCOMES.contains(secondCompile.task(":" + it)?.outcome) }
		(INSTALLER_TASKS + MERGED_PROCESSOR_TASKS).every { REUSED_OUTCOMES.contains(secondCompile.task(":" + it)?.outcome) }
		REUSED_OUTCOMES.contains(secondCompile.task(":remapModImplementationCompileDependencies").outcome)
		REUSED_OUTCOMES.contains(secondCompile.task(":compileJava").outcome)
		secondCompile.output.contains("Reusing configuration cache.")
	}

	def "processor settings contribute to the Minecraft output identity"() {
		setup:
		def gradle = gradleProject(project: "minimalBase")
		configureMinecraft(gradle, "remapJsrAnnotationsToJetBrains = false")
		gradle.buildGradle << '''
            afterEvaluate {
                def processMinecraftOutput = tasks.named("processMinecraftMergedJar", net.fabricmc.loom.task.ProcessMinecraftJarTask)
                println("PROCESS_MINECRAFT_OUTPUT=" + processMinecraftOutput.get().outputJar.get().asFile.absolutePath)
            }
        '''

		when:
		def jsrDisabledOutput = processMinecraftOutput(gradle.run(task: "help"))
		gradle.buildGradle.text = gradle.buildGradle.text.replace(
				"remapJsrAnnotationsToJetBrains = false",
				"remapJsrAnnotationsToJetBrains = true"
				)
		def jsrEnabledOutput = processMinecraftOutput(gradle.run(task: "help"))

		then:
		jsrDisabledOutput.parentFile.canonicalFile != jsrEnabledOutput.parentFile.canonicalFile
		!jsrDisabledOutput.exists()
		!jsrEnabledOutput.exists()
	}

	@Unroll
	def "registers process tasks for the #configurationName jar configuration"() {
		setup:
		def gradle = gradleProject(project: "minimalBase")
		configureMinecraft(gradle, loomConfiguration, true)
		def expectedRawTasks = RAW_TASKS_BY_CONFIGURATION[configurationName]
		def rawTaskOrder = RAW_TASK_ORDER_BY_CONFIGURATION[configurationName]

		when:
		def result = gradle.run(tasks: ["tasks", "--all"])
		def firstCompile = gradle.run(task: "compileJava")
		def secondCompile = gradle.run(task: "compileJava")

		then:
		result.task(":tasks").outcome == SUCCESS
		result.output.contains(PROCESS_MINECRAFT_JARS)
		expectedTasks.every { result.output.contains(it) }
		(PROCESS_TASKS - expectedTasks).every { !result.output.contains(it) }
		SHARED_RAW_TASKS.every { FIRST_RUN_OUTCOMES.contains(firstCompile.task(":" + it)?.outcome) }
		expectedRawTasks.every { FIRST_RUN_OUTCOMES.contains(firstCompile.task(":" + it)?.outcome) }
		(RAW_SIDE_TASKS - expectedRawTasks).every { firstCompile.task(":" + it) == null }
		expectedTasks.every { FIRST_RUN_OUTCOMES.contains(firstCompile.task(":" + it).outcome) }
		(PROCESS_TASKS - expectedTasks).every { firstCompile.task(":" + it) == null }
		firstCompile.task(":compileJava").outcome == SUCCESS
		assertTaskOrder(firstCompile, rawTaskOrder)
		SHARED_RAW_TASKS.every { REUSED_OUTCOMES.contains(secondCompile.task(":" + it)?.outcome) }
		expectedRawTasks.every { REUSED_OUTCOMES.contains(secondCompile.task(":" + it)?.outcome) }
		expectedTasks.every { REUSED_OUTCOMES.contains(secondCompile.task(":" + it).outcome) }

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

		when:
		def commonCompile = gradle.run(task: "compileJava")
		def clientCompile = gradle.run(task: "compileClientJava")

		then:
		FIRST_RUN_OUTCOMES.contains(commonCompile.task(":processMinecraftCommonJar").outcome)
		commonCompile.task(":processMinecraftClientOnlyJar") == null
		commonCompile.task(":compileJava").outcome == SUCCESS
		REUSED_OUTCOMES.contains(clientCompile.task(":processMinecraftCommonJar").outcome)
		FIRST_RUN_OUTCOMES.contains(clientCompile.task(":processMinecraftClientOnlyJar").outcome)
		clientCompile.task(":compileClientJava").outcome == SUCCESS
	}

	@Unroll
	def "#configurationName named jar client annotation is #expected"() {
		setup:
		def gradle = gradleProject(project: "minimalBase")
		configureMinecraft(gradle, loomConfiguration)
		gradle.buildGradle << """
			afterEvaluate {
				def remap = tasks.named("${taskName}", net.fabricmc.loom.task.RemapMinecraftJarTask).get()
				println("APPLY_CLIENT_ONLY_ANNOTATION=" + remap.applyClientOnlyAnnotation.get())
			}
		"""

		when:
		def result = gradle.run(task: "help")

		then:
		result.task(":help").outcome == SUCCESS
		result.output.contains("APPLY_CLIENT_ONLY_ANNOTATION=${expected}")

		where:
		configurationName | loomConfiguration          | taskName                              || expected
		"client-only"     | "clientOnlyMinecraftJar()" | "remapMinecraftClientToNamed"         || false
		"split client"    | "splitMinecraftJar()"      | "remapMinecraftClientOnlyToNamed"     || true
	}

	def "processed Minecraft jars do not use the project local Minecraft Maven"() {
		setup:
		def gradle = gradleProject(project: "accesswidener")
		def processedMinecraft = new File(gradle.projectDir, ".gradle/loom-cache/minecraft/processed")
		def processorAnalysis = new File(gradle.projectDir, ".gradle/loom-cache/minecraft/processor-analysis")
		def installerData = new File(gradle.projectDir, ".gradle/loom-cache/installer-data")

		when:
		def help = gradle.run(task: "help")
		def processedFiles = filesUnder(processedMinecraft)

		then:
		help.task(":help").outcome == SUCCESS
		(INSTALLER_TASKS + PROCESSOR_ANALYSIS_TASKS + MERGED_PROCESSOR_TASKS).every { help.task(":" + it) == null }
		localMinecraftMavenArtifacts(gradle.projectDir).empty
		processedFiles.empty
		filesUnder(processorAnalysis).empty
		filesUnder(installerData).empty

		when:
		def result = gradle.run(task: "compileJava")
		processedFiles = filesUnder(processedMinecraft)

		then:
		FIRST_RUN_OUTCOMES.contains(result.task(":processMinecraftMergedJar").outcome)
		(INSTALLER_TASKS + MERGED_PROCESSOR_TASKS).every { FIRST_RUN_OUTCOMES.contains(result.task(":" + it)?.outcome) }
		result.task(":compileJava").outcome == SUCCESS
		localMinecraftMavenArtifacts(gradle.projectDir).empty
		processedFiles.any { it.name.endsWith(".jar") }
		processedFiles.every { !it.name.endsWith(".jar.backup") }
		processedFiles.every { !it.name.endsWith(".pom") }
		filesUnder(processorAnalysis).any { it.name.endsWith(".json") }
		filesUnder(installerData).any { it.name == "descriptor.json" }
	}

	@RestoreSystemProperties
	def "idea sync processes Minecraft without run configurations"() {
		setup:
		System.setProperty("idea.sync.active", "true")
		def gradle = gradleProject(project: "minimalBase")
		configureMinecraft(gradle, "runs.clear()")
		new File(gradle.projectDir, ".idea").mkdirs()

		when:
		def result = gradle.run(tasks: [])

		then:
		FIRST_RUN_OUTCOMES.contains(result.task(":processMinecraftMergedJar").outcome)
		result.task(":processMinecraftJars").outcome == SUCCESS
		result.task(":ideaSyncTask").outcome == SUCCESS
		result.task(":genSourcesWithVineflower") == null
	}

	def "generated sources are adjacent and attached to the Eclipse classpath"() {
		setup:
		def gradle = gradleProject(project: "minimalBase", warningMode: "none")
		configureMinecraft(gradle)
		gradle.buildGradle << '''
            afterEvaluate {
                def processMinecraft = tasks.named("processMinecraftMergedJar", net.fabricmc.loom.task.ProcessMinecraftJarTask).get()
                println("PROCESS_MINECRAFT_INPUT=" + processMinecraft.inputJar.get().asFile.absolutePath)
                println("PROCESS_MINECRAFT_OUTPUT=" + processMinecraft.outputJar.get().asFile.absolutePath)
                println("LINE_MAPPED_MINECRAFT_INPUT=" + processMinecraft.lineMappedInputCandidates.singleFile.absolutePath)
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
		def lineMappedJar = processMinecraftPath(first, "LINE_MAPPED_MINECRAFT_INPUT=")
		def backupJar = new File(inputJar.parentFile, inputJar.name + ".backup")
		def lineMap = new File(lineMappedJar.parentFile, lineMappedJar.name + ".linemap.txt")
		def lineMappedInputHash = new File(lineMappedJar.parentFile, lineMappedJar.name + ".input.sha256")
		def sourcesWorkJar = new File(lineMappedJar.parentFile, "minecraft-merged-sources.jar")
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
		lineMappedJar.isFile()
		lineMap.isFile()
		lineMappedInputHash.isFile()
		sourcesWorkJar.isFile()
		lineMappedJar.parentFile.canonicalFile != outputJar.parentFile.canonicalFile
		outputJar.canonicalFile.toPath().startsWith(taskBasedCache.canonicalFile.toPath())
		outputFiles == [
			"minecraft-merged.jar",
			"minecraft-merged-sources.jar"
		] as Set
		Files.mismatch(inputJar.toPath(), backupJar.toPath()) == -1
		Files.mismatch(lineMappedJar.toPath(), outputJar.toPath()) == -1
		minecraftEntry.size() == 1
		new File(minecraftEntry.@path.text()).canonicalFile == outputJar.canonicalFile
		new File(minecraftEntry.@sourcepath.text()).canonicalFile == sourcesJar.canonicalFile

		when:
		Files.writeString(sourcesJar.toPath(), "corrupt")
		def repairedSources = gradle.run(task: "processMinecraftMergedJar", configurationCache: false)

		then:
		repairedSources.task(":processMinecraftMergedJar").outcome == SUCCESS
		Files.mismatch(sourcesWorkJar.toPath(), sourcesJar.toPath()) == -1

		when:
		Files.delete(lineMappedInputHash.toPath())
		def withoutSourceProvenance = gradle.run(task: "processMinecraftMergedJar", configurationCache: false)

		then:
		withoutSourceProvenance.task(":processMinecraftMergedJar").outcome == SUCCESS
		outputJar.isFile()
		!sourcesJar.exists()
		sourcesWorkJar.isFile()
		Files.mismatch(inputJar.toPath(), outputJar.toPath()) == -1
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

	private static void assertTaskOrder(BuildResult result, List<List<String>> taskOrder) {
		def executedTasks = result.tasks*.path

		taskOrder.each { pair ->
			def before = ":" + pair[0]
			def after = ":" + pair[1]
			assert executedTasks.indexOf(before) >= 0
			assert executedTasks.indexOf(after) >= 0
			assert executedTasks.indexOf(before) < executedTasks.indexOf(after)
		}
	}

	private static List<File> taskOutputRoots(GradleProjectTestTrait.GradleProject gradle) {
		def outputRoots = [
			new File(gradle.projectDir, ".gradle/loom-cache/minecraft"),
			new File(gradle.projectDir, ".gradle/loom-cache/installer-data"),
			new File(gradle.projectDir, "build/loom-cache/remapped-mods")
		]
		[
			gradle.projectDir.absolutePath,
			gradle.projectDir.canonicalPath
		].toSet().each { projectPath ->
			String projectHash = Checksum.of(projectPath + "::").sha1().hex()
			outputRoots.add(new File(gradle.gradleHomeDir, "caches/fabric-loom/taskBasedMinecraft/${projectHash}"))
		}

		return outputRoots
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

	private static List<File> localMinecraftMavenArtifacts(File projectDir) {
		return filesUnder(new File(projectDir, ".gradle/loom-cache/minecraftMaven")).findAll {
			it.name.endsWith(".jar") || it.name.endsWith(".pom")
		}
	}
}
