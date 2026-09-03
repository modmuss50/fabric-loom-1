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

package net.fabricmc.loom.task

import java.nio.file.Files
import java.nio.file.Path

import spock.lang.Specification
import spock.lang.Unroll

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace
import net.fabricmc.loom.test.util.ZipTestUtils

class TaskBackedMinecraftSafetyTest extends Specification {
	@Unroll
	def "uses #expected source namespace when legacy split is #legacySplit"() {
		expect:
		RemapMinecraftJarTask.RemapMinecraftJarAction.resolveSourceNamespace(
				MappingsNamespace.OFFICIAL.toString(),
				legacyNamespace?.toString(),
				legacySplit
				) == expected.toString()

		where:
		legacyNamespace                    | legacySplit || expected
		MappingsNamespace.CLIENT_OFFICIAL  | true        || MappingsNamespace.CLIENT_OFFICIAL
		MappingsNamespace.SERVER_OFFICIAL  | true        || MappingsNamespace.SERVER_OFFICIAL
		MappingsNamespace.CLIENT_OFFICIAL  | false       || MappingsNamespace.OFFICIAL
		null                                | true        || MappingsNamespace.OFFICIAL
	}

	def "rejects splitting a server jar without bundle metadata"() {
		given:
		Path serverJar = ZipTestUtils.createZip(["server.class": "server"], ".jar")

		when:
		SplitMinecraftJarsTask.SplitMinecraftJarsAction.requireBundledServerJar(serverJar)

		then:
		def exception = thrown(UnsupportedOperationException)
		exception.message.contains("bundled server jar")

		cleanup:
		Files.deleteIfExists(serverJar)
	}

	def "allows splitting a bundled server jar"() {
		given:
		Map<String, String> serverContents = [:]
		serverContents["META-INF/libraries.list"] = ""
		serverContents["META-INF/versions.list"] = "hash\tserver\tserver.jar"
		serverContents["META-INF/main-class"] = "net.minecraft.bundler.Main"
		Path serverJar = ZipTestUtils.createZip(serverContents, ".jar")

		when:
		SplitMinecraftJarsTask.SplitMinecraftJarsAction.requireBundledServerJar(serverJar)

		then:
		noExceptionThrown()

		cleanup:
		Files.deleteIfExists(serverJar)
	}
}
