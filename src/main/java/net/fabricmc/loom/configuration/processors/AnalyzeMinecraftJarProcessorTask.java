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

package net.fabricmc.loom.configuration.processors;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import javax.inject.Inject;

import com.google.gson.JsonObject;
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

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.api.processor.MarkdownJavadocOption;
import net.fabricmc.loom.configuration.accesswidener.ProcessMinecraftAccessWidenersTask;
import net.fabricmc.loom.configuration.ifaceinject.InterfaceInjectionProcessor.InjectedInterface;
import net.fabricmc.loom.configuration.ifaceinject.ProcessMinecraftInterfaceInjectionsTask;
import net.fabricmc.loom.task.AbstractLoomTask;
import net.fabricmc.loom.task.PrepareSourceMappingsTask;
import net.fabricmc.loom.util.fmj.FabricModJson;
import net.fabricmc.loom.util.fmj.FabricModJsonFactory;
import net.fabricmc.loom.util.fmj.FabricModJsonSource;
import net.fabricmc.loom.util.fmj.ModEnvironment;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

@ApiStatus.Internal
@CacheableTask
public abstract class AnalyzeMinecraftJarProcessorTask extends AbstractLoomTask {
	public enum ProcessorType {
		ACCESS_WIDENER,
		MOD_JAVADOC,
		INTERFACE_INJECTION
	}

	@Input
	public abstract Property<ProcessorType> getProcessorType();

	@Input
	public abstract Property<Boolean> getProcessorEnabled();

	@Input
	public abstract Property<Boolean> getIncludeDependencies();

	@Input
	public abstract Property<String> getProductionNamespace();

	@Input
	public abstract Property<String> getMarkdownJavadocOption();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	@Optional
	public abstract RegularFileProperty getLocalAccessWidener();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	@Optional
	public abstract RegularFileProperty getLocalFabricModJson();

	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getLocalResources();

	@Internal
	public abstract ListProperty<String> getLocalResourcePaths();

	@Input
	public abstract ListProperty<String> getLocalResourceOrder();

	@InputFiles
	@PathSensitive(PathSensitivity.NONE)
	public abstract ConfigurableFileCollection getCommonCompileMods();

	@InputFiles
	@PathSensitive(PathSensitivity.NONE)
	public abstract ConfigurableFileCollection getCommonRuntimeMods();

	@InputFiles
	@PathSensitive(PathSensitivity.NONE)
	public abstract ConfigurableFileCollection getClientCompileMods();

	@InputFiles
	@PathSensitive(PathSensitivity.NONE)
	public abstract ConfigurableFileCollection getClientRuntimeMods();

	@Input
	public abstract ListProperty<String> getUnsupportedProcessors();

	@OutputFile
	public abstract RegularFileProperty getOutputAnalysis();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public AnalyzeMinecraftJarProcessorTask() {
		getProcessorEnabled().convention(true);
		getIncludeDependencies().convention(false);
		getLocalResources().from(Collections.emptyList());
		getLocalResourcePaths().convention(Collections.emptyList());
		getLocalResourceOrder().convention(Collections.emptyList());
		getCommonCompileMods().from(Collections.emptyList());
		getCommonRuntimeMods().from(Collections.emptyList());
		getClientCompileMods().from(Collections.emptyList());
		getClientRuntimeMods().from(Collections.emptyList());
		getUnsupportedProcessors().convention(Collections.emptyList());
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(AnalyzeAction.class, parameters -> {
			parameters.getProcessorType().set(getProcessorType());
			parameters.getProcessorEnabled().set(getProcessorEnabled());
			parameters.getIncludeDependencies().set(getIncludeDependencies());
			parameters.getProductionNamespace().set(getProductionNamespace());
			parameters.getMarkdownJavadocOption().set(getMarkdownJavadocOption());
			parameters.getLocalAccessWidener().set(getLocalAccessWidener());
			parameters.getLocalFabricModJson().set(getLocalFabricModJson());
			parameters.getLocalResourcePaths().set(getLocalResourcePaths());
			parameters.getCommonCompileMods().from(getCommonCompileMods());
			parameters.getCommonRuntimeMods().from(getCommonRuntimeMods());
			parameters.getClientCompileMods().from(getClientCompileMods());
			parameters.getClientRuntimeMods().from(getClientRuntimeMods());
			parameters.getUnsupportedProcessors().set(getUnsupportedProcessors());
			parameters.getOutputAnalysis().set(getOutputAnalysis());
		});
	}

