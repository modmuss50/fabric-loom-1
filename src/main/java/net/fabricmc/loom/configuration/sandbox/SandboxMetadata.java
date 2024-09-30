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

package net.fabricmc.loom.configuration.sandbox;

import static net.fabricmc.loom.util.fmj.FabricModJsonUtils.ParseException;
import static net.fabricmc.loom.util.fmj.FabricModJsonUtils.getJsonObject;
import static net.fabricmc.loom.util.fmj.FabricModJsonUtils.readInt;
import static net.fabricmc.loom.util.fmj.FabricModJsonUtils.readString;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.fabricmc.loom.util.Platform;
import net.fabricmc.loom.util.ZipUtils;

public sealed interface SandboxMetadata permits SandboxMetadata.V2 {
	String SANDBOX_METADATA_FILENAME = "fabric-sandbox.json";

	static SandboxMetadata readFromJar(Path path) {
		try {
			JsonObject jsonObject = ZipUtils.unpackGson(path, SANDBOX_METADATA_FILENAME, JsonObject.class);
			int version = readInt(jsonObject, "version");
			return switch (version) {
			case 2 -> SandboxMetadata.V2.parse(jsonObject);
			default -> throw new UnsupportedOperationException("Unsupported sandbox metadata version: " + version);
			};
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read: " + SANDBOX_METADATA_FILENAME, e);
		}
	}

	/**
	 * @param platform The platform to check.
	 * @return True if the sandbox supports the platform, false otherwise.
	 */
	boolean supportsPlatform(Platform platform);

	/**
	 * @param platform The platform to get the configuration for.
	 * @return The configuration for the platform.
	 */
	Configuration getConfiguration(Platform platform);

	record V2(Map<OperatingSystemAndArchitecture, Configuration> platforms) implements SandboxMetadata {
		static V2 parse(JsonObject jsonObject) {
			Map<OperatingSystemAndArchitecture, Configuration> platforms = new HashMap<>();

			for (Map.Entry<String, JsonElement> entry : getJsonObject(jsonObject, "platforms").entrySet()) {
				if (!entry.getValue().isJsonObject()) {
					throw new ParseException("Unexpected json object type for key (%s)", entry.getKey());
				}

				final OperatingSystemAndArchitecture osAndArch = OperatingSystemAndArchitecture.parse(entry.getKey());
				final Configuration configuration = V2Configuration.parse(entry.getValue().getAsJsonObject());

				platforms.put(osAndArch, configuration);
			}

			return new V2(Collections.unmodifiableMap(platforms));
		}

		@Override
		public boolean supportsPlatform(Platform platform) {
			for (OperatingSystemAndArchitecture entry : platforms.keySet()) {
				if (entry.compatibleWith(platform)) {
					return true;
				}
			}

			return false;
		}

		@Override
		public Configuration getConfiguration(Platform platform) {
			for (OperatingSystemAndArchitecture entry : platforms.keySet()) {
				if (entry.compatibleWith(platform)) {
					return platforms.get(entry);
				}
			}

			throw new ParseException("No compatible configuration found for platform: %s", platform);
		}

		record V2Configuration(String entrypoint) implements Configuration {
			private static Configuration parse(JsonObject jsonObject) {
				return new V2Configuration(readString(jsonObject, "entrypoint"));
			}
		}
	}

	enum OperatingSystem {
		WINDOWS,
		MAC_OS,
		LINUX;

		public boolean compatibleWith(Platform platform) {
			final Platform.OperatingSystem operatingSystem = platform.getOperatingSystem();

			return switch (this) {
			case WINDOWS -> operatingSystem.isWindows();
			case MAC_OS -> operatingSystem.isMacOS();
			case LINUX -> operatingSystem.isLinux();
			};
		}

		private static OperatingSystem parse(String os) {
			return switch (os) {
			case "windows" -> OperatingSystem.WINDOWS;
			case "macos" -> OperatingSystem.MAC_OS;
			case "linux" -> OperatingSystem.LINUX;
			default -> throw new ParseException("Unsupported sandbox operating system: %s", os);
			};
		}
	}

	enum Architecture {
		X86_64,
		ARM64;

		public boolean compatibleWith(Platform platform) {
			final Platform.Architecture architecture = platform.getArchitecture();

			if (!architecture.is64Bit()) {
				return false;
			}

			return switch (this) {
			case X86_64 -> !architecture.isArm();
			case ARM64 -> architecture.isArm();
			};
		}

		private static Architecture parse(String arch) {
			return switch (arch) {
			case "x86_64" -> Architecture.X86_64;
			case "arm64" -> Architecture.ARM64;
			default -> throw new ParseException("Unsupported sandbox architecture: %s", arch);
			};
		}
	}

	record OperatingSystemAndArchitecture(OperatingSystem operatingSystem, Architecture architecture) {
		private static OperatingSystemAndArchitecture parse(String osAndArch) {
			String[] parts = osAndArch.split("/");

			if (parts.length != 2) {
				throw new ParseException("Invalid os and architecture format: %s", osAndArch);
			}

			return new OperatingSystemAndArchitecture(OperatingSystem.parse(parts[0]), Architecture.parse(parts[1]));
		}

		public boolean compatibleWith(Platform platform) {
			return operatingSystem.compatibleWith(platform) && architecture.compatibleWith(platform);
		}
	}

	interface Configuration {
		/**
		 * @return The entrypoint for the sandbox.
		 */
		String entrypoint();
	}
}
