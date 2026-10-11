package dev.graft.legacy;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

@IFMLLoadingPlugin.Name("Graft")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.TransformerExclusions("dev.graft.legacy.")
public final class GraftCorePlugin implements IFMLLoadingPlugin {
	@Override
	public String[] getASMTransformerClass() {
		// -Dgraft.disabled=true runs vanilla OpenGL.
		boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
		boolean on = mac && !Boolean.getBoolean("graft.disabled");
		return on || Boolean.getBoolean("graft.bench") ? new String[] {"dev.graft.legacy.GlTransformer"} : new String[0];
	}

	@Override
	public String getModContainerClass() {
		return null;
	}

	@Override
	public String getSetupClass() {
		return null;
	}

	@Override
	public void injectData(Map<String, Object> data) {
		Object dir = data.get("mcLocation");
		if (dir instanceof java.io.File) {
			Scale.load(new java.io.File((java.io.File)dir, "config"));
		}
	}

	@Override
	public String getAccessTransformerClass() {
		return null;
	}
}
