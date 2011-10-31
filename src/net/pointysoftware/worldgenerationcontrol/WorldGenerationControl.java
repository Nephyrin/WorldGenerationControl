/*
   See README.markdown for more information
   
   World Generation Control - Bukkit chunk preloader
   Copyright (C) 2011 john@pointysoftware.net

   This program is free software; you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation; either version 3 of the License, or
   (at your option) any later version.

   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with this program; if not, write to the Free Software Foundation,
   Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA
 */

package net.pointysoftware.worldgenerationcontrol;

import java.lang.reflect.Field;

import java.util.logging.Logger;
import java.util.Iterator;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.ArrayDeque;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.CreatureType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import org.bukkit.ChatColor;

import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.CommandSender;

import org.bukkit.scheduler.BukkitScheduler;

// Plugin *Does not* require craftbukkit, but lighting wont be available
// otherwise as Bukkit doesn't currently provide the right calls.
// (Our old method was a hack that relied on CraftBukkit quirks anyway)
import org.bukkit.craftbukkit.CraftChunk;
// This is used by checkCWTickList to work around a CW bug
import org.bukkit.craftbukkit.CraftWorld;

public class WorldGenerationControl extends JavaPlugin implements Runnable
{
    private final static String VERSION = "2.3";
    public enum GenerationSpeed
    {
        // Only pause two ticks between regions, unplayable
        // lag, but gets the job done quickest.
        ALLATONCE,
        // Do a lot of processing on 3s intervals. Very laggy
        // even on good systems.
        VERYFAST,
        // Less processing on 3s intervals, fairly laggy.
        FAST,
        // Even less processing. Mild lag.
        NORMAL,
        // Less - little lag
        SLOW,
        // tiny regions, very minimal lag, will
        // take *forever*.
        VERYSLOW
    }
    public enum GenerationLighting
    {
        // Force recalculate lighting on all chunks
        // we pass over
        EXTREME,
        // Update unprocessed lighting on chunks we
        // pass over
        NORMAL,
        // Don't force lighting. Generated areas
        // will have invalid lighting until a
        // player wanders near them. This is only
        // a problem if you want to use a external
        // map that cares about lighting, or if
        // you want to get the CPU time involved
        // in lighting a chunk out of the way.
        NONE
    }
    public class GenerationRegion
    {
        GenerationRegion(World world, GenerationSpeed speed, GenerationLighting lighting) { this._construct(world, speed, lighting, false, false, false); }
        GenerationRegion(World world, GenerationSpeed speed, GenerationLighting lighting, boolean debug) { this._construct(world, speed, lighting, debug, false, false); }
        GenerationRegion(World world, GenerationSpeed speed, GenerationLighting lighting, boolean debug, boolean forceRegeneration) { this._construct(world, speed, lighting, debug, forceRegeneration, false); }
        GenerationRegion(World world, GenerationSpeed speed, GenerationLighting lighting, boolean debug, boolean forceRegeneration, boolean onlywhenempty) { this._construct(world, speed, lighting, debug, forceRegeneration, onlywhenempty); }
        private void _construct(World world, GenerationSpeed speed, GenerationLighting lighting, boolean debug, boolean forceRegeneration, boolean onlywhenempty)
        {
            this.debug = debug;
            this.totalregions = 0;
            this.world = world;
            this.speed = speed;
            this.fixlighting = lighting;
            this.queuedregions = new ArrayDeque<QueuedRegion>();
            this.starttime = 0;
            this.forceregeneration = forceRegeneration;
            this.onlywhenempty = onlywhenempty;
            this.lastnag = 0;
            
            if (this.speed == GenerationSpeed.VERYFAST) regionsize = 32;
            else if (this.speed == GenerationSpeed.FAST) regionsize = 24;
            else if (this.speed == GenerationSpeed.NORMAL) regionsize = 12;
            else if (this.speed == GenerationSpeed.SLOW) regionsize = 8;
            else if (this.speed == GenerationSpeed.VERYSLOW) regionsize = 6;
            else regionsize = 20;
        }
        
