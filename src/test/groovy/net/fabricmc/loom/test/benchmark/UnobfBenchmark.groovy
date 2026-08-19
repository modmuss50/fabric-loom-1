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

package net.fabricmc.loom.test.benchmark

import groovy.transform.Immutable
import org.apache.commons.io.FileUtils
import org.gradle.util.GradleVersion

import net.fabricmc.loom.test.LoomTestVersions
import net.fabricmc.loom.test.util.GradleProjectTestTrait

/**
 * Run with {@code ./gradlew unobfBenchmark}. The first run populates the dependency caches and
 * warms each lifecycle scenario before measurements are recorded.
 */
@Singleton
class UnobfBenchmark implements GradleProjectTestTrait {
	private static final String SENTINEL = ".loom-unobf-benchmark"
	private static final int WARMUPS = 2
	private static final int ITERATIONS = 5
	private static final int RESOURCE_SIZE = 8 * 1024 * 1024
	private static int sourceVersion

	def run(File dir, boolean profile) {
		prepareBenchmarkDir(dir)
		def gradle = gradleProject(
				project: "minimalBaseNoRemap",
				version: GradleVersion.current().version,
				projectDir: new File(dir, "project"),
				gradleHomeDir: new File(dir, "gradlehome")
				)

		gradle.buildGradle << """
                dependencies {
                    minecraft "com.mojang:minecraft:26.1-snapshot-1"
                    implementation "net.fabricmc.fabric-api:fabric-api:0.140.3+26.1"
                    implementation "${LoomTestVersions.FABRIC_LOADER.mavenNotation()}"
                }
            """

		def source = new File(gradle.projectDir, "src/main/java/com/example/BenchmarkMod.java")
		source.parentFile.mkdirs()
		source.text = '''package com.example;

public final class BenchmarkMod {
	public static final int ITERATION = 0;

	private BenchmarkMod() {
	}
}
'''
		def resource = new File(gradle.projectDir, "src/main/resources/benchmark.bin")
		resource.parentFile.mkdirs()
		def resourceBytes = new byte[RESOURCE_SIZE]
		new Random(0).nextBytes(resourceBytes)
		resource.bytes = resourceBytes

		def scenarios = [
			new Scenario("loom-cache-rebuild", ["build", "--rerun-tasks"], false, true, false),
			new Scenario("full-configuration", ["help"], false, false, false),
			new Scenario("configuration-cache-reuse", ["help"], false, false, true),
			new Scenario("clean-build", ["clean", "build"], false, false, true),
			new Scenario("no-op-build", ["build"], false, false, true),
			new Scenario("source-change", ["build"], true, false, true),
			new Scenario("launch-setup", ["configureClientLaunch"], false, false, true)
		]
		def coldScenario = scenarios.first()
		def warmScenarios = scenarios.tail()

		// Populate shared caches before scenario-specific warmups so network time is not measured.
		runScenario(gradle, scenarios[3], source, -1, false, dir, "cache-population")
		WARMUPS.times { iteration ->
			warmScenarios.each { scenario ->
				runScenario(gradle, scenario, source, iteration, profile, dir, "warmup-${iteration}")
			}
		}

		def results = []
		ITERATIONS.times { iteration ->
			warmScenarios.each { scenario ->
				results << runScenario(gradle, scenario, source, iteration, profile, dir, "iteration-${iteration}")
			}
		}
		WARMUPS.times { iteration ->
			runScenario(gradle, coldScenario, source, iteration, profile, dir, "warmup-${iteration}")
		}
		ITERATIONS.times { iteration ->
			results << runScenario(gradle, coldScenario, source, iteration, profile, dir, "iteration-${iteration}")
		}

		def output = new File(dir, profile ? "results-profiled.csv" : "results.csv")
		output.parentFile.mkdirs()
		output.text = "scenario,iteration,duration_ms,configuration_cache_reused,task_outcomes\n" + results.collect {
			"${it.scenario},${it.iteration},${it.durationMs},${it.configurationCacheReused},${it.taskOutcomes}"
		}.join("\n") + "\n"
		new File(dir, "environment.properties").text = """gradle=${GradleVersion.current().version}
java=${System.getProperty('java.version')}
os=${System.getProperty('os.name')} ${System.getProperty('os.arch')}
minecraft=26.1-snapshot-1
fabricApi=0.140.3+26.1
fabricLoader=${LoomTestVersions.FABRIC_LOADER.version()}
loomRevision=${loomRevision()}
profile=${profile}
fixtureResourceBytes=${RESOURCE_SIZE}
"""

		results.groupBy { it.scenario }.each { scenario, measurements ->
			def durations = measurements.collect { it.durationMs }.sort()
			println("${scenario}: median ${durations[durations.size().intdiv(2)]} ms (${durations.join(', ')} ms)")
		}
		println("Results: ${output.absolutePath}")
	}

