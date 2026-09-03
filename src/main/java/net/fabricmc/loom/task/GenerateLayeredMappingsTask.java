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
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import javax.inject.Inject;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.api.mappings.layered.MappingLayer;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.providers.mappings.IntermediateMappingsService;
import net.fabricmc.loom.configuration.providers.mappings.LayeredMappingSpec;
import net.fabricmc.loom.configuration.providers.mappings.LayeredMappingsProcessor;
import net.fabricmc.loom.configuration.providers.mappings.NoOpMappingLayer;
import net.fabricmc.loom.configuration.providers.mappings.extras.annotations.AnnotationsData;
import net.fabricmc.loom.configuration.providers.mappings.extras.annotations.AnnotationsLayer;
import net.fabricmc.loom.configuration.providers.mappings.extras.signatures.SignatureFixesLayerImpl;
import net.fabricmc.loom.configuration.providers.mappings.extras.unpick.UnpickLayer;
import net.fabricmc.loom.configuration.providers.mappings.file.FileMappingsLayer;
import net.fabricmc.loom.configuration.providers.mappings.intermediary.IntermediaryMappingLayer;
import net.fabricmc.loom.configuration.providers.mappings.mojmap.MojangMappingLayer;
import net.fabricmc.loom.configuration.providers.mappings.parchment.ParchmentMappingLayer;
import net.fabricmc.loom.configuration.providers.mappings.unpick.UnpickMetadata;
import net.fabricmc.loom.configuration.providers.mappings.utils.AddConstructorMappingVisitor;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.download.Download;
import net.fabricmc.loom.util.download.DownloadBuilder;
import net.fabricmc.mappingio.adapter.MappingDstNsReorder;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

@ApiStatus.Internal
@CacheableTask
public abstract class GenerateLayeredMappingsTask extends AbstractLoomTask {
	private static final String INTERMEDIARY = "intermediary";
	private static final String MOJANG = "mojang";
	private static final String PARCHMENT = "parchment";
	private static final String FILE = "file";
	private static final String SIGNATURE_FIX = "signature-fix";

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	@Optional
	public abstract RegularFileProperty getMinecraftMetadata();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	@Optional
	public abstract RegularFileProperty getIntermediaryMappings();

	@InputFiles
	@PathSensitive(PathSensitivity.NONE)
	public abstract ConfigurableFileCollection getLayerFiles();

	@Internal
	public abstract ListProperty<String> getLayerFilePaths();

	@Input
	public abstract ListProperty<String> getLayerFileOrder();

	@Input
	public abstract ListProperty<String> getLayers();

	@Input
	public abstract Property<Boolean> getUseIntermediateMappings();

	@Input
	public abstract Property<Boolean> getDropNonIntermediateRootMethods();

	@Input
	public abstract Property<Boolean> getOffline();

	@Input
	public abstract Property<Boolean> getRefresh();

	@OutputFile
	public abstract RegularFileProperty getOutputMappings();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public GenerateLayeredMappingsTask() {
		getLayerFiles().from(Collections.emptyList());
		getLayerFilePaths().convention(Collections.emptyList());
		getLayerFileOrder().convention(Collections.emptyList());
		getLayers().convention(Collections.emptyList());
		getUseIntermediateMappings().convention(true);
		getDropNonIntermediateRootMethods().convention(false);
		getOffline().convention(false);
		getRefresh().convention(false);
		getOutputs().upToDateWhen(task -> !((GenerateLayeredMappingsTask) task).getRefresh().get());
		getOutputs().doNotCacheIf("Dependency refresh is enabled", task -> ((GenerateLayeredMappingsTask) task).getRefresh().get());
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(GenerateLayeredMappingsAction.class, parameters -> {
			parameters.getMinecraftMetadata().set(getMinecraftMetadata());
			parameters.getIntermediaryMappings().set(getIntermediaryMappings());
			parameters.getLayerFilePaths().set(getLayerFilePaths());
			parameters.getLayers().set(getLayers());
			parameters.getUseIntermediateMappings().set(getUseIntermediateMappings());
			parameters.getDropNonIntermediateRootMethods().set(getDropNonIntermediateRootMethods());
			parameters.getOffline().set(getOffline());
			parameters.getRefresh().set(getRefresh());
			parameters.getOutputMappings().set(getOutputMappings());
		});
	}

