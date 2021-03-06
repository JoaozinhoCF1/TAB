package me.neznamy.tab.platforms.velocity;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.slf4j.Logger;

import com.google.inject.Inject;
import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.StateRegistry.PacketMapping;
import com.velocitypowered.proxy.protocol.StateRegistry.PacketRegistry;
import com.velocitypowered.proxy.protocol.packet.PlayerListItem;

import io.netty.channel.*;
import me.neznamy.tab.api.TABAPI;
import me.neznamy.tab.platforms.velocity.protocol.*;
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
import me.neznamy.tab.shared.packets.*;
import me.neznamy.tab.shared.packets.PacketPlayOutPlayerInfo.EnumPlayerInfoAction;
import me.neznamy.tab.shared.placeholders.*;
import net.kyori.text.Component;
import net.kyori.text.TextComponent;

@Plugin(id = "tab", name = "TAB", version = "2.6.5", description = "Change a player's tablist prefix/suffix, name tag prefix/suffix, header/footer, bossbar and more", authors = {"NEZNAMY"})
public class Main implements MainClass{

	public static ProxyServer server;
	public static Logger logger;
	private PluginMessenger plm;

	@Inject
	public Main(ProxyServer server, Logger logger) {
		Main.server = server;
		Main.logger = logger;
	}
	@Subscribe
	public void onProxyInitialization(ProxyInitializeEvent event) {
		try {
			Class.forName("org.yaml.snakeyaml.Yaml");
			long time = System.currentTimeMillis();
			me.neznamy.tab.shared.ProtocolVersion.SERVER_VERSION = me.neznamy.tab.shared.ProtocolVersion.BUNGEE;
			Shared.mainClass = this;
			Shared.separatorType = "server";
			TabCommand command = new TabCommand();
			server.getCommandManager().register("btab", new Command() {
				public void execute(CommandSource sender, String[] args) {
					command.execute(sender instanceof Player ? Shared.getPlayer(((Player)sender).getUniqueId()) : null, args);
				}
			});
			registerPackets();
			plm = new PluginMessenger(this);
			load(false, true);
			if (!Shared.disabled) Shared.print('a', "Enabled in " + (System.currentTimeMillis()-time) + "ms");
		} catch (ClassNotFoundException e) {
			sendConsoleMessage("&c[TAB] The plugin requires Velocity 1.1.0 and up to work ! Get it at https://ci.velocitypowered.com/job/velocity-1.1.0/");
		}
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
			for (Player p : server.getAllPlayers()) {
				ITabPlayer t = new TabPlayer(p, p.getCurrentServer().get().getServerInfo().getName());
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
		} catch (Throwable e) {
			Shared.disabled = true;
			if (e.getClass().getSimpleName().equals("ParserException") || e.getClass().getSimpleName().equals("ScannerException")) { //avoiding NoClassDefFoundError on Velocity <1.1.0
				Shared.print('c', "Did not enable due to a broken configuration file.");
			} else {
				Shared.print('c', "Failed to enable");
				sendConsoleMessage("&c" + e.getClass().getName() +": " + e.getMessage());
				for (StackTraceElement ste : e.getStackTrace()) {
					sendConsoleMessage("&c       at " + ste.toString());
				}
			}
		}
	}
	@Subscribe
	public void a(DisconnectEvent e){
		if (Shared.disabled) return;
		ITabPlayer disconnectedPlayer = Shared.getPlayer(e.getPlayer().getUniqueId());
		if (disconnectedPlayer == null) return; //player connected to bungeecord successfully, but not to the bukkit server anymore ? idk the check is needed
		NameTag16.playerQuit(disconnectedPlayer);
		ScoreboardManager.unregister(disconnectedPlayer);
		if (Configs.SECRET_remove_ghost_players) {
			Object packet = new PacketPlayOutPlayerInfo(EnumPlayerInfoAction.REMOVE_PLAYER, disconnectedPlayer.getInfoData()).toVelocity(null);
			for (ITabPlayer all : Shared.getPlayers()) {
				all.sendPacket(packet);
			}
		}
		Shared.data.remove(e.getPlayer().getUniqueId());
		//after removing data so reader considers the player offline and does not cancel removal
		GlobalPlayerlist.onQuit(disconnectedPlayer);
	}
	@Subscribe
	public void a(ServerConnectedEvent e){
		try{
			if (Shared.disabled) return;
			ITabPlayer p = Shared.getPlayer(e.getPlayer().getUniqueId());
			if (p == null) {
				p = new TabPlayer(e.getPlayer(), e.getServer().getServerInfo().getName());
				Shared.data.put(e.getPlayer().getUniqueId(), p);
				inject(p.getUniqueId());
				HeaderFooter.playerJoin(p);
				BossBar.playerJoin(p);
				GlobalPlayerlist.onJoin(p);
				ITabPlayer pl = p;
				//sending custom packets with a delay, it would not work otherwise
				Executors.newCachedThreadPool().submit(new Runnable() {

					@Override
					public void run() {
						NameTag16.playerJoin(pl);
						ScoreboardManager.register(pl);
						TabObjective.playerJoin(pl);
						BelowName.playerJoin(pl);
					}
				});
			} else {
				String from = p.getWorldName();
				String to = p.world = e.getServer().getServerInfo().getName();
				p.onWorldChange(from, to);
			}
		} catch (Throwable ex){
			Shared.errorManager.criticalError("An error occurred when player joined/changed server", ex);
		}
	}
	/*	@Subscribe
	public void a(PlayerChatEvent e) {
		ITabPlayer sender = Shared.getPlayer(e.getPlayer().getUniqueId());
		if (e.getMessage().equalsIgnoreCase("/btab")) {
			Shared.sendPluginInfo(sender);
			return;
		}
		if (BossBar.onChat(sender, e.getMessage())) e.setResult(ChatResult.denied());
		if (ScoreboardManager.onCommand(sender, e.getMessage())) e.setResult(ChatResult.denied());
	}*/
	private void inject(UUID uuid) {
		Channel channel = (Channel) Shared.getPlayer(uuid).getChannel();
		if (channel.pipeline().names().contains(Shared.DECODER_NAME)) channel.pipeline().remove(Shared.DECODER_NAME);
		channel.pipeline().addBefore("handler", Shared.DECODER_NAME, new ChannelDuplexHandler() {

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
						PacketPlayOutPlayerInfo p = PacketPlayOutPlayerInfo.fromVelocity(packet);
						Playerlist.modifyPacket(p, player);
						packet = p.toVelocity(null);
					}
					if (packet instanceof Team && NameTag16.enable) {
						if (killPacket((Team)packet)) return;
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
	//java class loader is `intelligent` and throws NoClassDefFoundError in inactive code (PacketPlayOutPlayerInfo#toVelocity)
	//making it return Object and then casting fixes it
	public static Object componentFromText(String text) {
		if (text == null) return null;
		return TextComponent.of(text);
	}
	public static String textFromComponent(Component component) {
		if (component == null) return null;
		return ((TextComponent) component).content();
	}
	public static void registerPlaceholders() {
		PluginHooks.luckPerms = server.getPluginManager().getPlugin("luckperms").isPresent();
		
		TABAPI.registerServerConstant(new ServerConstant("%maxplayers%") {
			public String get() {
				return server.getConfiguration().getShowMaxPlayers()+"";
			}
		});
		for (Entry<String, String> servers : server.getConfiguration().getServers().entrySet()) {
			TABAPI.registerServerPlaceholder(new ServerPlaceholder("%online_" + servers.getKey() + "%", 1000) {
				public String get() {
					return server.getServer(servers.getKey()).get().getPlayersConnected().size()+"";
				}
			});
		}
		Shared.registerUniversalPlaceholders();
	}
	private static Method map;
	
	public static PacketMapping map(final int id, final ProtocolVersion version, final boolean encodeOnly) throws Exception {
		return (PacketMapping) map.invoke(null, id, version, encodeOnly);
	}
	public void registerPackets() {
		try {
			Method register = null;
			for (Method m : PacketRegistry.class.getDeclaredMethods()) {
				if (m.getName().equals("register")) register = m;
			}
			register.setAccessible(true);
			map = StateRegistry.class.getDeclaredMethod("map", int.class, ProtocolVersion.class, boolean.class);
			map.setAccessible(true);

			Supplier<ScoreboardDisplay> display = ScoreboardDisplay::new;
			register.invoke(StateRegistry.PLAY.clientbound, ScoreboardDisplay.class, display, 
					new PacketMapping[] {
							map(0x3D, ProtocolVersion.MINECRAFT_1_8, false),
							map(0x38, ProtocolVersion.MINECRAFT_1_9, false),
							map(0x3A, ProtocolVersion.MINECRAFT_1_12, false),
							map(0x3B, ProtocolVersion.MINECRAFT_1_12_1, false),
							map(0x3E, ProtocolVersion.MINECRAFT_1_13, false),
							map(0x42, ProtocolVersion.MINECRAFT_1_14, false),
							map(0x43, ProtocolVersion.MINECRAFT_1_15, false)
			});
			Supplier<ScoreboardObjective> objective = ScoreboardObjective::new;
			register.invoke(StateRegistry.PLAY.clientbound, ScoreboardObjective.class, objective, 
					new PacketMapping[] {
							map(0x3B, ProtocolVersion.MINECRAFT_1_8, false),
							map(0x3F, ProtocolVersion.MINECRAFT_1_9, false),
							map(0x41, ProtocolVersion.MINECRAFT_1_12, false),
							map(0x42, ProtocolVersion.MINECRAFT_1_12_1, false),
							map(0x45, ProtocolVersion.MINECRAFT_1_13, false),
							map(0x49, ProtocolVersion.MINECRAFT_1_14, false),
							map(0x4A, ProtocolVersion.MINECRAFT_1_15, false)
			});
			Supplier<ScoreboardScore> score = ScoreboardScore::new;
			register.invoke(StateRegistry.PLAY.clientbound, ScoreboardScore.class, score, 
					new PacketMapping[] {
							map(0x3C, ProtocolVersion.MINECRAFT_1_8, false),
							map(0x42, ProtocolVersion.MINECRAFT_1_9, false),
							map(0x44, ProtocolVersion.MINECRAFT_1_12, false),
							map(0x45, ProtocolVersion.MINECRAFT_1_12_1, false),
							map(0x48, ProtocolVersion.MINECRAFT_1_13, false),
							map(0x4C, ProtocolVersion.MINECRAFT_1_14, false),
							map(0x4D, ProtocolVersion.MINECRAFT_1_15, false)
			});
			Supplier<Team> team = Team::new;
			register.invoke(StateRegistry.PLAY.clientbound, Team.class, team, 
					new PacketMapping[] {
							map(0x3E, ProtocolVersion.MINECRAFT_1_8, false),
							map(0x41, ProtocolVersion.MINECRAFT_1_9, false),
							map(0x43, ProtocolVersion.MINECRAFT_1_12, false),
							map(0x44, ProtocolVersion.MINECRAFT_1_12_1, false),
							map(0x47, ProtocolVersion.MINECRAFT_1_13, false),
							map(0x4B, ProtocolVersion.MINECRAFT_1_14, false),
							map(0x4C, ProtocolVersion.MINECRAFT_1_15, false)
			});
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/*
	 *  Implementing MainClass
	 */

	public void sendConsoleMessage(String message) {
		server.getConsoleCommandSource().sendMessage(TextComponent.of(Placeholders.color(message)));
	}
	public String getPermissionPlugin() {
		if (PluginHooks.luckPerms) return "luckperms";
		return "Unknown/None";
	}
	public Object buildPacket(UniversalPacketPlayOut packet, me.neznamy.tab.shared.ProtocolVersion protocolVersion) {
		return packet.toVelocity(protocolVersion);
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