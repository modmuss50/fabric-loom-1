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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.classtweaker.api.ClassTweakerReader;
import net.fabricmc.classtweaker.api.visitor.AccessWidenerVisitor;
import net.fabricmc.classtweaker.api.visitor.ClassTweakerVisitor;
import net.fabricmc.classtweaker.visitors.ClassTweakerRemapperVisitor;
import net.fabricmc.classtweaker.visitors.TransitiveOnlyFilter;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.processors.MappingProcessing;
import net.fabricmc.loom.configuration.processors.MinecraftJarProcessorAnalysis;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.TinyRemapper;

@ApiStatus.Internal
@CacheableTask
public abstract class PrepareSourceMappingsTask extends AbstractLoomTask {
	private static final String ACCESS_WIDENER = "access-widener";
	private static final String ACCESS_WIDENER_DATA = "access-widener-data";
	private static final String INTERFACE_INJECTION = "interface-injection";
	private static final String JAVADOC = "javadoc";

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	@Optional
	public abstract RegularFileProperty getInputMappings();

	@Input
	@Optional
	public abstract Property<String> getInputMappingsEntry();

	@Input
	public abstract Property<String> getMappingsSourceNamespace();

	@Input
	public abstract Property<String> getProductionNamespace();

	@Input
	public abstract Property<String> getOutputNamespace();

	@Input
	public abstract Property<Boolean> getDisableObfuscation();

	@Input
	public abstract Property<Boolean> getNormalizeNamespaces();

	@Input
	public abstract ListProperty<String> getMappingTransformations();

	@InputFiles
	@PathSensitive(PathSensitivity.NONE)
	public abstract ConfigurableFileCollection getProcessorAnalyses();

	@Internal
	public abstract ListProperty<String> getProcessorAnalysisPaths();

	@InputFiles
	@PathSensitive(PathSensitivity.NONE)
	public abstract ConfigurableFileCollection getTransformationSources();

	@Internal
	public abstract ListProperty<String> getTransformationSourcePaths();

	@Input
	protected List<String> getTransformationSourceHashes() {
		return getTransformationSourcePaths().get().stream()
				.map(Path::of)
				.map(Checksum::of)
				.map(checksum -> checksum.sha256().hex())
				.toList();
	}

	@Input
	public abstract ListProperty<String> getUnsupportedProcessors();

	@OutputFile
	public abstract RegularFileProperty getOutputMappings();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public PrepareSourceMappingsTask() {
		getNormalizeNamespaces().convention(true);
		getMappingTransformations().convention(Collections.emptyList());
		getProcessorAnalyses().from(Collections.emptyList());
		getProcessorAnalysisPaths().convention(Collections.emptyList());
		getTransformationSources().from(Collections.emptyList());
		getTransformationSourcePaths().convention(Collections.emptyList());
		getUnsupportedProcessors().convention(Collections.emptyList());
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(PrepareSourceMappingsAction.class, parameters -> {
			parameters.getInputMappings().set(getInputMappings());
			parameters.getInputMappingsEntry().set(getInputMappingsEntry());
			parameters.getMappingsSourceNamespace().set(getMappingsSourceNamespace());
			parameters.getProductionNamespace().set(getProductionNamespace());
			parameters.getOutputNamespace().set(getOutputNamespace());
			parameters.getDisableObfuscation().set(getDisableObfuscation());
			parameters.getNormalizeNamespaces().set(getNormalizeNamespaces());
			parameters.getMappingTransformations().set(getMappingTransformations());
			parameters.getProcessorAnalysisPaths().set(getProcessorAnalysisPaths());
			parameters.getTransformationSourcePaths().set(getTransformationSourcePaths());
			parameters.getUnsupportedProcessors().set(getUnsupportedProcessors());
			parameters.getOutputMappings().set(getOutputMappings());
		});
	}

	public static String encodeAccessWidener(String modId, boolean transitiveOnly, int sourceIndex, String pathWithinJar) {
		return encode(ACCESS_WIDENER, modId, Boolean.toString(transitiveOnly), Integer.toString(sourceIndex), pathWithinJar);
	}

