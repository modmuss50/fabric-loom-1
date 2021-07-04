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

package net.fabricmc.loom.configuration.providers.mappings.tiny;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.configuration.providers.mappings.MappingLayer;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.MappingNsRenamer;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;

// TODO how do we want to get extras out of here such as unpick? I guess another interface
public record MappingIOLayer(Path path, @Nullable String zipPath, Map<String, String> nameMap) implements MappingLayer {
	@Override
	public void visit(MappingVisitor mappingVisitor) throws IOException {
		try (Reader reader = readMappings()) {
			List<String> namespaces = MappingReader.getNamespaces(reader, null);

			if (!namespaces.get(0).equals(getSourceNamespace().stringValue())) {
				// Switch the source if its not correct
				mappingVisitor = new MappingSourceNsSwitch(mappingVisitor, getSourceNamespace().stringValue());
			}

			if (!namespaces.contains(getSourceNamespace().stringValue())) {
				// TODO look into passing a value that says if MappingUtil.NS_SOURCE_FALLBACK or MappingUtil.NS_TARGET_FALLBACK is the source
				throw new UnsupportedOperationException("Mapping do not contain source mapping %s, it only has [%s]".formatted(getSourceNamespace().stringValue(), String.join(", ", namespaces)));
			} else {
				// TODO no need to remap now?
			}

			if (nameMap() != null) {
				mappingVisitor = new MappingNsRenamer(mappingVisitor, nameMap());
			}

			MappingReader.read(reader, null, mappingVisitor);
		}
	}

	public Reader readMappings() throws IOException {
		if (zipPath() != null) {
			return readZip();
		}

		return readFile();
	}

	private Reader readFile() throws IOException {
		return Files.newBufferedReader(path, StandardCharsets.UTF_8);
	}

	private Reader readZip() throws IOException {
		try (ZipFile zipFile = new ZipFile(path().toFile())) {
			ZipEntry entry = zipFile.getEntry(Objects.requireNonNull(zipPath()));
			Objects.requireNonNull(entry, "Could not find %s in zip file %s".formatted(zipPath(), path().toString()));

			return new InputStreamReader(zipFile.getInputStream(entry));
		}
	}
}
