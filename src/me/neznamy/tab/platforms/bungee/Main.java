package me.neznamy.tab.platforms.bungee;

import java.util.HashMap;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.yaml.snakeyaml.parser.ParserException;
import org.yaml.snakeyaml.scanner.ScannerException;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import me.neznamy.tab.api.TABAPI;
import me.neznamy.tab.premium.ScoreboardManager;
import me.neznamy.tab.shared.*;
import me.neznamy.tab.shared.command.TabCommand;
import me.neznamy.tab.shared.features.BelowName;
import me.neznamy.tab.shared.features.BossBar;
import me.neznamy.tab.shared.features.GlobalPlayerlist;
import me.neznamy.tab.shared.features.HeaderFooter;
import me.neznamy.tab.shared.features.NameTag16;
import me.neznamy.tab.shared.features.Playerlist;
import me.neznamy.tab.shared.features.TabObjective;
import me.neznamy.tab.shared.features.TabObjective.TabObjectiveType;
import me.neznamy.tab.shared.packets.PacketPlayOutPlayerInfo;
import me.neznamy.tab.shared.packets.UniversalPacketPlayOut;
import me.neznamy.tab.shared.packets.PacketPlayOutPlayerInfo.EnumPlayerInfoAction;
import me.neznamy.tab.shared.placeholders.*;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.api.plugin.*;
import net.md_5.bungee.event.*;
import net.md_5.bungee.protocol.packet.*;

public class Main extends Plugin implements Listener, MainClass{

	private PluginMessenger plm;
	
