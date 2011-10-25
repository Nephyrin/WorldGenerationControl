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

import java.util.logging.Logger;
import java.util.Iterator;
import java.util.HashMap;
import java.util.ArrayList;
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

public class WorldGenerationControl extends JavaPlugin implements Runnable
{
    private final static String VERSION = "2.1";
    public enum GenerationSpeed
    {
        // Do everything on the same tick, locking up
        // the server until the generation is complete.
        ALLATONCE,
        // Split up region loading and lighting fixes,
        // processing in ticks. Very laggy, but doesn't
        // freeze the server.
        VERYFAST,
        // Process way smaller regions and way less
        // lighting per tick. Laggy, but playable.
        FAST,
        // Process even smaller regions and even less
        // lighting per tick. Causes mild to moderate
        // lag.
        NORMAL,
        // even smaller regions, less lag
        SLOW,
        // tiny regions, very minimal lag, will
        // take forever.
        VERYSLOW
    }
    public enum GenerationLighting
    {
        // EXISTING ones specify that we should
        // relight existing chunks in the region as well
        
        // Force update every chunk without
        // fullbright lighting, completely
        // recalculating all lighting in the
        // area. This will take 3x longer
        // than the rest of the generation
        // combined...
        EXTREME, EXTREME_EXISTING,
        // Force update all lighting by toggling
        // skyblocks. This will easily double
        // generation times, but give you proper
        // lighting on generated chunks
        NORMAL, NORMAL_EXISTING,
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
        GenerationRegion(World world, GenerationSpeed speed, GenerationLighting lighting) { this._construct(world, speed, lighting, false, false); }
        GenerationRegion(World world, GenerationSpeed speed, GenerationLighting lighting, boolean debug) { this._construct(world, speed, lighting, debug, false); }
        GenerationRegion(World world, GenerationSpeed speed, GenerationLighting lighting, boolean debug, boolean forceRegeneration) { this._construct(world, speed, lighting, debug, forceRegeneration); }
        private void _construct(World world, GenerationSpeed speed, GenerationLighting lighting, boolean debug, boolean forceRegeneration)
        {
            this.debug = debug;
            this.totalregions = 0;
            this.world = world;
            this.speed = speed;
            this.fixlighting = lighting;
            this.pendinglighting = new ArrayDeque<GenerationChunk>();
            this.pendingcleanup = new ArrayDeque<GenerationChunk>();
            this.queuedregions = new ArrayDeque<QueuedRegion>();
            this.currentregion = null;
            this.starttime = 0;
            this.forceregeneration = forceRegeneration;
            
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
        
        // returns true if complete
        // queued is number of generations the plugin intends to run after this
        // or -1 if the plugin intends to shutdown the server after this!
        public boolean runStep(int queued)
        {
            if (this.starttime == 0)
                this.starttime = System.nanoTime();
            
            String queuedtext = "";
            if (queued > 0)
                queuedtext = ChatColor.DARK_GRAY + " {" + ChatColor.GRAY + queued + " generations in queue" + ChatColor.DARK_GRAY + "}";
            if (queued == -1)
                queuedtext = ChatColor.DARK_GRAY + " {" + ChatColor.DARK_RED + "shutdown scheduled" + ChatColor.DARK_GRAY + "}";
            
            String state;
            long stime = debug ? System.nanoTime() : 0;
            int step;
            if (pendinglighting.size() > 0)
            {
                step = 2;
                state = "Generating lighting";
            }
            else if (pendingcleanup.size() > 0)
            {
                step = 3;
                state = "Saving chunks";
            }
            else if (currentregion != null)
            {
                step = 1;
                state = "Loading chunks";
            }
            else if (queuedregions.size() > 0)
            {
                step = 0;
                state = "Analyzing area";
            }
            else
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
            
            // Status message
            double lightingpct = (double)pendinglighting.size() / (this.regionsize * this.regionsize);
            // Assumes lighting is 92% of each chunk's processing, a rough estimate based on timing a generation on my system
            double pct = 1 - ((double)queuedregions.size() + 0.92 * lightingpct) / totalregions;
            int region = totalregions - queuedregions.size() + (step == 1 ? 1 : 0);
            statusMsg(ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + String.format("%.2f", 100*pct) + "%" + ChatColor.DARK_GRAY + "]" + ChatColor.GRAY + " Section " + ChatColor.WHITE + region + ChatColor.GRAY + "/" + ChatColor.WHITE + totalregions + ChatColor.GRAY + " :: " + state + queuedtext);
            
            if (step == 0)
            {
                this.currentregion = queuedregions.pop();
                this.currentregion.prepareChunks(this.world);
            }
            if (step == 1)
            {
                // Load these chunks as our step
                ArrayDeque<GenerationChunk> chunks = this.currentregion.getRemainingChunks();
                if (this.forceregeneration)
                {
                    Iterator<GenerationChunk> i = chunks.iterator();
                    while (i.hasNext())
                    {
                        GenerationChunk gc = i.next();
                        gc.kickPlayers("The region you are in was regenerated. Please rejoin");
                        gc.unload(true);
                    }
                }
                while (chunks.size() > 0)
                {
                    GenerationChunk c = chunks.pop();
                    c.load(this.forceregeneration);
                    if (this.fixlighting == GenerationLighting.NONE)
                        pendingcleanup.push(c);
                    else
                        pendinglighting.push(c);
                }
            }
            else if (step == 2)
            {
                int chunksPerTick;
                if (speed == GenerationSpeed.VERYFAST)
                    chunksPerTick = fixlighting == GenerationLighting.EXTREME ? 12 : 60;
                if (speed == GenerationSpeed.FAST)
                    chunksPerTick = fixlighting == GenerationLighting.EXTREME ? 5 : 30;
                else if (speed == GenerationSpeed.SLOW)
                    chunksPerTick = fixlighting == GenerationLighting.EXTREME ? 1 : 3;
                else if (speed == GenerationSpeed.VERYSLOW)
                    chunksPerTick = 1;
                else
                    chunksPerTick = fixlighting == GenerationLighting.EXTREME ? 2 : 5;
                // Run lighting step
                while (chunksPerTick > 0 && pendinglighting.size() > 0)
                {
                    GenerationChunk x = pendinglighting.pop();
                    // Only light chunks that were made by us, unless a _EXISTING lighting option was specified
                    if (x.wasCreated() || fixlighting == GenerationLighting.EXTREME_EXISTING || fixlighting == GenerationLighting.NORMAL_EXISTING)
                        // Chunks that don't need lighting dont count to the total
                        if (x.fixLighting(fixlighting == GenerationLighting.EXTREME)) chunksPerTick--;
                    pendingcleanup.push(x);
                }
            }
            else if (step == 3)
            {
                Iterator<GenerationChunk> cleaner = pendingcleanup.iterator();
                while (cleaner.hasNext())
                {
                    cleaner.next().unload();
                    cleaner.remove();
                }
            }
            
            if (debug)
                statusMsg("-- " + String.format("%.2f", (double)(System.nanoTime() - stime) / 1000000) + "ms elapsed. " + world.getLoadedChunks().length + " chunks now loaded");
            
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
            private int xStart, zStart, xEnd, zEnd, xCenter, zCenter, radius, x, z;
            private ArrayDeque<GenerationChunk> chunks;
            QueuedRegion(int xStart, int zStart, int xEnd, int zEnd, int xCenter, int zCenter, int radius)
            {
                this.chunks = null;
                this.x = xStart;
                this.z = zStart;
                this.xCenter = xCenter;
                this.zCenter = zCenter;
                this.xStart = xStart;
                this.zStart = zStart;
                this.xEnd = xEnd;
                this.zEnd = zEnd;
                this.radius = radius;
            }
            
            // For iterating over chunks
            public void reset() { x = xStart; z = zStart; }
            public boolean chunksPrepared() { return this.chunks != null; }
            public void prepareChunks(World world)
            {
                if (this.chunks != null) return;
                this.chunks = new ArrayDeque<GenerationChunk>();
                while (z <= zEnd)
                {
                    // Skip chunks outside circle radius
                    if ((radius == 0) || (radius >= Math.sqrt((double)(Math.pow(Math.abs(x - xCenter),2) + Math.pow(Math.abs(z - zCenter),2)))))
                        this.chunks.push(new GenerationChunk(x, z, world));
                    
                    x++;
                    if (x > xEnd)
                    {
                        x = xStart;
                        z++;
                    }
                }
            }
            public GenerationChunk getChunk()
            {
                if (this.chunks == null || this.chunks.size() < 1) return null;
                GenerationChunk ret = this.chunks.pop();
                if (this.chunks.size() < 1); this.chunks = null;
                return ret;
            }
            public ArrayDeque<GenerationChunk> getRemainingChunks()
            {
                if (this.chunks == null) return null;
                ArrayDeque<GenerationChunk> ret = this.chunks;
                this.chunks = null;
                return ret;
            }
            
            public int getNumRemainingChunks() { return this.chunks != null ? this.chunks.size() : 0; }
            // Chunks this represents
            public int getSize() { return (xEnd - xStart + 1) * (zEnd - zStart + 1); }
        }
        
        private ArrayDeque<GenerationChunk> pendinglighting, pendingcleanup;
        private ArrayDeque<QueuedRegion> queuedregions;
        private World world;
        private GenerationLighting fixlighting;
        private GenerationSpeed speed;
        private int totalregions;
        private int regionsize;
        private long starttime;
        private boolean debug;
        private boolean forceregeneration;
        private QueuedRegion currentregion;
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
        // This references a lot of blocks, if calling this on a lot of chunks,
        // a System.gc() afterwards might be necessary to prevent overhead errors.
        public boolean fixLighting() { return this.fixLighting(false); }
        public boolean fixLighting(boolean extreme)
        {
            // Don't run this step on chunks without all adjacent chunks loaded, or it will
            // actually corrupt the lighting
            if (this.chunk != null
                && this.world.isChunkLoaded(this.x - 1, this.z)
                && this.world.isChunkLoaded(this.x, this.z - 1)
                && this.world.isChunkLoaded(this.x + 1, this.z)
                && this.world.isChunkLoaded(this.x, this.z + 1)
                && this.world.isChunkLoaded(this.x - 1, this.z + 1)
                && this.world.isChunkLoaded(this.x + 1, this.z + 1)
                && this.world.isChunkLoaded(this.x - 1, this.z - 1)
                && this.world.isChunkLoaded(this.x + 1, this.z - 1))
            {
                // Only fast lighting is done if no living entities are near.
                // The solution is to create a noble chicken, who will be destroyed
                // after the updates have been forced.
                // Note that if we *didnt* update lighting, it would be updated
                // the first time a player wanders near anyway, this is just
                // a hack to make it happen at generation time.
                int worldHeight = this.world.getMaxHeight();
                // Center of the chunk. ish. Don't think it matters as long as he's in the chunk.
                // Since he's removed at the end of this function he doesn't live for a single tick,
                // so it doesn't matter if this puts him in a solid object - he doesn't have time to suffocate.
                LivingEntity bobthechicken = this.world.spawnCreature(this.chunk.getBlock(8, worldHeight - 1, 8).getLocation(), CreatureType.CHICKEN);
                for (int bx = 0; bx < 16; bx++) for (int bz = 0; bz < 16; bz++) for (int by = extreme ? 0 : worldHeight - 1; by < worldHeight; by++)
                {
                    Block bl = this.chunk.getBlock(bx, by, bz);
                    if (by == worldHeight - 1 || (extreme && bl.getLightLevel() < 15))
                    {
                        // All touched blocks have their state saved and re-applied
                        BlockState s = bl.getState();
                        // The way lighting works branches based on how far the skylight reaches down.
                        // Thus by toggling the top block between solid and not, we force a lighting update
                        // on this column of blocks.
                        if (bl.isEmpty())
                            bl.setType(Material.STONE);
                        else
                            bl.setType(Material.AIR);
                        s.update(true);
                    }
                }
                
                bobthechicken.remove();
                return true;
            }
            else return false;
        }
        
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
            boolean lightexisting = args.getSwitch("lightexisting") != null;
            if (lightswitch != null) lightswitch = lightswitch.toLowerCase();
            
            if (lightexisting && lightswitch == null) lightswitch = "normal"; // /lightexisting implies /light
            
            if (lightswitch != null && !lightswitch.equals("none"))
            {
                if (lightswitch.equals("extreme"))
                    lighting = lightexisting ? GenerationLighting.EXTREME_EXISTING : GenerationLighting.EXTREME;
                else if (lightswitch.equals("true") || lightswitch.equals("normal"))
                    lighting = lightexisting ? GenerationLighting.NORMAL_EXISTING : GenerationLighting.NORMAL;
                else
                {
                    statusMsg("Invalid lighting mode \""+lightswitch+"\"");
                    return true;
                }
            }
            else
                lighting = GenerationLighting.NONE;
            
            GenerationRegion gen = new GenerationRegion(world, speed, lighting, args.getSwitch("debug") != null || args.getSwitch("verbose") != null, args.getSwitch("destroyAndRegenerateArea") != null);
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
                statusMsg("Generation canceled by " + (sender instanceof Player ? ("player " + ChatColor.GOLD + ((Player)sender).getName() + ChatColor.WHITE) : "the console") + ", waiting for current section to finish.");
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
