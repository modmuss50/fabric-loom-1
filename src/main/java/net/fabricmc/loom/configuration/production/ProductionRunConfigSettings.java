package net.fabricmc.loom.configuration.production;

import org.gradle.api.Named;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

public abstract class ProductionRunConfigSettings implements Named {
	public abstract ConfigurableFileCollection getMods();

	public abstract ConfigurableFileCollection getRuntimeClasspath();

	public abstract Property<String> getMainClass();

	public abstract Property<String> getEnvironment();

	public abstract Property<String> getRunDir();

	@Inject
	public ProductionRunConfigSettings() {
		getRunDir().convention("run");
	}

	public void client() {
		getEnvironment().set("client");
	}

	public void server() {
		getEnvironment().set("server");
	}
}
