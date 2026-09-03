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

import spock.lang.Specification
import spock.lang.Unroll

import net.fabricmc.loom.configuration.providers.minecraft.library.Library

class DownloadMinecraftLibrariesTaskTest extends Specification {
	def "selects the highest module version for a runtime output"() {
		given:
		def libraries = [
			new Library("org.lwjgl", "lwjgl", "3.3.4", null, Library.Target.RUNTIME),
			new Library("org.lwjgl", "lwjgl", "3.4.3", null, Library.Target.COMPILE)
		]

		when:
		def selected = DownloadMinecraftLibrariesTask.DownloadMinecraftLibrariesAction.selectLibraries(
				libraries,
				EnumSet.of(Library.Target.COMPILE, Library.Target.RUNTIME)
				)

		then:
		selected*.version() == ["3.4.3"]
	}

	def "selects an equivalent module version deterministically"() {
		given:
		def first = new Library("example", "library", "1.0", null, Library.Target.COMPILE)
		def second = new Library("example", "library", "1.0.0", null, Library.Target.COMPILE)

		expect:
		[
			[first, second],
			[second, first]
		].every { libraries ->
			DownloadMinecraftLibrariesTask.DownloadMinecraftLibrariesAction.selectLibraries(
					libraries,
					EnumSet.of(Library.Target.COMPILE)
					)*.version() == ["1.0.0"]
		}
	}

	def "partitions shared artifacts onto one common path"() {
		given:
		def common = new Library("example", "common", "1.0", null, Library.Target.COMPILE)
		def client = new Library("example", "client", "1.0", null, Library.Target.COMPILE)
		def server = new Library("example", "server", "1.0", null, Library.Target.COMPILE)

		when:
		def partition = DownloadMinecraftLibrariesTask.DownloadMinecraftLibrariesAction.partitionLibraries(
				[common, client],
				[common, server]
				)

		then:
		partition.common() == [common]
		partition.clientOnly() == [client]
		partition.serverOnly() == [server]
	}

	@Unroll
	def "compares Maven-like version #left to #right"() {
		expect:
		Integer.signum(DownloadMinecraftLibrariesTask.DownloadMinecraftLibrariesAction.compareVersions(left, right)) == comparison

		where:
		left            | right          || comparison
		"3.4.3"         | "3.3.4"        || 1
		"4.1.115.Final" | "4.1.97.Final" || 1
		"1.0"           | "1.0-rc1"      || 1
		"1.0-sp1"       | "1.0"          || 1
		"1.0-beta2"     | "1.0-beta1"    || 1
	}
}