        public boolean shouldRunAllAtOnce() { return this.speed == GenerationSpeed.ALLATONCE; }
        
        public void cancelRemaining()
        {
            this.queuedregions.clear();
        }
        
        private int fixCWTickListLeak()
        {
            // See if this is CraftBukkit and we can fix the NextTickList leak
            // otherwise it can mean lots of useless idle time while the server
            // catches up slowly, see: https://github.com/Bukkit/CraftBukkit/pull/501
            TreeSet set = null;
            if (this.world instanceof CraftWorld)
            {
                Class cw;
                try
                {
                    cw = ((CraftWorld)this.world).getHandle().getClass().forName("net.minecraft.server.World");
                }
                catch (ClassNotFoundException e) { return -1; }
                Field fields[] = cw.getDeclaredFields();
                for (int i = 0; i < fields.length; i++)
                {
                    // 1.9p5: TickList is private TreeSet K
                    // 1.8.1: TickList is private TreeSet N
                    // No other treesets in the world fields, so this is pretty safe.
                    if (fields[i].getName() == "K" || fields[i].getName() == "N")
                    {
                        fields[i].setAccessible(true);
                        Object f;
                        try { f = fields[i].get(((CraftWorld)this.world).getHandle()); }
                        catch (IllegalAccessException e) { continue; }
                        if (f instanceof TreeSet)
                        {
                            set = (TreeSet)f;
                            break;
                        }
                    }
                }
                if (set != null && set.size() > 500000)
                {
                    try
                    {
                        // Flush the list
                        if (debug) statusMsg("-- Detected runaway NextTickList entries, a known CraftBukkit bug. Fixing.");
                        while (((CraftWorld)this.world).getHandle().a(true));
                    }
                    catch(Exception e)
                    {
                        // Probably CB version mismatch.
                        if (debug) statusMsg("-- ... Fix failed, unknown CraftBukkit version. Expect very high memory usage.");
                    }
                }
            }
            return (set == null) ? -1 : set.size();
        }
        
