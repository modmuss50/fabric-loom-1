package net.fabricmc.loom.configuration.providers.mappings;

import com.google.common.base.Preconditions;

import net.fabricmc.loom.util.Checksum;

import org.gradle.api.file.RegularFileProperty;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

public class PathSpec {
	private final Path path;
	private final byte[] fileHash;

	private PathSpec(Path path) {
		Preconditions.checkState(Files.exists(path), "%s does not exist".formatted(path.toString()));
		this.path = path;
		this.fileHash = Checksum.sha256(path.toFile());
	}

	public static PathSpec create(Object object) {
		Objects.requireNonNull(object, "Input file cannot be null");

		if (object instanceof File file) {
			return create(file.toPath());
		} else if (object instanceof Path path) {
			return new PathSpec(path);
		} else if (object instanceof RegularFileProperty fileProperty) {
			return create(fileProperty.getAsFile().get());
		} else {
			throw new UnsupportedOperationException("Unsupported file type of " + object.getClass());
		}
	}

	public Path getPath() {
		return path;
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof PathSpec other) {
			return Arrays.equals(this.fileHash, other.fileHash);
		}

		return false;
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(fileHash);
	}

	@Override
	public String toString() {
		return "PathSpec{" +
				"path=" + path +
				'}';
	}
}
