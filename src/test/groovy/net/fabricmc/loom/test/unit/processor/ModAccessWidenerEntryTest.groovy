/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2023 FabricMC
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

package net.fabricmc.loom.test.unit.processor

import spock.lang.Specification

import net.fabricmc.classtweaker.api.visitor.ClassTweakerVisitor
import net.fabricmc.loom.configuration.accesswidener.ModAccessWidenerEntry
import net.fabricmc.loom.util.fmj.FabricModJson
import net.fabricmc.loom.util.fmj.FabricModJsonSource
import net.fabricmc.loom.util.fmj.ModEnvironment

class ModAccessWidenerEntryTest extends Specification {
	def "read local mod"() {
		given:
		def mod = Mock(FabricModJson.Mockable)
		mod.getClassTweakers() >> ["test.accesswidener": ModEnvironment.UNIVERSAL]

		when:
		def entries = ModAccessWidenerEntry.readAll(mod, true)
		then:
		entries.size() == 1
		def entry = entries[0]

		entry.path() == "test.accesswidener"
		entry.environment() == ModEnvironment.UNIVERSAL
		entry.transitiveOnly()
		entry.hashCode() == -1218981396
	}

	def "caches access widener contents"() {
		given:
		def source = Mock(FabricModJsonSource)
		def mod = Mock(FabricModJson.Mockable)
		mod.getSource() >> source
		def entry = new ModAccessWidenerEntry(mod, "test.accesswidener", ModEnvironment.UNIVERSAL, true)
		def visitor = Mock(ClassTweakerVisitor)
		def contents = "accessWidener v2 official\naccessible class com/example/Test\n".bytes

		when:
		entry.readOfficial(visitor)
		entry.readOfficial(visitor)

		then:
		1 * source.read("test.accesswidener") >> contents
	}
}