	public interface Parameters extends WorkParameters {
		Property<ProcessorType> getProcessorType();
		Property<Boolean> getProcessorEnabled();
		Property<Boolean> getIncludeDependencies();
		Property<String> getProductionNamespace();
		Property<String> getMarkdownJavadocOption();
		RegularFileProperty getLocalAccessWidener();
		RegularFileProperty getLocalFabricModJson();
		ListProperty<String> getLocalResourcePaths();
		ConfigurableFileCollection getCommonCompileMods();
		ConfigurableFileCollection getCommonRuntimeMods();
		ConfigurableFileCollection getClientCompileMods();
		ConfigurableFileCollection getClientRuntimeMods();
		ListProperty<String> getUnsupportedProcessors();
		RegularFileProperty getOutputAnalysis();
	}

	public abstract static class AnalyzeAction implements WorkAction<Parameters> {
		private static final Logger LOGGER = LoggerFactory.getLogger(AnalyzeAction.class);

		@Override
		public void execute() {
			final Path output = getParameters().getOutputAnalysis().get().getAsFile().toPath();

			try {
				if (!getParameters().getUnsupportedProcessors().get().isEmpty()) {
					throw new UnsupportedOperationException("Task-backed Minecraft processing does not support custom processors: "
							+ String.join(", ", getParameters().getUnsupportedProcessors().get()));
				}

				final MinecraftJarProcessorAnalysis analysis = getParameters().getProcessorEnabled().get()
						? analyze()
						: MinecraftJarProcessorAnalysis.EMPTY;
				analysis.write(output);
			} catch (Exception e) {
				cleanOutput(output, e);
				throw new RuntimeException("Failed to analyze Minecraft processor inputs", e);
			}
		}

		private MinecraftJarProcessorAnalysis analyze() throws IOException {
			return switch (getParameters().getProcessorType().get()) {
			case ACCESS_WIDENER -> analyzeAccessWideners();
			case MOD_JAVADOC -> analyzeModJavadocs();
			case INTERFACE_INJECTION -> analyzeInterfaceInjections();
			};
		}

		private MinecraftJarProcessorAnalysis analyzeAccessWideners() throws IOException {
			final List<AccessWidenerData> accessWideners = new ArrayList<>();

			if (getParameters().getLocalAccessWidener().isPresent()) {
				final Path path = getParameters().getLocalAccessWidener().get().getAsFile().toPath();
				accessWideners.add(new AccessWidenerData("local", null, ModEnvironment.UNIVERSAL, true, false, Files.readAllBytes(path)));
			}

			if (getParameters().getIncludeDependencies().get()) {
				for (FabricModJson mod : dependencyMods().mods()) {
					for (Map.Entry<String, ModEnvironment> entry : mod.getClassTweakers().entrySet()) {
						accessWideners.add(new AccessWidenerData(
								mod.getId() + ":" + entry.getKey(),
								mod.getId(),
								entry.getValue(),
								false,
								true,
								mod.getSource().read(entry.getKey())
						));
					}
				}
			}

			accessWideners.sort(Comparator.comparing(AccessWidenerData::sortKey));
			final List<String> binaries = accessWideners.stream()
					.map(entry -> ProcessMinecraftAccessWidenersTask.encodeAccessWidenerData(
							entry.environment(),
							entry.local(),
							entry.transitiveOnly(),
							entry.data()
					))
					.toList();
			final List<String> mappings = accessWideners.stream()
					.filter(entry -> entry.mappingId() != null)
					.map(entry -> PrepareSourceMappingsTask.encodeAccessWidenerData(
							entry.mappingId(),
							entry.transitiveOnly(),
							entry.data()
					))
					.toList();
			return new MinecraftJarProcessorAnalysis(binaries, mappings, List.of());
		}

