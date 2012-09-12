package me.orthopteroid.playertrust;

import java.io.File;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.plugin.java.JavaPlugin;
import ru.tehkode.permissions.bukkit.PermissionsEx;
import ru.tehkode.permissions.PermissionUser;

public class PlayerTrust extends JavaPlugin implements Listener
{
	public interface TrustAPI
	{
		void tick();
		boolean testBinding();
		void initialize(PlayerTrust p);
		boolean isPlayerTrusted(String s);
		String getTrustedPlayers();
		//
		void trustPlayer(String s);
		void restrictPlayer(String s);
		void unrestrictPlayer(String s);
	};
	TrustAPI trustImpl;
	
	public class TrustViaPEX implements TrustAPI
	{
		PlayerTrust plugin;
		boolean dirty = false;
		
		@Override
		public void tick()
		{ if( dirty ) { PermissionsEx.getPlugin().saveConfig(); dirty = false; } }
		
		@Override
		public boolean testBinding() throws NoClassDefFoundError 
		{ return PermissionsEx.isAvailable(); }
		
		@Override
		public void initialize(PlayerTrust p)
		{ plugin = p; }
		
		@Override
		public boolean isPlayerTrusted(String s)
		{
			return PermissionsEx.getUser( s.toLowerCase() ).inGroup( plugin.getConfig().getString("PEXTrustedGroup") );
		}

		@Override
		public String getTrustedPlayers()
		{
			String pl = "";
			for( PermissionUser user : PermissionsEx.getPermissionManager().getGroup( plugin.getConfig().getString("PEXTrustedGroup") ).getUsers() )
			{ pl = user.getName() + " " + pl; }
			return pl;
		}
		
		@Override
		public void trustPlayer(String s)
		{
			PermissionsEx.getUser( s.toLowerCase() ).removeGroup( plugin.getConfig().getString("PEXRestrictedGroup") );
			PermissionsEx.getUser( s.toLowerCase() ).addGroup( plugin.getConfig().getString("PEXTrustedGroup") );
			dirty = true;
		}
		
		@Override
		public void restrictPlayer(String s)
		{
			PermissionsEx.getUser( s.toLowerCase() ).removeGroup( plugin.getConfig().getString("PEXTrustedGroup") );
			PermissionsEx.getUser( s.toLowerCase() ).addGroup( plugin.getConfig().getString("PEXRestrictedGroup") );
			dirty = true;
		}
		
		@Override
		public void unrestrictPlayer(String s)
		{
			PermissionsEx.getUser( s.toLowerCase() ).removeGroup( plugin.getConfig().getString("PEXTrustedGroup") );
			PermissionsEx.getUser( s.toLowerCase() ).removeGroup( plugin.getConfig().getString("PEXRestrictedGroup") );
			dirty = true;
		}
	};
	
	public class TrustViaConf implements TrustAPI
	{
		PlayerTrust plugin;
		List<String> trustedPlayers;
		boolean dirty = false;
		
		@Override
		public void tick()
		{
			if( dirty )
			{
				plugin.getConfig().set("YMLTrustedPlayers", trustedPlayers);
				plugin.saveConfig();
				dirty = false;
			}
		}
		
		@Override
		public boolean testBinding()
		{ return true; }
		
		@Override
		public void initialize(PlayerTrust p)
		{
			plugin = p;
			trustedPlayers = plugin.getConfig().getStringList("YMLTrustedPlayers");
		}
		
		@Override
		public boolean isPlayerTrusted(String s)
		{
			return trustedPlayers.contains( s.toLowerCase() );
		}

		@Override
		public String getTrustedPlayers()
		{
			String pl = "";
			for( String p : trustedPlayers )
			{ pl = p + " " + pl; }
			return pl;
		}
		
		@Override
		public void trustPlayer(String s)
		{ trustedPlayers.add( s.toLowerCase() ); dirty = true; }
		
		@Override
		public void restrictPlayer(String s)
		{ trustedPlayers.remove( s.toLowerCase() ); dirty = true; }
		
		@Override
		public void unrestrictPlayer(String s)
		{ trustedPlayers.remove( s.toLowerCase() ); dirty = true; }
	};
	
	/////////////////////////////////////////
	
	public synchronized boolean isPlayerTrusted(Player p)
	{ return p.isOp() | trustImpl.isPlayerTrusted( p.getName() ); }
	
	public synchronized String getTrustedPlayers()
	{ return trustImpl.getTrustedPlayers(); }

	public synchronized void trustPlayer(String n)
	{ trustImpl.trustPlayer( n ); }

	public synchronized void restrictPlayer(String n)
	{ trustImpl.restrictPlayer( n ); }

	public synchronized void unrestrictPlayer(String n)
	{ trustImpl.unrestrictPlayer( n ); }
	
	public synchronized void unrestrictPlayers()
	{
		// don't change the status of trusted players as the impl might do something odd...
		for (Player p : this.getServer().getOnlinePlayers() )
		{ if( isPlayerTrusted( p ) == false ) { unrestrictPlayer( p.getName() ); } }
	}
	
