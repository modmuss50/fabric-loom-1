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

package net.fabricmc.loom.task;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
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
import org.jetbrains.annotations.VisibleForTesting;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.commons.Remapper;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.providers.mappings.extras.annotations.AnnotationsData;
import net.fabricmc.loom.configuration.providers.mappings.extras.annotations.AnnotationsLayer;
import net.fabricmc.loom.configuration.providers.minecraft.AnnotationsApplyVisitor;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.configuration.providers.minecraft.SignatureFixerApplyVisitor;
import net.fabricmc.loom.util.SidedClassVisitor;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.TinyRemapperLoggerAdapter;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

@ApiStatus.Internal
@DisableCachingByDefault(because = "Minecraft jars should not be stored in the build cache")
public abstract class RemapMinecraftJarTask extends AbstractLoomTask {
	private static final String SIGNATURE_FIXES_PATH = "extras/record_signatures.json";

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getInputJar();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getMappingsFile();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	@Optional
	public abstract RegularFileProperty getMappingsExtrasJar();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getMinecraftMetadata();

	@Classpath
	public abstract ConfigurableFileCollection getRemapClasspath();

	@Classpath
	public abstract ConfigurableFileCollection getMappingsRemapClasspath();

	@Input
	public abstract Property<String> getSourceNamespace();

	@Input
	@Optional
	public abstract Property<String> getLegacySourceNamespace();

	@Input
	public abstract Property<String> getTargetNamespace();

	@Input
	public abstract Property<Boolean> getApplyClientOnlyAnnotation();

	@Input
	public abstract SetProperty<String> getKnownIndyBsms();

	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public RemapMinecraftJarTask() {
		getApplyClientOnlyAnnotation().convention(false);
		getKnownIndyBsms().convention(Collections.emptySet());
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(RemapMinecraftJarAction.class, parameters -> {
			parameters.getInputJar().set(getInputJar());
			parameters.getMappingsFile().set(getMappingsFile());
			parameters.getMappingsExtrasJar().set(getMappingsExtrasJar());
			parameters.getMinecraftMetadata().set(getMinecraftMetadata());
			parameters.getRemapClasspath().from(getRemapClasspath());
			parameters.getMappingsRemapClasspath().from(getMappingsRemapClasspath());
			parameters.getSourceNamespace().set(getSourceNamespace());
			parameters.getLegacySourceNamespace().set(getLegacySourceNamespace());
			parameters.getTargetNamespace().set(getTargetNamespace());
			parameters.getApplyClientOnlyAnnotation().set(getApplyClientOnlyAnnotation());
			parameters.getKnownIndyBsms().set(getKnownIndyBsms());
			parameters.getOutputJar().set(getOutputJar());
		});
	}

	public interface RemapMinecraftJarParameters extends WorkParameters {
		RegularFileProperty getInputJar();
		RegularFileProperty getMappingsFile();
		RegularFileProperty getMappingsExtrasJar();
		RegularFileProperty getMinecraftMetadata();
		ConfigurableFileCollection getRemapClasspath();
		ConfigurableFileCollection getMappingsRemapClasspath();
		Property<String> getSourceNamespace();
		Property<String> getLegacySourceNamespace();
		Property<String> getTargetNamespace();
		Property<Boolean> getApplyClientOnlyAnnotation();
		SetProperty<String> getKnownIndyBsms();
		RegularFileProperty getOutputJar();
	}

	public abstract static class RemapMinecraftJarAction implements WorkAction<RemapMinecraftJarParameters> {
		@Override
		public void execute() {
			final Path inputJar = getParameters().getInputJar().get().getAsFile().toPath();
			final Path mappingsFile = getParameters().getMappingsFile().get().getAsFile().toPath();
			final Path outputJar = getParameters().getOutputJar().get().getAsFile().toPath();
			TinyRemapper remapper = null;

			try {
				Files.createDirectories(outputJar.getParent());
				Files.deleteIfExists(outputJar);
				final MinecraftVersionMeta minecraftMetadata = readMetadata();

				final MemoryMappingTree mappings = new MemoryMappingTree();
				MappingReader.read(mappingsFile, mappings);
				final AnnotationsData annotations = readAnnotations(mappings);
				final Map<String, String> signatureFixes = readSignatureFixes(mappings);

				remapper = TinyRemapperHelper.getTinyRemapper(
						mappings,
						resolveSourceNamespace(
								getParameters().getSourceNamespace().get(),
								getParameters().getLegacySourceNamespace().getOrNull(),
								minecraftMetadata.isLegacySplitOfficialNamespaceVersion()
						),
						getParameters().getTargetNamespace().get(),
						shouldFixRecords(minecraftMetadata),
						getParameters().getKnownIndyBsms().get(),
						builder -> configureRemapper(builder, annotations, signatureFixes)
				);

				try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(outputJar).build()) {
					outputConsumer.addNonClassFiles(inputJar);

					for (var file : getParameters().getRemapClasspath()) {
						remapper.readClassPath(file.toPath());
					}

					remapper.readInputs(inputJar);
					remapper.apply(outputConsumer);
				}
			} catch (Exception e) {
				try {
					Files.deleteIfExists(outputJar);
				} catch (IOException cleanupException) {
					e.addSuppressed(cleanupException);
				}

				throw new RuntimeException("Failed to remap Minecraft jar: " + inputJar, e);
			} finally {
				if (remapper != null) {
					remapper.finish();
				}
			}
		}

