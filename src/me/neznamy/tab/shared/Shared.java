package me.neznamy.tab.shared;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import me.neznamy.tab.api.TABAPI;
import me.neznamy.tab.platforms.bukkit.*;
import me.neznamy.tab.platforms.bukkit.unlimitedtags.NameTagX;
import me.neznamy.tab.premium.ScoreboardManager;
import me.neznamy.tab.shared.features.*;
import me.neznamy.tab.shared.packets.*;
import me.neznamy.tab.shared.packets.PacketPlayOutChat.ChatMessageType;
import me.neznamy.tab.shared.placeholders.*;

public class Shared {

	public static final String DECODER_NAME = "TABReader";
	public static final String CHANNEL_NAME = "tab:placeholders";
	public static final String pluginVersion = "2.7.0-pre13";
	public static final int currentVersionId = 265;
	public static final DecimalFormat decimal2 = new DecimalFormat("#.##");
	public static final char COLOR = '\u00a7';

	public static ConcurrentHashMap<UUID, ITabPlayer> data = new ConcurrentHashMap<UUID, ITabPlayer>();

	public static boolean disabled;
	public static MainClass mainClass;
	public static String separatorType;
	public static CPUManager cpu;
	public static ErrorManager errorManager;

	public static Collection<ITabPlayer> getPlayers(){
		return data.values();
	}
	public static ITabPlayer getPlayer(String name) {
		for (ITabPlayer p : data.values()) {
			if (p.getName().equals(name)) return p;
		}
		return null;
	}
	public static ITabPlayer getPlayer(int entityId) {
		for (ITabPlayer p : data.values()) {
			if (((TabPlayer)p).player.getEntityId() == entityId) return p;
		}
		return null;
	}
	public static ITabPlayer getPlayer(UUID uniqueId) {
		return data.get(uniqueId);
	}
	public static ITabPlayer getPlayerByTablistUUID(UUID tablistId) {
		for (ITabPlayer p : data.values()) {
			if (p.getTablistId().toString().equals(tablistId.toString())) {
				return p;
			}
		}
		return null;
	}
	
