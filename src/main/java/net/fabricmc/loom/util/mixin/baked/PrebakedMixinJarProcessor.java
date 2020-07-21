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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.gradle.process.ExecResult;
import org.gradle.api.Project;

import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ConsumingOutputStream;
import net.fabricmc.loom.processors.JarProcessor;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.decompilers.fernflower.ForkingJavaExec;

public class PrebakedMixinJarProcessor implements JarProcessor {
	private static final Gson GSON = new GsonBuilder().create();

	private Project project;
	private Path metaJsonPath;
	private PrebakedMixinMetadata metadata;

	@Override
	public void setup(Project project) {
		this.project = project;
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);

		try {
			metaJsonPath = Files.createTempFile("metajson", ".json");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		metadata = new PrebakedMixinMetadata();
		metadata.setMappings(extension.getMappingsProvider().tinyMappings.toPath());
	}

	@Override
	public void process(File file) { }

	@Override
	public void postProcess(File file) {
		for (File inputFile : project.getConfigurations().getByName(Constants.MOD_COMPILE_CLASSPATH_MAPPED).resolve()) {
			// Loader cannot be anywhere near this stuff otherwise it explodes
			if (inputFile.getName().contains("fabric-loader")) {
				continue;
			}

			metadata.addDependencies(Collections.singletonList(inputFile.toPath()));
		}

		metadata.setTargetJarFile(file.toPath());

		try {
			Files.write(metaJsonPath, GSON.toJson(metadata).getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		ExecResult result = ForkingJavaExec.javaexec(project, spec -> {
			spec.setMain(MixinPrebaker.class.getName());
			spec.jvmArgs("-Xms200m", "-Xmx1G");
			spec.setArgs(Collections.singletonList(metaJsonPath.toAbsolutePath().toString()));
			spec.setErrorOutput(System.err);
			spec.setStandardOutput(new ConsumingOutputStream(project.getLogger()::lifecycle));

			for (File inputFile : project.getConfigurations().getByName(Constants.MOD_COMPILE_CLASSPATH_MAPPED).resolve()) {
				// Loader cannot be anywhere near this stuff otherwise it explodes
				if (inputFile.getName().contains("fabric-loader")) {
					continue;
				}

				spec.classpath(inputFile);
			}

			spec.classpath(project.getConfigurations().getByName(Constants.MINECRAFT_DEPENDENCIES));
			spec.classpath(file);
		});

		result.rethrowFailure();
		result.assertNormalExitValue();
	}

	@Override
	public boolean isInvalid(File file) {
		return true;
	}
}
