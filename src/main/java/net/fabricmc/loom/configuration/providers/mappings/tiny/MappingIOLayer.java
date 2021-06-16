package net.fabricmc.loom.configuration.providers.mappings.tiny;

import net.fabricmc.loom.configuration.providers.mappings.MappingLayer;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.MappingNsRenamer;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;

import org.jetbrains.annotations.Nullable;

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