	public static String intermediaryLayer() {
		return encode(INTERMEDIARY);
	}

	public static String mojangLayer(boolean nameSyntheticMembers) {
		return encode(MOJANG, Boolean.toString(nameSyntheticMembers));
	}

	public static String parchmentLayer(String source, boolean removePrefix) {
		return encode(PARCHMENT, source, Boolean.toString(removePrefix));
	}

	public static String fileLayer(String source, String mappingPath, String fallbackSourceNamespace, String fallbackTargetNamespace, boolean enigma, boolean unpick, boolean annotations, String mergeNamespace, @Nullable String fallbackUnpickConstants) {
		return encode(
				FILE,
				source,
				mappingPath,
				fallbackSourceNamespace,
				fallbackTargetNamespace,
				Boolean.toString(enigma),
				Boolean.toString(unpick),
				Boolean.toString(annotations),
				mergeNamespace,
				fallbackUnpickConstants == null ? "" : fallbackUnpickConstants
		);
	}

	public static String signatureFixLayer(String source) {
		return encode(SIGNATURE_FIX, source);
	}

	public static String fileSource(int index) {
		return "file:" + index;
	}

	public static String urlSource(String url) {
		return "url:" + url;
	}

	private static String encode(String type, String... values) {
		final Base64.Encoder encoder = Base64.getEncoder();
		final StringBuilder builder = new StringBuilder(type);

		for (String value : values) {
			builder.append(':').append(encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)));
		}

		return builder.toString();
	}

	public interface Parameters extends WorkParameters {
		RegularFileProperty getMinecraftMetadata();
		RegularFileProperty getIntermediaryMappings();
		ListProperty<String> getLayerFilePaths();
		ListProperty<String> getLayers();
		Property<Boolean> getUseIntermediateMappings();
		Property<Boolean> getDropNonIntermediateRootMethods();
		Property<Boolean> getOffline();
		Property<Boolean> getRefresh();
		RegularFileProperty getOutputMappings();
	}

	public abstract static class GenerateLayeredMappingsAction implements WorkAction<Parameters> {
		private static final Logger LOGGER = Logging.getLogger(GenerateLayeredMappingsAction.class);
		private final List<Path> temporaryFiles = new ArrayList<>();
		private MinecraftVersionMeta minecraftMetadata;
		private Supplier<MemoryMappingTree> intermediaryMappings;

		@Override
		public void execute() {
			final Path output = getParameters().getOutputMappings().get().getAsFile().toPath();

			try {
				Files.createDirectories(output.getParent());
				Files.deleteIfExists(output);
				final List<MappingLayer> layers = createLayers();
				final LayeredMappingsProcessor processor = new LayeredMappingsProcessor(new LayeredMappingSpec(List.of()), false);
				writeMappings(processor, layers, output);
			} catch (Exception e) {
				cleanOutput(output, e);
				throw new RuntimeException("Failed to generate layered mappings", e);
			} finally {
				cleanTemporaryFiles();
			}
		}

		private List<MappingLayer> createLayers() throws IOException {
			final List<MappingLayer> layers = new ArrayList<>();
			final List<Class<? extends MappingLayer>> visitedLayers = new ArrayList<>();

			for (String value : getParameters().getLayers().get()) {
				final EncodedLayer encoded = EncodedLayer.decode(value);
				final MappingLayer layer = createLayer(encoded);

				for (Class<? extends MappingLayer> dependency : layer.dependsOn()) {
					if (!visitedLayers.contains(dependency)) {
						throw new IllegalStateException("Layer %s depends on %s".formatted(layer.getClass().getName(), dependency.getName()));
					}
				}

				layers.add(layer);
				visitedLayers.add(layer.getClass());
			}

			return layers;
		}

		private MappingLayer createLayer(EncodedLayer encoded) throws IOException {
			return switch (encoded.type()) {
			case INTERMEDIARY -> createIntermediaryLayer(encoded.values());
			case MOJANG -> createMojangLayer(encoded.values());
			case PARCHMENT -> createParchmentLayer(encoded.values());
			case FILE -> createFileLayer(encoded.values());
			case SIGNATURE_FIX -> createSignatureFixLayer(encoded.values());
			default -> throw new IllegalArgumentException("Unknown layered mappings input: " + encoded.type());
			};
		}

		private MappingLayer createIntermediaryLayer(List<String> values) throws IOException {
			requireSize(values, 0, INTERMEDIARY);

			if (!getParameters().getUseIntermediateMappings().get()) {
				return NoOpMappingLayer.INSTANCE;
			}

			return new IntermediaryMappingLayer(intermediaryMappings());
		}

		private MappingLayer createMojangLayer(List<String> values) throws IOException {
			requireSize(values, 1, MOJANG);
			final MinecraftVersionMeta metadata = minecraftMetadata();
			final MinecraftVersionMeta.Download client = Objects.requireNonNull(
					metadata.download("client_mappings"),
					"Failed to find official Mojang client mappings for " + metadata.id()
			);
			final MinecraftVersionMeta.Download server = Objects.requireNonNull(
					metadata.download("server_mappings"),
					"Failed to find official Mojang server mappings for " + metadata.id()
			);
			final Path clientMappings = download(client.url(), client.sha1(), "mojang-client-", ".txt");
			final Path serverMappings = download(server.url(), server.sha1(), "mojang-server-", ".txt");
			return new MojangMappingLayer(
					clientMappings,
					serverMappings,
					Boolean.parseBoolean(values.getFirst()),
					getParameters().getDropNonIntermediateRootMethods().get(),
					getParameters().getUseIntermediateMappings().get() ? intermediaryMappings() : null,
					LOGGER
			);
		}

		private MappingLayer createParchmentLayer(List<String> values) throws IOException {
			requireSize(values, 2, PARCHMENT);
			return new ParchmentMappingLayer(resolveSource(values.get(0)), Boolean.parseBoolean(values.get(1)));
		}

		private MappingLayer createFileLayer(List<String> values) throws IOException {
			requireSize(values, 9, FILE);
			return new FileMappingsLayer(
					resolveSource(values.get(0)),
					values.get(1),
					values.get(2),
					values.get(3),
					Boolean.parseBoolean(values.get(4)),
					Boolean.parseBoolean(values.get(5)),
					Boolean.parseBoolean(values.get(6)),
					values.get(7),
					values.get(8).isEmpty() ? null : values.get(8)
			);
		}

		private MappingLayer createSignatureFixLayer(List<String> values) throws IOException {
			requireSize(values, 1, SIGNATURE_FIX);
			return new SignatureFixesLayerImpl(resolveSource(values.getFirst()));
		}

		private Path resolveSource(String source) throws IOException {
			if (source.startsWith("file:")) {
				final int index = Integer.parseInt(source.substring("file:".length()));
				final List<String> paths = getParameters().getLayerFilePaths().get();

				if (index < 0 || index >= paths.size()) {
					throw new IllegalArgumentException("Invalid layered mappings file index: " + index);
				}

				return Path.of(paths.get(index));
			}

			if (source.startsWith("url:")) {
				return download(source.substring("url:".length()), null, "layer-", ".bin");
			}

			throw new IllegalArgumentException("Invalid layered mappings source: " + source);
		}

		private Supplier<MemoryMappingTree> intermediaryMappings() throws IOException {
			if (intermediaryMappings == null) {
				if (!getParameters().getIntermediaryMappings().isPresent()) {
					throw new IllegalStateException("Intermediary mappings were not configured");
				}

				final Path path = getParameters().getIntermediaryMappings().get().getAsFile().toPath();
				final String expectedSourceNamespace = minecraftMetadata().isLegacySplitOfficialNamespaceVersion()
						? MappingsNamespace.INTERMEDIARY.toString()
						: MappingsNamespace.OFFICIAL.toString();
				intermediaryMappings = () -> IntermediateMappingsService.createMemoryMappingTree(path, expectedSourceNamespace);
			}

			return intermediaryMappings;
		}

		private MinecraftVersionMeta minecraftMetadata() throws IOException {
			if (minecraftMetadata == null) {
				if (!getParameters().getMinecraftMetadata().isPresent()) {
					throw new IllegalStateException("Minecraft metadata was not configured");
				}

				final Path path = getParameters().getMinecraftMetadata().get().getAsFile().toPath();

				try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
					minecraftMetadata = Objects.requireNonNull(
							LoomGradlePlugin.GSON.fromJson(reader, MinecraftVersionMeta.class),
							"Minecraft metadata is empty"
					);
				}
			}

			return minecraftMetadata;
		}

		private Path download(String url, @Nullable String sha1, String prefix, String suffix) throws IOException {
			final Path output = Files.createTempFile(getParameters().getOutputMappings().get().getAsFile().toPath().getParent(), prefix, suffix);
			temporaryFiles.add(output);
			Files.delete(output);
			final DownloadBuilder download;

			try {
				download = Download.create(url);
			} catch (URISyntaxException e) {
				throw new IOException("Invalid layered mappings URL: " + url, e);
			}

			if (sha1 != null) {
				download.sha1(sha1);
			} else {
				download.defaultCache();
			}

			if (getParameters().getOffline().get()) {
				download.offline();
			}

			if (getParameters().getRefresh().get()) {
				download.forceDownload();
			}

			download.downloadPath(output);
			return output;
		}

		private void writeMappings(LayeredMappingsProcessor processor, List<MappingLayer> layers, Path output) throws IOException {
			final MemoryMappingTree mappings = processor.getMappings(layers);

			try (Writer writer = new StringWriter()) {
				final Tiny2FileWriter tiny2Writer = new Tiny2FileWriter(writer, false);
				final List<String> destinationNamespaces = getParameters().getUseIntermediateMappings().get()
						? List.of(MappingsNamespace.NAMED.toString(), MappingsNamespace.OFFICIAL.toString())
						: List.of(MappingsNamespace.NAMED.toString());
				final MappingDstNsReorder namespaceReorder = new MappingDstNsReorder(tiny2Writer, destinationNamespaces);
				final String sourceNamespace = getParameters().getUseIntermediateMappings().get()
						? MappingsNamespace.INTERMEDIARY.toString()
						: MappingsNamespace.OFFICIAL.toString();
				final MappingSourceNsSwitch namespaceSwitch = new MappingSourceNsSwitch(namespaceReorder, sourceNamespace, true);
				mappings.accept(new AddConstructorMappingVisitor(namespaceSwitch));
				ZipUtils.add(output, "mappings/mappings.tiny", writer.toString().getBytes(StandardCharsets.UTF_8));
			}

			final List<AnnotationsData> annotationsData = processor.getAnnotationsData(layers);

			if (!annotationsData.isEmpty()) {
				final byte[] data = AnnotationsData.GSON.toJson(AnnotationsData.listToJson(annotationsData)).getBytes(StandardCharsets.UTF_8);
				ZipUtils.add(output, AnnotationsLayer.ANNOTATIONS_PATH, data);
			}

			final Map<String, String> signatureFixes = processor.getSignatureFixes(layers);

			if (signatureFixes != null) {
				ZipUtils.add(output, "extras/record_signatures.json", LoomGradlePlugin.GSON.toJson(signatureFixes).getBytes(StandardCharsets.UTF_8));
			}

			final UnpickLayer.UnpickData unpickData = processor.getUnpickData(layers);

			if (unpickData != null) {
				ZipUtils.add(output, UnpickMetadata.UNPICK_DEFINITIONS_PATH, unpickData.definitions());
				ZipUtils.add(output, UnpickMetadata.UNPICK_METADATA_PATH, UnpickMetadata.toJson(unpickData.metadata()).getBytes(StandardCharsets.UTF_8));
			}
		}

		private static void requireSize(List<String> values, int expected, String type) {
			if (values.size() != expected) {
				throw new IllegalArgumentException("Invalid " + type + " layered mappings input");
			}
		}

		private static void cleanOutput(Path output, Exception failure) {
			try {
				Files.deleteIfExists(output);
			} catch (IOException e) {
				failure.addSuppressed(e);
			}
		}

		private void cleanTemporaryFiles() {
			for (Path temporaryFile : temporaryFiles) {
				try {
					Files.deleteIfExists(temporaryFile);
				} catch (IOException e) {
					LOGGER.warn("Failed to delete temporary layered mappings input {}", temporaryFile, e);
				}
			}
		}
	}

	private record EncodedLayer(String type, List<String> values) {
		private static EncodedLayer decode(String value) {
			final String[] parts = value.split(":", -1);
			final Base64.Decoder decoder = Base64.getDecoder();
			final List<String> values = new ArrayList<>(parts.length - 1);

			for (int i = 1; i < parts.length; i++) {
				values.add(new String(decoder.decode(parts[i]), StandardCharsets.UTF_8));
			}

			return new EncodedLayer(parts[0], values);
		}
	}
}
