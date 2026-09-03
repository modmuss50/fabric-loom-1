/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2022 FabricMC
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

package net.fabricmc.loom.configuration.providers.minecraft.mapped;

import java.util.List;

import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.providers.minecraft.LegacyMergedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MergedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftSourceSets;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftTaskGraph;
import net.fabricmc.loom.configuration.providers.minecraft.SingleJarEnvType;
import net.fabricmc.loom.configuration.providers.minecraft.SingleJarMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.SplitMinecraftProvider;
import net.fabricmc.loom.task.MergeMinecraftJarsTask;
import net.fabricmc.loom.task.ProcessMinecraftJarTask;
import net.fabricmc.loom.util.Constants;

public abstract class NamedMinecraftProvider<M extends MinecraftProvider> extends AbstractMappedMinecraftProvider<M> {
	public NamedMinecraftProvider(Project project, M minecraftProvider) {
		super(project, minecraftProvider);
	}

	@Override
	public final MappingsNamespace getTargetNamespace() {
		return MappingsNamespace.NAMED;
	}

	public static final class MergedImpl extends NamedMinecraftProvider<MergedMinecraftProvider> implements Merged {
		public MergedImpl(Project project, MergedMinecraftProvider minecraftProvider) {
			super(project, minecraftProvider);
		}

		@Override
		public List<RemappedJars> getRemappedJars() {
			return List.of(
				new RemappedJars(minecraftProvider.getMergedJar(), getMergedJar(), minecraftProvider.getOfficialNamespace())
			);
		}

		@Override
		public List<MinecraftJar.Type> getDependencyTypes() {
			return List.of(MinecraftJar.Type.MERGED);
		}
	}

	public static final class LegacyMergedImpl extends NamedMinecraftProvider<LegacyMergedMinecraftProvider> implements Merged {
		private final SingleJarImpl server;
		private final SingleJarImpl client;

		public LegacyMergedImpl(Project project, LegacyMergedMinecraftProvider minecraftProvider) {
			super(project, minecraftProvider);
			server = new SingleJarImpl(project, minecraftProvider.getServerMinecraftProvider(), SingleJarEnvType.SERVER);
			client = new SingleJarImpl(project, minecraftProvider.getClientMinecraftProvider(), SingleJarEnvType.CLIENT);
		}

		@Override
		public List<MinecraftJar> provide(ProvideContext context) throws Exception {
			final ProvideContext childContext = context.withApplyDependencies(false);
			final List<MinecraftJar> minecraftJars = List.of(getMergedJar());

			// Map the client and server jars separately
			server.provide(childContext);
			client.provide(childContext);

			final MinecraftJar mergedJar = getMergedJar();
			final TaskProvider<MergeMinecraftJarsTask> mergeTask = getProject().getTasks().register("mergeLegacyMinecraftJarsToNamed", MergeMinecraftJarsTask.class, task -> {
				task.setDescription("Merges the legacy named Minecraft client and server jars.");
				task.setGroup(Constants.TaskGroup.FABRIC);
				task.getClientJar().fileValue(client.getEnvOnlyJar().toFile());
				task.getServerJar().fileValue(server.getEnvOnlyJar().toFile());
				task.getOutputJar().fileValue(mergedJar.toFile());
			});
			final MinecraftTaskGraph taskGraph = MinecraftTaskGraph.get(getProject());
			taskGraph.dependsOn(mergeTask, client.getEnvOnlyJar().getPath(), server.getEnvOnlyJar().getPath());
			taskGraph.registerOutput(mergedJar.getPath(), mergeTask);

			final var backupTask = getProject().getTasks().register("backupLegacyMinecraftJarNamed", ProcessMinecraftJarTask.class, task -> {
				task.setDescription("Backs up the legacy named Minecraft jar.");
				task.setGroup(Constants.TaskGroup.FABRIC);
				task.getInputJar().fileValue(mergedJar.toFile());
				task.getOutputJar().fileValue(getBackupJarPath(mergedJar).toFile());
			});
			taskGraph.dependsOn(backupTask, mergedJar.getPath());
			taskGraph.registerOutput(getBackupJarPath(mergedJar), backupTask);

			if (context.applyDependencies()) {
				MinecraftSourceSets.get(getProject()).applyDependencies(
						(configuration, type) -> getProject().getDependencies().add(configuration, taskGraph.files(getJar(type))),
						getDependencyTypes()
				);
			}

			return minecraftJars;
		}

		@Override
		public List<RemappedJars> getRemappedJars() {
			// The delegate providers will handle the remapping
			throw new UnsupportedOperationException("LegacyMergedImpl does not support getRemappedJars");
		}

		@Override
		public List<? extends OutputJar> getOutputJars() {
			return List.of(
				new SimpleOutputJar(getMergedJar())
			);
		}

		@Override
		public List<MinecraftJar.Type> getDependencyTypes() {
			return List.of(MinecraftJar.Type.MERGED);
		}
	}

	public static final class SplitImpl extends NamedMinecraftProvider<SplitMinecraftProvider> implements Split {
		public SplitImpl(Project project, SplitMinecraftProvider minecraftProvider) {
			super(project, minecraftProvider);
		}

		@Override
		public List<RemappedJars> getRemappedJars() {
			return List.of(
				new RemappedJars(minecraftProvider.getMinecraftCommonJar(), getCommonJar(), minecraftProvider.getOfficialNamespace()),
				new RemappedJars(minecraftProvider.getMinecraftClientOnlyJar(), getClientOnlyJar(), minecraftProvider.getOfficialNamespace(), minecraftProvider.getMinecraftCommonJar())
			);
		}

		@Override
		public List<MinecraftJar.Type> getDependencyTypes() {
			return List.of(MinecraftJar.Type.CLIENT_ONLY, MinecraftJar.Type.COMMON);
		}
	}

	public static final class SingleJarImpl extends NamedMinecraftProvider<SingleJarMinecraftProvider> implements SingleJar {
		private final SingleJarEnvType env;

		private SingleJarImpl(Project project, SingleJarMinecraftProvider minecraftProvider, SingleJarEnvType env) {
			super(project, minecraftProvider);
			this.env = env;
		}

		public static SingleJarImpl server(Project project, SingleJarMinecraftProvider minecraftProvider) {
			return new SingleJarImpl(project, minecraftProvider, SingleJarEnvType.SERVER);
		}

		public static SingleJarImpl client(Project project, SingleJarMinecraftProvider minecraftProvider) {
			return new SingleJarImpl(project, minecraftProvider, SingleJarEnvType.CLIENT);
		}

		@Override
		public List<RemappedJars> getRemappedJars() {
			return List.of(
				new RemappedJars(minecraftProvider.getMinecraftEnvOnlyJar(), getEnvOnlyJar(), minecraftProvider.getOfficialNamespace())
			);
		}

		@Override
		public List<MinecraftJar.Type> getDependencyTypes() {
			return List.of(envType());
		}

		@Override
		public SingleJarEnvType env() {
			return env;
		}
	}
}