        // returns true if complete
        // queued is number of generations the plugin intends to run after this
        // or -1 if the plugin intends to shutdown the server after this!
        public boolean runStep(int queued)
        {
            if (this.starttime == 0)
                this.starttime = System.nanoTime();
            
            // Check for the ticklist bug
            int ticklistbug = this.fixCWTickListLeak();
            
            // Status message
            String queuedtext = "";
            if (queued > 0)
                queuedtext = ChatColor.DARK_GRAY + " {" + ChatColor.GRAY + queued + " generations in queue" + ChatColor.DARK_GRAY + "}";
            if (queued == -1)
                queuedtext = ChatColor.DARK_GRAY + " {" + ChatColor.DARK_RED + "shutdown scheduled" + ChatColor.DARK_GRAY + "}";

            // Check memory
            String nag = null;
            double usedmem = ((double)(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / Runtime.getRuntime().maxMemory());
            if (usedmem > 0.85D)
            {
                if (this.speed == GenerationSpeed.ALLATONCE)
                {
                    // If we're going all at once, spend our time
                    // waiting on ram invoking GC
                    System.runFinalization();
                    System.gc();
                }
                nag = "Less than 15% free memory -- taking a break to let the server catch up";
            }
            
            // Check for /onlyWhenEmpty
            if (this.onlywhenempty && getServer().getOnlinePlayers().length > 0)
                nag = "Paused while players are present";
            
            // Status message
            double pct = 1 - (double)queuedregions.size() / totalregions;
            int region = totalregions - queuedregions.size() + 1;
            String prefix = ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + String.format("%.2f", 100*pct) + "%" + ChatColor.DARK_GRAY + "]" + ChatColor.GRAY + " ";
            
            if (nag != null)
            {
                // Should bail out
                long now = System.nanoTime();
                if (this.lastnag + 300000000000L < now)
                {
                    this.lastnag = System.nanoTime();
                    statusMsg(prefix + nag + queuedtext);
                }
                return false;
            }
            else
                this.lastnag = 0;
            
            statusMsg(prefix + ChatColor.GRAY + "Section " + ChatColor.WHITE + region + ChatColor.GRAY + "/" + ChatColor.WHITE + totalregions + queuedtext);
            
            // Get next region
            ArrayDeque<GenerationChunk> chunks = null;
            while (queuedregions.size() > 0 && chunks == null)
                chunks = queuedregions.pop().getChunks(this.world);
            
            long stime = debug ? System.nanoTime() : 0;
            if (chunks == null)
            {
                // Generation complete
                long millis = (System.nanoTime() - this.starttime) / 1000000;
                long seconds = millis / 1000;
                long minutes = seconds / 60;
                long hours = minutes / 60;
                long days = hours / 24;
                String took = (days > 0 ? String.format("%d days, ", days) : "")
                    + (hours > 0 ? String.format("%d hours, ", hours % 24) : "")
                    + (minutes > 0 ? String.format("%d minutes, ", minutes % 60) : "")
                    + String.format("%d seconds", seconds % 60);
                    
                statusMsg("Generation complete in " + took + ". " + (queued > 0 ? "Loading next generation job" : "Have a nice day!") + queuedtext);
                return true;
            }
            
            //
            // Load Chunks
            //
            if (this.forceregeneration)
            {
                
                // Force unload the area first, so all blocks only get populators
                // run on them from their newly generated counterparts. Because the
                // regions overlap, we dont need to worry about the edges.
                Iterator<GenerationChunk> i = chunks.iterator();
                while (i.hasNext())
                {
                    GenerationChunk gc = i.next();
                    gc.kickPlayers("The region you are in was regenerated. Please rejoin");
                    gc.unload(true);
                }
            }
            Iterator<GenerationChunk> iter = chunks.iterator();
            while (iter.hasNext())
            {
                GenerationChunk c = iter.next();
                c.load(this.forceregeneration);
            }
            
            //
            // Lighting
            //
            if (this.fixlighting != GenerationLighting.NONE)
            {
                iter = chunks.iterator();
                while (iter.hasNext())
                {
                    GenerationChunk c = iter.next();
                    try
                    {
                        c.fixLighting(fixlighting == GenerationLighting.EXTREME);
                    }
                    catch (Exception e)
                    {
                        // ClassCastException, MethodNotFound exception, or even an error inside craftbukkit.
                        // Either way, stop lighting for this generation.
                        if (e instanceof ClassCastException)
                            statusMsg("Error: WorldGenerationControl only supports lighting on CraftBukkit due to Bukkit API limitations. Disabling lighting for this generation.");
                        else
                            statusMsg("Error: Error in CraftBukkit while generating lighting (probably an unsupported minecraft version). Disabling lighting for this generation.");
                        this.fixlighting = GenerationLighting.NONE;
                    }
                }
            }

            //
            // Cleanup Chunks
            //
            while (chunks.size() > 0)
            {
                chunks.pop().unload();
            }
            
            if (debug)
            {
                String pctmem = String.format("%.2f", 100 * ((double)(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / Runtime.getRuntime().maxMemory())) + "%";
                String elapsed = String.format("%.2f", (double)(System.nanoTime() - stime) / 1000000) + "ms";
                String tickstr = ticklistbug == -1 ? "No CB ticklist found" : ("NextTickList at " + ticklistbug + " entries");
                statusMsg("-- " + elapsed + " elapsed. " + world.getLoadedChunks().length + " chunks now loaded - " + pctmem + " memory in use - " + tickstr);
            }
            
            return false;
        }
        
        // Returns number of chunks queued
        public int addCircularRegion(World world, int xCenter, int zCenter, int radius)
        {
            return this._addRegion(_toChunk(xCenter - radius), _toChunk(zCenter - radius), _toChunk(xCenter + radius), _toChunk(zCenter + radius), _toChunk(xCenter), _toChunk(zCenter), _toChunk(radius));
        }
        // Returns number of chunks queued
        public int addSquareRegion(World world, int xStart, int zStart, int xEnd, int zEnd)
        {
            return this._addRegion(_toChunk(xStart), _toChunk(zStart), _toChunk(xEnd), _toChunk(zEnd), 0, 0, 0);
        }
        
        // Returns number of chunks queued
        // values are in *chunk coordinates* (see _toChunk)
        private int _addRegion(int xStart, int zStart, int xEnd, int zEnd, int xCenter, int zCenter, int radius)
        {
            if (debug) statusMsg("-- Preparing to generate region, in chunk coordinates: xStart: " + xStart + ", zStart: " + zStart + " xEnd: " + xEnd + ", zEnd: " + zEnd + ", xCenter: " + xCenter + ", zCenter: " + zCenter + ", radius: " + radius);
            if (xStart > xEnd || zStart > zEnd || radius < 0)
                return 0;
            
            // Break into regions
            
            // Regions need to overlap by 2 so block populators
            // and lighting can run. (edge chunks wont work in either)
            int overlap = 2;
            
            int zNext = zStart;
            int xNext = xStart;
            
            while (zNext <= zEnd)
            {
                if (zNext == zStart) zNext += overlap;
                if (xNext == xStart) xNext += overlap;
                
                int x1 = xNext - overlap;
                int x2 = Math.min(x1 + regionsize - 1, xEnd);
                int z1 = zNext - overlap;
                int z2 = Math.min(z1 + regionsize - 1, zEnd);
                
                queuedregions.add(new QueuedRegion(x1, z1, x2, z2, xCenter, zCenter, radius));
                this.totalregions++;
                
                xNext = x2 + 1;
                
                if (xNext > xEnd)
                {
                    xNext = xStart + overlap;
                    zNext = z2 + 1;
                }
            }
            return (xEnd - xStart + 1) * (zEnd - zStart + 1);
        }
        
        private int _toChunk(int worldCoordinate)
        {
            // -1 through -16 are chunk -1,
            // 0 through 15 are chunk 0,
            // 16 through 32 are chunk 1...
            if (worldCoordinate < 0)
                return (worldCoordinate + 1)/16 - 1;
            else
                return worldCoordinate/16;
        }
        
        private class QueuedRegion
        {
            private int xStart, zStart, xEnd, zEnd, xCenter, zCenter, radius;
            QueuedRegion(int xStart, int zStart, int xEnd, int zEnd, int xCenter, int zCenter, int radius)
            {
                this.xCenter = xCenter;
                this.zCenter = zCenter;
                this.xStart = xStart;
                this.zStart = zStart;
                this.xEnd = xEnd;
                this.zEnd = zEnd;
                this.radius = radius;
            }
            
            public ArrayDeque<GenerationChunk> getChunks(World world)
            {
                ArrayDeque<GenerationChunk> ret = new ArrayDeque<GenerationChunk>();
                int x = xStart, z = zStart;
                while (z <= zEnd)
                {
                    // Skip chunks outside circle radius
                    if ((radius == 0) || (radius >= Math.sqrt((Math.pow(Math.abs(x - xCenter),2) + Math.pow(Math.abs(z - zCenter),2)))))
                        ret.push(new GenerationChunk(x, z, world));
                    
                    x++;
                    if (x > xEnd)
                    {
                        x = xStart;
                        z++;
                    }
                }
                return ret.size() > 0 ? ret : null;
            }
            
            // Chunks this represents
            public int getSize() { return (xEnd - xStart + 1) * (zEnd - zStart + 1); }
        }
        
        private ArrayDeque<QueuedRegion> queuedregions;
        private World world;
        private GenerationLighting fixlighting;
        private GenerationSpeed speed;
        private int totalregions;
        private int regionsize;
        private long starttime;
        private boolean debug;
        private boolean forceregeneration;
        private boolean onlywhenempty;
        private long lastnag;
    }
    private class GenerationChunk
    {
        private int x, z;
        private World world;
        private Chunk chunk;
        private boolean wascreated;
        GenerationChunk(int x, int z, World world) { this.x = x; this.z = z; this.world = world; this.wascreated = false; }
        public int getX() { return x; }
        public int getZ() { return z; }
        public boolean wasCreated() { return this.wascreated; }
        public int kickPlayers(String msg)
        {
            int kicked = 0;
            if (world.isChunkLoaded(this.x, this.z))
            {
                for (Entity ent:world.getChunkAt(this.x, this.z).getEntities())
                    if (ent instanceof Player)
                    {
                        ((Player)ent).kickPlayer(msg);
                        kicked++;
                    }
            }
            return kicked;
        }
        
        // Try to call the craftbukkit lighting update.
        // This will throw exceptions if: Server isn't craftbukkit, craftbukkit isn't the expected version, craftbukkit has an error...
        // *catch exceptions* if you don't want to assume we're running on compatible craftbukkit.
        public void fixLighting(boolean force)
        {
            if (this.chunk == null) return;
            // initLighting 'resets' the lighting for a chunk, doing fast lighting on everything and marking them all as needing full lighting
            // Don't do it on chunks without their adjacents loaded, since the h() will then fail to fix them and we're actually breaking
            // potentially good lighting.
            if (force && this.world.isChunkLoaded(x - 1, z - 1) &&
                         this.world.isChunkLoaded(x - 1, z) &&
                         this.world.isChunkLoaded(x - 1, z + 1) &&
                         this.world.isChunkLoaded(x, z - 1) &&
                         this.world.isChunkLoaded(x, z + 1) &&
                         this.world.isChunkLoaded(x + 1, z - 1) &&
                         this.world.isChunkLoaded(x + 1, z) &&
                         this.world.isChunkLoaded(x + 1, z + 1))
                ((CraftChunk)this.chunk).getHandle().initLighting();
            // h() calls i() (private) which relights all x/z columns marked as needing full lighting
            ((CraftChunk)this.chunk).getHandle().h();
        }
        
        public void load() { this.load(false); }
        public void load(boolean regenerateChunk)
        {
            this.chunk = this.world.getChunkAt(this.x, this.z);
            if (!this.chunk.isLoaded())
            {
                // Try to load it without allowing generation.
                // to determine if it already existed
                if (this.chunk.load(false))
                    this.wascreated = false;
                else
                {
                    this.chunk.load(true);
                    this.wascreated = true;
                }
            }
            else
                this.wascreated = false;
            
            if (regenerateChunk && !this.wascreated)
            {
                this.world.regenerateChunk(this.x, this.z);
                this.wascreated = true;
            }
        }
        
        public void unload() { this.unload(false); }
        public void unload(boolean force)
        {
            if (this.world.isChunkLoaded(this.x, this.z))
                this.world.unloadChunk(x, z, !force, !force);
        }
    }
    
    // *very* simple class the parse arguments with quoting
    private class NiceArgsParseIntException extends Throwable
    {
        private final static long serialVersionUID = -5360208863240437042L;
        private String argName, badValue;
        NiceArgsParseIntException(String argName, String badValue)
        {
            this.argName = argName;
            this.badValue = badValue;
        }
        public String getName() { return this.argName; }
        public String getBadValue() { return this.badValue; }
    }
    private class NiceArgsParseException extends Throwable
    {
        private final static long serialVersionUID = -1873367217076514922L;
        private String error;
        NiceArgsParseException(String error) { this.error = error; }
        public String getError() { return this.error; }
    }
    private class NiceArgs
    {
        private ArrayList<String> cleanArgs;
        private HashMap<String, String> switches;
        private int[] parsedInts;
        NiceArgs(String[] args) throws NiceArgsParseException
        {
            String allargs = "";
            for (int x = 0; x < args.length; x++)
                allargs += (allargs.length() > 0 ? " " : "") + args[x];

            cleanArgs = new ArrayList<String>();
            switches = new HashMap<String, String>();

            // Matches any list of items delimited by spaces. An item can have quotes around it to escape spaces
            // inside said quotes. Also honors escape sequences
            // E.g. arg1 "arg2 stillarg2" arg3 "arg4 \"bob\" stillarg4" arg5\ stillarg5
            Matcher m = Pattern.compile("\\s*(?:\\\"((?:[^\\\"\\\\]|\\\\.)*)\\\"|((?:[^\\s\\\\\\\"]|\\\\(?:.|$))+))(?:\\s|$)").matcher(allargs);
            while (m.regionStart() < m.regionEnd())
            {
                if (m.lookingAt())
                {
                    String rawarg = m.group(1) == null ? m.group(2) : m.group(1);
                    String arg = rawarg.replaceAll("\\\\(.|$)", "$1");
                    if (m.group(2) != null && rawarg.charAt(0) == '/')
                    {
                        // Handle switches. (Matches in group 1 are quoted arugments. They cannot be switches.)
                        String st[] = arg.substring(1).split(":");
                        if (st.length > 2)
                            throw new NiceArgsParseException("Invalid option: " + arg.substring(1));
                        this.switches.put(st[0].toLowerCase(), st.length == 2 ? st[1] : "true");
                    }
                    else
                    {
                        cleanArgs.add(arg);
                    }
                    m.region(m.end(), m.regionEnd());
                }
                else
                    throw new NiceArgsParseException("Error - Mismatched/errant quotes in arguments. You can escape quotes in world names with backslashes, e.g. \\\"");
            }
        }
        public int length() { return this.cleanArgs.size(); }
        public String get(int x) { return this.cleanArgs.get(x); }
        public String getSwitch(String key) { return switches.get(key.toLowerCase()); }
        public int getInt(int i, String argName) throws NiceArgsParseIntException
        {
            try
                { return Integer.parseInt(this.cleanArgs.get(i)); }
            catch (NumberFormatException e)
                { throw new NiceArgsParseIntException(argName, this.cleanArgs.get(i)); }
        }
    }

    private Logger logger = Bukkit.getLogger();
    private GenerationRegion currentRegion;
    private ArrayDeque<GenerationRegion> pendingRegions = new ArrayDeque<GenerationRegion>();
    private int taskId = 0;
    private boolean quitAfter = false;
    
    public void onEnable()
    {
        statusMsg("v"+VERSION+" Loaded");
    }
    
    // Send a status message to all players
    // with worldgenerationcontrol.statusmessage permissions,
    // as well as the console. If a player/console (target) is 
    // specified, include him regardless of his permissions.
    // if senderOnly is true, only send the message to him.
    private void statusMsg(String str, CommandSender target, boolean senderOnly)
    {
        //ChatColor.stripColor
        String msg = ChatColor.DARK_GRAY + "[" + ChatColor.GRAY + "WorldGenCtrl" + ChatColor.DARK_GRAY + "]" + ChatColor.WHITE + " " + str;
        
        // Message target if provided
        if (target instanceof Player)
            // commandsender.sendmessage doesn't support color, even if its a player :<
            ((Player)target).sendMessage(msg);
        else if (target != null)
            target.sendMessage(ChatColor.stripColor(msg));
        
        if (!senderOnly)
        {
            // Message all non-target players
            for (Player p:getServer().getOnlinePlayers())
            {
                if (p != target && p.hasPermission("worldgenerationcontrol.statusupdates"))
                    p.sendMessage(msg);
            }
            
            // Message console/logger, unless its the target
            if (!(target instanceof ConsoleCommandSender))
                logger.info(ChatColor.stripColor(msg));
        }
    }
    private void statusMsg(String str)
    {
        this.statusMsg(str, null, false);
    }
    private void statusMsg(String str, CommandSender target)
    {
        this.statusMsg(str, target, true);
    }

    public void onDisable()
    {
        if (this.taskId != 0)
        {
            statusMsg("Plugin unloaded, aborting generation.");
            this.endTask();
        }
    }

    

    public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] rawargs)
    {
        NiceArgs args;
        try
        {
            args = new NiceArgs(rawargs);
        }
        catch (NiceArgsParseException e)
        {
            statusMsg(e.getError(), sender);
            return true;
        }
        
        boolean bCircular = commandLabel.compareToIgnoreCase("generatecircularregion") == 0 || commandLabel.compareToIgnoreCase("gencircle") == 0;
        if (bCircular || commandLabel.compareToIgnoreCase("generateregion") == 0 || commandLabel.compareToIgnoreCase("genregion") == 0)
        {
            if (!sender.isOp() && !sender.hasPermission("worldgenerationcontrol.generate"))
            {
                statusMsg(ChatColor.RED + "Only server ops or those with the worldgenerationcontrol.generate permission may do that :<", sender);
                return true;
            }
            if     ((bCircular && (args.length() != 1 && args.length() != 4))
                || !bCircular && (args.length() != 5))
            {
                return false;
            }
            
            World world = null;
            int xCenter = 0, zCenter = 0, xStart = 0, zStart = 0, xEnd = 0, zEnd = 0, radius = 0;
            try
            {
                if (bCircular)
                {
                    radius = args.getInt(0, "radius");

                    if (radius < 1)
                    {
                        statusMsg("Radius must be > 1", sender);
                        return true;
                    }
                    
                    if (sender instanceof Player && args.length() < 4)
                    {
                        // Use player's location to center circle
                        Block c = ((Player)sender).getLocation().getBlock();
                        world = c.getWorld();
                        xCenter = c.getX();
                        zCenter = c.getZ();
                    }
                    else
                    {
                        if (args.length() < 4)
                        {
                            statusMsg("You're not a player, so you need to specify a world name and location.", sender);
                            return true;
                        }
                        world = getServer().getWorld(args.get(1));
                        if (world == null)
                        {
                            statusMsg("World \"" + ChatColor.GOLD + args.get(1) + ChatColor.WHITE + "\" does not exist.", sender);
                            return true;
                        }
                        xCenter = args.getInt(2, "xCenter");
                        zCenter = args.getInt(3, "zCenter");
                    }
                }
                else
                {
                    world = getServer().getWorld(args.get(0));
                    if (world == null)
                    {
                        statusMsg("World \"" + ChatColor.GOLD + args.get(0) + ChatColor.WHITE + "\" does not exist.", sender);
                        return true;
                    }
                    xStart = args.getInt(1, "xStart");
                    zStart = args.getInt(2, "zStart");
                    xEnd   = args.getInt(3, "xEnd");
                    zEnd   = args.getInt(4, "zEnd");
                }
            }
            catch (NiceArgsParseIntException e)
            {
                statusMsg("Error: " + e.getName() + " argument must be a number, not \"" + e.getBadValue() + "\"", sender);
                return true;
            }
            
            if (bCircular && radius < 1)
            {
                statusMsg("Circle radius must be > 0.", sender);
                return true;
            }
            else if (!bCircular && (xEnd - xStart < 1 || zEnd - zStart < 1))
            {
                statusMsg("xEnd and zEnd must be greater than xStart and zStart respectively.", sender);
                return true;
            }

            int numChunks;
            GenerationSpeed speed = GenerationSpeed.NORMAL;
            if (args.getSwitch("allatonce") != null)
                speed = GenerationSpeed.ALLATONCE;
            else if (args.getSwitch("veryfast") != null)
                speed = GenerationSpeed.VERYFAST;
            else if (args.getSwitch("fast") != null)
                speed = GenerationSpeed.FAST;
            else if (args.getSwitch("slow") != null)
                speed = GenerationSpeed.SLOW;
            else if (args.getSwitch("veryslow") != null)
                speed = GenerationSpeed.VERYSLOW;
            
            GenerationLighting lighting;
            String lightswitch = args.getSwitch("lighting");
            if (lightswitch == null)
                lightswitch = "normal";
            else
                lightswitch = lightswitch.toLowerCase();
            
            if (!lightswitch.equals("none"))
            {
                if (lightswitch.equals("extreme") || lightswitch.equals("force"))
                    lighting = GenerationLighting.EXTREME;
                else if (lightswitch.equals("true") || lightswitch.equals("normal"))
                    lighting = GenerationLighting.NORMAL;
                else
                {
                    statusMsg("Invalid lighting mode \""+lightswitch+"\"");
                    return true;
                }
            }
            else
                lighting = GenerationLighting.NONE;
            
            GenerationRegion gen = new GenerationRegion(world, speed, lighting, args.getSwitch("debug") != null || args.getSwitch("verbose") != null, args.getSwitch("destroyAndRegenerateArea") != null, args.getSwitch("onlyWhenEmpty") != null);
            if (bCircular)
                numChunks = gen.addCircularRegion(world, xCenter, zCenter, radius);
            else
                numChunks = gen.addSquareRegion(world, xStart, zStart, xEnd, zEnd);
            if (numChunks < 1)
            {
                // This shouldn't really be possible
                statusMsg("Specified region contains no loadable chunks (did you mix up positive/negatives?).", sender);
                return true;
            }
            this.queueGeneration(gen);
            if (args.getSwitch("quitafter") != null)
                this.quitAfterGeneration(true);
            statusMsg((sender instanceof Player ? ("Player " + ChatColor.GOLD + ((Player)sender).getName() + ChatColor.WHITE) : "The console") + " queued generation of " + numChunks + " chunk region (" + (numChunks * 16) + " blocks).");
        }
        else if (commandLabel.compareToIgnoreCase("cancelgeneration") == 0 || commandLabel.compareToIgnoreCase("cancelgen") == 0)
        {
            if (this.taskId == 0)
            {
                statusMsg("There is no chunk generation in progress", sender);
                return true;
            }
            else
            {
                statusMsg("Generation canceled by " + (sender instanceof Player ? ("player " + ChatColor.GOLD + ((Player)sender).getName() + ChatColor.WHITE) : "the console"));
                this.cancelGeneration();
            }
        }
        return true;
    }
    
