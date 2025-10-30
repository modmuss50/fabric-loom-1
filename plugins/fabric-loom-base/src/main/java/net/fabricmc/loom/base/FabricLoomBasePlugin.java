/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2025 FabricMC
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

package net.fabricmc.loom.base;

import java.util.Objects;
import java.util.function.Supplier;

import org.gradle.api.Project;
import org.gradle.internal.impldep.com.google.gson.Gson;
import org.gradle.internal.impldep.com.google.gson.GsonBuilder;

import net.fabricmc.loom.base.api.LoomBaseExtension;
import net.fabricmc.loom.base.task.ExportClasspathTask;
import net.fabricmc.loom.configuration.LoomConfigurations;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.Lazy;

public class FabricLoomBasePlugin extends FabricLoomAbstractPlugin {
	public static final String LOOM_VERSION = Objects.requireNonNullElse(FabricLoomBasePlugin.class.getPackage().getImplementationVersion(), "0.0.0+unknown");
	public static final Supplier<Gson> GSON = Lazy.of(() -> new GsonBuilder().setPrettyPrinting().create());

	@Override
	protected void apply(Project project) {
		project.getLogger().lifecycle("Fabric Loom: " + LOOM_VERSION);

		project.getExtensions().create(LoomBaseExtension.class, LoomBaseExtensionImpl.NAME, LoomBaseExtensionImpl.class);

		var exportClassPathTask = project.getTasks().register(Constants.Task.EXPORT_CLASSPATH, ExportClasspathTask.class);
		project.getConfigurations().register(Constants.Configurations.EXPORTED_CLASSPATH, LoomConfigurations.Role.CONSUMABLE::apply);
		project.artifacts(artifactHandler -> artifactHandler.add(Constants.Configurations.EXPORTED_CLASSPATH, exportClassPathTask));
	}
}
