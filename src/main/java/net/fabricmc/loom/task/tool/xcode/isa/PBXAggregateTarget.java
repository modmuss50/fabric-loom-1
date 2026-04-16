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

package net.fabricmc.loom.task.tool.xcode.isa;

import net.fabricmc.loom.task.tool.xcode.OpenStepPropertyList;
import net.fabricmc.loom.task.tool.xcode.writer.OpenStepPropertyListWriter;

public class PBXAggregateTarget extends OpenStepPropertyList.BaseObject {
	public OpenStepPropertyList.ObjRef<XCConfigurationList> buildConfigurationList;
	public String name;

	@Override
	public String isa() {
		return "PBXAggregateTarget";
	}

	@Override
	public void write(OpenStepPropertyListWriter.Obj obj) {
		super.write(obj);
		obj.write("buildConfigurationList", buildConfigurationList);
		try (var phases = obj.pushArray("buildPhases")) { }
		try (var deps = obj.pushArray("dependencies")) { }
		obj.write("name", name);
		obj.write("productName", name);
	}
}