	public static String encodeAccessWidenerData(String modId, boolean transitiveOnly, byte[] data) {
		return encode(ACCESS_WIDENER_DATA, modId, Boolean.toString(transitiveOnly), Base64.getEncoder().encodeToString(data));
	}

	public static String encodeInterfaceInjection(String modId, String className, String interfaceName) {
		return encode(INTERFACE_INJECTION, modId, className, interfaceName);
	}

	public static String encodeJavadoc(String modId, String namespace, String kind, String owner, @Nullable String name, @Nullable String descriptor, String comment) {
		return encode(JAVADOC, modId, namespace, kind, owner, nullToEmpty(name), nullToEmpty(descriptor), comment);
	}

	private static String encode(String type, String... values) {
		final Base64.Encoder encoder = Base64.getEncoder();
		final StringBuilder builder = new StringBuilder(type);

		for (String value : values) {
			builder.append(':').append(encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)));
		}

		return builder.toString();
	}

	private static String nullToEmpty(@Nullable String value) {
		return value == null ? "" : value;
	}

	public interface Parameters extends WorkParameters {
		RegularFileProperty getInputMappings();
		Property<String> getInputMappingsEntry();
		Property<String> getMappingsSourceNamespace();
		Property<String> getProductionNamespace();
		Property<String> getOutputNamespace();
		Property<Boolean> getDisableObfuscation();
		Property<Boolean> getNormalizeNamespaces();
		ListProperty<String> getMappingTransformations();
		ListProperty<String> getProcessorAnalysisPaths();
		ListProperty<String> getTransformationSourcePaths();
		ListProperty<String> getUnsupportedProcessors();
		RegularFileProperty getOutputMappings();
	}

	public abstract static class PrepareSourceMappingsAction implements WorkAction<Parameters> {
		private static final Logger LOGGER = LoggerFactory.getLogger(PrepareSourceMappingsAction.class);

		@Override
		public void execute() {
			final Path outputMappings = getParameters().getOutputMappings().get().getAsFile().toPath();

			try {
				Files.createDirectories(outputMappings.getParent());
				Files.deleteIfExists(outputMappings);

				if (!getParameters().getUnsupportedProcessors().get().isEmpty()) {
					throw new UnsupportedOperationException("Source mappings task does not support processors: "
							+ String.join(", ", getParameters().getUnsupportedProcessors().get()));
				}

				if (!getParameters().getNormalizeNamespaces().get()) {
					Files.copy(getParameters().getInputMappings().get().getAsFile().toPath(), outputMappings, StandardCopyOption.REPLACE_EXISTING);
					return;
				}

				final MemoryMappingTree mappings = readMappings();
				applyTransformations(mappings);
				writeMappings(mappings, outputMappings);
			} catch (Exception e) {
				cleanOutput(outputMappings, e);
				throw new RuntimeException("Failed to prepare source mappings", e);
			}
		}

		private MemoryMappingTree readMappings() throws IOException {
			final MemoryMappingTree mappings = new MemoryMappingTree();
			final String mappingsSourceNamespace = getParameters().getMappingsSourceNamespace().get();

			try (Reader reader = createMappingsReader()) {
				MappingReader.read(reader, new MappingSourceNsSwitch(mappings, mappingsSourceNamespace));
			}

			return mappings;
		}

		private Reader createMappingsReader() throws IOException {
			if (!getParameters().getInputMappings().isPresent()) {
				return new StringReader("tiny\t2\t0\tofficial\n");
			}

			final Path input = getParameters().getInputMappings().get().getAsFile().toPath();
			final byte[] data = getParameters().getInputMappingsEntry().isPresent()
					? ZipUtils.unpack(input, getParameters().getInputMappingsEntry().get())
					: Files.readAllBytes(input);
			return new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8);
		}

		private void applyTransformations(MemoryMappingTree mappings) throws IOException {
			TinyRemapper remapper = null;

			try {
				for (String encoded : mappingTransformations()) {
					final Transformation transformation = Transformation.decode(encoded);

					switch (transformation.type()) {
					case ACCESS_WIDENER -> {
						final AccessWidenerTransformation accessWidener = AccessWidenerTransformation.create(transformation.values(), sourcePaths());
						remapper = applyAccessWidener(mappings, accessWidener, remapper);
					}
					case ACCESS_WIDENER_DATA -> {
						final AccessWidenerTransformation accessWidener = AccessWidenerTransformation.createFromData(transformation.values());
						remapper = applyAccessWidener(mappings, accessWidener, remapper);
					}
					case INTERFACE_INJECTION -> applyInterfaceInjection(mappings, transformation.values());
					case JAVADOC -> applyJavadoc(mappings, transformation.values());
					default -> throw new IllegalArgumentException("Unknown source mappings transformation: " + transformation.type());
					}
				}
			} finally {
				if (remapper != null) {
					remapper.finish();
				}
			}
		}

		private TinyRemapper applyAccessWidener(MemoryMappingTree mappings, AccessWidenerTransformation accessWidener, @Nullable TinyRemapper remapper) throws IOException {
			if (!getParameters().getDisableObfuscation().get() && accessWidener.requiresRemapper() && remapper == null) {
				remapper = TinyRemapperHelper.getTinyRemapper(
						mappings,
						getParameters().getProductionNamespace().get(),
						MappingsNamespace.NAMED.toString(),
						false,
						Collections.emptySet(),
						builder -> { }
				);
			}

			accessWidener.apply(mappings, remapper, getParameters().getProductionNamespace().get(), getParameters().getDisableObfuscation().get());
			return remapper;
		}

		private List<String> mappingTransformations() throws IOException {
			final List<String> transformations = new ArrayList<>(getParameters().getMappingTransformations().get());

			for (String path : getParameters().getProcessorAnalysisPaths().get()) {
				transformations.addAll(MinecraftJarProcessorAnalysis.read(Path.of(path)).mappingTransformations());
			}

			return transformations;
		}

		private List<Path> sourcePaths() {
			return getParameters().getTransformationSourcePaths().get().stream().map(Path::of).toList();
		}

		private void applyInterfaceInjection(MemoryMappingTree mappings, List<String> values) {
			requireSize(values, 3, INTERFACE_INJECTION);
			final String modId = values.get(0);
			final String className = values.get(1);
			final String interfaceName = values.get(2);
			final int productionNamespaceId = productionNamespaceId(mappings);
			final MappingTree.ClassMapping classMapping = MappingProcessing.getOrCreateClassMapping(
					mappings,
					className,
					productionNamespaceId,
					getParameters().getDisableObfuscation().get()
			);

			if (classMapping == null) {
				LOGGER.warn("Failed to find class ({}) to add injected interface from mod ({})", className, modId);
				return;
			}

			final String interfaceComment = "<p>Interface {@link %s} injected by mod %s</p>"
					.formatted(interfaceName.replace('/', '.').replace('$', '.'), modId);
			classMapping.setComment(appendUniqueComment(classMapping.getComment(), interfaceComment));
		}

		private void applyJavadoc(MemoryMappingTree mappings, List<String> values) {
			requireSize(values, 7, JAVADOC);
			final String modId = values.get(0);
			final String namespace = values.get(1);
			final String kind = values.get(2);
			final String owner = values.get(3);
			final String name = emptyToNull(values.get(4));
			final String descriptor = emptyToNull(values.get(5));
			final String comment = values.get(6);
			final int namespaceId = mappings.getNamespaceId(namespace);

			if (namespaceId == MappingTreeView.NULL_NAMESPACE_ID) {
				throw new IllegalStateException("Mapping tree must have namespace " + namespace);
			}

			final boolean create = getParameters().getDisableObfuscation().get();
			final MappingTree.ClassMapping ownerMapping = MappingProcessing.getOrCreateClassMapping(mappings, owner, namespaceId, create);

			if (ownerMapping == null) {
				LOGGER.warn("Could not find provided javadoc target class {} from mod {}", owner, modId);
				return;
			}

			final MappingTree.ElementMapping target = switch (kind) {
			case "class" -> ownerMapping;
			case "field" -> MappingProcessing.getOrCreateFieldMapping(mappings, ownerMapping, name, descriptor, namespaceId, create);
			case "method" -> MappingProcessing.getOrCreateMethodMapping(mappings, ownerMapping, name, descriptor, namespaceId, create);
			default -> throw new IllegalArgumentException("Unknown javadoc mapping kind: " + kind);
			};

			if (target == null) {
				LOGGER.warn("Could not find provided javadoc target {} {}{} from mod {}", kind, name, descriptor, modId);
				return;
			}

			target.setComment(appendComment(target.getComment(), comment));
		}

		private int productionNamespaceId(MemoryMappingTree mappings) {
			final String productionNamespace = getParameters().getProductionNamespace().get();
			final int namespaceId = mappings.getNamespaceId(productionNamespace);

			if (namespaceId == MappingTreeView.NULL_NAMESPACE_ID) {
				throw new IllegalStateException("Mapping tree must have namespace " + productionNamespace);
			}

			return namespaceId;
		}

		private void writeMappings(MemoryMappingTree mappings, Path output) throws IOException {
			try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
				mappings.accept(new MappingSourceNsSwitch(
						new Tiny2FileWriter(writer, false),
						getParameters().getOutputNamespace().get()
				));
			}
		}

		private static String appendUniqueComment(@Nullable String existing, String comment) {
			if (existing != null && existing.contains(comment)) {
				return existing;
			}

			return appendComment(existing, comment);
		}

		private static String appendComment(@Nullable String existing, String comment) {
			return existing == null || existing.isEmpty() ? comment : existing + '\n' + comment;
		}

		private static @Nullable String emptyToNull(String value) {
			return value.isEmpty() ? null : value;
		}

		private static void requireSize(List<String> values, int expected, String type) {
			if (values.size() != expected) {
				throw new IllegalArgumentException("Invalid " + type + " source mappings transformation");
			}
		}

		private static void cleanOutput(Path output, Exception failure) {
			try {
				Files.deleteIfExists(output);
			} catch (IOException e) {
				failure.addSuppressed(e);
			}
		}
	}

	private record Transformation(String type, List<String> values) {
		private static Transformation decode(String value) {
			final String[] parts = value.split(":", -1);
			final Base64.Decoder decoder = Base64.getDecoder();
			final List<String> values = new ArrayList<>(parts.length - 1);

			for (int i = 1; i < parts.length; i++) {
				values.add(new String(decoder.decode(parts[i]), StandardCharsets.UTF_8));
			}

			return new Transformation(parts[0], values);
		}
	}

	private record AccessWidenerTransformation(String modId, boolean transitiveOnly, byte[] data) {
		private static AccessWidenerTransformation create(List<String> values, List<Path> sources) throws IOException {
			PrepareSourceMappingsAction.requireSize(values, 4, ACCESS_WIDENER);
			final int sourceIndex = Integer.parseInt(values.get(2));

			if (sourceIndex < 0 || sourceIndex >= sources.size()) {
				throw new IllegalArgumentException("Invalid access widener source index: " + sourceIndex);
			}

			final Path source = sources.get(sourceIndex);
			final String pathWithinJar = values.get(3);
			final byte[] data = pathWithinJar.isEmpty()
					? Files.readAllBytes(source)
					: ZipUtils.unpack(source, pathWithinJar);
			return new AccessWidenerTransformation(values.get(0), Boolean.parseBoolean(values.get(1)), data);
		}

		private static AccessWidenerTransformation createFromData(List<String> values) {
			PrepareSourceMappingsAction.requireSize(values, 3, ACCESS_WIDENER_DATA);
			return new AccessWidenerTransformation(
					values.get(0),
					Boolean.parseBoolean(values.get(1)),
					Base64.getDecoder().decode(values.get(2))
			);
		}

		private boolean requiresRemapper() throws IOException {
			return !MappingsNamespace.NAMED.toString().equals(ClassTweakerReader.readHeader(data).getNamespace());
		}

		private void apply(MemoryMappingTree mappings, @Nullable TinyRemapper remapper, String productionNamespace, boolean disableObfuscation) throws IOException {
			ClassTweakerVisitor visitor = new MappingCommentClassTweakerVisitor(modId, productionNamespaceId(mappings, productionNamespace), mappings, disableObfuscation);

			if (transitiveOnly) {
				visitor = new TransitiveOnlyFilter(visitor);
			}

			final String inputNamespace = ClassTweakerReader.readHeader(data).getNamespace();

			if (disableObfuscation) {
				if (!MappingsNamespace.OFFICIAL.toString().equals(inputNamespace)) {
					throw new IOException("Expected official namespace for access widener entry, found: " + inputNamespace);
				}
			} else if (!MappingsNamespace.NAMED.toString().equals(inputNamespace)) {
				if (remapper == null) {
					throw new IllegalStateException("Missing access widener remapper");
				}

				visitor = new ClassTweakerRemapperVisitor(
						visitor,
						remapper.getEnvironment().getRemapper(),
						productionNamespace,
						MappingsNamespace.NAMED.toString()
				);
			}

			ClassTweakerReader.create(visitor).read(data);
		}

		private static int productionNamespaceId(MemoryMappingTree mappings, String productionNamespace) {
			final int namespaceId = mappings.getNamespaceId(productionNamespace);

			if (namespaceId == MappingTreeView.NULL_NAMESPACE_ID) {
				throw new IllegalStateException("Mapping tree must have namespace " + productionNamespace);
			}

			return namespaceId;
		}
	}

	private record MappingCommentClassTweakerVisitor(String modId, int productionNamespaceId, MemoryMappingTree mappings, boolean createMissingEntries) implements ClassTweakerVisitor {
		@Override
		public AccessWidenerVisitor visitAccessWidener(String owner) {
			return new MappingCommentAccessWidenerVisitor(owner);
		}

		private final class MappingCommentAccessWidenerVisitor implements AccessWidenerVisitor {
			private final String className;

			private MappingCommentAccessWidenerVisitor(String className) {
				this.className = className;
			}

			@Override
			public void visitClass(AccessType access, boolean transitive) {
				final MappingTree.ClassMapping classMapping = MappingProcessing.getOrCreateClassMapping(mappings, className, productionNamespaceId, createMissingEntries);

				if (classMapping != null) {
					classMapping.setComment(appendAccessWidenerComment(classMapping.getComment(), access));
				}
			}

			@Override
			public void visitMethod(String name, String descriptor, AccessType access, boolean transitive) {
				visitClass(access, transitive);
				final MappingTree.ClassMapping classMapping = MappingProcessing.getOrCreateClassMapping(mappings, className, productionNamespaceId, createMissingEntries);

				if (classMapping == null) {
					return;
				}

				final MappingTree.MethodMapping methodMapping = MappingProcessing.getOrCreateMethodMapping(mappings, classMapping, name, descriptor, productionNamespaceId, createMissingEntries);

				if (methodMapping != null) {
					methodMapping.setComment(appendAccessWidenerComment(methodMapping.getComment(), access));
				}
			}

			@Override
			public void visitField(String name, String descriptor, AccessType access, boolean transitive) {
				visitClass(access, transitive);
				final MappingTree.ClassMapping classMapping = MappingProcessing.getOrCreateClassMapping(mappings, className, productionNamespaceId, createMissingEntries);

				if (classMapping == null) {
					return;
				}

				final MappingTree.FieldMapping fieldMapping = MappingProcessing.getOrCreateFieldMapping(mappings, classMapping, name, descriptor, productionNamespaceId, createMissingEntries);

				if (fieldMapping != null) {
					fieldMapping.setComment(appendAccessWidenerComment(fieldMapping.getComment(), access));
				}
			}

			private String appendAccessWidenerComment(@Nullable String existing, AccessType access) {
				final String comment = "Access widened by %s to %s".formatted(modId, access);
				return PrepareSourceMappingsAction.appendUniqueComment(existing, comment);
			}
		}
	}
}
