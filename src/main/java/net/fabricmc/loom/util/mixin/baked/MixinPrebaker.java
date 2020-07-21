/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
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

package net.fabricmc.loom.util.mixin.baked;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import com.google.common.io.ByteStreams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.FabricMixinTransformerProxy;

import net.fabricmc.mapping.tree.TinyMappingFactory;
import net.fabricmc.mapping.tree.TinyTree;

// Loosly based off the previous attempt https://github.com/FabricMC/fabric-loom/commit/8481ccc4781a0dad43be394fb8f9a9bac78b6cc7
public class MixinPrebaker {
	private static final Gson GSON = new GsonBuilder().create();

	public static void main(String[] args) {
		PrebakedMixinMetadata metadata;

		try {
			metadata = GSON.fromJson(new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8), PrebakedMixinMetadata.class);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		try {
			// Just a quick test to ensure that we have the classpath setup right
			System.out.println(Class.forName("net.fabricmc.fabric.mixin.command.MixinCommandManager"));
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}

		try {
			bake(metadata);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void bake(PrebakedMixinMetadata metadata) throws IOException {
		// We could try doing this on its own class loader, but the past has shown it needs to run in its own throwaway jvm
		MixinBootstrap.init();
		setupMappings(metadata);

		System.out.println("Pre baking mixins");

		collectMixins(metadata)
				.forEach(Mixins::addConfiguration);

		MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);

		FabricMixinTransformerProxy transformerProxy = new FabricMixinTransformerProxy();
		transformerProxy.audit(MixinEnvironment.getDefaultEnvironment());

		Path tempOutputJar = Paths.get(metadata.getTargetJar().toString() + ".mixin.jar");

		Files.deleteIfExists(tempOutputJar);

		try (JarInputStream input = new JarInputStream(Files.newInputStream(metadata.getTargetJar())); JarOutputStream output = new JarOutputStream(Files.newOutputStream(tempOutputJar))) {
			JarEntry entry;

			while ((entry = input.getNextJarEntry()) != null) {
				if (entry.getName().endsWith(".class")) {
					byte[] classIn = ByteStreams.toByteArray(input);
					String className = entry.getName().substring(0, entry.getName().length() - 6).replace('/', '.');

					byte[] classOut = transformerProxy.transformClassBytes(className, className, classIn);

					if (classIn != classOut) {
						System.out.println("Transformed " + className);
						classOut = DesprinklingClassVisitor.desprinkle(classOut);
					}

					JarEntry newEntry = new JarEntry(entry.getName());
					newEntry.setComment(entry.getComment());
					newEntry.setSize(classOut.length);
					newEntry.setLastModifiedTime(FileTime.from(Instant.now()));

					output.putNextEntry(newEntry);
					output.write(classOut);
				} else {
					output.putNextEntry(entry);
					ByteStreams.copy(input, output);
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		//Files.copy(tempOutputJar, metadata.getTargetJar(), StandardCopyOption.REPLACE_EXISTING);
	}

	private static void setupMappings(PrebakedMixinMetadata metadata) {
		TinyTree mappings;

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(metadata.getMappings())))) {
			mappings = TinyMappingFactory.loadWithDetection(reader);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		String targetNamespace = "named";
		System.setProperty("mixin.env.remapRefMap", "true");

		try {
			MixinIntermediaryDevRemapper remapper = new MixinIntermediaryDevRemapper(mappings, "intermediary", targetNamespace);
			MixinEnvironment.getDefaultEnvironment().getRemappers().add(remapper);
			System.out.println("Loaded Fabric development mappings for mixin remapper!");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static List<String> collectMixins(PrebakedMixinMetadata metadata) {
		List<String> mixins = new ArrayList<>();

		for (Path dependency : metadata.getDependencies()) {
			try (JarFile jarFile = new JarFile(dependency.toFile())) {
				ZipEntry modEntry = jarFile.getEntry("fabric.mod.json");

				if (modEntry == null) {
					// Not a mod
					continue;
				}

				try (InputStream inputStream = jarFile.getInputStream(modEntry)) {
					JsonObject json = GSON.fromJson(new InputStreamReader(inputStream), JsonObject.class);

					if (json.has("mixins")) {
						JsonArray mixinsArray = json.getAsJsonArray("mixins");

						for (JsonElement mixinElement : mixinsArray) {
							if (isValidMixin(jarFile, mixinElement.getAsString())) {
								mixins.add(mixinElement.getAsString());
							}
						}
					}
				}
			} catch (IOException ignored) {
				// Nope, not a jar
			}
		}

		return mixins;
	}

	private static boolean isValidMixin(JarFile jarFile, String name) {
		ZipEntry mixinEntry = jarFile.getEntry(name);

		if (mixinEntry == null) {
			return false;
		}

		try (InputStream inputStream = jarFile.getInputStream(mixinEntry)) {
			JsonObject json = GSON.fromJson(new InputStreamReader(inputStream), JsonObject.class);

			// Skip over applying any mixins that have a mixin plugin, as it will end badly
			if (json.has("plugin")) {
				return false;
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		return true;
	}
}
