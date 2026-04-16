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

import net.fabricmc.loom.api.RunConfiguration
import net.fabricmc.loom.task.tool.xcode.PbxprojFactory
import org.gradle.api.provider.Property
import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleTestUtil

class PbxprojFactoryTest extends Specification {
	static Project project = GradleTestUtil.mockProject()
	static ObjectFactory objectFactory = project.getObjects()

	def "generates expected pbxproj output with no runs"() {
		given:
		def expected = PbxprojFactoryTest.getResourceAsStream("PbxprojFactoryTest.pbxproj").text

		expect:
		new PbxprojFactory().generate("MyProject", []).serialize() == expected
	}

	def "generates expected pbxproj output with one run"() {
		given:
		def expected = PbxprojFactoryTest.getResourceAsStream("PbxprojFactoryTest_withRuns.pbxproj").text
		def run = objectFactory.newInstance(RunConfiguration, "client")
		run.displayName.set("Minecraft")

		expect:
		new PbxprojFactory().generate("MyProject", [run]).serialize() == expected
	}
}
