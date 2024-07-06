/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022 FabricMC
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

package net.fabricmc.loom.task.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.gradle.api.model.ObjectFactory;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.extension.RemapperExtensionHolder;
import net.fabricmc.loom.task.RemapJarTask;
import net.fabricmc.loom.util.kotlin.KotlinClasspath;
import net.fabricmc.loom.util.kotlin.KotlinClasspathService;
import net.fabricmc.loom.util.kotlin.KotlinRemapperClassloader;
import net.fabricmc.loom.util.service.SharedServiceManager;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.InputTag;
import net.fabricmc.tinyremapper.TinyRemapper;

public class TinyRemapperService implements AutoCloseable {
	public static TinyRemapperService getOrCreate(SharedServiceManager serviceManager, RemapJarTask.RemapParams params) {
		final String to = params.getTargetNamespace().get();
		final String from = params.getSourceNamespace().get();
		final boolean legacyMixin = !params.getUseMixinExtension().get();
		final @Nullable KotlinClasspathService kotlinClasspathService = KotlinClasspathService.getOrCreateIfRequired(serviceManager, project);

		List<MappingsService.Mappings> mappings = params.getMappings().get();

		TinyRemapperService service = new TinyRemapperService(mappings, !legacyMixin, kotlinClasspathService, extension.getKnownIndyBsms().get(), extension.getRemapperExtensions().get(), from, to, project.getObjects());

		List<Path> classPath = params.getClasspath()
				.getFiles()
				.stream()
				.map(File::toPath)
				.filter(Files::exists)
				.toList();

		// TODO pass in via constructor
		service.readClasspath(classPath);
		return service;
	}

	private TinyRemapper tinyRemapper;
	@Nullable
	private KotlinRemapperClassloader kotlinRemapperClassloader;
	private final Map<String, InputTag> inputTagMap = new HashMap<>();
	private final HashSet<Path> classpath = new HashSet<>();
	// Set to true once remapping has started, once set no inputs can be read.
	private boolean isRemapping = false;

	private TinyRemapperService(List<IMappingProvider> mappings, boolean useMixinExtension, @Nullable KotlinClasspath kotlinClasspath, Set<String> knownIndyBsms, List<RemapperExtensionHolder> remapperExtensions, String sourceNamespace, String targetNamespace, ObjectFactory objectFactory) {
		TinyRemapper.Builder builder = TinyRemapper.newRemapper().withKnownIndyBsm(knownIndyBsms);

		for (IMappingProvider provider : mappings) {
			builder.withMappings(provider);
		}

		if (useMixinExtension) {
			builder.extension(new net.fabricmc.tinyremapper.extension.mixin.MixinExtension());
		}

		if (kotlinClasspath != null) {
			kotlinRemapperClassloader = KotlinRemapperClassloader.create(kotlinClasspath);
			builder.extension(kotlinRemapperClassloader.getTinyRemapperExtension());
		}

		for (RemapperExtensionHolder holder : remapperExtensions) {
			holder.apply(builder, sourceNamespace, targetNamespace, objectFactory);
		}

		tinyRemapper = builder.build();
	}

	public synchronized InputTag getOrCreateTag(Path file) {
		InputTag tag = inputTagMap.get(file.toAbsolutePath().toString());

		if (tag == null) {
			tag = tinyRemapper.createInputTag();
			inputTagMap.put(file.toAbsolutePath().toString(), tag);
		}

		return tag;
	}

	public TinyRemapper getTinyRemapperForRemapping() {
		synchronized (this) {
			isRemapping = true;
			return Objects.requireNonNull(tinyRemapper, "Tiny remapper has not been setup");
		}
	}

	public synchronized TinyRemapper getTinyRemapperForInputs() {
		synchronized (this) {
			if (isRemapping) {
				throw new IllegalStateException("Cannot read inputs as remapping has already started");
			}

			return tinyRemapper;
		}
	}

	void readClasspath(List<Path> paths) {
		List<Path> toRead = new ArrayList<>();

		synchronized (classpath) {
			for (Path path: paths) {
				if (classpath.contains(path)) {
					continue;
				}

				toRead.add(path);
				classpath.add(path);
			}
		}

		if (toRead.isEmpty()) {
			return;
		}

		tinyRemapper.readClassPath(toRead.toArray(Path[]::new));
	}

	@Override
	public void close() throws IOException {
		if (tinyRemapper != null) {
			tinyRemapper.finish();
			tinyRemapper = null;
		}

		if (kotlinRemapperClassloader != null) {
			kotlinRemapperClassloader.close();
		}
	}
}