		private MinecraftVersionMeta readMetadata() throws IOException {
			final Path metadataPath = getParameters().getMinecraftMetadata().get().getAsFile().toPath();
			return Objects.requireNonNull(
					LoomGradlePlugin.GSON.fromJson(Files.readString(metadataPath, StandardCharsets.UTF_8), MinecraftVersionMeta.class),
					"Minecraft metadata is empty"
			);
		}

		private boolean shouldFixRecords(MinecraftVersionMeta metadata) {
			final MinecraftVersionMeta.JavaVersion javaVersion = metadata.javaVersion();
			return javaVersion != null && javaVersion.majorVersion() >= 16;
		}

		@VisibleForTesting
		static String resolveSourceNamespace(String sourceNamespace, @Nullable String legacySourceNamespace, boolean legacySplitOfficialNamespace) {
			return legacySplitOfficialNamespace && legacySourceNamespace != null
					? legacySourceNamespace
					: sourceNamespace;
		}

		private void configureRemapper(TinyRemapper.Builder builder, AnnotationsData annotations, Map<String, String> signatureFixes) {
			if (annotations != null) {
				builder.extraPostApplyVisitor(new AnnotationsApplyVisitor(annotations));
			}

			builder.extraPostApplyVisitor(new SignatureFixerApplyVisitor(signatureFixes));

			if (getParameters().getApplyClientOnlyAnnotation().get()) {
				builder.extraPostApplyVisitor(SidedClassVisitor.CLIENT);
			}
		}

		private AnnotationsData readAnnotations(MemoryMappingTree mappings) throws IOException {
			final byte[] data = readExtras(AnnotationsLayer.ANNOTATIONS_PATH);

			if (data == null) {
				return null;
			}

			final List<AnnotationsData> annotations = AnnotationsData.readList(new StringReader(new String(data, StandardCharsets.UTF_8)));
			AnnotationsData result = null;

			for (AnnotationsData annotationData : annotations) {
				final AnnotationsData remapped = remapAnnotations(annotationData, mappings);
				result = result == null ? remapped : result.merge(remapped);
			}

			return result;
		}

		private AnnotationsData remapAnnotations(AnnotationsData annotations, MemoryMappingTree mappings) {
			final String targetNamespace = getParameters().getTargetNamespace().get();

			if (annotations.namespace().equals(targetNamespace)) {
				return annotations;
			}

			final TinyRemapper remapper = createMappingsOnlyRemapper(mappings, annotations.namespace(), targetNamespace);

			try {
				return annotations.remap(remapper, targetNamespace);
			} finally {
				remapper.finish();
			}
		}

		private Map<String, String> readSignatureFixes(MemoryMappingTree mappings) throws IOException {
			final byte[] data = readExtras(SIGNATURE_FIXES_PATH);

			if (data == null) {
				return Collections.emptyMap();
			}

			@SuppressWarnings("unchecked") final Map<String, String> signatureFixes = LoomGradlePlugin.GSON.fromJson(
					new String(data, StandardCharsets.UTF_8),
					Map.class
			);
			final String targetNamespace = getParameters().getTargetNamespace().get();

			if (MappingsNamespace.INTERMEDIARY.toString().equals(targetNamespace)) {
				return signatureFixes;
			}

			final TinyRemapper remapper = createMappingsOnlyRemapper(mappings, MappingsNamespace.INTERMEDIARY.toString(), targetNamespace);

			try {
				final Remapper asmRemapper = remapper.getEnvironment().getRemapper();
				final Map<String, String> remapped = new HashMap<>();
				signatureFixes.forEach((name, signature) -> remapped.put(
						asmRemapper.map(name),
						asmRemapper.mapSignature(signature, false)
				));
				return remapped;
			} finally {
				remapper.finish();
			}
		}

		private TinyRemapper createMappingsOnlyRemapper(MemoryMappingTree mappings, String sourceNamespace, String targetNamespace) {
			final TinyRemapper remapper = TinyRemapper.newRemapper(TinyRemapperLoggerAdapter.INSTANCE)
					.withMappings(TinyRemapperHelper.create(mappings, sourceNamespace, targetNamespace, true))
					.build();

			for (var file : getParameters().getMappingsRemapClasspath()) {
				remapper.readClassPath(file.toPath());
			}

			return remapper;
		}

		private byte[] readExtras(String path) throws IOException {
			if (!getParameters().getMappingsExtrasJar().isPresent()) {
				return null;
			}

			return ZipUtils.unpackNullable(getParameters().getMappingsExtrasJar().get().getAsFile().toPath(), path);
		}
	}
}
