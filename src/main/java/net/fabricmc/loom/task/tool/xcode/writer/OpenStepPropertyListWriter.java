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

import net.fabricmc.loom.task.tool.xcode.OpenStepPropertyList;

public interface OpenStepPropertyListWriter {
	/** Writes the UTF-8 header line and opens the root brace. */
	Obj pushRoot();

	interface Obj extends AutoCloseable {
		void write(String key, String value);
		void write(String key, boolean value);
		void write(String key, int value);
		void write(String key, OpenStepPropertyList.ObjRef<?> value);

		/** Opens a nested anonymous dict: {@code key = { ... };} */
		Obj pushObj(String key);
		/** Opens an array: {@code key = ( ... );} */
		Array pushArray(String key);

		/** Writes {@code ID = { ... };} for an ISA object entry. */
		Obj pushObjectEntry(OpenStepPropertyList.BaseObject obj);

		void writeBlankLine();

		@Override
		void close();
	}

	interface Array extends AutoCloseable {
		void write(String value);
		void write(boolean value);
		void write(int value);
		void write(OpenStepPropertyList.ObjRef<?> value);

		@Override
		void close();
	}
}
