/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022 FabricMC
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

package net.fabricmc.loom.configuration.providers.mappings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import net.fabricmc.loom.util.service.SharedService;
import net.fabricmc.loom.util.service.SharedServiceManager;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public final class TinyMappingsService implements SharedService {
	private MemoryMappingTree mappingTree;

	public TinyMappingsService(Path tinyMappings, String srcNs) {
		try {
			this.mappingTree = new MemoryMappingTree();
			MappingSourceNsSwitch srcNsSwitch = new MappingSourceNsSwitch(this.mappingTree, srcNs);
			MappingReader.read(tinyMappings, srcNsSwitch);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read mappings", e);
		}
	}

	public static synchronized TinyMappingsService create(SharedServiceManager serviceManager, Path tinyMappings, String srcNs) {
		return serviceManager.getOrCreateService("TinyMappingsService:" + tinyMappings.toAbsolutePath() + " [" + srcNs + "]", () -> new TinyMappingsService(tinyMappings, srcNs));
	}

	public MemoryMappingTree getMappingTree() {
		return mappingTree;
	}
}
