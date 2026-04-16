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

package net.fabricmc.loom.test.unit.xcode

import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.task.tool.xcode.XcodeClasspathFileFactory

class XcodeClasspathFileFactoryTest extends Specification {
	@TempDir
	File tempDir

	def "generates classpath arg file"() {
		given:
		def outputFile = new File(tempDir, "args/classpath.txt")
		def classpath = [
			new File("/libs/foo.jar"),
			new File("/libs/bar.jar"),
			new File("/libs/baz with spaces.jar")
		]
		def expected = XcodeClasspathFileFactoryTest.getResourceAsStream("XcodeClasspathFileFactoryTest.txt").text

		when:
		def arg = XcodeClasspathFileFactory.generate(classpath, outputFile)

		then:
		arg == "@" + outputFile.absolutePath
		outputFile.text == expected
	}
}
