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

package net.fabricmc.loom.configuration.accesswidener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
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

import net.fabricmc.classtweaker.api.ClassTweaker;
import net.fabricmc.classtweaker.api.ClassTweakerReader;
import net.fabricmc.classtweaker.api.visitor.ClassTweakerVisitor;
import net.fabricmc.classtweaker.visitors.ClassTweakerRemapperVisitor;
import net.fabricmc.classtweaker.visitors.TransitiveOnlyFilter;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.processors.MinecraftJarProcessorAnalysis;
import net.fabricmc.loom.task.AbstractLoomTask;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.fmj.ModEnvironment;
import net.fabricmc.tinyremapper.TinyRemapper;

@ApiStatus.Internal
@DisableCachingByDefault(because = "Minecraft jars should not be stored in the build cache")
public abstract class ProcessMinecraftAccessWidenersTask extends AbstractLoomTask {
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getInputJar();

	@Input
	public abstract ListProperty<String> getAccessWideners();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	@Optional
	public abstract RegularFileProperty getProcessorAnalysis();

	@InputFiles
	@PathSensitive(PathSensitivity.NONE)
	public abstract ConfigurableFileCollection getAccessWidenerSources();

	@Internal
	public abstract ListProperty<String> getAccessWidenerSourcePaths();

	@Input
	protected List<String> getAccessWidenerSourceHashes() {
		return getAccessWidenerSourcePaths().get().stream()
				.map(Path::of)
				.map(Checksum::of)
				.map(checksum -> checksum.sha256().hex())
				.toList();
	}

	@Input
	public abstract Property<Boolean> getIncludesClient();

	@Input
	public abstract Property<Boolean> getIncludesServer();

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
	public ProcessMinecraftAccessWidenersTask() {
		getAccessWideners().convention(Collections.emptyList());
		getAccessWidenerSources().from(Collections.emptyList());
		getAccessWidenerSourcePaths().convention(Collections.emptyList());
		getRemapClasspath().from(Collections.emptyList());
		getKnownIndyBsms().convention(Collections.emptySet());
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(ProcessAction.class, parameters -> {
			parameters.getInputJar().set(getInputJar());
			parameters.getAccessWideners().set(getAccessWideners());
			parameters.getProcessorAnalysis().set(getProcessorAnalysis());
			parameters.getAccessWidenerSources().from(getAccessWidenerSources());
			parameters.getAccessWidenerSourcePaths().set(getAccessWidenerSourcePaths());
			parameters.getIncludesClient().set(getIncludesClient());
			parameters.getIncludesServer().set(getIncludesServer());
			parameters.getDisableObfuscation().set(getDisableObfuscation());
			parameters.getProductionNamespace().set(getProductionNamespace());
			parameters.getMappingsFile().set(getMappingsFile());
			parameters.getRemapClasspath().from(getRemapClasspath());
			parameters.getKnownIndyBsms().set(getKnownIndyBsms());
			parameters.getOutputJar().set(getOutputJar());
		});
	}

	public static String encodeAccessWidener(int processorIndex, ModEnvironment environment, boolean local, boolean transitiveOnly, int sourceIndex, String pathWithinJar) {
		return "%d:%s:%s:%s:%s:%d:%s".formatted(
				processorIndex,
				environment.isClient(),
				environment.isServer(),
				local,
				transitiveOnly,
				sourceIndex,
				Base64.getEncoder().encodeToString(pathWithinJar.getBytes(StandardCharsets.UTF_8))
		);
	}

	public static String encodeAccessWidenerData(ModEnvironment environment, boolean local, boolean transitiveOnly, byte[] data) {
		return "data:%s:%s:%s:%s:%s".formatted(
				environment.isClient(),
				environment.isServer(),
				local,
				transitiveOnly,
				Base64.getEncoder().encodeToString(data)
		);
	}

