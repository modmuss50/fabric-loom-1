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

import net.fabricmc.loom.task.tool.xcode.OpenStepPropertyList
import net.fabricmc.loom.task.tool.xcode.writer.OpenStepPropertyListWriterImpl
import spock.lang.Specification

class OpenStepPropertyListWriterImplTest extends Specification {

	def "writes all value types and structural elements correctly"() {
		given:
		def expected = OpenStepPropertyListWriterImplTest.getResourceAsStream("OpenStepPropertyListWriterImplTest.txt").text
		def sw = new StringWriter()
		def writer = new OpenStepPropertyListWriterImpl(sw)

		def obj1 = new OpenStepPropertyList.BaseObject() {
			String isa() { "FakeISA" }
		}
		def obj2 = new OpenStepPropertyList.BaseObject() {
			String isa() { "FakeISA" }
		}

		// Pre-assign IDs so they are deterministic
		writer.allocId(obj1)
		writer.allocId(obj2)

		def ref1 = new OpenStepPropertyList.ObjRef(obj1)
		def ref2 = new OpenStepPropertyList.ObjRef(obj2)

		when:
		try (def root = writer.pushRoot()) {
			root.write("strKey", "hello world")  // quoted: contains space
			root.write("simple", "abc")           // unquoted
			root.write("empty", "")               // quoted: empty string
			root.write("boolTrue", true)
			root.write("boolFalse", false)
			root.write("count", 42)
			root.write("namedRef", ref1)
			root.write("anonRef", ref2)
			try (def nested = root.pushObj("nested")) {
				nested.write("x", "y")
			}
			try (def arr = root.pushArray("items")) {
				arr.write("foo")
				arr.write(false)
				arr.write(9)
				arr.write(ref1)
			}
			root.writeBlankLine()
			try (def e1 = root.pushObjectEntry(obj1)) {
				e1.write("isa", "FakeISA")
			}
			try (def e2 = root.pushObjectEntry(obj2)) {
				e2.write("isa", "FakeISA")
			}
		}

		then:
		sw.toString() == expected
	}
}
