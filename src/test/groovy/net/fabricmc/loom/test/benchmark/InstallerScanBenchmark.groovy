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
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

import groovy.io.FileType
import groovy.transform.Immutable

import net.fabricmc.loom.configuration.InstallerData
import net.fabricmc.loom.util.FileSystemUtil

/**
 * Run {@code ./gradlew unobfBenchmark} once to populate the fixture, then run
 * {@code ./gradlew installerScanBenchmark} to compare installer entry probes.
 */
class InstallerScanBenchmark {
	private static final int WARMUPS = 50
	private static final int ITERATIONS = 50
	private static final int EXPECTED_FABRIC_API_JARS = 44
	private static final String EXPECTED_FABRIC_API = "0.140.3+26.1"
	private static final String EXPECTED_LOADER = "0.19.2"
	private static final String EXPECTED_INPUTS = "0cde9408a12493c5d56d8913c3bf9ab5b319445db42a811c083dfa4b640c675c"

	static void main(String[] args) {
		if (args.length != 1) {
			throw new IllegalArgumentException("Expected the unobfuscated benchmark working directory")
		}

		def benchmarkDir = new File(args[0])

		if (!new File(benchmarkDir, ".loom-unobf-benchmark").isFile()) {
			throw new IllegalArgumentException("Run unobfBenchmark first or use its benchmarkDir")
		}

		def moduleCache = new File(benchmarkDir, "gradlehome/caches/modules-2/files-2.1")
		def fabricApiDir = new File(moduleCache, "net.fabricmc.fabric-api")
		def loaderDir = new File(moduleCache, "net.fabricmc/fabric-loader/${EXPECTED_LOADER}")
		def fabricApiJars = findJars(fabricApiDir)
		def loaderJars = findJars(loaderDir)

		if (fabricApiJars.size() != EXPECTED_FABRIC_API_JARS || !new File(fabricApiDir, "fabric-api/${EXPECTED_FABRIC_API}").isDirectory()) {
			throw new IllegalStateException("Expected the ${EXPECTED_FABRIC_API} fixture with ${EXPECTED_FABRIC_API_JARS} jars, found ${fabricApiJars.size()}; use a fresh benchmarkDir")
		}

		if (loaderJars.size() != 1) {
			throw new IllegalStateException("Expected one Fabric Loader ${EXPECTED_LOADER} jar, found ${loaderJars.size()}; use a fresh benchmarkDir")
		}

		List<Path> jars = fabricApiJars + loaderJars
		def inputManifest = jars.collect {
			moduleCache.toPath().relativize(it).toString().replace(File.separator, "/")
		}.join("\n") + "\n"
		assert signatureHash(inputManifest) == EXPECTED_INPUTS
		assert findInstallersZipFs(fabricApiJars) == 0
		assert findInstallersZipFile(fabricApiJars) == 0
		assert findInstallersZipFs(loaderJars) == 1
		assert findInstallersZipFile(loaderJars) == 1

		WARMUPS.times {
			assert findInstallerZipFs(jars)
			assert findInstallerZipFile(jars)
		}

		def measurements = []

		ITERATIONS.times { iteration ->
			long zipFsDuration
			long zipFileDuration

			if (iteration % 2 == 0) {
				zipFsDuration = measure { findInstallerZipFs(jars) }
				zipFileDuration = measure { findInstallerZipFile(jars) }
			} else {
				zipFileDuration = measure { findInstallerZipFile(jars) }
				zipFsDuration = measure { findInstallerZipFs(jars) }
			}

			measurements << new Measurement(iteration, zipFsDuration, zipFileDuration)
		}

		def output = new File(benchmarkDir, "installer-scan-results.csv")
		output.text = "iteration,zipfs_duration_ns,zipfile_duration_ns,jar_count\n" + measurements.collect {
			"${it.iteration},${it.zipFsDurationNs},${it.zipFileDurationNs},${jars.size()}"
		}.join("\n") + "\n"
		new File(benchmarkDir, "installer-scan-inputs.txt").text = inputManifest
		new File(benchmarkDir, "installer-scan-environment.properties").text = """java=${System.getProperty('java.version')}
os=${System.getProperty('os.name')} ${System.getProperty('os.arch')}
loomRevision=${loomRevision()}
loomDirty=${loomDirty()}
fabricApi=${EXPECTED_FABRIC_API}
fabricLoader=${EXPECTED_LOADER}
inputsSha256=${EXPECTED_INPUTS}
jarCount=${jars.size()}
"""

		def zipFsMedian = median(measurements.collect { it.zipFsDurationNs })
		def zipFileMedian = median(measurements.collect { it.zipFileDurationNs })
		println("Installer scan: ZipFS ${zipFsMedian / 1_000_000} ms, ZipFile ${zipFileMedian / 1_000_000} ms (${jars.size()} jars)")
		println("Results: ${output.absolutePath}")
	}

	private static List<Path> findJars(File directory) {
		def jars = []

		if (directory.isDirectory()) {
			directory.traverse(type: FileType.FILES, nameFilter: ~/.*\.jar/) { jars << it.toPath() }
		}

		return jars.sort()
	}

	private static long measure(Closure<Boolean> operation) {
		long start = System.nanoTime()
		assert operation.call()
		return System.nanoTime() - start
	}

	private static boolean findInstallerZipFs(List<Path> jars) {
		return jars.parallelStream().filter { hasInstallerZipFs(it) }.findFirst().isPresent()
	}

	private static boolean findInstallerZipFile(List<Path> jars) {
		return jars.parallelStream().filter { hasInstallerZipFile(it) }.findFirst().isPresent()
	}

	private static long findInstallersZipFs(List<Path> jars) {
		return jars.parallelStream().filter { hasInstallerZipFs(it) }.count()
	}

	private static long findInstallersZipFile(List<Path> jars) {
		return jars.parallelStream().filter { hasInstallerZipFile(it) }.count()
	}

	private static boolean hasInstallerZipFs(Path jar) {
		FileSystemUtil.getJarFileSystem(jar).withCloseable { fs ->
			Files.exists(fs.getPath(InstallerData.INSTALLER_PATH))
		}
	}

	private static boolean hasInstallerZipFile(Path jar) {
		new ZipFile(jar.toFile()).withCloseable { zipFile ->
			zipFile.getEntry(InstallerData.INSTALLER_PATH) != null
		}
	}

	private static String signatureHash(String value) {
		def digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
		return HexFormat.of().formatHex(digest)
	}

	private static long median(List<Long> values) {
		def sorted = values.sort()
		def middle = sorted.size().intdiv(2)
		return (sorted[middle - 1] + sorted[middle]).intdiv(2)
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
	private static class Measurement {
		int iteration
		long zipFsDurationNs
		long zipFileDurationNs
	}
}
