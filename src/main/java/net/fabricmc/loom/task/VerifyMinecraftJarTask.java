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

package net.fabricmc.loom.task;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.LocalState;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.configuration.providers.minecraft.verify.CertificateRevocationList;
import net.fabricmc.loom.configuration.providers.minecraft.verify.MinecraftJarVerification;
import net.fabricmc.loom.configuration.providers.minecraft.verify.SignatureVerificationFailure;
import net.fabricmc.loom.util.download.Download;
import net.fabricmc.loom.util.download.DownloadBuilder;
import net.fabricmc.loom.util.download.DownloadException;

@ApiStatus.Internal
@DisableCachingByDefault(because = "Minecraft jars should not be stored in the build cache")
public abstract class VerifyMinecraftJarTask extends AbstractLoomTask {
	private static final Logger LOGGER = LoggerFactory.getLogger(VerifyMinecraftJarTask.class);

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getInputJar();

	@Input
	public abstract Property<String> getMinecraftVersion();

	@Input
	public abstract Property<Side> getSide();

	@Input
	public abstract Property<Boolean> getVerificationEnabled();

	@Input
	public abstract Property<Boolean> getOffline();

	@Input
	public abstract Property<Boolean> getRefresh();

	@LocalState
	public abstract DirectoryProperty getCrlCacheDirectory();

	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public VerifyMinecraftJarTask() {
		getVerificationEnabled().convention(true);
		getOffline().convention(false);
		getRefresh().convention(false);
		getOutputs().upToDateWhen(task -> !((VerifyMinecraftJarTask) task).getRefresh().get());
	}

	@TaskAction
	public void run() {
		final WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(VerifyMinecraftJarAction.class, parameters -> {
			parameters.getInputJar().set(getInputJar());
			parameters.getMinecraftVersion().set(getMinecraftVersion());
			parameters.getSide().set(getSide());
			parameters.getVerificationEnabled().set(getVerificationEnabled());
			parameters.getOffline().set(getOffline());
			parameters.getRefresh().set(getRefresh());
			parameters.getCrlCacheDirectory().set(getCrlCacheDirectory());
			parameters.getOutputJar().set(getOutputJar());
		});
	}

	public enum Side {
		CLIENT,
		SERVER
	}

	public interface Parameters extends WorkParameters {
		RegularFileProperty getInputJar();
		Property<String> getMinecraftVersion();
		Property<Side> getSide();
		Property<Boolean> getVerificationEnabled();
		Property<Boolean> getOffline();
		Property<Boolean> getRefresh();
		DirectoryProperty getCrlCacheDirectory();
		RegularFileProperty getOutputJar();
	}

	public abstract static class VerifyMinecraftJarAction implements WorkAction<Parameters> {
		@Override
		public void execute() {
			final Path inputJar = getParameters().getInputJar().get().getAsFile().toPath();
			final Path outputJar = getParameters().getOutputJar().get().getAsFile().toPath();

			try {
				Files.createDirectories(outputJar.getParent());
				Files.deleteIfExists(outputJar);

				if (getParameters().getVerificationEnabled().get()) {
					verify(inputJar);
				}

				Files.copy(inputJar, outputJar, StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception e) {
				cleanOutput(outputJar, e);
				throw new RuntimeException("Failed to verify Minecraft jar: " + inputJar, e);
			}
		}

		private void verify(Path inputJar) throws IOException, SignatureVerificationFailure {
			final String minecraftVersion = getParameters().getMinecraftVersion().get();
			final CertificateRevocationList revocationList = downloadRevocationLists();

			try {
				switch (getParameters().getSide().get()) {
				case CLIENT -> MinecraftJarVerification.verifyClientJar(inputJar, minecraftVersion, revocationList);
				case SERVER -> MinecraftJarVerification.verifyServerJar(inputJar, minecraftVersion, revocationList);
				}
			} catch (SignatureVerificationFailure e) {
				try {
					Files.deleteIfExists(inputJar);
				} catch (IOException cleanupException) {
					e.addSuppressed(cleanupException);
				}

				throw e;
			}
		}

		private CertificateRevocationList downloadRevocationLists() throws IOException {
			final List<java.security.cert.X509CRL> crls = new ArrayList<>();
			boolean downloadFailure = false;

			for (String url : CertificateRevocationList.CSC3_2010) {
				final String fileName = url.substring(url.lastIndexOf('/') + 1);
				final Path path = getParameters().getCrlCacheDirectory().file(fileName).get().getAsFile().toPath();

				try {
					final DownloadBuilder download = Download.create(url)
							.allowInsecureProtocol()
							.maxAge(Duration.ofDays(7));

					if (getParameters().getOffline().get()) {
						download.offline();
					}

					if (getParameters().getRefresh().get()) {
						download.forceDownload();
					}

					download.downloadPath(path);
					crls.add(CertificateRevocationList.parse(path));
				} catch (DownloadException | URISyntaxException e) {
					LOGGER.info("Failed to download CRL from {}: {}", url, e.getMessage());
					LOGGER.info("Loom will not be able to fully verify the Minecraft jar certificate revocation status");
					downloadFailure = true;
				}
			}

			return new CertificateRevocationList(crls, downloadFailure);
		}

		private static void cleanOutput(Path output, Exception failure) {
			try {
				Files.deleteIfExists(output);
			} catch (IOException e) {
				failure.addSuppressed(e);
			}
		}
	}
}