	public static void print(char color, String message) {
		mainClass.sendConsoleMessage("&" + color + "[TAB] " + message);
	}
	public static void debug(String message) {
		if (Configs.SECRET_debugMode) mainClass.sendConsoleMessage("&7[TAB DEBUG] " + message);
	}
	public static void sendPluginInfo(ITabPlayer to) {
		IChatBaseComponent message = new IChatBaseComponent("TAB v" + pluginVersion).setColor(EnumChatFormat.DARK_AQUA).onHoverShowText(COLOR + "aClick to visit plugin's spigot page").onClickOpenUrl("https://www.spigotmc.org/resources/57806/");
		message.addExtra(new IChatBaseComponent(" by _NEZNAMY_ (discord: NEZNAMY#4659)").setColor(EnumChatFormat.BLACK));
		to.sendCustomPacket(new PacketPlayOutChat(message.toString(), ChatMessageType.CHAT));
	}
	public static void unload() {
		try {
			if (disabled) return;
			long time = System.currentTimeMillis();
			cpu.cancelAllTasks();
			Configs.animations = new ArrayList<Animation>();
			HeaderFooter.unload();
			TabObjective.unload();
			BelowName.unload();
			Playerlist.unload();
			NameTag16.unload();
			BossBar.unload();
			ScoreboardManager.unload();
			if (separatorType.equals("world")) {
				NameTagX.unload();
				PerWorldPlayerlist.unload();
				if (PluginHooks.placeholderAPI) PlaceholderAPIExpansion.unregister();
			}
			data.clear();
			mainClass.sendConsoleMessage("&a[TAB] Disabled in " + (System.currentTimeMillis()-time) + "ms");
		} catch (Throwable e) {
			errorManager.criticalError("Failed to unload the plugin", e);
		}
	}
	public static void checkForUpdates() {
		new Thread(new Runnable() {

			@Override
			public void run() {
				try {
					HttpURLConnection con = (HttpURLConnection) new URL("http://207.180.242.97/spigot/tab/latest.version").openConnection();
					BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream()));
					String versionId = br.readLine();
					String versionString = br.readLine();
					br.close();
					int latestVersion = Integer.parseInt(versionId);
					if (latestVersion > currentVersionId) {
						mainClass.sendConsoleMessage("&a[TAB] Version " + versionString + " is out! Your version: " + pluginVersion);
						mainClass.sendConsoleMessage("&a[TAB] Get the update at https://www.spigotmc.org/resources/57806/");
					}
				} catch (Exception e) {
//					mainClass.sendConsoleMessage("&a[TAB] Failed to check for updates (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
				}
			}
		}).run();
	}
	public static void registerUniversalPlaceholders() {
		for (Animation a : Configs.animations) {
			TABAPI.registerServerPlaceholder(new ServerPlaceholder("%animation:" + a.getName() + "%", a.getInterval()-1) {
				public String get() {
					return a.getMessage();
				}
				@Override
				public String[] getChilds(){
					return a.getAllMessages();
				}
			});
			TABAPI.registerServerPlaceholder(new ServerPlaceholder("{animation:" + a.getName() + "}", a.getInterval()-1) {
				public String get() {
					return a.getMessage();
				}
				@Override
				public String[] getChilds(){
					return a.getAllMessages();
				}
			});
		}
		TABAPI.registerPlayerPlaceholder(new PlayerPlaceholder("%rank%", 1000) {
			public String get(ITabPlayer p) {
				return p.getRank();
			}
			@Override
			public String[] getChilds(){
				return Configs.rankAliases.values().toArray(new String[0]);
			}
		});
		TABAPI.registerServerPlaceholder(new ServerPlaceholder("%staffonline%", 2000) {
			public String get() {
				int var = 0;
				for (ITabPlayer all : getPlayers()){
					if (all.isStaff()) var++;
				}
				return var+"";
			}
		});
		TABAPI.registerServerPlaceholder(new ServerPlaceholder("%nonstaffonline%", 2000) {
			public String get() {
				int var = getPlayers().size();
				for (ITabPlayer all : getPlayers()){
					if (all.isStaff()) var--;
				}
				return var+"";
			}
		});
		TABAPI.registerPlayerPlaceholder(new PlayerPlaceholder("%"+separatorType+"%", 1000) {
			public String get(ITabPlayer p) {
				if (Configs.serverAliases != null && Configs.serverAliases.containsKey(p.getWorldName())) return Configs.serverAliases.get(p.getWorldName())+""; //bungee only
				return p.getWorldName();
			}
		});
		TABAPI.registerPlayerPlaceholder(new PlayerPlaceholder("%"+separatorType+"online%", 1000) {
			public String get(ITabPlayer p) {
				int var = 0;
				for (ITabPlayer all : getPlayers()){
					if (p.getWorldName().equals(all.getWorldName())) var++;
				}
				return var+"";
			}
		});
		TABAPI.registerServerPlaceholder(new ServerPlaceholder("%memory-used%", 200) {
			public String get() {
				return ((int) ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576) + "");
			}
		});
		TABAPI.registerServerConstant(new ServerConstant("%memory-max%") {
			public String get() {
				return ((int) (Runtime.getRuntime().maxMemory() / 1048576))+"";
			}
		});
		TABAPI.registerServerPlaceholder(new ServerPlaceholder("%memory-used-gb%", 200) {
			public String get() {
				return (decimal2.format((float)(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) /1024/1024/1024) + "");
			}
		});
		TABAPI.registerServerConstant(new ServerConstant("%memory-max-gb%") {
			public String get() {
				return (decimal2.format((float)Runtime.getRuntime().maxMemory() /1024/1024/1024))+"";
			}
		});
		TABAPI.registerPlayerPlaceholder(new PlayerPlaceholder("%nick%", 999999999) {
			public String get(ITabPlayer p) {
				return p.getName();
			}
		});
		TABAPI.registerServerPlaceholder(new ServerPlaceholder("%time%", 900) {
			public String get() {
				return Configs.timeFormat.format(new Date(System.currentTimeMillis() + (int)Configs.timeOffset*3600000));
			}
		});
		TABAPI.registerServerPlaceholder(new ServerPlaceholder("%date%", 60000) {
			public String get() {
				return Configs.dateFormat.format(new Date(System.currentTimeMillis() + (int)Configs.timeOffset*3600000));
			}
		});
		TABAPI.registerServerPlaceholder(new ServerPlaceholder("%online%", 1000) {
			public String get() {
				return getPlayers().size()+"";
			}
		});
		TABAPI.registerPlayerPlaceholder(new PlayerPlaceholder("%ping%", 2000) {
			public String get(ITabPlayer p) {
				return p.getPing()+"";
			}
		});
		TABAPI.registerPlayerPlaceholder(new PlayerPlaceholder("%player-version%", 999999999) {
			public String get(ITabPlayer p) {
				return p.getVersion().getFriendlyName();
			}
		});
		for (int i=5; i<=15; i++) {
			final int version = i;
			TABAPI.registerServerPlaceholder(new ServerPlaceholder("%version-group:1-" + version + "-x%", 1000) {
				public String get() {
					int count = 0;
					for (ITabPlayer p : getPlayers()) {
						if (p.getVersion().getMinorVersion() == version) count++;
					}
					return count+"";
				}
			});
		}
		if (PluginHooks.luckPerms) {
			TABAPI.registerPlayerPlaceholder(new PlayerPlaceholder("%luckperms-prefix%", 49) {
				public String get(ITabPlayer p) {
					return PluginHooks.LuckPerms_getPrefix(p);
				}
			});
			TABAPI.registerPlayerPlaceholder(new PlayerPlaceholder("%luckperms-suffix%", 49) {
				public String get(ITabPlayer p) {
					return PluginHooks.LuckPerms_getSuffix(p);
				}
			});
		}
		for (String placeholder : Placeholders.usedPlaceholders) {
			if (!Placeholders.usedServerPlaceholders.containsKey(placeholder) && 
				!Placeholders.usedPlayerPlaceholders.containsKey(placeholder) && 
				!Placeholders.usedServerConstants.containsKey(placeholder)) {
				Configs.assignPlaceholder(placeholder);
			}
		}
	}
}