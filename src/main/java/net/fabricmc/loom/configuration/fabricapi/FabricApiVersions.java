/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2024 FabricMC
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

package net.fabricmc.loom.configuration.fabricapi;

import java.io.File;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.gradle.api.Project;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.util.download.Download;
import net.fabricmc.loom.util.download.DownloadException;

public abstract class FabricApiVersions {
	@Inject
	protected abstract Project getProject();

	private final Map<String, Provider<Map<String, String>>> apiModuleVersions = new ConcurrentHashMap<>();
	private final Map<String, Provider<Map<String, String>>> deprecatedApiModuleVersions = new ConcurrentHashMap<>();

	public Provider<String> module(String moduleName, String fabricApiVersion) {
		return moduleVersion(moduleName, fabricApiVersion)
				.map(moduleVersion -> String.format("net.fabricmc.fabric-api:%s:%s", moduleName, moduleVersion));
	}

	public Provider<String> moduleVersion(String moduleName, String fabricApiVersion) {
		final Provider<String> moduleVersion = apiModuleVersions
				.computeIfAbsent(fabricApiVersion, version -> createModuleVersionsProvider("fabric-api", version, false))
				.map(versions -> versions.get(moduleName))
				.orElse(deprecatedApiModuleVersions
						.computeIfAbsent(fabricApiVersion, version -> createModuleVersionsProvider("fabric-api-deprecated", version, true))
						.map(versions -> versions.get(moduleName)));

		return moduleVersion.orElse(getProject().getProviders().provider(() -> {
			throw new RuntimeException("Failed to find module version for module: " + moduleName);
		}));
	}

	private Provider<Map<String, String>> createModuleVersionsProvider(String name, String fabricApiVersion, boolean missingAllowed) {
		final LoomGradleExtension extension = LoomGradleExtension.get(getProject());

		return getProject().getProviders().of(ModuleVersionsValueSource.class, spec -> {
			final ModuleVersionsValueSource.Parameters parameters = spec.getParameters();
			parameters.getName().set(name);
			parameters.getFabricApiVersion().set(fabricApiVersion);
			parameters.getUserCachePath().set(extension.getFiles().getUserCache().getAbsolutePath());
			parameters.getOffline().set(getProject().getGradle().getStartParameter().isOffline());
			parameters.getRefresh().set(extension.refreshDeps());
			parameters.getMissingAllowed().set(missingAllowed);
		});
	}

	private static Map<String, String> populateModuleVersionMap(File pomFile) {
		try {
			DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
			Document pom = docBuilder.parse(pomFile);

			Map<String, String> versionMap = new HashMap<>();

			NodeList dependencies = ((Element) pom.getElementsByTagName("dependencies").item(0)).getElementsByTagName("dependency");

			for (int i = 0; i < dependencies.getLength(); i++) {
				Element dep = (Element) dependencies.item(i);
				Element artifact = (Element) dep.getElementsByTagName("artifactId").item(0);
				Element version = (Element) dep.getElementsByTagName("version").item(0);

				if (artifact == null || version == null) {
					throw new RuntimeException("Failed to find artifact or version");
				}

				versionMap.put(artifact.getTextContent(), version.getTextContent());
			}

			return versionMap;
		} catch (Exception e) {
			throw new RuntimeException("Failed to parse " + pomFile.getName(), e);
		}
	}

	private static File getPom(String name, String version, File userCache, boolean offline, boolean refresh) throws PomNotFoundException {
		final File mavenPom = new File(userCache, "fabric-api/%s-%s.pom".formatted(name, version));

		try {
			final var download = Download.create(String.format("https://maven.fabricmc.net/net/fabricmc/fabric-api/%2$s/%1$s/%2$s-%1$s.pom", version, name));

			if (offline) {
				download.offline();
			}

			if (refresh) {
				download.forceDownload();
			}

			download.defaultCache().downloadPath(mavenPom.toPath());
		} catch (DownloadException e) {
			if (e.getStatusCode() == 404) {
				throw new PomNotFoundException(e);
			}

			throw new UncheckedIOException("Failed to download maven info to " + mavenPom.getName(), e);
		} catch (URISyntaxException e) {
			throw new RuntimeException("Failed to create Fabric API POM download", e);
		}

		return mavenPom;
	}

	public abstract static class ModuleVersionsValueSource implements ValueSource<Map<String, String>, ModuleVersionsValueSource.Parameters> {
		public interface Parameters extends ValueSourceParameters {
			Property<String> getName();

			Property<String> getFabricApiVersion();

			Property<String> getUserCachePath();

			Property<Boolean> getOffline();

			Property<Boolean> getRefresh();

			Property<Boolean> getMissingAllowed();
		}

		@Override
		public Map<String, String> obtain() {
			final Parameters parameters = getParameters();
			final String name = parameters.getName().get();
			final String fabricApiVersion = parameters.getFabricApiVersion().get();
			final File userCache = new File(parameters.getUserCachePath().get());
			final boolean offline = parameters.getOffline().get();
			final boolean refresh = parameters.getRefresh().get();

			try {
				return populateModuleVersionMap(getPom(name, fabricApiVersion, userCache, offline, refresh));
			} catch (PomNotFoundException e) {
				if (parameters.getMissingAllowed().get()) {
					return Map.of();
				}

				throw new RuntimeException("Could not find fabric-api version: " + fabricApiVersion);
			}
		}
	}

	private static class PomNotFoundException extends Exception {
		PomNotFoundException(Throwable cause) {
			super(cause);
		}
	}
}
