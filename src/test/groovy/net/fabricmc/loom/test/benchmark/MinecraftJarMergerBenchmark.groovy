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
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package net.fabricmc.loom.test.benchmark

import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarMerger
import net.fabricmc.loom.util.Checksum

/**
 * Run {@code ./gradlew unobfBenchmark} once to populate the fixture, then run
 * {@code ./gradlew minecraftJarMergerBenchmark}.
 */
class MinecraftJarMergerBenchmark {
	private static final int WARMUPS = 3
	private static final int ITERATIONS = 15
	private static final String MINECRAFT_VERSION = "26.1-snapshot-1"
	private static final String CLIENT_SHA1 = "bd354bbd46835d7c7753e0b19c718777fb2386ba"
	private static final String SERVER_SHA1 = "2aba7467eb813f864f6eacd527c08b9dd71f2ca5"
	private static final String OUTPUT_SHA1 = "f287b164c56788642018b58fb7e350964aec5528"

	static void main(String[] args) {
		if (args.length != 1) {
			throw new IllegalArgumentException("Expected the unobfuscated benchmark working directory")
		}

		def benchmarkDir = new File(args[0])

		if (!new File(benchmarkDir, ".loom-unobf-benchmark").isFile()) {
			throw new IllegalArgumentException("Run unobfBenchmark first or use its benchmarkDir")
		}

		def minecraftDir = new File(benchmarkDir, "gradlehome/caches/fabric-loom/${MINECRAFT_VERSION}")
		def client = new File(minecraftDir, "minecraft-client.jar")
		def server = new File(minecraftDir, "minecraft-extracted_server.jar")
		def output = new File(benchmarkDir, "minecraft-merger-output.jar")
		assert Checksum.of(client).sha1().matchesStr(CLIENT_SHA1)
		assert Checksum.of(server).sha1().matchesStr(SERVER_SHA1)

		WARMUPS.times {
			merge(client, server, output)
			assert Checksum.of(output).sha1().matchesStr(OUTPUT_SHA1)
		}

		def durations = []

		ITERATIONS.times { iteration ->
			long start = System.nanoTime()
			merge(client, server, output)
			long duration = System.nanoTime() - start
			assert Checksum.of(output).sha1().matchesStr(OUTPUT_SHA1)
			durations << [iteration, duration]
		}

		def results = new File(benchmarkDir, "minecraft-merger-results.csv")
		results.text = "iteration,duration_ns\n" + durations.collect { "${it[0]},${it[1]}" }.join("\n") + "\n"
		new File(benchmarkDir, "minecraft-merger-environment.properties").text = """java=${System.getProperty('java.version')}
os=${System.getProperty('os.name')} ${System.getProperty('os.arch')}
loomRevision=${loomRevision()}
loomDirty=${loomDirty()}
minecraft=${MINECRAFT_VERSION}
clientSha1=${CLIENT_SHA1}
serverSha1=${SERVER_SHA1}
outputSha1=${OUTPUT_SHA1}
"""

		def sorted = durations.collect { it[1] }.sort()
		println("Minecraft jar merge: median ${sorted[sorted.size().intdiv(2)] / 1_000_000} ms")
		println("Results: ${results.absolutePath}")
	}

	private static void merge(File client, File server, File output) {
		new MinecraftJarMerger(client, server, output).withCloseable {
			it.merge()
		}
	}

	private static String loomRevision() {
		return commandOutput("git", "rev-parse", "--short=8", "HEAD") ?: "unknown"
	}

	private static boolean loomDirty() {
		return !commandOutput("git", "status", "--porcelain").empty
	}

	private static String commandOutput(String... command) {
		try {
			def process = command.toList().execute(null, new File(System.getProperty("user.dir")))
			return process.waitFor() == 0 ? process.text.trim() : ""
		} catch (IOException ignored) {
			return ""
		}
	}
}
