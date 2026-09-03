/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
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

package net.fabricmc.loom.configuration.providers.minecraft;

import java.nio.file.Path;
import java.util.List;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.task.SplitMinecraftJarsTask;
import net.fabricmc.loom.util.Constants;

public final class SplitMinecraftProvider extends MinecraftProvider {
	public static final String SPLIT_TASK = "splitMinecraftJars";
	private Path minecraftClientOnlyJar;
	private Path minecraftCommonJar;

	public SplitMinecraftProvider(MinecraftMetadataProvider metadataProvider, ConfigContext configContext) {
		super(metadataProvider, configContext);
	}

	@Override
	protected void initFiles() {
		super.initFiles();

		minecraftClientOnlyJar = path("minecraft-client-only.jar");
		minecraftCommonJar = path("minecraft-common.jar");
	}

	@Override
	public List<Path> getMinecraftJars() {
		return List.of(minecraftClientOnlyJar, minecraftCommonJar);
	}

	@Override
	public MappingsNamespace getOfficialNamespace() {
		return MappingsNamespace.OFFICIAL;
	}

	@Override
	public void provide() throws Exception {
		super.provide();

		final Path clientJar = getMinecraftClientJar().toPath();
		final Path serverJar = getMinecraftExtractedServerJar().toPath();
		final var splitTask = getProject().getTasks().register(SPLIT_TASK, SplitMinecraftJarsTask.class, task -> {
			task.setDescription("Splits Minecraft into common and client-only jars.");
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.getClientJar().fileValue(clientJar.toFile());
			task.getServerJar().fileValue(serverJar.toFile());
			task.getServerBundleJar().fileValue(getMinecraftServerJar());
			task.getCommonJar().fileValue(minecraftCommonJar.toFile());
			task.getClientOnlyJar().fileValue(minecraftClientOnlyJar.toFile());
		});
		final MinecraftTaskGraph taskGraph = MinecraftTaskGraph.get(getProject());
		taskGraph.dependsOn(splitTask, clientJar, serverJar, getMinecraftServerJar().toPath());
		taskGraph.registerOutput(minecraftCommonJar, splitTask);
		taskGraph.registerOutput(minecraftClientOnlyJar, splitTask);
	}

	public Path getMinecraftClientOnlyJar() {
		return minecraftClientOnlyJar;
	}

	public Path getMinecraftCommonJar() {
		return minecraftCommonJar;
	}
}