	public interface Parameters extends WorkParameters {
		RegularFileProperty getInputJar();
		ListProperty<String> getAccessWideners();
		RegularFileProperty getProcessorAnalysis();
		ConfigurableFileCollection getAccessWidenerSources();
		ListProperty<String> getAccessWidenerSourcePaths();
		Property<Boolean> getIncludesClient();
		Property<Boolean> getIncludesServer();
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
				throw new RuntimeException("Failed to apply access wideners to Minecraft jar: " + inputJar, e);
			}
		}

		private void process(Path outputJar) throws IOException {
			int processorIndex = -1;
			List<AccessWidenerInput> processorInputs = new ArrayList<>();
			final List<Path> sources = getParameters().getAccessWidenerSourcePaths().get().stream()
					.map(Path::of)
					.toList();

			final List<String> accessWideners = getParameters().getProcessorAnalysis().isPresent()
					? MinecraftJarProcessorAnalysis.read(getParameters().getProcessorAnalysis().get().getAsFile().toPath()).binaryTransformations()
					: getParameters().getAccessWideners().get();

			for (String value : accessWideners) {
				final AccessWidenerInput input = AccessWidenerInput.decode(value, sources);

				if (processorIndex != input.processorIndex()) {
					if (processorIndex > input.processorIndex()) {
						throw new IllegalArgumentException("Access widener processor inputs are out of order");
					}

					applyProcessor(outputJar, processorInputs);
					processorIndex = input.processorIndex();
					processorInputs = new ArrayList<>();
				}

				processorInputs.add(input);
			}

			applyProcessor(outputJar, processorInputs);
		}

		private void applyProcessor(Path outputJar, List<AccessWidenerInput> inputs) throws IOException {
			if (inputs.isEmpty()) {
				return;
			}

			final List<AccessWidenerInput> supportedInputs = inputs.stream()
					.filter(this::supportsEnvironment)
					.toList();
			final ClassTweaker classTweaker = ClassTweaker.newInstance();

			if (getParameters().getDisableObfuscation().get()) {
				for (AccessWidenerInput input : supportedInputs) {
					readOfficial(input, classTweaker);
				}
			} else {
				readAndRemap(supportedInputs, classTweaker);
			}

			new AccessWidenerTransformer(classTweaker).apply(outputJar);
		}

		private boolean supportsEnvironment(AccessWidenerInput input) {
			return getParameters().getIncludesClient().get() && input.client()
					|| getParameters().getIncludesServer().get() && input.server();
		}

		private void readOfficial(AccessWidenerInput input, ClassTweakerVisitor visitor) throws IOException {
			visitor = transitiveOnly(input, visitor);
			final ClassTweakerReader.Header header = ClassTweakerReader.readHeader(input.data());

			if (!header.getNamespace().equals(MappingsNamespace.OFFICIAL.toString())) {
				throw new IOException("Expected official namespace for access widener entry, found: " + header.getNamespace());
			}

			ClassTweakerReader.create(visitor).read(input.data());
		}

		private void readAndRemap(List<AccessWidenerInput> inputs, ClassTweakerVisitor visitor) throws IOException {
			TinyRemapper remapper = null;

			try {
				for (AccessWidenerInput input : inputs) {
					ClassTweakerVisitor entryVisitor = transitiveOnly(input, visitor);

					if (!input.local()) {
						final ClassTweakerReader.Header header = ClassTweakerReader.readHeader(input.data());

						if (!header.getNamespace().equals(MappingsNamespace.NAMED.toString())) {
							if (remapper == null) {
								remapper = createRemapper();
							}

							entryVisitor = new ClassTweakerRemapperVisitor(
									entryVisitor,
									remapper.getEnvironment().getRemapper(),
									getParameters().getProductionNamespace().get(),
									MappingsNamespace.NAMED.toString()
							);
						}
					}

					ClassTweakerReader.create(entryVisitor).read(input.data());
				}
			} finally {
				if (remapper != null) {
					remapper.finish();
				}
			}
		}

		private TinyRemapper createRemapper() throws IOException {
			if (!getParameters().getMappingsFile().isPresent()) {
				throw new IllegalStateException("Mappings are required to remap dependency access wideners");
			}

			final TinyRemapper remapper = TinyRemapperHelper.getTinyRemapper(
					getParameters().getMappingsFile().get().getAsFile().toPath(),
					getParameters().getProductionNamespace().get(),
					MappingsNamespace.NAMED.toString(),
					false,
					getParameters().getKnownIndyBsms().get(),
					builder -> { }
			);

			for (var file : getParameters().getRemapClasspath()) {
				remapper.readClassPath(file.toPath());
			}

			return remapper;
		}

		private static ClassTweakerVisitor transitiveOnly(AccessWidenerInput input, ClassTweakerVisitor visitor) {
			return input.transitiveOnly() ? new TransitiveOnlyFilter(visitor) : visitor;
		}

		private static void cleanOutput(Path output, Exception failure) {
			try {
				Files.deleteIfExists(output);
			} catch (IOException e) {
				failure.addSuppressed(e);
			}
		}
	}

	private record AccessWidenerInput(int processorIndex, boolean client, boolean server, boolean local, boolean transitiveOnly, byte[] data) {
		private static AccessWidenerInput decode(String value, List<Path> sources) throws IOException {
			if (value.startsWith("data:")) {
				final String[] parts = value.split(":", 6);

				if (parts.length != 6) {
					throw new IllegalArgumentException("Invalid encoded access widener data");
				}

				return new AccessWidenerInput(
						0,
						Boolean.parseBoolean(parts[1]),
						Boolean.parseBoolean(parts[2]),
						Boolean.parseBoolean(parts[3]),
						Boolean.parseBoolean(parts[4]),
						Base64.getDecoder().decode(parts[5])
				);
			}

			final String[] parts = value.split(":", 7);

			if (parts.length != 7) {
				throw new IllegalArgumentException("Invalid encoded access widener input");
			}

			final int sourceIndex = Integer.parseInt(parts[5]);

			if (sourceIndex < 0 || sourceIndex >= sources.size()) {
				throw new IllegalArgumentException("Invalid access widener source index: " + sourceIndex);
			}

			final Path source = sources.get(sourceIndex);
			final String pathWithinJar = new String(Base64.getDecoder().decode(parts[6]), StandardCharsets.UTF_8);
			final byte[] data = pathWithinJar.isEmpty()
					? Files.readAllBytes(source)
					: ZipUtils.unpack(source, pathWithinJar);

			return new AccessWidenerInput(
					Integer.parseInt(parts[0]),
					Boolean.parseBoolean(parts[1]),
					Boolean.parseBoolean(parts[2]),
					Boolean.parseBoolean(parts[3]),
					Boolean.parseBoolean(parts[4]),
					data
			);
		}
	}
}