		private MinecraftJarProcessorAnalysis analyzeInterfaceInjections() throws IOException {
			final List<FabricModJson> mods = new ArrayList<>(localMods());
			final DependencyMods dependencies = dependencyMods();

			if (getParameters().getIncludeDependencies().get()) {
				mods.addAll(dependencies.mods());
			}

			final List<InjectedInterface> interfaces = InjectedInterface.fromMods(mods);
			final List<String> binaries = interfaces.stream()
					.map(injectedInterface -> ProcessMinecraftInterfaceInjectionsTask.encodeInjectedInterface(
							injectedInterface.modId(),
							injectedInterface.className(),
							injectedInterface.ifaceName(),
							injectedInterface.generics()
					))
					.toList();
			final List<String> mappings = interfaces.stream()
					.map(injectedInterface -> PrepareSourceMappingsTask.encodeInterfaceInjection(
							injectedInterface.modId(),
							injectedInterface.className(),
							injectedInterface.ifaceName()
					))
					.toList();
			return new MinecraftJarProcessorAnalysis(binaries, mappings, dependencies.clientOnlyModIds());
		}

		private MinecraftJarProcessorAnalysis analyzeModJavadocs() {
			final List<String> mappings = new ArrayList<>();
			final MappingsNamespace productionNamespace = Objects.requireNonNull(
					MappingsNamespace.of(getParameters().getProductionNamespace().get()),
					"Unknown production namespace"
			);
			final MarkdownJavadocOption markdownOption = MarkdownJavadocOption.valueOf(getParameters().getMarkdownJavadocOption().get());

			for (FabricModJson mod : dependencyMods().mods()) {
				final ModJavadocProcessor.ModJavadoc javadoc = ModJavadocProcessor.ModJavadoc.create(mod, productionNamespace, markdownOption);

				if (javadoc != null) {
					addJavadocTransformations(mappings, javadoc);
				}
			}

			return new MinecraftJarProcessorAnalysis(List.of(), mappings, List.of());
		}

		private static void addJavadocTransformations(List<String> transformations, ModJavadocProcessor.ModJavadoc javadoc) {
			final MemoryMappingTree mappings = javadoc.mappingTree();
			final String namespace = mappings.getSrcNamespace();

			for (MappingTree.ClassMapping classMapping : mappings.getClasses()) {
				addJavadocTransformation(transformations, javadoc.modId(), namespace, "class", classMapping.getSrcName(), null, null, classMapping.getComment());

				for (MappingTree.FieldMapping fieldMapping : classMapping.getFields()) {
					addJavadocTransformation(transformations, javadoc.modId(), namespace, "field", classMapping.getSrcName(), fieldMapping.getSrcName(), fieldMapping.getSrcDesc(), fieldMapping.getComment());
				}

				for (MappingTree.MethodMapping methodMapping : classMapping.getMethods()) {
					addJavadocTransformation(transformations, javadoc.modId(), namespace, "method", classMapping.getSrcName(), methodMapping.getSrcName(), methodMapping.getSrcDesc(), methodMapping.getComment());
				}
			}
		}

		private static void addJavadocTransformation(List<String> transformations, String modId, String namespace, String kind, String owner, @Nullable String name, @Nullable String descriptor, @Nullable String comment) {
			if (comment == null) {
				LOGGER.warn("Mod {} provided javadoc has mapping for {} {}, without comment", modId, kind, name != null ? name : owner);
				return;
			}

			transformations.add(PrepareSourceMappingsTask.encodeJavadoc(modId, namespace, kind, owner, name, descriptor, comment));
		}

