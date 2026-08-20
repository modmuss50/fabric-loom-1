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

package net.fabricmc.loom.test.unit

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.file.Files

import spock.lang.Specification

import net.fabricmc.loom.configuration.DebofInstallerData
import net.fabricmc.loom.configuration.InstallerData

import static net.fabricmc.loom.test.util.ZipTestUtils.createZip

class DebofInstallerDataTest extends Specification {
	private static final Method GET_INSTALLER = DebofInstallerData.getDeclaredMethod("getInstaller", File)

	static {
		GET_INSTALLER.setAccessible(true)
	}

	def "reads installer and mod metadata"() {
		given:
		def jar = createZip([
			"fabric.mod.json": '''{"schemaVersion":1,"id":"test-loader","version":"1.2.3"}''',
			"fabric-installer.json": '''{"libraries":{"common":[]}}'''
		])

		when:
		def installer = getInstaller(jar.toFile())

		then:
		installer.version() == "1.2.3"
		installer.installerJson().getAsJsonObject("libraries").getAsJsonArray("common").isEmpty()

		when:
		Files.delete(jar)

		then:
		Files.notExists(jar)
	}

	def "ignores archives without installer metadata"() {
		given:
		def jar = createZip(["some-file.txt": "test"])

		expect:
		getInstaller(jar.toFile()) == null
	}

	def "ignores invalid archives"() {
		given:
		def jar = Files.createTempFile("loom-test", ".jar")
		Files.writeString(jar, "not a zip")

		expect:
		getInstaller(jar.toFile()) == null
	}

	def "requires mod metadata when installer metadata is present"() {
		given:
		def jar = createZip(["fabric-installer.json": '''{"libraries":{"common":[]}}'''])

		when:
		getInstaller(jar.toFile())

		then:
		thrown(UncheckedIOException)
	}

	private static InstallerData getInstaller(File file) {
		try {
			return GET_INSTALLER.invoke(null, file) as InstallerData
		} catch (InvocationTargetException e) {
			throw e.cause
		}
	}
}
