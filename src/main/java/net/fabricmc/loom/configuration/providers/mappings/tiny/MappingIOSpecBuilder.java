package net.fabricmc.loom.configuration.providers.mappings.tiny;

import com.google.common.base.Preconditions;

import net.fabricmc.loom.configuration.providers.mappings.PathSpec;

import org.jetbrains.annotations.Nullable;

public class MappingIOSpecBuilder {
	private PathSpec path;
	@Nullable
	private String zipContents;

	public MappingIOSpecBuilder path(Object object) {
		Preconditions.checkState(path == null, "Only one path can be set per layer");
		this.path = PathSpec.create(object);
		return this;
	}

	public MappingIOSpecBuilder setZipContents(String zipContents) {
		this.zipContents = zipContents;
		return this;
	}

	public MappingIOSpec build() {
		Preconditions.checkNotNull(path, "A path must be set");
		return new MappingIOSpec(path, zipContents);
	}
}
