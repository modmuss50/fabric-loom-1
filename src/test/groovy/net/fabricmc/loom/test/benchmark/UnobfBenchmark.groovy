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
	private static final int ITERATIONS = 10
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
			new Scenario("minecraft-provider-rebuild", ["help"], false, true, false, false),
			new Scenario("loom-cache-rebuild", ["build", "--rerun-tasks"], false, true, false, false),
			new Scenario("full-configuration", ["help"], false, false, false, false),
			new Scenario("offline-configuration", ["help"], false, false, false, true),
			new Scenario("gen-sources-configuration", ["genSources", "--dry-run"], false, false, false, false),
			new Scenario("dli-config", [
				"generateDLIConfig",
				"--rerun-tasks"
			], false, false, false, true),
			new Scenario("configuration-cache-reuse", ["help"], false, false, true, false),
			new Scenario("clean-build", ["clean", "build"], false, false, true, false),
			new Scenario("no-op-build", ["build"], false, false, true, false),
			new Scenario("source-change", ["build"], true, false, true, false),
			new Scenario("launch-setup", ["configureClientLaunch"], false, false, true, false)
		]
		def coldScenarios = scenarios.findAll { it.clearLoomCache }
		def warmScenarios = scenarios.findAll { !it.clearLoomCache }

		// Populate shared caches before scenario-specific warmups so network time is not measured.
		runScenario(gradle, scenarios.find { it.name == "clean-build" }, source, -1, false, dir, "cache-population")
		WARMUPS.times { iteration ->
			orderedWarmScenarios(warmScenarios, iteration).each { scenario ->
				runScenario(gradle, scenario, source, iteration, profile, dir, "warmup-${iteration}")
			}
		}

		def results = []
		ITERATIONS.times { iteration ->
			orderedWarmScenarios(warmScenarios, iteration).each { scenario ->
				results << runScenario(gradle, scenario, source, iteration, profile, dir, "iteration-${iteration}")
			}
		}
		WARMUPS.times { iteration ->
			orderedColdScenarios(coldScenarios, iteration).each { scenario ->
				runScenario(gradle, scenario, source, iteration, profile, dir, "warmup-${iteration}")
			}
		}
		ITERATIONS.times { iteration ->
			orderedColdScenarios(coldScenarios, iteration).each { scenario ->
				results << runScenario(gradle, scenario, source, iteration, profile, dir, "iteration-${iteration}")
			}
		}

		def output = new File(dir, profile ? "results-profiled.csv" : "results.csv")
		output.parentFile.mkdirs()
		output.text = "scenario,iteration,duration_ms,configuration_cache_reused,minecraft_cache_files,minecraft_cache_jars,minecraft_cache_backups,minecraft_cache_logical_bytes,minecraft_cache_allocated_kib,task_outcomes\n" + results.collect {
			"${it.scenario},${it.iteration},${it.durationMs},${it.configurationCacheReused},${it.cache.files},${it.cache.jars},${it.cache.backups},${it.cache.logicalBytes},${it.cache.allocatedKiB},${it.taskOutcomes}"
		}.join("\n") + "\n"
		new File(dir, "environment.properties").text = """gradle=${GradleVersion.current().version}
java=${System.getProperty('java.version')}
os=${System.getProperty('os.name')} ${System.getProperty('os.arch')}
minecraft=26.1-snapshot-1
fabricApi=0.140.3+26.1
fabricLoader=${LoomTestVersions.FABRIC_LOADER.version()}
loomRevision=${loomRevision()}
loomDirty=${loomDirty()}
profile=${profile}
fixtureResourceBytes=${RESOURCE_SIZE}
"""

		results.groupBy { it.scenario }.each { scenario, measurements ->
			def durations = measurements.collect { it.durationMs }.sort()
			def middle = durations.size().intdiv(2)
			def median = durations.size() % 2 == 0 ? (durations[middle - 1] + durations[middle]) / 2 : durations[middle]
			println("${scenario}: median ${median} ms (${durations.join(', ')} ms)")
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

		def args = scenario.offline ? ["--offline"] : []

		if (profile) {
			args << "--profile"
		}

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
		def cache = measureMinecraftCache(gradle)
		return new Measurement(scenario.name, iteration, durationMs, configurationCacheReused, cache, taskOutcomes)
	}

	private static CacheMeasurement measureMinecraftCache(GradleProject gradle) {
		def roots = [
			new File(gradle.projectDir, ".gradle/loom-cache/minecraftMaven"),
			new File(gradle.gradleHomeDir, "caches/fabric-loom/minecraftMaven")
		]
		def files = []
		roots.findAll { it.isDirectory() }.each { root ->
			root.traverse(type: groovy.io.FileType.FILES) { files << it }
		}
		def allocations = roots.findAll { it.exists() }.collect { allocatedSizeKiB(it) }
		def allocatedKiB = allocations.any { it < 0 } ? -1L : (allocations.sum() ?: 0L)

		return new CacheMeasurement(
				files.size(),
				files.count { it.name.endsWith(".jar") },
				files.count { it.name.endsWith(".jar.backup") },
				files.sum { it.length() } ?: 0L,
				allocatedKiB
				)
	}

	private static long allocatedSizeKiB(File directory) {
		try {
			def process = [
				"du",
				"-sk",
				directory.absolutePath
			].execute()
			if (process.waitFor() != 0) {
				return -1L
			}

			def output = process.text.trim()
			return output ==~ /\d+\s+.*/ ? output.split(/\s+/)[0].toLong() : -1L
		} catch (IOException | NumberFormatException ignored) {
			return -1L
		}
	}

	private static List<Scenario> orderedColdScenarios(List<Scenario> scenarios, int iteration) {
		return iteration % 2 == 0 ? scenarios : scenarios.reverse()
	}

	private static List<Scenario> orderedWarmScenarios(List<Scenario> scenarios, int iteration) {
		if (iteration % 2 == 0) {
			return scenarios
		}

		def fullConfiguration = scenarios.find { it.name == "full-configuration" }
		def offlineConfiguration = scenarios.find { it.name == "offline-configuration" }
		return scenarios.collect { scenario ->
			if (scenario == fullConfiguration) {
				return offlineConfiguration
			}

			return scenario == offlineConfiguration ? fullConfiguration : scenario
		}
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

	private static boolean loomDirty() {
		def process = [
			"git",
			"status",
			"--porcelain"
		].execute()
		return process.waitFor() != 0 || !process.text.trim().isEmpty()
	}

	@Immutable
	private static class Scenario {
		String name
		List<String> tasks
		boolean mutateSource
		boolean clearLoomCache
		boolean configurationCache
		boolean offline
	}

	@Immutable
	private static class Measurement {
		String scenario
		int iteration
		long durationMs
		boolean configurationCacheReused
		CacheMeasurement cache
		String taskOutcomes
	}

	@Immutable
	private static class CacheMeasurement {
		int files
		int jars
		int backups
		long logicalBytes
		long allocatedKiB
	}
}
