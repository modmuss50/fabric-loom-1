package net.fabricmc.loom.configuration.providers.mappings.tiny;

import net.fabricmc.loom.configuration.providers.mappings.PathSpec;
import net.fabricmc.loom.configuration.providers.mappings.MappingContext;
import net.fabricmc.loom.configuration.providers.mappings.MappingsSpec;

import org.jetbrains.annotations.Nullable;

public record MappingIOMappingsSpec(PathSpec path, @Nullable String zipContentPath) implements MappingsSpec<MappingIOLayer> {
	@Override
	public MappingIOLayer createLayer(MappingContext context) {
		if (zipContentPath != null) {
			return new MappingIOLayer(path.getPath(), zipContentPath);
		}

		return new MappingIOFileLayer(path.getPath());
	}
}
