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
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import net.fabricmc.loom.api.RunConfiguration;

public class XcschemeFactory {
	private static final List<String> JVM_SIGNALS = List.of("SIGSEGV", "SIGBUS", "SIGILL", "SIGTRAP", "SIGABRT");

	public String generate(RunConfiguration run, File javaExecutable, String classpathArg) {
		try {
			return generate(
					javaExecutable.getAbsolutePath(),
					run.getRunDirectory().get().getAsFile().getAbsolutePath(),
					classpathArg,
					run.getJvmArguments().get(),
					run.getMainClass().get(),
					run.getProgramArguments().get(),
					run.getEnvironmentVars().get()
			);
		} catch (Exception e) {
			throw new RuntimeException("Failed to generate xcscheme", e);
		}
	}

	private String generate(String javaExecutable, String workingDirectory, String classpathArg,
			List<String> jvmArgs, String mainClass, List<String> programArgs,
			Map<String, Object> envVars) throws Exception {
		var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();

		var scheme = elem(doc, "Scheme");
		scheme.setAttribute("LastUpgradeVersion", "1500");
		scheme.setAttribute("version", "1.8");
		doc.appendChild(scheme);

		var buildAction = elem(doc, "BuildAction");
		buildAction.setAttribute("parallelizeBuildables", "YES");
		buildAction.setAttribute("buildImplicitDependencies", "YES");
		scheme.appendChild(buildAction);

		var launchAction = elem(doc, "LaunchAction");
		launchAction.setAttribute("buildConfiguration", "Debug");
		launchAction.setAttribute("selectedDebuggerIdentifier", "Xcode.DebuggerFoundation.Debugger.LLDB");
		launchAction.setAttribute("selectedLauncherIdentifier", "Xcode.DebuggerFoundation.Launcher.LLDB");
		launchAction.setAttribute("launchStyle", "0");
		launchAction.setAttribute("useCustomWorkingDirectory", "YES");
		launchAction.setAttribute("customWorkingDirectory", workingDirectory);
		launchAction.setAttribute("ignoresPersistentStateOnLaunch", "NO");
		launchAction.setAttribute("debugDocumentVersioning", "YES");
		scheme.appendChild(launchAction);

		var pathRunnable = elem(doc, "PathRunnable");
		pathRunnable.setAttribute("runnableDebuggingMode", "0");
		pathRunnable.setAttribute("FilePath", javaExecutable);
		launchAction.appendChild(pathRunnable);

		var cmdArgs = elem(doc, "CommandLineArguments");
		launchAction.appendChild(cmdArgs);
		cmdArgs.appendChild(commandLineArgument(doc, classpathArg));
		for (var arg : jvmArgs) {
			cmdArgs.appendChild(commandLineArgument(doc, arg));
		}
		cmdArgs.appendChild(commandLineArgument(doc, mainClass));
		for (var arg : programArgs) {
			cmdArgs.appendChild(commandLineArgument(doc, arg));
		}

		if (!envVars.isEmpty()) {
			var envVarsEl = elem(doc, "EnvironmentVariables");
			launchAction.appendChild(envVarsEl);
			for (var entry : envVars.entrySet()) {
				var envVar = elem(doc, "EnvironmentVariable");
				envVar.setAttribute("key", entry.getKey());
				envVar.setAttribute("value", entry.getValue().toString());
				envVar.setAttribute("isEnabled", "YES");
				envVarsEl.appendChild(envVar);
			}
		}

		// JVM uses these signals internally; prevent the debugger from intercepting them
		var signalSettings = elem(doc, "SignalSettings");
		signalSettings.setAttribute("shouldStopForInternalExceptions", "NO");
		launchAction.appendChild(signalSettings);
		var signals = elem(doc, "Signals");
		signalSettings.appendChild(signals);
		for (var name : JVM_SIGNALS) {
			var signal = elem(doc, "Signal");
			signal.setAttribute("name", name);
			signal.setAttribute("shouldStop", "NO");
			signals.appendChild(signal);
		}

		return serialize(doc);
	}

	private static Element elem(Document doc, String tag) {
		return doc.createElement(tag);
	}

	private static Element commandLineArgument(Document doc, String value) {
		var el = elem(doc, "CommandLineArgument");
		el.setAttribute("argument", value);
		el.setAttribute("isEnabled", "YES");
		return el;
	}

	private static String serialize(Document doc) throws Exception {
		var transformer = TransformerFactory.newInstance().newTransformer();
		transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "3");
		var sw = new StringWriter();
		transformer.transform(new DOMSource(doc), new StreamResult(sw));
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + sw;
	}
}
