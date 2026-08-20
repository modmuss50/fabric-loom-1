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

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipFile

import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarMerger
import net.fabricmc.loom.util.Checksum

/**
 * Run {@code ./gradlew unobfBenchmark} once to populate the fixture, then run
 * {@code ./gradlew minecraftJarMergerBenchmark}.
 */
class MinecraftJarMergerBenchmark {
	private static final int WARMUPS = 10
	private static final int ITERATIONS = 30
	private static final String MINECRAFT_VERSION = "26.1-snapshot-1"
	private static final String CLIENT_SHA1 = "bd354bbd46835d7c7753e0b19c718777fb2386ba"
	private static final String SERVER_SHA1 = "2aba7467eb813f864f6eacd527c08b9dd71f2ca5"
	private static final String OUTPUT_SIGNATURE = "e7ca3231477f4f12ae985238e8498f8383c36b953bd9cd31c5d89254bb24d7e1"

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
		def expectedOutput = new File(minecraftDir, "minecraft-merged.jar")
		def output = new File(benchmarkDir, "minecraft-merger-output.jar")
		assert Checksum.of(client).sha1().matchesStr(CLIENT_SHA1)
		assert Checksum.of(server).sha1().matchesStr(SERVER_SHA1)
		assert contentSignature(expectedOutput) == OUTPUT_SIGNATURE

		WARMUPS.times {
			merge(client, server, output)
			assert contentSignature(output) == OUTPUT_SIGNATURE
		}

		def durations = []

		ITERATIONS.times { iteration ->
			long start = System.nanoTime()
			merge(client, server, output)
			long duration = System.nanoTime() - start
			assert contentSignature(output) == OUTPUT_SIGNATURE
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
outputSignatureSha256=${OUTPUT_SIGNATURE}
"""

		def sorted = durations.collect { it[1] }.sort()
		println("Minecraft jar merge: median ${sorted[sorted.size().intdiv(2)] / 1_000_000} ms")
		println("Results: ${results.absolutePath}")
	}

	private static void merge(File client, File server, File output) {
		new MinecraftJarMerger(client, server, output).withCloseable {
			it.enableSyntheticParamsOffset()
			it.merge()
		}
	}

	private static String contentSignature(File file) {
		def digest = MessageDigest.getInstance("SHA-256")

		new ZipFile(file).withCloseable { zip ->
			Collections.list(zip.entries()).findAll { !it.directory }.sort { it.name }.each { entry ->
				digest.update(entry.name.getBytes(StandardCharsets.UTF_8))
				digest.update((byte) 0)
				digest.update(zip.getInputStream(entry).withCloseable { it.readAllBytes() })
			}
		}

		return HexFormat.of().formatHex(digest.digest())
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
