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

package net.fabricmc.loom.configuration.processors;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import net.fabricmc.loom.LoomGradlePlugin;

@ApiStatus.Internal
public record MinecraftJarProcessorAnalysis(List<String> binaryTransformations, List<String> mappingTransformations, List<String> clientOnlyModIds) {
	public static final MinecraftJarProcessorAnalysis EMPTY = new MinecraftJarProcessorAnalysis(List.of(), List.of(), List.of());

	public MinecraftJarProcessorAnalysis {
		binaryTransformations = List.copyOf(binaryTransformations);
		mappingTransformations = List.copyOf(mappingTransformations);
		clientOnlyModIds = List.copyOf(clientOnlyModIds);
	}

	public static MinecraftJarProcessorAnalysis read(Path path) throws IOException {
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return LoomGradlePlugin.GSON.fromJson(reader, MinecraftJarProcessorAnalysis.class);
		}
	}

	public void write(Path path) throws IOException {
		Files.createDirectories(path.getParent());

		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			LoomGradlePlugin.GSON.toJson(this, writer);
		}
	}
}
