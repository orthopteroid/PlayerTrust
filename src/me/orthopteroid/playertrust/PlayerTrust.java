package me.orthopteroid.playertrust;

import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
		void trustPlayer(String s);
		void restrictPlayer(String s);
		void unrestrictPlayer(String s);
	};

	public class TrustViaPEX implements TrustAPI
	{
		PlayerTrust plugin;
		FileConfiguration conf;
		boolean dirty = false;
		
		@Override
		public void tick()
		{ if( dirty ) { PermissionsEx.getPlugin().saveConfig(); dirty = false; } }
		
		@Override
		public boolean testBinding() throws NoClassDefFoundError 
		{ return PermissionsEx.isAvailable(); }
		
		@Override
		public void initialize(PlayerTrust p)
		{ plugin = p; conf = p.getConfig(); }
		
		@Override
		public boolean isPlayerTrusted(String s)
		{
			return PermissionsEx.getUser( s.toLowerCase() ).inGroup( conf.getString("pextrustedgroup") );
		}

		@Override
		public String getTrustedPlayers()
		{
			String pl = "";
			for( PermissionUser user : PermissionsEx.getPermissionManager().getGroup("pextrustedgroup").getUsers() )
			{ pl = user.getName() + " " + pl; }
			return pl;
		}
		
		@Override
		public void trustPlayer(String s)
		{
			PermissionsEx.getUser( s.toLowerCase() ).removeGroup( conf.getString("pexrestrictedgroup") );
			PermissionsEx.getUser( s.toLowerCase() ).addGroup( conf.getString("pextrustedgroup") );
			dirty = true;
		}
		
		@Override
		public void restrictPlayer(String s)
		{
			PermissionsEx.getUser( s.toLowerCase() ).removeGroup( conf.getString("pextrustedgroup") );
			PermissionsEx.getUser( s.toLowerCase() ).addGroup( conf.getString("pexrestrictedgroup") );
			dirty = true;
		}
		
		@Override
		public void unrestrictPlayer(String s)
		{
			PermissionsEx.getUser( s.toLowerCase() ).removeGroup( conf.getString("pextrustedgroup") );
			PermissionsEx.getUser( s.toLowerCase() ).removeGroup( conf.getString("pexrestrictedgroup") );
			dirty = true;
		}
	};
	
	public class TrustViaConf implements TrustAPI
	{
		PlayerTrust plugin;
		FileConfiguration conf;
		List<String> trustedPlayers;
		List<String> restrictedPlayers;
		boolean dirty = false;
		
		@Override
		public void tick()
		{
			if( dirty )
			{
				conf.set("trustedplayers", trustedPlayers);
				conf.set("restrictedplayers", restrictedPlayers);
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
			plugin = p; conf = p.getConfig();
			trustedPlayers = conf.getStringList("trustedplayers");
			restrictedPlayers = conf.getStringList("restrictedplayers");
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
		{
			restrictedPlayers.remove( s.toLowerCase() );
			trustedPlayers.add( s.toLowerCase() );
			dirty = true;
		}
		
		@Override
		public void restrictPlayer(String s)
		{
			trustedPlayers.remove( s.toLowerCase() );
			restrictedPlayers.add( s.toLowerCase() );
			dirty = true;
		}
		
		@Override
		public void unrestrictPlayer(String s)
		{
			restrictedPlayers.remove( s.toLowerCase() );
			trustedPlayers.remove( s.toLowerCase() );
			dirty = true;
		}
	};
	
	/////////////////////////////////////////
	
	TrustAPI trustImpl;
	
	void instanceTrustImpl()
	{
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
	}
	
	/////////////////////////////////////////

	int restrictionState = -1; /* <-1 is counting up to unlocked, -1 is unlocked, 0 is invalid, 1 is locked, >1 is counting down to lock */
	int changeRestrictionState = 0; /* 0 is no set, others are set values */

	private boolean isServerToBeRestricted() { return restrictionState > 1; }
	private boolean isServerToBeUnrestricted() { return restrictionState < -1; }
	private boolean isServerRestricted() { return restrictionState == 1 | isServerToBeUnrestricted(); }
	private boolean isServerUnrestricted() { return restrictionState == -1 | isServerToBeRestricted(); }

	public synchronized boolean isPlayerTrusted(Player p)
	{ return p.isOp() | trustImpl.isPlayerTrusted( p.getName() ); }
	
	public synchronized String getTrustedPlayers()
	{ return trustImpl.getTrustedPlayers(); }

	public synchronized void trustPlayer(Player p)
	{ if( p.isOp() == false ) { trustImpl.trustPlayer( p.getName() ); } }

	public synchronized void restrictPlayer(Player p)
	{ if( p.isOp() == false ) { trustImpl.restrictPlayer( p.getName() ); } }

	public synchronized void unrestrictPlayer(Player p)
	{ if( p.isOp() == false ) { trustImpl.unrestrictPlayer( p.getName() ); } }
	
	public synchronized void unrestrictPlayers()
	{
		for (Player p : this.getServer().getOnlinePlayers() )
		{ unrestrictPlayer( p ); }
	}
	
	public synchronized void restrictPlayers()
	{
		// don't restrict trusted players by accident
		for (Player p : this.getServer().getOnlinePlayers() )
		{ if (isPlayerTrusted(p)==false) { restrictPlayer( p ); } }
	}
	
	public synchronized void tick() // serialized!
	{
		// check for trusted players
		boolean areTrustedPlayersOnline = false;
		for( Player p : this.getServer().getOnlinePlayers() )
		{
			if ( isPlayerTrusted( p ) ) { areTrustedPlayersOnline = true; break; }
		}

		// change state?
		if( areTrustedPlayersOnline )
		{
			if( isServerRestricted() & isServerToBeUnrestricted()==false )
			{
				changeRestrictionState = -(this.getConfig().getInt("secondstounlock") / secondsPerTick) - 1;
				if( changeRestrictionState > -2 ) { changeRestrictionState = -2; }
			}
		}
		else
		{
			if( isServerUnrestricted() & isServerToBeRestricted()==false )
			{
				changeRestrictionState = (this.getConfig().getInt("secondstolock") / secondsPerTick) + 1;
				if( changeRestrictionState < 2 ) { changeRestrictionState = 2; }
			}
		}

		// notify players of change
		if( changeRestrictionState > 0 )
		{ this.getServer().broadcastMessage( this.getConfig().getString("restrictionsgoingon") ); }
		else
		if( changeRestrictionState < 0 )
		{ this.getServer().broadcastMessage( this.getConfig().getString("restrictionsgoingoff") ); }

		// update lock state
		if( changeRestrictionState != 0 )
		{ restrictionState = changeRestrictionState; changeRestrictionState = 0; }

		// tick down to lock or tick up to unlock
		if( restrictionState > 1 )
		{
			restrictionState--;
			if( restrictionState == 1 )
			{
				this.getServer().broadcastMessage( this.getConfig().getString("restrictionson") );
				restrictPlayers();
			}
		}
		else
		if( restrictionState < -1 )
		{
			restrictionState++;
			if( restrictionState == -1 )
			{
				this.getServer().broadcastMessage( this.getConfig().getString("restrictionsoff") );
				unrestrictPlayers();
			}
		}
		this.trustImpl.tick(); // save when dirty
	}
	
	/////////////////////////////////////////
	
	final int secondsPerTick = 2;
	
	@Override
	public void onEnable()
	{
		this.reloadConfig();
		//
		instanceTrustImpl();
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

		if( args.length == 0 )
		{
			sender.sendMessage(
				ChatColor.YELLOW +
				"*** " + this.getDescription().getName() + " " +
				"v" + this.getDescription().getVersion() + "\n" +
				this.getDescription().getCommands().get( label.toLowerCase() ).get( "usage" )
			);
			return true;
		}
		else if( args.length == 1 )
		{
			if( args[0].equalsIgnoreCase("list") )
			{ sender.sendMessage( ChatColor.YELLOW + "trusted players are: " + getTrustedPlayers() ); }
		}
		else if( args.length > 1 )
		{
			if( args[1].equalsIgnoreCase("*") )
			{
				if( args[0].equalsIgnoreCase("add") )
				{ for( Player p: this.getServer().getOnlinePlayers() ) { trustPlayer( p ); } }
				else if( args[0].equalsIgnoreCase("remove") )
				{ for( Player p: this.getServer().getOnlinePlayers() ) { unrestrictPlayer( p ); } }
			}
			else if( args[0].equalsIgnoreCase("add") )
			{ for( int j = 1; j < args.length; j++ ) { trustImpl.trustPlayer( args[j] ); } }
			else if( args[0].equalsIgnoreCase("remove") )
			{ for( int j = 1; j < args.length; j++ ) { trustImpl.unrestrictPlayer( args[j] ); } }
		}
		//
		return true;
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event)
	{
		Player p = event.getPlayer();
		if( isPlayerTrusted(p) | isServerUnrestricted() )
		{ unrestrictPlayer( p ); tick(); }
		else
		{
			restrictPlayer( p );
			p.sendMessage( this.getConfig().getString("restrictionson") );
		}
	}

	/////////////////////////////////////////
	
	@EventHandler
	public void onPlayerBedEnter(PlayerBedEnterEvent event)
	{ event.setCancelled( isServerRestricted() ); }

	@EventHandler
	public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event)
	{ event.setCancelled( isServerRestricted() ); }

	@EventHandler
	public void onPlayerDropItem(PlayerDropItemEvent event)
	{ event.setCancelled( isServerRestricted() ); }

	@EventHandler
	public void onPlayerFish(PlayerFishEvent event)
	{ event.setCancelled( isServerRestricted() ); }
	
	@EventHandler
	public void onPlayerInteract(PlayerInteractEvent event)
	{ event.setCancelled( isServerRestricted() ); }
	
	@EventHandler
	public void onPlayerInteractEntity(PlayerInteractEntityEvent event)
	{ event.setCancelled( isServerRestricted() ); }

	@EventHandler
	public void onPlayerMove(PlayerMoveEvent event)
	{ /*event.setCancelled( isServerLocked() );*/ }

	@EventHandler
	public void onPlayerPickupItem(PlayerPickupItemEvent event)
	{ event.setCancelled( isServerRestricted() ); }
}
