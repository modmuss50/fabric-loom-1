/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2025 FabricMC
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

import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import spock.lang.Specification

import net.fabricmc.loom.api.RunConfiguration
import net.fabricmc.loom.task.tool.xcode.XcschemeFactory
import net.fabricmc.loom.test.util.GradleTestUtil

class XcschemeFactoryTest extends Specification {
	static Project project = GradleTestUtil.mockProject()
	static ObjectFactory objectFactory = project.getObjects()

	def "generates expected xcscheme output"() {
		given:
		def expected = XcschemeFactoryTest.getResourceAsStream("XcschemeFactoryTest.xcscheme").text

		def run = objectFactory.newInstance(RunConfiguration, "client")
		run.jvmArguments.set([
			"-XstartOnFirstThread",
			"-Xmx2G"
		])
		run.mainClass.set("com.example.Main")
		run.programArguments.set(["--gameDir", "/run"])
		run.environmentVars.set(["MY_VAR": "my_value"])
		run.runDirectory.set(new File("/project/run"))

		expect:
		new XcschemeFactory().generate(run, new File("/usr/bin/java"), "@/tmp/classpath.txt") == expected
	}
}
