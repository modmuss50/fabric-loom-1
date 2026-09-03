/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2024 FabricMC
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

import java.nio.file.Path

import org.intellij.lang.annotations.Language
import spock.lang.Specification
import spock.lang.Unroll

import net.fabricmc.loom.configuration.mods.dependency.LocalMavenHelper
import net.fabricmc.loom.test.LoomTestVersions
import net.fabricmc.loom.test.unit.sandbox.SandboxEntrypoint
import net.fabricmc.loom.test.util.GradleProjectTestTrait
import net.fabricmc.loom.test.util.ZipTestUtils
import net.fabricmc.loom.util.Constants
import net.fabricmc.loom.util.ZipUtils

import static net.fabricmc.loom.test.LoomTestConstants.STANDARD_TEST_VERSIONS
import static org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

class SandboxTest extends Specification implements GradleProjectTestTrait {
	@Unroll
	def "sandbox (gradle #version)"() {
		setup:
		def gradle = gradleProject(project: "minimalBase", version: version)
		def sandboxVersion = "1.0.0-${UUID.randomUUID()}"
		gradle.buildGradle << """
                repositories {
					mavenLocal()
				}

                dependencies {
                    minecraft "com.mojang:minecraft:1.20.4"
                    mappings "net.fabricmc:yarn:1.20.4+build.3:v2"
                    modImplementation "${LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
                }
            """
		new File(gradle.getProjectDir(), "gradle.properties").text = "${Constants.Properties.SANDBOX}=net.fabricmc.loom.test:sandbox:${sandboxVersion}"

		when:
		def helpResult = gradle.run(task: "help", args: ["--offline"])

		then:
		helpResult.task(":help").outcome == SUCCESS
		helpResult.task(":scanFabricSandbox") == null
		!new File(gradle.projectDir, "build/loom-cache/sandbox/main-class.txt").exists()

		when:
		publishDummySandbox(gradle, sandboxVersion)
		def result = gradle.run(task: "runClientSandbox")

		then:
		result.task(":runClientSandbox").outcome == SUCCESS
		result.task(":scanFabricSandbox").outcome in [
			SUCCESS,
			FROM_CACHE,
			UP_TO_DATE
		]
		result.output.contains("Running real main: net.fabricmc.loader.impl.launch.knot.KnotClient")
		// Ensure that we weren't launched via DLI
		!result.output.contains("at net.fabricmc.devlaunchinjector.Main")

		where:
		version << STANDARD_TEST_VERSIONS
	}

	def "sandbox metadata is available to IntelliJ sync"() {
		setup:
		def gradle = gradleProject(project: "minimalBase")
		def sandboxVersion = "1.0.0-${UUID.randomUUID()}"
		gradle.buildGradle << """
			repositories {
				mavenLocal()
			}

			loom.runs.configureEach {
				preferGradleTask = false
			}

			dependencies {
				minecraft "com.mojang:minecraft:1.20.4"
				mappings "net.fabricmc:yarn:1.20.4+build.3:v2"
				modImplementation "${LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
			}
		"""
		gradle.gradleProperties.text = "${Constants.Properties.SANDBOX}=net.fabricmc.loom.test:sandbox:${sandboxVersion}"
		publishDummySandbox(gradle, sandboxVersion)

		when:
		def result = gradle.run(task: "ideaSyncTask")

		then:
		result.task(":scanFabricSandbox").outcome in [
			SUCCESS,
			FROM_CACHE,
			UP_TO_DATE
		]
		def runConfig = new File(gradle.projectDir, ".idea/runConfigurations/Client_Sandbox.xml")
		runConfig.exists()
		runConfig.text.contains("net.fabricmc.loom.test.unit.sandbox.SandboxEntrypoint")
	}

	private static void publishDummySandbox(GradleProjectTestTrait.GradleProject gradle, String version) {
		def mavenHelper = new LocalMavenHelper("net.fabricmc.loom.test", "sandbox", version, null, gradle.mavenLocalDir.toPath())
		mavenHelper.copyToMaven(createDummySandboxJar(), null)
		mavenHelper.savePom()
	}

	static Path createDummySandboxJar() {
		def zip = ZipTestUtils.createZip(["fabric-sandbox.json": METADATA_JSON], ".jar")
		ZipUtils.add(zip, "net/fabricmc/loom/test/unit/sandbox/SandboxEntrypoint.class", getClassBytes(SandboxEntrypoint.class))
		return zip
	}

	static byte[] getClassBytes(Class<?> clazz) {
		return clazz.classLoader.getResourceAsStream(clazz.name.replace('.', '/') + ".class").withCloseable {
			it.bytes
		}
	}

	// Ensure that all platforms that the test may run on are listed here
	@Language("json")
	private static String METADATA_JSON = """
		{
			"version": 1,
			"mainClass": "net.fabricmc.loom.test.unit.sandbox.SandboxEntrypoint",
			"platforms": {
				"windows": [
					"arm64",
					"x86_64"
				],
				"macos": [
					"arm64",
					"x86_64"
				],
				"linux": [
					"arm64",
					"x86_64"
				]
			}
		}
	"""
}
