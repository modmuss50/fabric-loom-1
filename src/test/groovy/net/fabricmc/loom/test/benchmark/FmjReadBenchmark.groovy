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
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import groovy.io.FileType
import groovy.transform.Immutable

import net.fabricmc.loom.util.fmj.FabricModJsonFactory

/**
 * Run {@code ./gradlew unobfBenchmark} once to populate the fixture, then run
 * {@code ./gradlew fmjReadBenchmark} to measure its Fabric API metadata scan.
 */
class FmjReadBenchmark {
	private static final int WARMUPS = 50
	private static final int ITERATIONS = 50
	private static final int EXPECTED_JARS = 44
	private static final String EXPECTED_FABRIC_API = "0.140.3+26.1"
	private static final String EXPECTED_SIGNATURE = "3182e1f1b828bf03212d4b615838cd900b3a08aefb1bc7dc2082abf78842cbe9"

	static void main(String[] args) {
		if (args.length != 1) {
			throw new IllegalArgumentException("Expected the unobfuscated benchmark working directory")
		}

		def benchmarkDir = new File(args[0])

		if (!new File(benchmarkDir, ".loom-unobf-benchmark").isFile()) {
			throw new IllegalArgumentException("Run unobfBenchmark first or use its benchmarkDir")
		}

		def modulesDir = new File(benchmarkDir, "gradlehome/caches/modules-2/files-2.1/net.fabricmc.fabric-api")
		def jars = []

		if (modulesDir.isDirectory()) {
			modulesDir.traverse(type: FileType.FILES, nameFilter: ~/.*\.jar/) { jars << it.toPath() }
		}

		jars.sort()

		if (jars.size() != EXPECTED_JARS || !new File(modulesDir, "fabric-api/${EXPECTED_FABRIC_API}").isDirectory()) {
			throw new IllegalStateException("Expected the ${EXPECTED_FABRIC_API} fixture with ${EXPECTED_JARS} jars, found ${jars.size()}; use a fresh benchmarkDir")
		}

		def durations = []
		List<String> expected

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			expected = scan(jars, executor).signature
			assert expected.every { !it.empty }
			assert signatureHash(expected) == EXPECTED_SIGNATURE

			(WARMUPS - 1).times {
				assert scan(jars, executor).signature == expected
			}

			ITERATIONS.times { iteration ->
				long start = System.nanoTime()
				def result = scan(jars, executor)
				long duration = System.nanoTime() - start
				assert result.signature == expected
				durations << new Measurement(iteration, duration, jars.size(), result.modCount)
			}
		}

		def output = new File(benchmarkDir, "fmj-results.csv")
		output.text = "iteration,duration_ns,jar_count,mod_count\n" + durations.collect {
			"${it.iteration},${it.durationNs},${it.jarCount},${it.modCount}"
		}.join("\n") + "\n"
		new File(benchmarkDir, "fmj-inputs.txt").text = jars.collect {
			modulesDir.toPath().relativize(it).toString()
		}.join("\n") + "\n"
		new File(benchmarkDir, "fmj-environment.properties").text = """java=${System.getProperty('java.version')}
os=${System.getProperty('os.name')} ${System.getProperty('os.arch')}
loomRevision=${loomRevision()}
loomDirty=${loomDirty()}
fabricApi=${EXPECTED_FABRIC_API}
signatureSha256=${EXPECTED_SIGNATURE}
jarCount=${jars.size()}
"""

		def sorted = durations.collect { it.durationNs }.sort()
		def middle = sorted.size().intdiv(2)
		def median = (sorted[middle - 1] + sorted[middle]) / 2
		println("FMJ scan: median ${median / 1_000_000} ms (${jars.size()} jars, ${durations.first().modCount} mods)")
		println("Results: ${output.absolutePath}")
	}

	private static ScanResult scan(List<Path> jars, ExecutorService executor) {
		def futures = jars.collect { jar ->
			executor.submit({
				def mod = FabricModJsonFactory.createFromZipOptional(jar)
				return mod.map { "${it.id}:${it.modVersion}" }.orElse("")
			} as Callable<String>)
		}
		def signature = futures.collect { it.get() }
		return new ScanResult(signature, signature.count { !it.empty })
	}

	private static String signatureHash(List<String> signature) {
		def digest = MessageDigest.getInstance("SHA-256").digest(signature.join("\n").getBytes(StandardCharsets.UTF_8))
		return HexFormat.of().formatHex(digest)
	}

	private static String loomRevision() {
		def process = ["git", "rev-parse", "HEAD"].execute()
		return process.waitFor() == 0 ? process.text.trim() : "unknown"
	}

	private static boolean loomDirty() {
		def process = ["git", "status", "--porcelain"].execute()
		return process.waitFor() != 0 || !process.text.trim().isEmpty()
	}

	@Immutable
	private static class ScanResult {
		List<String> signature
		int modCount
	}

	@Immutable
	private static class Measurement {
		int iteration
		long durationNs
		int jarCount
		int modCount
	}
}
