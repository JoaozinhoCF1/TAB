package me.neznamy.tab.shared.command.level1;

import me.neznamy.tab.platforms.bukkit.unlimitedtags.NameTagLineManager;
import me.neznamy.tab.shared.Configs;
import me.neznamy.tab.shared.ITabPlayer;
import me.neznamy.tab.shared.command.SubCommand;

public class NTPreviewCommand extends SubCommand{

	public NTPreviewCommand() {
		super("ntpreview", "tab.ntpreview");
	}

	@Override
	public void execute(ITabPlayer sender, String[] args) {
		if (Configs.unlimitedTags) {
			if (sender != null) {
				if (sender.previewingNametag) {
					NameTagLineManager.destroy(sender, sender);
					sendMessage(sender, Configs.preview_off);
				} else {
					NameTagLineManager.spawnArmorStand(sender, sender, false);
					sendMessage(sender, Configs.preview_on);
				}
				sender.previewingNametag = !sender.previewingNametag;
			}
		} else sendMessage(sender, Configs.unlimited_nametag_mode_not_enabled);
	}
	@Override
	public Object complete(ITabPlayer sender, String currentArgument) {
		return null;
	}
}