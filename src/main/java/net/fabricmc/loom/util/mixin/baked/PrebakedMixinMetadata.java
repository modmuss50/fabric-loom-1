/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
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

package net.fabricmc.loom.util.mixin.baked;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// This class is used to transfer metadata to the separate process that does the mixin baking
public class PrebakedMixinMetadata {
	// All of the normal runtime deps, mapped to named will also be on the classpath of the jvm that is currenly being ran
	private List<String> dependencies;
	private String targetJarFile;
	private String mappings;

	public List<Path> getDependencies() {
		return dependencies.stream()
				.map(Paths::get)
				.collect(Collectors.toList());
	}

	public Path getTargetJar() {
		return Paths.get(targetJarFile);
	}

	public Path getMappings() {
		return Paths.get(mappings);
	}

	public PrebakedMixinMetadata addDependencies(List<Path> dependencies) {
		if (this.dependencies == null) {
			this.dependencies = new ArrayList<>();
		}

		dependencies.stream()
				.map(Path::toAbsolutePath)
				.map(Path::toString)
				.forEach(this.dependencies::add);

		return this;
	}

	public PrebakedMixinMetadata setTargetJarFile(Path targetJarFile) {
		this.targetJarFile = targetJarFile.toAbsolutePath().toString();
		return this;
	}

	public PrebakedMixinMetadata setMappings(Path mappings) {
		this.mappings = mappings.toAbsolutePath().toString();
		return this;
	}
}