	public void onEnable(){
		long time = System.currentTimeMillis();
		ProtocolVersion.SERVER_VERSION = ProtocolVersion.BUNGEE;
		Shared.mainClass = this;
		Shared.separatorType = "server";
		getProxy().getPluginManager().registerListener(this, this);
		if (getProxy().getPluginManager().getPlugin("PremiumVanish") != null) getProxy().getPluginManager().registerListener(this, new PremiumVanishListener());
		TabCommand command = new TabCommand();
		getProxy().getPluginManager().registerCommand(this, new Command("btab") {
			public void execute(CommandSender sender, String[] args) {
				command.execute(sender instanceof ProxiedPlayer ? Shared.getPlayer(((ProxiedPlayer)sender).getUniqueId()) : null, args);
			}
		});
		plm = new PluginMessenger(this);
		load(false, true);
		Metrics metrics = new Metrics(this);
		metrics.addCustomChart(new Metrics.SimplePie("permission_system", new Callable<String>() {
			public String call() {
				return getPermissionPlugin();
			}
		}));
		metrics.addCustomChart(new Metrics.SimplePie("global_playerlist_enabled", new Callable<String>() {
			public String call() {
				return GlobalPlayerlist.enabled ? "Yes" : "No";
			}
		}));
		if (!Shared.disabled) Shared.print('a', "Enabled in " + (System.currentTimeMillis()-time) + "ms");
	}
	public void onDisable() {
		if (!Shared.disabled) {
			for (ITabPlayer p : Shared.getPlayers()) ((Channel) p.getChannel()).pipeline().remove(Shared.DECODER_NAME);
			Shared.unload();
		}
	}
	public void load(boolean broadcastTime, boolean inject) {
		try {
			long time = System.currentTimeMillis();
			Shared.disabled = false;
			Shared.cpu = new CPUManager();
			Shared.errorManager = new ErrorManager();
			Configs.loadFiles();
			registerPlaceholders();
			Shared.data.clear();
			for (ProxiedPlayer p : getProxy().getPlayers()) {
				ITabPlayer t = new TabPlayer(p);
				Shared.data.put(p.getUniqueId(), t);
				if (inject) inject(t.getUniqueId());
			}
			BossBar.load();
			NameTag16.load();
			Playerlist.load();
			TabObjective.load();
			BelowName.load();
			HeaderFooter.load();
			ScoreboardManager.load();
			Shared.checkForUpdates();
			Shared.errorManager.printConsoleWarnCount();
			if (broadcastTime) Shared.print('a', "Enabled in " + (System.currentTimeMillis()-time) + "ms");
		} catch (ParserException | ScannerException e) {
			Shared.print('c', "Did not enable due to a broken configuration file.");
			Shared.disabled = true;
		} catch (Throwable e) {
			Shared.print('c', "Failed to enable");
			sendConsoleMessage("&c" + e.getClass().getName() +": " + e.getMessage());
			for (StackTraceElement ste : e.getStackTrace()) {
				sendConsoleMessage("&c       at " + ste.toString());
			}
			Shared.disabled = true;
		}
	}
	@EventHandler(priority = EventPriority.LOWEST)
	public void a(PlayerDisconnectEvent e){
		if (Shared.disabled) return;
		ITabPlayer disconnectedPlayer = Shared.getPlayer(e.getPlayer().getUniqueId());
		if (disconnectedPlayer == null) return; //player connected to bungeecord successfully, but not to the bukkit server anymore ? idk the check is needed
		NameTag16.playerQuit(disconnectedPlayer);
		ScoreboardManager.unregister(disconnectedPlayer);
		if (Configs.SECRET_remove_ghost_players) {
			Object packet = new PacketPlayOutPlayerInfo(EnumPlayerInfoAction.REMOVE_PLAYER, disconnectedPlayer.getInfoData()).toBungee(null);
			for (ITabPlayer all : Shared.getPlayers()) {
				all.sendPacket(packet);
			}
		}
		Shared.data.remove(e.getPlayer().getUniqueId());
		//after removing data so reader considers the player offline and does not cancel removal
		GlobalPlayerlist.onQuit(disconnectedPlayer);
	}
	@EventHandler
	public void a(ServerSwitchEvent e){
		try{
			if (Shared.disabled) return;
			ITabPlayer p = Shared.getPlayer(e.getPlayer().getUniqueId());
			if (p == null) {
				p = new TabPlayer(e.getPlayer());
				Shared.data.put(e.getPlayer().getUniqueId(), p);
				inject(p.getUniqueId());
				HeaderFooter.playerJoin(p);
				BossBar.playerJoin(p);
				TabObjective.playerJoin(p);
				ScoreboardManager.register(p);
				NameTag16.playerJoin(p);
				BelowName.playerJoin(p);
				GlobalPlayerlist.onJoin(p);
			} else {
				String from = p.getWorldName();
				String to = p.world = e.getPlayer().getServer().getInfo().getName();
				p.onWorldChange(from, to);
			}
		} catch (Throwable ex){
			Shared.errorManager.criticalError("An error occurred when player joined/changed server", ex);
		}
	}
	@EventHandler
	public void a(ChatEvent e) {
		ITabPlayer sender = Shared.getPlayer(((ProxiedPlayer)e.getSender()).getUniqueId());
		if (e.getMessage().equalsIgnoreCase("/btab")) {
			Shared.sendPluginInfo(sender);
			return;
		}
		if (BossBar.onChat(sender, e.getMessage())) e.setCancelled(true);
		if (ScoreboardManager.onCommand(sender, e.getMessage())) e.setCancelled(true);
	}
	private void inject(UUID uuid) {
		Channel channel = (Channel) Shared.getPlayer(uuid).getChannel();
		if (channel.pipeline().names().contains(Shared.DECODER_NAME)) channel.pipeline().remove(Shared.DECODER_NAME);
		channel.pipeline().addBefore("inbound-boss", Shared.DECODER_NAME, new ChannelDuplexHandler() {

			public void channelRead(ChannelHandlerContext context, Object packet) throws Exception {
				super.channelRead(context, packet);
			}
			public void write(ChannelHandlerContext context, Object packet, ChannelPromise channelPromise) throws Exception {
				try{
					ITabPlayer player = Shared.getPlayer(uuid);
					if (player == null) {
						//wtf
						super.write(context, packet, channelPromise);
						return;
					}
					if (packet instanceof PlayerListItem && Playerlist.enable && player.getVersion().getMinorVersion() >= 8) {
						PacketPlayOutPlayerInfo p = PacketPlayOutPlayerInfo.fromBungee(packet);
						Playerlist.modifyPacket(p, player);
						packet = p.toBungee(null);
					}
					if (packet instanceof Team && NameTag16.enable) {
						Team team = (Team) packet;
						if (killPacket(team)) {
							return;
						}
					}
					if (packet instanceof ByteBuf && NameTag16.enable) {
						ByteBuf buf = ((ByteBuf) packet).duplicate();
						byte packetId = buf.readByte();
						Team team = null;
						if (packetId == player.getVersion().getPacketPlayOutScoreboardTeamId()) {
							team = new Team();
							team.read(buf, null, player.getVersion().getNetworkId());
						}
						if (team != null) {
							if (killPacket(team)) {
								return;
							}
						}
					}
				} catch (Throwable e){
					Shared.errorManager.printError("An error occurred when analyzing packets", e);
				}
				super.write(context, packet, channelPromise);
			}
		});
	}
	public boolean killPacket(Team packet){
		if (packet.getFriendlyFire() != 69) {
			String[] players = packet.getPlayers();
			if (players == null) return false;
			for (ITabPlayer p : Shared.getPlayers()) {
				for (String player : players) {
					if (player.equals(p.getName()) && !p.disabledNametag) {
						return true;
					}
				}
			}
		}
		return false;
	}
	public static void registerPlaceholders() {
		PluginHooks.premiumVanish = ProxyServer.getInstance().getPluginManager().getPlugin("PremiumVanish") != null;
		PluginHooks.luckPerms = ProxyServer.getInstance().getPluginManager().getPlugin("LuckPerms") != null;
		if (PluginHooks.premiumVanish) {
			TABAPI.registerServerPlaceholder(new ServerPlaceholder("%canseeonline%", 1000) {
				public String get() {
					return PluginHooks.PremiumVanish_getVisiblePlayerCount()+"";
				}
			});
		}
		TABAPI.registerServerConstant(new ServerConstant("%maxplayers%") {
			public String get() {
				return ProxyServer.getInstance().getConfigurationAdapter().getListeners().iterator().next().getMaxPlayers()+"";
			}
		});
		for (Entry<String, ServerInfo> server : ProxyServer.getInstance().getServers().entrySet()) {
			TABAPI.registerServerPlaceholder(new ServerPlaceholder("%online_" + server.getKey() + "%", 1000) {
				public String get() {
					return server.getValue().getPlayers().size()+"";
				}
			});
			TABAPI.registerServerPlaceholder(new ServerPlaceholder("%canseeonline_" + server.getKey() + "%", 1000) {
				public String get() {
					int count = server.getValue().getPlayers().size();
					for (ProxiedPlayer p : server.getValue().getPlayers()) {
						if (PluginHooks._isVanished(Shared.getPlayer(p.getUniqueId()))) count--;
					}
					return count+"";
				}
			});
		}
		Shared.registerUniversalPlaceholders();
	}


