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

package net.fabricmc.loom.task.tool.xcode.writer;

import java.io.StringWriter;
import java.util.IdentityHashMap;
import java.util.Map;

import net.fabricmc.loom.task.tool.xcode.OpenStepPropertyList;

public class OpenStepPropertyListWriterImpl implements OpenStepPropertyListWriter {
	private final StringWriter writer;
	private final Map<OpenStepPropertyList.BaseObject, String> objectIds = new IdentityHashMap<>();
	private int idCounter = 1;
	private int indentLevel = 0;

	public OpenStepPropertyListWriterImpl(StringWriter writer) {
		this.writer = writer;
	}

	public String allocId(OpenStepPropertyList.BaseObject obj) {
		return objectIds.computeIfAbsent(obj, k -> String.format("%024X", idCounter++));
	}

	public String refStr(OpenStepPropertyList.ObjRef<?> ref) {
		return allocId(ref.obj());
	}

	private void writeLine(String line) {
		for (int i = 0; i < indentLevel; i++) {
			writer.write("\t");
		}

		writer.write(line);
		writer.write("\n");
	}

	private void writeLine(String format, Object... args) {
		writeLine(String.format(format, args));
	}

	private void writeRawLine(String line) {
		writer.write(line);
		writer.write("\n");
	}

	private static String formatString(String value) {
		if (value.isEmpty() || !value.matches("[A-Za-z0-9_./$\\-]+")) {
			return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		}

		return value;
	}

	@Override
	public Obj pushRoot() {
		writeRawLine("// !$*UTF8*$!");
		return new ObjImpl(null);
	}

	private class ObjImpl implements OpenStepPropertyListWriter.Obj {
		ObjImpl(String openLine) {
			if (openLine != null) {
				writeLine(openLine);
			} else {
				writeRawLine("{");
			}

			indentLevel++;
		}

		@Override
		public void write(String key, String value) {
			writeLine("%s = %s;", formatString(key), formatString(value));
		}

		@Override
		public void write(String key, boolean value) {
			writeLine("%s = %s;", formatString(key), value ? "YES" : "NO");
		}

		@Override
		public void write(String key, int value) {
			writeLine("%s = %d;", formatString(key), value);
		}

		@Override
		public void write(String key, OpenStepPropertyList.ObjRef<?> value) {
			writeLine("%s = %s;", formatString(key), refStr(value));
		}

		@Override
		public Obj pushObj(String key) {
			return new ObjImpl(formatString(key) + " = {") {
				@Override
				public void close() {
					indentLevel--;
					writeLine("};");
				}
			};
		}

		@Override
		public Array pushArray(String key) {
			writeLine("%s = (", formatString(key));
			indentLevel++;
			return new ArrayImpl();
		}

		@Override
		public Obj pushObjectEntry(OpenStepPropertyList.BaseObject obj) {
			return new ObjImpl(allocId(obj) + " = {") {
				@Override
				public void close() {
					indentLevel--;
					writeLine("};");
				}
			};
		}

		@Override
		public void writeBlankLine() {
			writeRawLine("");
		}

		@Override
		public void close() {
			indentLevel--;
			writeLine("}");
		}
	}

	private class ArrayImpl implements OpenStepPropertyListWriter.Array {
		@Override
		public void write(String value) {
			writeLine("%s,", formatString(value));
		}

		@Override
		public void write(boolean value) {
			writeLine("%s,", value ? "YES" : "NO");
		}

		@Override
		public void write(int value) {
			writeLine("%d,", value);
		}

		@Override
		public void write(OpenStepPropertyList.ObjRef<?> value) {
			writeLine("%s,", refStr(value));
		}

		@Override
		public void close() {
			indentLevel--;
			writeLine(");");
		}
	}
}