	static void main(String[] args) {
		if (args.length == 0) {
			throw new IllegalArgumentException("Expected a benchmark working directory")
		}

		getInstance().run(new File(args[0]), args.contains("--profile"))
	}

	private static Measurement runScenario(GradleProject gradle, Scenario scenario, File source, int iteration, boolean profile, File dir, String profileLabel) {
		if (scenario.clearLoomCache) {
			deleteBenchmarkDirectory(dir, new File(gradle.projectDir, ".gradle/loom-cache/minecraftMaven"))
			deleteBenchmarkDirectory(dir, new File(gradle.gradleHomeDir, "caches/fabric-loom/minecraftMaven"))
		}

		if (scenario.mutateSource) {
			source.text = source.text.replaceFirst(/ITERATION = \d+/, "ITERATION = ${++sourceVersion}")
		}

		def args = profile ? ["--profile"] : []
		def reportDir = new File(gradle.projectDir, "build/reports/profile")

		if (profile) {
			deleteBenchmarkDirectory(dir, reportDir)
		}

		def start = System.nanoTime()
		def result = gradle.run(tasks: scenario.tasks, args: args, configurationCache: scenario.configurationCache)
		def durationMs = (System.nanoTime() - start).intdiv(1_000_000L)

		if (profile) {
			def destination = new File(dir, "profiles/${scenario.name}-${profileLabel}")
			deleteBenchmarkDirectory(dir, destination)
			FileUtils.copyDirectory(reportDir, destination)
		}

		def taskOutcomes = result.tasks.collect { "${it.path}=${it.outcome}" }.join(";")
		def configurationCacheReused = result.output.contains("Reusing configuration cache.")
		return new Measurement(scenario.name, iteration, durationMs, configurationCacheReused, taskOutcomes)
	}

	private static void prepareBenchmarkDir(File dir) {
		if (dir.exists() && !dir.isDirectory()) {
			throw new IllegalArgumentException("Benchmark path is not a directory: ${dir}")
		}

		if (dir.exists() && java.nio.file.Files.isSymbolicLink(dir.toPath())) {
			throw new IllegalArgumentException("Benchmark directory must not be a symbolic link: ${dir}")
		}

		def sentinel = new File(dir, SENTINEL)

		if (dir.exists() && dir.listFiles().length > 0 && !sentinel.isFile()) {
			throw new IllegalArgumentException("Refusing to use an existing directory not created by this benchmark: ${dir}")
		}

		dir.mkdirs()
		sentinel.createNewFile()
		["project", "gradlehome"].each { name ->
			def child = new File(dir, name)

			if (child.exists() && java.nio.file.Files.isSymbolicLink(child.toPath())) {
				throw new IllegalArgumentException("Benchmark path must not be a symbolic link: ${child}")
			}
		}
	}

	private static void deleteBenchmarkDirectory(File benchmarkDir, File target) {
		def root = benchmarkDir.canonicalFile.toPath()
		def resolvedTarget = target.canonicalFile.toPath()

		if (!resolvedTarget.startsWith(root) || resolvedTarget == root) {
			throw new IllegalArgumentException("Refusing to delete path outside the benchmark directory: ${target}")
		}

		FileUtils.deleteDirectory(target)
	}

	private static String loomRevision() {
		def process = ["git", "rev-parse", "HEAD"].execute()
		return process.waitFor() == 0 ? process.text.trim() : "unknown"
	}

	@Immutable
	private static class Scenario {
		String name
		List<String> tasks
		boolean mutateSource
		boolean clearLoomCache
		boolean configurationCache
	}

	@Immutable
	private static class Measurement {
		String scenario
		int iteration
		long durationMs
		boolean configurationCacheReused
		String taskOutcomes
	}
}
