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

package net.fabricmc.loom.util;

import java.util.List;

import com.google.common.collect.ImmutableList;

public class Constants {
	public static final String LIBRARIES_BASE = "https://libraries.minecraft.net/";
	public static final String RESOURCES_BASE = "http://resources.download.minecraft.net/";
	public static final String VERSION_MANIFESTS = "https://launchermeta.mojang.com/mc/game/version_manifest.json";

	public static final String SYSTEM_ARCH = System.getProperty("os.arch").equals("64") ? "64" : "32";

	public static final List<RemappedConfigurationEntry> MOD_COMPILE_ENTRIES = ImmutableList.of(
			new RemappedConfigurationEntry("modCompile", "compile", true, "compile"),
			new RemappedConfigurationEntry("modApi", "api", true, "compile"),
			new RemappedConfigurationEntry("modImplementation", "implementation", true, "runtime"),
			new RemappedConfigurationEntry("modRuntime", "runtimeOnly", false, ""),
			new RemappedConfigurationEntry("modCompileOnly", "compileOnly", true, "")
	);

	private Constants() {
	}

	/**
	 * Constants related to configurations.
	 */
	public static final class Configurations {
		public static final String MOD_COMPILE_CLASSPATH = "modCompileClasspath";
		public static final String MOD_COMPILE_CLASSPATH_MAPPED = "modCompileClasspathMapped";
		public static final String INCLUDE = "include";
		public static final String MINECRAFT = "minecraft";
		public static final String MINECRAFT_DEPENDENCIES = "minecraftLibraries";
		public static final String MINECRAFT_REMAP_CLASSPATH = "minecraftRemapClasspath";
		public static final String MINECRAFT_NAMED = "minecraftNamed";
		public static final String MAPPINGS = "mappings";
		public static final String MAPPINGS_FINAL = "mappingsFinal";
		public static final String LOADER_DEPENDENCIES = "loaderLibraries";

		private Configurations() {
		}
	}

	/**
	 * Constants related to dependencies.
	 */
	public static final class Dependencies {
		public static final String MIXIN_COMPILE_EXTENSIONS = "net.fabricmc:fabric-mixin-compile-extensions:";
		public static final String DEV_LAUNCH_INJECTOR = "net.fabricmc:dev-launch-injector:";
		public static final String TERMINAL_CONSOLE_APPENDER = "net.minecrell:terminalconsoleappender:";
		public static final String JETBRAINS_ANNOTATIONS = "org.jetbrains:annotations:";

		private Dependencies() {
		}

		/**
		 * Constants for versions of dependencies.
		 */
		public static final class Versions {
			public static final String MIXIN_COMPILE_EXTENSIONS = "0.3.2.6";
			public static final String DEV_LAUNCH_INJECTOR = "0.2.1+build.8";
			public static final String TERMINAL_CONSOLE_APPENDER = "1.2.0";
			public static final String JETBRAINS_ANNOTATIONS = "19.0.0";

			private Versions() {
			}
		}
	}

	public static final class MixinArguments {
		public static final String IN_MAP_FILE_NAMED_INTERMEDIARY = "inMapFileNamedIntermediary";
		public static final String OUT_MAP_FILE_NAMED_INTERMEDIARY = "outMapFileNamedIntermediary";
		public static final String OUT_REFMAP_FILE = "outRefMapFile";
		public static final String DEFAULT_OBFUSCATION_ENV = "defaultObfuscationEnv";

		private MixinArguments() {
		}
	}

	public static final class LaunchWrapper {
		public static final String DEFAULT_FABRIC_CLIENT_TWEAKER = "net.fabricmc.loader.launch.FabricClientTweaker";
		public static final String DEFAULT_FABRIC_SERVER_TWEAKER = "net.fabricmc.loader.launch.FabricServerTweaker";

		private LaunchWrapper() {
		}
	}
}