    public void queueGeneration(GenerationRegion region)
    {
        if (this.currentRegion != null)
            this.pendingRegions.push(region);
        else
        {
            this.currentRegion = region;
            this.restartTask(region.shouldRunAllAtOnce() ? 2 : 60);
        }
    }

    public void quitAfterGeneration() { this.quitAfterGeneration(true); }
    public void quitAfterGeneration(boolean yesno) { if (this.currentRegion != null) this.quitAfter = yesno; }
    
    public void cancelGeneration()
    {
        this.quitAfter = false;
        if (this.currentRegion != null) this.currentRegion.cancelRemaining();
        this.pendingRegions.clear();
    }
    
    // use cancelGeneration to stop generation, this should only be used internally
    private void endTask()
    {
        if (this.taskId != 0)
            getServer().getScheduler().cancelTask(this.taskId);
        this.taskId = 0;
    }
    
    private void restartTask() { this.restartTask(60); }
    private void restartTask(int period)
    {
        this.endTask();
        this.taskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, this, period, period);
    }

    public void run()
    {
        if (this.taskId == 0) return; // Prevent inappropriate calls

        int pending = this.pendingRegions.size();
        // Pass -1 as pending if we're about to quit
        if (this.currentRegion.runStep((pending == 0 && this.quitAfter) ? -1 : pending))
        {
            if (pending > 0)
            {
                GenerationRegion next = this.pendingRegions.pop();
                // Adjust scheduling if needed
                if (this.currentRegion.shouldRunAllAtOnce() != next.shouldRunAllAtOnce())
                    this.restartTask(next.shouldRunAllAtOnce() ? 2 : 60);

                this.currentRegion = next;
            }
            else
            {
                this.currentRegion = null;
                this.endTask();
                if (this.quitAfter)
                {
                    statusMsg("/quitAfter specified, shutting down");
                    getServer().shutdown();
                }
            }
        }
    }
}
