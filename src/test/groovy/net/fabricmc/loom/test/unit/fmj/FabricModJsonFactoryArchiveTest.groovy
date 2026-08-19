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

package net.fabricmc.loom.test.unit.fmj

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import com.google.gson.JsonSyntaxException
import spock.lang.Specification

import net.fabricmc.loom.test.util.ZipTestUtils
import net.fabricmc.loom.util.fmj.FabricModJsonFactory
import net.fabricmc.loom.util.fmj.FabricModJsonSource

class FabricModJsonFactoryArchiveTest extends Specification {
	private static final String MOD_JSON = '{"schemaVersion":1,"id":"test-mod","version":"1.2.3","accessWidener":"test.accesswidener"}'

	def "reads metadata without retaining the archive"() {
		given:
		def accessWidener = "accessWidener v2 named\n".getBytes(StandardCharsets.UTF_8)
		def jar = ZipTestUtils.createZipFromBytes([
			"fabric.mod.json": MOD_JSON.getBytes(StandardCharsets.UTF_8),
			"test.accesswidener": accessWidener
		], ".jar")

		when:
		def mod = FabricModJsonFactory.createFromZipNullable(jar)

		then:
		mod.id == "test-mod"
		mod.modVersion == "1.2.3"
		mod.source instanceof FabricModJsonSource.ZipSource
		mod.source.read("test.accesswidener") == accessWidener

		when:
		Files.delete(jar)

		then:
		Files.notExists(jar)
	}

	def "returns null for missing and empty metadata"() {
		expect:
		FabricModJsonFactory.createFromZipNullable(ZipTestUtils.createZip(["other.json": "{}"], ".jar")) == null
		FabricModJsonFactory.createFromZipNullable(ZipTestUtils.createZip(["fabric.mod.json": ""], ".jar")) == null
	}

	def "reports malformed metadata with archive context"() {
		given:
		def jar = ZipTestUtils.createZip(["fabric.mod.json": "{]"], ".jar")

		when:
		FabricModJsonFactory.createFromZipNullable(jar)

		then:
		def exception = thrown(JsonSyntaxException)
		exception.message == "Failed to parse fabric.mod.json in zip: ${jar}"
	}

	def "reports invalid archives with context"() {
		given:
		def jar = Files.createTempFile("loom-test", ".jar")
		Files.writeString(jar, "not a zip")

		when:
		FabricModJsonFactory.createFromZipNullable(jar)

		then:
		def exception = thrown(UncheckedIOException)
		exception.message == "Failed to read zip: ${jar}"
	}

	def "uses root metadata in multi-release archives"() {
		given:
		def jar = ZipTestUtils.createZip([
			"fabric.mod.json": MOD_JSON,
			"META-INF/versions/21/fabric.mod.json": '{"schemaVersion":1,"id":"versioned-mod","version":"1.0.0"}'
		], ".jar")

		expect:
		FabricModJsonFactory.createFromZipNullable(jar).id == "test-mod"
	}
}