	public synchronized void restrictPlayers()
	{
		// don't restrict trusted players by accident
		for (Player p : this.getServer().getOnlinePlayers() )
		{ if( isPlayerTrusted( p ) == false ) { restrictPlayer( p.getName() ); } }
	}

	//////////////////////////////
	
	int restrictionState = -1; /* <-1 is counting up to unlocked, -1 is unlocked, 0 is invalid, 1 is locked, >1 is counting down to lock */
	int changeRestrictionState = 0; /* 0 is no set, others are set values */

	private boolean isServerToBeRestricted() { return restrictionState > 1; }
	private boolean isServerToBeUnrestricted() { return restrictionState < -1; }
	private boolean isServerRestricted() { return restrictionState == 1; }
	private boolean isServerUnrestricted() { return restrictionState == -1; }
	private boolean isServerInCountdown() { return (restrictionState * restrictionState) != 1; }

	public synchronized void tick() // serialized!
	{
		// check for trusted players
		boolean areTrustedPlayersOnline = false;
		for( Player p : this.getServer().getOnlinePlayers() )
		{
			if ( isPlayerTrusted( p ) )
			{ areTrustedPlayersOnline = true; break; }
		}

		// change state?
		if( areTrustedPlayersOnline )
		{
			if( isServerRestricted() | isServerToBeRestricted() )
			{
				changeRestrictionState = -(this.getConfig().getInt("SecondsToUnlock") / secondsPerTick) - 1;
				if( changeRestrictionState > -2 ) { changeRestrictionState = -2; }
			}
		}
		else
		{
			if( isServerUnrestricted() )
			{
				changeRestrictionState = (this.getConfig().getInt("SecondsToLock") / secondsPerTick) + 1;
				if( changeRestrictionState < 2 ) { changeRestrictionState = 2; }
			}
		}

		// notify players of change
		if( changeRestrictionState > 0 )
		{ 
			this.getServer().broadcastMessage( this.getConfig().getString("RestrictionsGoingOn") );
		}
		else
		if( changeRestrictionState < 0 )
		{
			this.getServer().broadcastMessage( this.getConfig().getString("RestrictionsGoingOff") );
		}

		// update lock state
		if( changeRestrictionState != 0 )
		{ restrictionState = changeRestrictionState; changeRestrictionState = 0; }

		// tick down to lock or tick up to unlock
		if( restrictionState > 1 )
		{
			restrictionState--;
			if( restrictionState == 1 )
			{
				this.getServer().broadcastMessage( this.getConfig().getString("RestrictionsOn") );
				restrictPlayers();
			}
		}
		else
		if( restrictionState < -1 )
		{
			restrictionState++;
			if( restrictionState == -1 )
			{
				this.getServer().broadcastMessage( this.getConfig().getString("RestrictionsOff") );
				unrestrictPlayers();
			}
		}
		this.trustImpl.tick(); // save when dirty
	}
	
	////////////////////////

	boolean restrictBedEnter;
	boolean restrictCommandPreprocess;
	boolean restrictDropItem;
	boolean restrictFishing;
	boolean restrictInteract;
	boolean restrictInteractEntity;
	boolean restrictMove;
	boolean restrictPickupItem;
		
	@EventHandler
	public void onPlayerBedEnter(PlayerBedEnterEvent event)
	{ event.setCancelled( restrictBedEnter & isServerRestricted() ); }

