/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2026 FabricMC
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

package net.fabricmc.loom.configuration.ifaceinject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.configuration.ifaceinject.InterfaceInjectionProcessor.InjectedInterface;
import net.fabricmc.loom.configuration.processors.MinecraftJarProcessorAnalysis;
import net.fabricmc.loom.task.AbstractLoomTask;

@ApiStatus.Internal
@DisableCachingByDefault(because = "Minecraft jars should not be stored in the build cache")
public abstract class ProcessMinecraftInterfaceInjectionsTask extends AbstractLoomTask {
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getInputJar();

	@Input
	public abstract ListProperty<String> getInjectedInterfaces();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	@Optional
	public abstract RegularFileProperty getProcessorAnalysis();

	@InputFiles
	@PathSensitive(PathSensitivity.NONE)
	public abstract ConfigurableFileCollection getProcessorSources();

	@Input
	public abstract SetProperty<String> getClientOnlyModIds();

	@Input
	public abstract Property<Boolean> getIncludesClient();

	@Input
	public abstract Property<Boolean> getDisableObfuscation();

	@Input
	public abstract Property<String> getProductionNamespace();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	@Optional
	public abstract RegularFileProperty getMappingsFile();

	@InputFiles
	@PathSensitive(PathSensitivity.NONE)
	public abstract ConfigurableFileCollection getRemapClasspath();

	@Input
	public abstract SetProperty<String> getKnownIndyBsms();

	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public ProcessMinecraftInterfaceInjectionsTask() {
		getInjectedInterfaces().convention(Collections.emptyList());
		getProcessorSources().from(Collections.emptyList());
		getClientOnlyModIds().convention(Collections.emptySet());
		getRemapClasspath().from(Collections.emptyList());
		getKnownIndyBsms().convention(Collections.emptySet());
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(ProcessAction.class, parameters -> {
			parameters.getInputJar().set(getInputJar());
			parameters.getInjectedInterfaces().set(getInjectedInterfaces());
			parameters.getProcessorAnalysis().set(getProcessorAnalysis());
			parameters.getClientOnlyModIds().set(getClientOnlyModIds());
			parameters.getIncludesClient().set(getIncludesClient());
			parameters.getDisableObfuscation().set(getDisableObfuscation());
			parameters.getProductionNamespace().set(getProductionNamespace());
			parameters.getMappingsFile().set(getMappingsFile());
			parameters.getRemapClasspath().from(getRemapClasspath());
			parameters.getKnownIndyBsms().set(getKnownIndyBsms());
			parameters.getOutputJar().set(getOutputJar());
		});
	}

	public static String encodeInjectedInterface(String modId, String className, String interfaceName, @Nullable String generics) {
		return encode(modId, className, interfaceName, generics == null ? "" : generics);
	}

	private static String encode(String... values) {
		final Base64.Encoder encoder = Base64.getEncoder();
		return String.join(":", Arrays.stream(values)
				.map(value -> encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)))
				.toList());
	}

	public interface Parameters extends WorkParameters {
		RegularFileProperty getInputJar();
		ListProperty<String> getInjectedInterfaces();
		RegularFileProperty getProcessorAnalysis();
		SetProperty<String> getClientOnlyModIds();
		Property<Boolean> getIncludesClient();
		Property<Boolean> getDisableObfuscation();
		Property<String> getProductionNamespace();
		RegularFileProperty getMappingsFile();
		ConfigurableFileCollection getRemapClasspath();
		SetProperty<String> getKnownIndyBsms();
		RegularFileProperty getOutputJar();
	}

	public abstract static class ProcessAction implements WorkAction<Parameters> {
		@Override
		public void execute() {
			final Path inputJar = getParameters().getInputJar().get().getAsFile().toPath();
			final Path outputJar = getParameters().getOutputJar().get().getAsFile().toPath();

			try {
				Files.createDirectories(outputJar.getParent());
				Files.deleteIfExists(outputJar);
				Files.copy(inputJar, outputJar, StandardCopyOption.REPLACE_EXISTING);
				process(outputJar);
			} catch (Exception e) {
				cleanOutput(outputJar, e);
				throw new RuntimeException("Failed to apply Minecraft interface injections", e);
			}
		}

		private void process(Path outputJar) throws IOException {
			final MinecraftJarProcessorAnalysis analysis = getParameters().getProcessorAnalysis().isPresent()
					? MinecraftJarProcessorAnalysis.read(getParameters().getProcessorAnalysis().get().getAsFile().toPath())
					: null;
			final List<String> encodedInterfaces = analysis != null ? analysis.binaryTransformations() : getParameters().getInjectedInterfaces().get();

			if (encodedInterfaces.isEmpty()) {
				return;
			}

			final boolean disableObfuscation = getParameters().getDisableObfuscation().get();

			if (!disableObfuscation && !getParameters().getMappingsFile().isPresent()) {
				throw new IllegalStateException("Mappings are required to remap injected interfaces");
			}

			final List<String> clientOnlyModIds = analysis != null ? analysis.clientOnlyModIds() : List.copyOf(getParameters().getClientOnlyModIds().get());
			final List<InjectedInterface> injectedInterfaces = encodedInterfaces.stream()
					.map(ProcessAction::decodeInjectedInterface)
					.toList();
			final InterfaceInjectionProcessor.Spec spec = new InterfaceInjectionProcessor.Spec(
					injectedInterfaces,
					Set.copyOf(clientOnlyModIds)
			);
			InterfaceInjectionProcessor.processJarForTask(
					outputJar,
					spec,
					getParameters().getIncludesClient().get(),
					disableObfuscation,
					getParameters().getProductionNamespace().get(),
					disableObfuscation ? null : getParameters().getMappingsFile().get().getAsFile().toPath(),
					getParameters().getRemapClasspath().getFiles().stream().map(file -> file.toPath()).toList(),
					getParameters().getKnownIndyBsms().get()
			);
		}

		private static InjectedInterface decodeInjectedInterface(String value) {
			final String[] parts = value.split(":", -1);

			if (parts.length != 4) {
				throw new IllegalArgumentException("Invalid encoded injected interface");
			}

			final Base64.Decoder decoder = Base64.getDecoder();
			final List<String> values = Arrays.stream(parts)
					.map(part -> new String(decoder.decode(part), StandardCharsets.UTF_8))
					.toList();
			return new InjectedInterface(values.get(0), values.get(1), values.get(2), values.get(3).isEmpty() ? null : values.get(3));
		}

		private static void cleanOutput(Path output, Exception failure) {
			try {
				Files.deleteIfExists(output);
			} catch (IOException e) {
				failure.addSuppressed(e);
			}
		}
	}
}
