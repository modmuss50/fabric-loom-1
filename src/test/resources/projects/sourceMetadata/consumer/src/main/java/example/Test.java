package example;

import net.minecraft.resources.Identifier;

public class Test {
	public static String test() {
		Identifier identifier = Identifier.fromNamespaceAndPath("loom", "test");
		identifier.injectedMethod();
		return identifier.path;
	}
}
