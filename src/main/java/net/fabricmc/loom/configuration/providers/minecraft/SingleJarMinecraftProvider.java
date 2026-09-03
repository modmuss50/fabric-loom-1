/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2025 FabricMC
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
import net.fabricmc.loom.task.SanitizeMinecraftJarTask;
import net.fabricmc.loom.util.Constants;

public abstract sealed class SingleJarMinecraftProvider extends MinecraftProvider permits SingleJarMinecraftProvider.Server, SingleJarMinecraftProvider.Client {
	public static final String SANITIZE_SERVER_TASK = "sanitizeMinecraftServerJar";
	public static final String SANITIZE_CLIENT_TASK = "sanitizeMinecraftClientJar";
	private final MappingsNamespace officialNamespace;
	private Path minecraftEnvOnlyJar;

	private SingleJarMinecraftProvider(MinecraftMetadataProvider metadataProvider, ConfigContext configContext, MappingsNamespace officialNamespace) {
		super(metadataProvider, configContext);
		this.officialNamespace = officialNamespace;
	}

	public static SingleJarMinecraftProvider.Server server(MinecraftMetadataProvider metadataProvider, ConfigContext configContext) {
		return new SingleJarMinecraftProvider.Server(metadataProvider, configContext, MappingsNamespace.OFFICIAL);
	}

	public static SingleJarMinecraftProvider.Client client(MinecraftMetadataProvider metadataProvider, ConfigContext configContext) {
		return new SingleJarMinecraftProvider.Client(metadataProvider, configContext, MappingsNamespace.OFFICIAL);
	}

	static SingleJarMinecraftProvider.Server legacyServer(MinecraftMetadataProvider metadataProvider, ConfigContext configContext) {
		return new SingleJarMinecraftProvider.Server(metadataProvider, configContext, MappingsNamespace.SERVER_OFFICIAL);
	}

	static SingleJarMinecraftProvider.Client legacyClient(MinecraftMetadataProvider metadataProvider, ConfigContext configContext) {
		return new SingleJarMinecraftProvider.Client(metadataProvider, configContext, MappingsNamespace.CLIENT_OFFICIAL);
	}

	@Override
	protected void initFiles() {
		super.initFiles();

		minecraftEnvOnlyJar = path("minecraft-%s-only.jar".formatted(type()));
	}

	@Override
	public List<Path> getMinecraftJars() {
		return List.of(minecraftEnvOnlyJar);
	}

	@Override
	public void provide() throws Exception {
		super.provide();
		provideFrom(getInputJar(this));
	}

	void provideFrom(Path inputJar) {
		initialize();
		final String taskName = type() == SingleJarEnvType.SERVER ? SANITIZE_SERVER_TASK : SANITIZE_CLIENT_TASK;
		final var sanitizeTask = getProject().getTasks().register(taskName, SanitizeMinecraftJarTask.class, task -> {
			task.setDescription("Sanitizes the Minecraft %s jar.".formatted(type()));
			task.setGroup(Constants.TaskGroup.FABRIC);
			task.getInputJar().fileValue(inputJar.toFile());
			task.getOutputJar().fileValue(minecraftEnvOnlyJar.toFile());
		});
		final MinecraftTaskGraph taskGraph = MinecraftTaskGraph.get(getProject());
		taskGraph.dependsOn(sanitizeTask, inputJar);
		taskGraph.registerOutput(minecraftEnvOnlyJar, sanitizeTask);
	}

	public Path getMinecraftEnvOnlyJar() {
		return minecraftEnvOnlyJar;
	}

	@Override
	public MappingsNamespace getOfficialNamespace() {
		return officialNamespace;
	}

	public MappingsNamespace getLegacyOfficialNamespace() {
		return type() == SingleJarEnvType.SERVER
				? MappingsNamespace.SERVER_OFFICIAL
				: MappingsNamespace.CLIENT_OFFICIAL;
	}

	abstract SingleJarEnvType type();

	abstract Path getInputJar(SingleJarMinecraftProvider provider) throws Exception;

	public static final class Server extends SingleJarMinecraftProvider {
		private Server(MinecraftMetadataProvider metadataProvider, ConfigContext configContext, MappingsNamespace officialNamespace) {
			super(metadataProvider, configContext, officialNamespace);
		}

		@Override
		public SingleJarEnvType type() {
			return SingleJarEnvType.SERVER;
		}

		@Override
		public Path getInputJar(SingleJarMinecraftProvider provider) {
			return provider.getMinecraftExtractedServerJar().toPath();
		}

		@Override
		protected boolean provideServer() {
			return true;
		}

		@Override
		protected boolean provideClient() {
			return false;
		}
	}

	public static final class Client extends SingleJarMinecraftProvider {
		private Client(MinecraftMetadataProvider metadataProvider, ConfigContext configContext, MappingsNamespace officialNamespace) {
			super(metadataProvider, configContext, officialNamespace);
		}

		@Override
		public SingleJarEnvType type() {
			return SingleJarEnvType.CLIENT;
		}

		@Override
		public Path getInputJar(SingleJarMinecraftProvider provider) throws Exception {
			return provider.getMinecraftClientJar().toPath();
		}

		@Override
		protected boolean provideServer() {
			return false;
		}

		@Override
		protected boolean provideClient() {
			return true;
		}
	}
}
