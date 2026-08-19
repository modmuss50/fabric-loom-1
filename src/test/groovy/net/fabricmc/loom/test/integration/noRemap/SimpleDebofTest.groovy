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

import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

import org.intellij.lang.annotations.Language
import spock.lang.Specification
import spock.lang.Unroll

import net.fabricmc.loom.test.LoomTestVersions
import net.fabricmc.loom.test.util.GradleProjectTestTrait
import net.fabricmc.loom.util.Checksum

import static net.fabricmc.loom.test.LoomTestConstants.PRE_RELEASE_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class SimpleDebofTest extends Specification implements GradleProjectTestTrait {
	@Unroll
	def "build"() {
		setup:
		def gradle = gradleProject(project: "minimalBaseNoRemap", version: PRE_RELEASE_GRADLE)
		gradle.buildGradle << """
				dependencies {
					minecraft 'com.mojang:minecraft:25w45a_unobfuscated'
					implementation "${LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
					implementation "net.fabricmc.fabric-api:fabric-api:0.138.3+1.21.11_unobfuscated"
                }
		"""
		def sourceFile = new File(gradle.projectDir, "src/main/java/example/Test.java")
		sourceFile.parentFile.mkdirs()
		@Language("JAVA") String src =  """
		package example;

		import net.minecraft.resources.Identifier;

		import org.spongepowered.asm.mixin.Mixin; // Make sure we applied loaders deps via the installer data

		public class Test {
			public static void main(String[] args) {
			    Identifier id = Identifier.fromNamespaceAndPath("loom", "test");
			}
		}
		"""
		sourceFile.text = src

		when:
		def result = gradle.run(tasks: [
			"build",
			"configureClientLaunch"
		])

		then:
		result.task(":build").outcome == SUCCESS
		result.task(":configureClientLaunch").outcome == SUCCESS
	}

	@Unroll
	def "split build"() {
		setup:
		def gradle = gradleProject(project: "minimalBaseNoRemap", version: PRE_RELEASE_GRADLE)
		gradle.buildGradle << """
				loom {
					splitEnvironmentSourceSets()
				}

				dependencies {
					minecraft 'com.mojang:minecraft:25w45a_unobfuscated'
					implementation "${LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
                }
		"""
		def sourceFile = new File(gradle.projectDir, "src/main/java/example/Test.java")
		sourceFile.parentFile.mkdirs()
		@Language("JAVA") String src =  """
		package example;

		import net.minecraft.resources.Identifier;

		import org.spongepowered.asm.mixin.Mixin; // Make sure we applied loaders deps via the installer data

		public class Test {
			public static void main(String[] args) {
			    Identifier id = Identifier.fromNamespaceAndPath("loom", "test");
			}
		}
		"""
		sourceFile.text = src

		when:
		def result = gradle.run(task: "build")

		then:
		result.task(":build").outcome == SUCCESS
	}

	@Unroll
	def "genSources split build"() {
		setup:
		def gradle = gradleProject(project: "minimalBaseNoRemap", version: PRE_RELEASE_GRADLE, gradleHomeDir: File.createTempDir())
		gradle.buildGradle << """
				loom {
					splitEnvironmentSourceSets()
				}

				dependencies {
					minecraft 'com.mojang:minecraft:26.1-snapshot-1'
					implementation "${LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
					implementation 'net.fabricmc.fabric-api:fabric-api:0.140.3+26.1'
                }
		"""

		when:
		def result = gradle.run(task: "genSources")

		then:
		result.task(":genSources").outcome == SUCCESS
		findFiles(globalMinecraftMaven(gradle), ".jar.backup").isEmpty()
		findFiles(globalMinecraftMaven(gradle), ".jar.backup.not-required").size() == 2
		findFiles(localMinecraftMaven(gradle), ".jar.backup").size() == 2
	}

	def "processed provider only keeps final minecraft backup"() {
		setup:
		def gradleHome = File.createTempDir()
		def processed = gradleProject(project: "minimalBaseNoRemap", version: PRE_RELEASE_GRADLE, gradleHomeDir: gradleHome)
		configure26_1(processed, true)

		when:
		def initialResult = processed.run(task: "help", configurationCache: false)
		def globalJar = onlyFile(globalMinecraftMaven(processed), ".jar")
		def localJar = onlyFile(localMinecraftMaven(processed), ".jar")
		def localBackup = onlyFile(localMinecraftMaven(processed), ".jar.backup")

		then:
		initialResult.task(":help").outcome == SUCCESS
		findFiles(globalMinecraftMaven(processed), ".jar.backup").isEmpty()
		findFiles(globalMinecraftMaven(processed), ".jar.backup.not-required").size() == 1
		localBackup.isFile()

		when:
		assert localBackup.delete()
		def recoveryResult = processed.run(task: "help", configurationCache: false)

		then:
		recoveryResult.task(":help").outcome == SUCCESS
		localBackup.isFile()

		when:
		def unprocessed = gradleProject(project: "minimalBaseNoRemap", version: PRE_RELEASE_GRADLE, gradleHomeDir: gradleHome)
		configure26_1(unprocessed, false)
		def unprocessedResult = unprocessed.run(task: "help", configurationCache: false)
		def globalBackup = onlyFile(globalMinecraftMaven(unprocessed), ".jar.backup")
		globalJar.setBytes("not a jar".getBytes(StandardCharsets.UTF_8))

		then:
		unprocessedResult.task(":help").outcome == SUCCESS
		globalBackup.isFile()
		findFiles(globalMinecraftMaven(unprocessed), ".jar.backup.not-required").isEmpty()

		when:
		def secondProcessed = gradleProject(project: "minimalBaseNoRemap", version: PRE_RELEASE_GRADLE, gradleHomeDir: gradleHome)
		configure26_1(secondProcessed, true)
		def sharedCacheResult = secondProcessed.run(task: "help", configurationCache: false)
		def secondLocalJar = onlyFile(localMinecraftMaven(secondProcessed), ".jar")

		then:
		sharedCacheResult.task(":help").outcome == SUCCESS
		zipEntryCount(secondLocalJar) > 0
		Checksum.of(localJar).sha256().hex() == Checksum.of(secondLocalJar).sha256().hex()

		when:
		assert globalBackup.delete()
		def recoveredProcessed = gradleProject(project: "minimalBaseNoRemap", version: PRE_RELEASE_GRADLE, gradleHomeDir: gradleHome)
		configure26_1(recoveredProcessed, true)
		def missingBackupResult = recoveredProcessed.run(task: "help", configurationCache: false)
		def recoveredLocalJar = onlyFile(localMinecraftMaven(recoveredProcessed), ".jar")

		then:
		missingBackupResult.task(":help").outcome == SUCCESS
		zipEntryCount(globalJar) > 0
		findFiles(globalMinecraftMaven(recoveredProcessed), ".jar.backup").isEmpty()
		findFiles(globalMinecraftMaven(recoveredProcessed), ".jar.backup.not-required").size() == 1
		Checksum.of(localJar).sha256().hex() == Checksum.of(recoveredLocalJar).sha256().hex()
	}

	private static void configure26_1(GradleProjectTestTrait.GradleProject gradle, boolean processed) {
		gradle.buildGradle << """
				dependencies {
					minecraft 'com.mojang:minecraft:26.1-snapshot-1'
					implementation "${LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
					${processed ? "implementation 'net.fabricmc.fabric-api:fabric-api:0.140.3+26.1'" : ""}
				}
		"""
	}

	private static File globalMinecraftMaven(GradleProjectTestTrait.GradleProject gradle) {
		return new File(gradle.gradleHomeDir, "caches/fabric-loom/minecraftMaven")
	}

	private static File localMinecraftMaven(GradleProjectTestTrait.GradleProject gradle) {
		return new File(gradle.projectDir, ".gradle/loom-cache/minecraftMaven")
	}

	private static List<File> findFiles(File root, String suffix) {
		def files = []

		if (root.isDirectory()) {
			root.eachFileRecurse { file ->
				if (file.isFile() && file.name.endsWith(suffix)) {
					files << file
				}
			}
		}

		return files
	}

	private static File onlyFile(File root, String suffix) {
		def files = findFiles(root, suffix)

		if (files.size() != 1) {
			throw new AssertionError("Expected one ${suffix} file in ${root}, found ${files}")
		}

		return files.first()
	}

	private static int zipEntryCount(File file) {
		return new ZipFile(file).withCloseable { it.size() }
	}
}