	@EventHandler
	public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event)
	{ event.setCancelled( restrictCommandPreprocess & isServerRestricted() ); }

	@EventHandler
	public void onPlayerDropItem(PlayerDropItemEvent event)
	{ event.setCancelled( restrictDropItem & isServerRestricted() ); }

	@EventHandler
	public void onPlayerFish(PlayerFishEvent event)
	{ event.setCancelled( restrictFishing & isServerRestricted() ); }

	@EventHandler
	public void onPlayerInteract(PlayerInteractEvent event)
	{ event.setCancelled( restrictInteract & isServerRestricted() ); }

	@EventHandler
	public void onPlayerInteractEntity(PlayerInteractEntityEvent event)
	{ event.setCancelled( restrictInteractEntity & isServerRestricted() ); }

	@EventHandler
	public void onPlayerMove(PlayerMoveEvent event)
	{ event.setCancelled( restrictMove & isServerRestricted() ); }

	@EventHandler
	public void onPlayerPickupItem(PlayerPickupItemEvent event)
	{ event.setCancelled( restrictPickupItem & isServerRestricted() ); }
	
	/////////////////////////////////////////
	
	final int secondsPerTick = 2;
	final String conf_yml = "" +
		"Info: This server uses PlayerTrust. When all trusted players logout the server will restrict untrusted players.\n" +
		"RestrictionsOn: No trusted members remain on the server. Player actions locked.\n" +
		"RestrictionsOff: Trusted members are present on the server. Player actions are unlocked.\n" +
		"RestrictionsGoingOn: No trusted members remain on the server. Player actions are locking....\n" +
		"RestrictionsGoingOff: Trusted members are present on the server. Player actions are unlocking...\n" +
		"SecondsToLock: 30\n" +
		"SecondsToUnlock: 3\n" +
		"RestrictBedEnter: true\n" +
		"RestrictCommandPreprocess: false\n" +
		"RestrictDropItem: true\n" +
		"RestrictFishing: true\n" +
		"RestrictInteract: true\n" +
		"RestrictInteractEntity: true\n" +
		"RestrictMove: false\n" +
		"RestrictPickupItem: true\n" +
		"PEXTrustedGroup: Trusted\n" +
		"PEXRestrictedGroup: Restricted\n" +
		"YMLTrustedPlayers: []\n" +
		"";
	
	@Override
	public void onEnable()
	{
		// OMG the config stuff is all wierdly borked!
		String configFilename = this.getDataFolder().toString() + File.separatorChar + "config.yml";
		if( (new File( configFilename ).isFile()) == false )
		{
			try
			{ this.getConfig().loadFromString( conf_yml ); }
			catch( InvalidConfigurationException ex )
			{ }
			this.saveConfig();
		}
		this.reloadConfig();
		//
		try
		{
			trustImpl = null;
			trustImpl = new TrustViaPEX();
			if( trustImpl.testBinding() )
			{ this.getLogger().info("Using PermissionsEx backend"); }
		}
		catch( NoClassDefFoundError ex1 )
		{
			trustImpl = null;
			trustImpl = new TrustViaConf();
			if( trustImpl.testBinding() )
			{ this.getLogger().info("Using config.yml backend"); }
		}
		trustImpl.initialize( this );
		//
		restrictBedEnter = this.getConfig().getBoolean( "RestrictBedEnter" );
		restrictCommandPreprocess = this.getConfig().getBoolean( "RestrictCommandPreprocess" );
		restrictDropItem = this.getConfig().getBoolean( "RestrictDropItem" );
		restrictFishing = this.getConfig().getBoolean( "RestrictFishing" );
		restrictInteract = this.getConfig().getBoolean( "RestrictInteract" );
		restrictInteractEntity = this.getConfig().getBoolean( "RestrictInteractEntity" );
		restrictMove = this.getConfig().getBoolean( "RestrictMove" );
		restrictPickupItem = this.getConfig().getBoolean( "RestrictPickupItem" );
		//
		final PlayerTrust watcher = this;
		this.getServer().getScheduler().scheduleAsyncRepeatingTask
		(
			watcher,
			new Runnable() { @Override public void run() { watcher.tick(); } },
			20L, secondsPerTick * 20L
		);
		//
		this.getServer().getPluginManager().registerEvents( this, this );
	}
	
	@Override	
	public void onDisable()
	{
		this.getServer().getScheduler().cancelTasks( this );
		this.saveConfig();
	}
	
	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args)
	{
		if( (sender instanceof Player) && (sender.isOp() == false) ) { return true; }

		boolean showHelp = false;
		if( args.length == 0 )
		{ showHelp = true; }
		else if( args.length == 1 )
		{
			if( args[0].equalsIgnoreCase("list") )
			{ sender.sendMessage( ChatColor.YELLOW + "trusted players are: " + getTrustedPlayers() ); }
			else { showHelp = true; }
		}
		else if( args.length > 1 )
		{
			if( args[1].equalsIgnoreCase("*") )
			{
				if( args[0].equalsIgnoreCase("add") )
				{ for( Player p: this.getServer().getOnlinePlayers() ) { trustPlayer( p.getName() ); } }
				else if( args[0].equalsIgnoreCase("remove") )
				{
					// don't restrict trusted players by accident
					for( Player p: this.getServer().getOnlinePlayers() )
					{ if( p.isOp() == false ) { unrestrictPlayer( p.getName() ); } }
				}
				else { showHelp = true; }
			}
			else if( args[0].equalsIgnoreCase("add") )
			{ for( int j = 1; j < args.length; j++ ) { trustPlayer( args[j] ); } }
			else if( args[0].equalsIgnoreCase("remove") )
			{
				// i suppose a bug here might be that with PEX you can make an op restricted, if they are offline?
				for( int j = 1; j < args.length; j++ )
				{ unrestrictPlayer( args[j] ); }
			}
			else { showHelp = true; }
		}
		//
		if( showHelp )
		{
			sender.sendMessage(
				ChatColor.YELLOW +
				"*** " + this.getDescription().getName() + " " +
				"v" + this.getDescription().getVersion() + "\n" +
				this.getDescription().getCommands().get( label.toLowerCase() ).get( "usage" )
			);
		}
		//
		return true;
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event)
	{
		Player p = event.getPlayer();
		if( isPlayerTrusted( p ) )
		{ tick(); }
		else
		{
			p.sendMessage( this.getConfig().getString("Info") );
			if( isServerUnrestricted() )
			{ unrestrictPlayer( p.getName() ); } // just to be safe...
			else
			{
				restrictPlayer( p.getName() );
				p.sendMessage( this.getConfig().getString("RestrictionsOn") );
			}
		}
	}

}
