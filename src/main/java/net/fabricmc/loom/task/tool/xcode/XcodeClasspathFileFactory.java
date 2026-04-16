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

package net.fabricmc.loom.task.tool.xcode;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.stream.Collectors;

public class XcodeClasspathFileFactory {
	public static String generate(Collection<File> classpath, File outputFile) {
		String content = "-classpath\n" + classpath.stream()
				.map(File::getAbsolutePath)
				.map(XcodeClasspathFileFactory::quoteArg)
				.collect(Collectors.joining(File.pathSeparator));

		try {
			Files.createDirectories(outputFile.toPath().getParent());
			Files.writeString(outputFile.toPath(), content, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to create classpath arg file", e);
		}

		return "@" + outputFile.getAbsolutePath();
	}

	// Based off https://github.com/JetBrains/intellij-community/blob/295dd68385a458bdfde638152e36d19bed18b666/platform/util/src/com/intellij/execution/CommandLineWrapperUtil.java#L87
	private static String quoteArg(String arg) {
		final String specials = " #'\"\n\r\t\f";

		if (!containsAnyChar(arg, specials)) {
			return arg;
		}

		final StringBuilder sb = new StringBuilder(arg.length() * 2);

		for (int i = 0; i < arg.length(); i++) {
			char c = arg.charAt(i);

			switch (c) {
			case ' ', '#', '\'' -> sb.append('"').append(c).append('"');
			case '"' -> sb.append("\"\\\"\"");
			case '\n' -> sb.append("\"\\n\"");
			case '\r' -> sb.append("\"\\r\"");
			case '\t' -> sb.append("\"\\t\"");
			case '\f' -> sb.append("\"\\f\"");
			default -> sb.append(c);
			}
		}

		return sb.toString();
	}

	// https://github.com/JetBrains/intellij-community/blob/295dd68385a458bdfde638152e36d19bed18b666/platform/util/base/src/com/intellij/openapi/util/text/Strings.java#L100-L118
	static boolean containsAnyChar(final String value, final String chars) {
		return chars.length() > value.length()
				? containsAnyChar(value, chars, 0, value.length())
				: containsAnyChar(chars, value, 0, chars.length());
	}

	static boolean containsAnyChar(final String value, final String chars, final int start, final int end) {
		for (int i = start; i < end; i++) {
			if (chars.indexOf(value.charAt(i)) >= 0) {
				return true;
			}
		}

		return false;
	}
}
