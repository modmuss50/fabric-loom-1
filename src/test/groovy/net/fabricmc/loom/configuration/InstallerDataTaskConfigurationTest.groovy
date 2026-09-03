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

package net.fabricmc.loom.configuration

import java.nio.file.Files
import java.nio.file.Path

import spock.lang.Specification

import net.fabricmc.loom.LoomGradlePlugin
import net.fabricmc.loom.test.util.ZipTestUtils
import net.fabricmc.loom.util.Constants

class InstallerDataTaskConfigurationTest extends Specification {
	def "scans the first dependency containing installer data"() {
		given:
		Path invalidJar = ZipTestUtils.createZip(["ignored.txt": "ignored"], ".jar")
		Map<String, String> installerContents = [:]
		installerContents["fabric.mod.json"] = '{"id":"test-loader","version":"9.8.7"}'
		installerContents["fabric-installer.json"] = '''{
				"mainClass": {
					"client": "example.CustomClient",
					"server": "example.CustomServer"
				},
				"libraries": { "common": [] }
			}'''
		Path installerJar = ZipTestUtils.createZip(installerContents, ".jar")

		when:
		def result = ScanInstallerDataTask.ScanInstallerDataAction.findInstallerData([
			invalidJar.toFile(),
			installerJar.toFile()
		])
		def descriptor = result.descriptor()
		def contents = LoomGradlePlugin.GSON.toJson(descriptor)

		then:
		descriptor.get("found").asBoolean
		result.installerJar() == installerJar
		InstallerDataTaskConfiguration.readInstallerVersion(contents).orElseThrow() == "9.8.7"
		InstallerDataTaskConfiguration.readMainClass("client", contents) == "example.CustomClient"
		InstallerDataTaskConfiguration.readMainClass("server", contents) == "example.CustomServer"

		cleanup:
		Files.deleteIfExists(invalidJar)
		Files.deleteIfExists(installerJar)
	}

	def "reports no installer data and uses default main classes"() {
		given:
		Path jar = ZipTestUtils.createZip(["fabric.mod.json": '{"id":"plain-mod","version":"1.0"}'], ".jar")

		when:
		def result = ScanInstallerDataTask.ScanInstallerDataAction.findInstallerData([jar.toFile()])
		def descriptor = result.descriptor()
		def contents = LoomGradlePlugin.GSON.toJson(descriptor)

		then:
		!descriptor.get("found").asBoolean
		result.installerJar() == null
		InstallerDataTaskConfiguration.readInstallerVersion(contents).empty
		InstallerDataTaskConfiguration.readMainClass("client", contents) == Constants.Knot.KNOT_CLIENT
		InstallerDataTaskConfiguration.readMainClass("server", contents) == Constants.Knot.KNOT_SERVER

		cleanup:
		Files.deleteIfExists(jar)
	}

	def "supports one main class for both environments"() {
		given:
		def contents = '''{
			"found": true,
			"version": "1.2.3",
			"installer": { "mainClass": "example.SharedMain" }
		}'''

		expect:
		InstallerDataTaskConfiguration.readMainClass("client", contents) == "example.SharedMain"
		InstallerDataTaskConfiguration.readMainClass("server", contents) == "example.SharedMain"
	}
}
