/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2021 FabricMC
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

import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.java.archives.Manifest;
import org.gradle.api.java.archives.internal.DefaultManifest;
import org.gradle.api.provider.Provider;
import org.gradle.jvm.tasks.Jar;

import net.fabricmc.loom.task.service.JarManifestService;
import net.fabricmc.loom.util.Constants;

/**
 * Adds Loom metadata to the effective Gradle manifest immediately before the jar is created.
 */
public class ManifestModificationAction implements Action<Task>, Serializable {
	private final Provider<JarManifestService> manifestService;
	private final String targetNamespace;
	private final Provider<Boolean> areEnvironmentSourceSetsSplit;
	private final Provider<List<String>> clientOnlyEntries;

	public ManifestModificationAction(
			Provider<JarManifestService> manifestService,
			String targetNamespace,
			Provider<Boolean> areEnvironmentSourceSetsSplit,
			Provider<List<String>> clientOnlyEntries) {
		this.manifestService = manifestService;
		this.targetNamespace = targetNamespace;
		this.areEnvironmentSourceSetsSplit = areEnvironmentSourceSetsSplit;
		this.clientOnlyEntries = clientOnlyEntries;
	}

	@Override
	public void execute(Task t) {
		final Jar jarTask = (Jar) t;
		final Manifest manifest = jarTask.getManifest();
		final Manifest effectiveManifest = manifest.getEffectiveManifest();
		final Map<String, Object> attributes = new LinkedHashMap<>(effectiveManifest.getAttributes());
		final Map<String, Map<String, Object>> sections = new LinkedHashMap<>();

		effectiveManifest.getSections().forEach((name, values) -> sections.put(name, new LinkedHashMap<>(values)));

		// The Jar task may have cached this manifest while snapshotting its inputs. Mutate that
		// instance and remove its now-materialized merge sources rather than replacing it.
		if (manifest instanceof DefaultManifest defaultManifest) {
			defaultManifest.clear();
		} else {
			manifest.getAttributes().clear();
			manifest.getSections().clear();
		}

		manifest.attributes(attributes);
		sections.forEach((name, values) -> manifest.attributes(values, name));

		final boolean hasMixinVersion = attributes.containsKey(Constants.Manifest.MIXIN_VERSION);
		manifest.attributes(manifestService.get().createAttributes(getManifestAttributes(), hasMixinVersion));
	}

	private Map<String, String> getManifestAttributes() {
		Map<String, String> manifestAttributes = new HashMap<>();

		// Set the mapping namespace to "official" for non-remapped jars
		manifestAttributes.put(Constants.Manifest.MAPPING_NAMESPACE, targetNamespace);

		// Set split environment flag if source sets are split (even for common-only jars)
		if (areEnvironmentSourceSetsSplit.get()) {
			manifestAttributes.put(Constants.Manifest.SPLIT_ENV, "true");
		}

		// Add client-only entries list if present
		if (clientOnlyEntries != null) {
			List<String> entries = clientOnlyEntries.get();

			if (!entries.isEmpty()) {
				manifestAttributes.put(Constants.Manifest.CLIENT_ENTRIES, String.join(";", entries));
			}
		}

		return manifestAttributes;
	}
}