		private List<FabricModJson> localMods() throws IOException {
			if (getParameters().getLocalFabricModJson().isPresent()) {
				return List.of(FabricModJsonFactory.createFromFile(getParameters().getLocalFabricModJson().get().getAsFile()));
			}

			final List<Path> roots = getParameters().getLocalResourcePaths().get().stream().map(Path::of).toList();

			for (Path root : roots) {
				final Path fabricModJson = root.resolve(FabricModJsonFactory.FABRIC_MOD_JSON);

				if (Files.isRegularFile(fabricModJson)) {
					try (Reader reader = Files.newBufferedReader(fabricModJson, StandardCharsets.UTF_8)) {
						final JsonObject json = LoomGradlePlugin.GSON.fromJson(reader, JsonObject.class);
						return List.of(FabricModJsonFactory.create(json, new ResourceDirectoriesSource(roots)));
					}
				}
			}

			return List.of();
		}

		private DependencyMods dependencyMods() {
			final Map<String, FabricModJson> commonCompile = loadMods(getParameters().getCommonCompileMods());
			final Map<String, FabricModJson> commonRuntime = loadMods(getParameters().getCommonRuntimeMods());
			final Map<String, FabricModJson> clientCompile = loadMods(getParameters().getClientCompileMods());
			final Map<String, FabricModJson> clientRuntime = loadMods(getParameters().getClientRuntimeMods());
			final Set<String> commonIds = intersection(commonCompile.keySet(), commonRuntime.keySet());
			final Set<String> clientIds = intersection(clientCompile.keySet(), clientRuntime.keySet());
			final Map<String, FabricModJson> mods = new TreeMap<>();

			for (String modId : commonIds) {
				mods.put(modId, commonCompile.get(modId));
			}

			for (String modId : clientIds) {
				mods.putIfAbsent(modId, clientCompile.get(modId));
			}

			final Set<String> clientOnlyIds = new LinkedHashSet<>(clientIds);
			clientOnlyIds.removeAll(commonIds);
			return new DependencyMods(List.copyOf(mods.values()), List.copyOf(clientOnlyIds));
		}

		private static Map<String, FabricModJson> loadMods(ConfigurableFileCollection files) {
			final Map<String, FabricModJson> mods = new LinkedHashMap<>();
			final List<Path> paths = files.getFiles().stream()
					.map(file -> file.toPath().toAbsolutePath().normalize())
					.sorted()
					.toList();

			for (Path path : paths) {
				final FabricModJson mod;

				if (Files.isDirectory(path)) {
					final Path fabricModJson = path.resolve(FabricModJsonFactory.FABRIC_MOD_JSON);

					if (!Files.isRegularFile(fabricModJson)) {
						continue;
					}

					mod = FabricModJsonFactory.createFromFile(fabricModJson.toFile());
				} else if (Files.isRegularFile(path)) {
					mod = FabricModJsonFactory.createFromZipNullable(path);
				} else {
					continue;
				}

				if (mod != null) {
					mods.putIfAbsent(mod.getId(), mod);
				}
			}

			return mods;
		}

		private static Set<String> intersection(Set<String> left, Set<String> right) {
			final Set<String> result = new java.util.TreeSet<>(left);
			result.retainAll(right);
			return result;
		}

		private static void cleanOutput(Path output, Exception failure) {
			try {
				Files.deleteIfExists(output);
			} catch (IOException e) {
				failure.addSuppressed(e);
			}
		}
	}

	private record AccessWidenerData(String sortKey, @Nullable String mappingId, ModEnvironment environment, boolean local, boolean transitiveOnly, byte[] data) {
	}

	private record DependencyMods(List<FabricModJson> mods, List<String> clientOnlyModIds) {
	}

	private record ResourceDirectoriesSource(List<Path> roots) implements FabricModJsonSource {
		@Override
		public byte[] read(String path) throws IOException {
			for (Path root : roots) {
				final Path file = root.resolve(path);

				if (Files.isRegularFile(file)) {
					return Files.readAllBytes(file);
				}
			}

			throw new java.io.FileNotFoundException("Could not find local mod resource: " + path);
		}
	}
}
