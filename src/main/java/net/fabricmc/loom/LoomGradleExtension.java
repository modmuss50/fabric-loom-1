/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2021 FabricMC
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

package net.fabricmc.loom;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.mercury.Mercury;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.SourceSet;

import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.InstallerData;
import net.fabricmc.loom.configuration.LoomDependencyManager;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerFile;
import net.fabricmc.loom.configuration.processors.JarProcessorManager;
import net.fabricmc.loom.configuration.providers.mappings.MappingsProviderImpl;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.intermediary.IntermediaryMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.named.NamedMinecraftProvider;
import net.fabricmc.loom.extension.LoomFiles;
import net.fabricmc.loom.extension.MixinExtension;

public interface LoomGradleExtension extends LoomGradleExtensionAPI {
	static LoomGradleExtension get(Project project) {
		return (LoomGradleExtension) project.getExtensions().getByName("loom");
	}

	LoomFiles getFiles();

	default NamedDomainObjectProvider<Configuration> createLazyConfiguration(String name) {
		return createLazyConfiguration(name, config -> {
		});
	}

	NamedDomainObjectProvider<Configuration> createLazyConfiguration(String name, Action<? super Configuration> configurationAction);

	NamedDomainObjectProvider<Configuration> getLazyConfigurationProvider(String name);

	MappingSet getOrCreateSrcMappingCache(int id, Supplier<MappingSet> factory);

	Mercury getOrCreateSrcMercuryCache(int id, Supplier<Mercury> factory);

	ConfigurableFileCollection getUnmappedModCollection();

	void setInstallerData(InstallerData data);

	InstallerData getInstallerData();

	void setDependencyManager(LoomDependencyManager dependencyManager);

	LoomDependencyManager getDependencyManager();

	void setJarProcessorManager(JarProcessorManager jarProcessorManager);

	JarProcessorManager getJarProcessorManager();

	default MinecraftProvider getMinecraftProvider() {
		return getDependencyManager().getProvider(MinecraftProvider.class);
	}

	default MappingsProviderImpl getMappingsProvider() {
		return getDependencyManager().getProvider(MappingsProviderImpl.class);
	}

	default NamedMinecraftProvider<?> getNamedMinecraftProvider() {
		return getMappingsProvider().getNamedMinecraftProvider();
	}

	default IntermediaryMinecraftProvider<?> getIntermediaryMinecraftProvider() {
		return getMappingsProvider().getIntermediaryMinecraftProvider();
	}

	default List<Path> getMinecraftJars(MappingsNamespace mappingsNamespace) {
		return switch (mappingsNamespace) {
		case NAMED -> getNamedMinecraftProvider().getMinecraftJars();
		case INTERMEDIARY -> getIntermediaryMinecraftProvider().getMinecraftJars();
		case OFFICIAL -> getMinecraftProvider().getMinecraftJars();
		};
	}

	FileCollection getMinecraftJarsCollection(MappingsNamespace mappingsNamespace);

	File getMixinMappings(SourceSet sourceSet);

	FileCollection getAllMixinMappings();

	boolean isRootProject();

	default String getIntermediaryUrl(String minecraftVersion) {
		return String.format(this.getIntermediaryUrl().get(), minecraftVersion);
	}

	@Override
	MixinExtension getMixin();

	List<AccessWidenerFile> getTransitiveAccessWideners();

	void addTransitiveAccessWideners(List<AccessWidenerFile> accessWidenerFiles);
}
