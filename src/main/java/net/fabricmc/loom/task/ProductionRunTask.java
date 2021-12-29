package net.fabricmc.loom.task;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.JavaExec;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.production.ProductionRunConfigSettings;
import net.fabricmc.loom.util.Constants;

public abstract class ProductionRunTask extends JavaExec {
	private final ProductionRunConfigSettings settings;

	public abstract ConfigurableFileCollection getRunClasspath();

	public ProductionRunTask(ProductionRunConfigSettings settings) {
		this.settings = settings;

		setClasspath(getRunClasspath());
		getRunClasspath().from(settings.getRuntimeClasspath());
		getMainClass().set(settings.getMainClass());

		configureClasspath();
	}

	private void configureClasspath() {
		FileCollection envClasspath = switch (settings.getEnvironment().get()) {
			case "client" -> getClientClasspath();
			case "server" -> getServerClasspath();
			default -> throw new UnsupportedOperationException("Unsupported environment " + settings.getEnvironment().get());
		};

		getRunClasspath().from(getCommonClasspath().plus(envClasspath));
	}

	private FileCollection getClientClasspath() {
		ConfigurableFileCollection fileCollection = getProject().files();

		fileCollection.from(getExtension().getMinecraftProvider().getMinecraftClientJar());
		fileCollection.from(getProject().getConfigurations().named(Constants.Configurations.MINECRAFT_DEPENDENCIES));

		return fileCollection;
	}

	private FileCollection getServerClasspath() {
		ConfigurableFileCollection fileCollection = getProject().files();

		fileCollection.from(getExtension().getMinecraftProvider().getMinecraftServerJar());
		fileCollection.from(getProject().getConfigurations().named(Constants.Configurations.MINECRAFT_SERVER_DEPENDENCIES));

		return fileCollection;
	}

	private FileCollection getCommonClasspath() {
		ConfigurableFileCollection fileCollection = getProject().files();

		// TODO loader and intermediary
		Configuration loaderConfiguration = getProject().getConfigurations().detachedConfiguration(
				getProject().getDependencies().create(""),
				getProject().getDependencies().create("")
		);

		fileCollection.from(loaderConfiguration);
		fileCollection.from(getProject().getConfigurations().named(Constants.Configurations.LOADER_DEPENDENCIES));

		return fileCollection;
	}

	private LoomGradleExtension getExtension() {
		return LoomGradleExtension.get(getProject());
	}
}
