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

import spock.lang.Specification

import net.fabricmc.loom.test.LoomTestVersions
import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

class MinecraftJarProcessingTaskTest extends Specification implements GradleProjectTestTrait {
	def "source generation remains available without processing tasks"() {
		given:
		def gradle = gradleProject(project: "minimalBase")
		gradle.buildGradle << """
            dependencies {
                minecraft "com.mojang:minecraft:1.18.1"
                mappings "net.fabricmc:yarn:1.18.1+build.18:v2"
                modImplementation "${LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
            }
        """

		when:
		def sources = gradle.run(task: "genSourcesWithVineflower", configurationCache: false,
		args: ["-Dfabric.loom.ci=true"])

		then:
		sources.task(":genSourcesWithVineflower").outcome == SUCCESS
	}

	def "split source sets use their matching processing tasks"() {
		given:
		def gradle = gradleProject(project: "minimalBase")
		gradle.gradleProperties << "\nfabric.loom.experimental.minecraftJarProcessing.generateSources=false\n"
		gradle.buildGradle << """
            loom {
                splitMinecraftJar()
                splitEnvironmentSourceSets()
            }

            dependencies {
                minecraft "com.mojang:minecraft:1.18.1"
                mappings "net.fabricmc:yarn:1.18.1+build.18:v2"
                modImplementation "${LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
            }
        """
		gradle.buildGradle << '''
            import java.nio.file.Files
            import java.nio.file.StandardCopyOption
            import org.gradle.api.tasks.TaskAction
            import net.fabricmc.loom.api.processor.MinecraftJarProcessingTask
            import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar

            abstract class CopyMinecraftJar extends MinecraftJarProcessingTask {
                @TaskAction
                void copyJar() {
                    def output = outputJar.get().asFile.toPath()
                    Files.createDirectories(output.parent)
                    Files.copy(inputJar.get().asFile.toPath(), output, StandardCopyOption.REPLACE_EXISTING)
                }
            }

            def common = tasks.register("processCommonMinecraft", CopyMinecraftJar) {
                outputJar = layout.buildDirectory.file("minecraft/common.jar")
            }
            def clientOnly = tasks.register("processClientOnlyMinecraft", CopyMinecraftJar) {
                outputJar = layout.buildDirectory.file("minecraft/client-only.jar")
            }
            loom.addMinecraftJarProcessingTask(MinecraftJar.Type.COMMON, common)
            loom.addMinecraftJarProcessingTask(MinecraftJar.Type.CLIENT_ONLY, clientOnly)
        '''
		def commonSource = new File(gradle.projectDir, "src/main/java/UsesCommonMinecraft.java")
		commonSource.parentFile.mkdirs()
		commonSource.text = '''
            import net.minecraft.block.Block;

            public final class UsesCommonMinecraft {
                private Block block;
            }
        '''
		def clientSource = new File(gradle.projectDir, "src/client/java/UsesClientMinecraft.java")
		clientSource.parentFile.mkdirs()
		clientSource.text = '''
            import net.minecraft.client.MinecraftClient;

            public final class UsesClientMinecraft {
                private MinecraftClient client;
            }
        '''

		when:
		def commonCompile = gradle.run(task: "compileJava", configurationCache: false)
		def clientCompile = gradle.run(task: "compileClientJava", configurationCache: false)
		def disabledSources = gradle.run(task: "genCommonSourcesWithVineflower", configurationCache: false, expectFailure: true)
		def enabledCommonCompile = gradle.run(task: "compileJava", configurationCache: false,
		args: [
			"-Pfabric.loom.experimental.minecraftJarProcessing.generateSources=true"
		])
		def enabledClientCompile = gradle.run(task: "compileClientJava", configurationCache: false,
		args: [
			"-Pfabric.loom.experimental.minecraftJarProcessing.generateSources=true"
		])

		then:
		commonCompile.task(":processCommonMinecraft").outcome == SUCCESS
		commonCompile.task(":processClientOnlyMinecraft") == null
		commonCompile.task(":genCommonSourcesWithVineflower") == null
		commonCompile.task(":compileJava").outcome == SUCCESS
		clientCompile.task(":processCommonMinecraft").outcome == UP_TO_DATE
		clientCompile.task(":processClientOnlyMinecraft").outcome == SUCCESS
		clientCompile.task(":genClientOnlySourcesWithVineflower") == null
		clientCompile.task(":compileClientJava").outcome == SUCCESS
		disabledSources.output.contains("Minecraft source generation is disabled for task-based jar processing")
		enabledCommonCompile.task(":genCommonSourcesWithVineflower").outcome == SUCCESS
		enabledCommonCompile.task(":genClientOnlySourcesWithVineflower") == null
		enabledClientCompile.task(":genCommonSourcesWithVineflower").outcome == UP_TO_DATE
		enabledClientCompile.task(":genClientOnlySourcesWithVineflower").outcome == SUCCESS
	}