	/*
	 *  Implementing MainClass
	 */

	@SuppressWarnings("deprecation")
	public void sendConsoleMessage(String message) {
		ProxyServer.getInstance().getConsole().sendMessage(Placeholders.color(message));
	}
	public String getPermissionPlugin() {
		if (PluginHooks.luckPerms) return "LuckPerms";
		if (ProxyServer.getInstance().getPluginManager().getPlugin("BungeePerms") != null) return "BungeePerms";
		return "Unknown/None";
	}
	public Object buildPacket(UniversalPacketPlayOut packet, ProtocolVersion protocolVersion) {
		return packet.toBungee(protocolVersion);
	}
	public void loadConfig() throws Exception {
		Configs.config = new ConfigurationFile("bungeeconfig.yml", "config.yml", null);
		TabObjective.rawValue = Configs.config.getString("tablist-objective-value", "%ping%");
		TabObjective.type = (TabObjective.rawValue.length() == 0) ? TabObjectiveType.NONE : TabObjectiveType.CUSTOM;
		BelowName.number = Configs.config.getString("belowname.number", "%ping%");
		BelowName.text = Configs.config.getString("belowname.text", "&aPing");
		NameTag16.enable = Configs.config.getBoolean("change-nametag-prefix-suffix", true);
		GlobalPlayerlist.enabled = Configs.config.getBoolean("global-playerlist", false);
		Configs.serverAliases = Configs.config.getConfigurationSection("server-aliases");
		if (Configs.serverAliases == null) Configs.serverAliases = new HashMap<String, Object>();
	}
	public void registerUnknownPlaceholder(String identifier) {
		if (identifier.contains("_")) {
			TABAPI.registerPlayerPlaceholder(new PlayerPlaceholder(identifier, 49){
				public String get(ITabPlayer p) {
					plm.requestPlaceholder(p, identifier);
					return lastValue.get(p.getName());
				}
			});
			return;
		}
	}
	public void convertConfig(ConfigurationFile config) {
		if (config.getName().equals("config.yml")) {
			if (config.get("belowname.refresh-interval") != null) {
				int value = (int) config.get("belowname.refresh-interval");
				convert(config, "belowname.refresh-interval", value, "belowname.refresh-interval-milliseconds", value);
			}
		}
	}
	private void convert(ConfigurationFile config, String oldKey, Object oldValue, String newKey, Object newValue) {
		if (oldKey == null) {
			config.set(newKey, newValue);
			Shared.print('2', "Added new " + config.getName() + " value " + newKey + " (" + newValue + ")");
			return;
		}
		config.set(oldKey, null);
		config.set(newKey, newValue);
		Shared.print('2', "Converted old " + config.getName() + " value " + oldKey + " (" + oldValue + ") to new " + newKey + " (" + newValue + ")");
	}
}