	def "registered processing tasks form an ordered compile classpath chain"() {
		given:
		def gradle = gradleProject(project: "minimalBase")
		gradle.buildGradle << """
            dependencies {
                minecraft "com.mojang:minecraft:1.18.1"
                mappings "net.fabricmc:yarn:1.18.1+build.18:v2"
                modImplementation "${LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
            }
        """
		gradle.buildGradle << '''
            import java.nio.file.Files
            import java.nio.file.StandardCopyOption
            import org.gradle.api.tasks.TaskAction
            import net.fabricmc.loom.api.processor.MinecraftJarProcessingTask
            import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar

            abstract class CopyMinecraftJar extends MinecraftJarProcessingTask {
                @TaskAction
                void copyJar() {
                    def output = outputJar.get().asFile.toPath()
                    Files.createDirectories(output.parent)
                    Files.copy(inputJar.get().asFile.toPath(), output, StandardCopyOption.REPLACE_EXISTING)
                }
            }

            def first = tasks.register("firstMinecraftStep", CopyMinecraftJar) {
                outputJar = layout.buildDirectory.file("minecraft/first.jar")
            }
            def second = tasks.register("secondMinecraftStep", CopyMinecraftJar) {
                outputJar = layout.buildDirectory.file("minecraft/second.jar")
            }
            loom.addMinecraftJarProcessingTask(MinecraftJar.Type.MERGED, first)
            loom.addMinecraftJarProcessingTask(MinecraftJar.Type.MERGED, second)

            tasks.register("inspectNamedMinecraftJars") {
                inputs.files(loom.namedMinecraftJars)
                doLast {
                    println("NAMED_JARS=" + loom.namedMinecraftJars.files*.name.join(","))
                }
            }
        '''
		def source = new File(gradle.projectDir, "src/main/java/UsesMinecraft.java")
		source.parentFile.mkdirs()
		source.text = '''
            import net.minecraft.block.Block;

            public final class UsesMinecraft {
                private Block block;
            }
        '''

		when:
		def firstCompile = gradle.run(task: "compileJava", configurationCache: false)
		def secondCompile = gradle.run(task: "compileJava", configurationCache: false)
		def namedJars = gradle.run(task: "inspectNamedMinecraftJars", configurationCache: false)
		def sources = gradle.run(task: "genSourcesWithVineflower", configurationCache: false)
		def ciCompile = gradle.run(task: "compileJava", configurationCache: false, args: ["-Dfabric.loom.ci=true"])
		def ciSources = gradle.run(task: "genSourcesWithVineflower", configurationCache: false,
		args: ["-Dfabric.loom.ci=true"], expectFailure: true)
		def forcedCiCompile = gradle.run(task: "compileJava", configurationCache: false,
		args: [
			"-Dfabric.loom.ci=true",
			"-Pfabric.loom.experimental.minecraftJarProcessing.generateSources=true"
		])

		then:
		firstCompile.task(":firstMinecraftStep").outcome == SUCCESS
		firstCompile.task(":secondMinecraftStep").outcome == SUCCESS
		firstCompile.task(":genSourcesWithVineflower").outcome == SUCCESS
		firstCompile.task(":compileJava").outcome == SUCCESS
		new File(gradle.projectDir, "build/minecraft/second.jar").isFile()
		secondCompile.task(":firstMinecraftStep").outcome == UP_TO_DATE
		secondCompile.task(":secondMinecraftStep").outcome == UP_TO_DATE
		secondCompile.task(":genSourcesWithVineflower").outcome == UP_TO_DATE
		namedJars.task(":secondMinecraftStep").outcome == UP_TO_DATE
		namedJars.output.contains("NAMED_JARS=second-line-mapped.jar")
		sources.task(":firstMinecraftStep").outcome == UP_TO_DATE
		sources.task(":secondMinecraftStep").outcome == UP_TO_DATE
		sources.task(":genSourcesWithVineflower").outcome == UP_TO_DATE
		new File(gradle.projectDir, "build/minecraft/second-sources.jar").isFile()
		new File(gradle.projectDir, "build/minecraft/second-line-mapped.jar").isFile()
		Files.mismatch(
				new File(gradle.projectDir, "build/minecraft/first.jar").toPath(),
				new File(gradle.projectDir, "build/minecraft/second.jar").toPath()
		) == -1
		ciCompile.task(":genSourcesWithVineflower") == null
		ciCompile.task(":compileJava").outcome in [SUCCESS, UP_TO_DATE]
		ciSources.output.contains("Minecraft source generation is disabled for task-based jar processing")
		forcedCiCompile.task(":genSourcesWithVineflower").outcome == UP_TO_DATE
	}
}
