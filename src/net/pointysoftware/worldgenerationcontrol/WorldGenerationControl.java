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
    private final static String VERSION = "2.0";
    public enum GenerationSpeed
    {
        // Do everything on the same tick, locking up
        // the server until the generation is complete.
        ALLATONCE,
        // Process whole regions per tick, extremely laggy.
        VERYFAST,
        // Split up region loading and lighting fixes
        // laggy, but playable.
        FAST,
        // like fast, but smaller regions, moderate
        // lag depending on conditions
        NORMAL,
        // even smaller regions, less lag
        SLOW,
        // tiny regions, very minimal lag, will
        // take forever.
        VERYSLOW
    }
    public enum GenerationLighting
    {
        // Force update every chunk without
        // fullbright lighting, completely
        // recalculating all lighting in the
        // area. This will take 3x longer
        // than the rest of the generation
        // combined...
        EXTREME,
        // Force update all lighting by toggling
        // skyblocks. This will easily double
        // generation times, but give you proper
        // lighting on generated chunks
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
        GenerationRegion(World world, GenerationSpeed speed, GenerationLighting lighting) { this._construct(world, speed, lighting, false); }
        GenerationRegion(World world, GenerationSpeed speed, GenerationLighting lighting, boolean debug) { this._construct(world, speed, lighting, debug); }
        private void _construct(World world, GenerationSpeed speed, GenerationLighting lighting, boolean debug)
        {
            this.debug = debug;
            this.totalregions = 0;
            this.world = world;
            this.speed = speed;
            this.fixlighting = lighting;
            this.pendinglighting = new ArrayDeque<GenerationChunk>();
            this.pendingcleanup = new ArrayDeque<GenerationChunk>();
            this.queuedregions = new ArrayDeque<QueuedRegion>();

            if (this.speed == GenerationSpeed.NORMAL) regionsize = 16;
            else if (this.speed == GenerationSpeed.SLOW) regionsize = 13;
            else if (this.speed == GenerationSpeed.VERYSLOW) regionsize = 10;
            else regionsize = 24;
        }
        
        // returns true if complete
        public boolean runStep()
        {
            boolean done = false;
            String state;
            long stime = debug ? System.nanoTime() : 0;
            int step;
            if (pendinglighting.size() > 0)
            {
                step = 2;
                state = "Generating lighting";
            }
            else if (queuedregions.size() > 0)
            {
                step = 1;
                state = "Loading chunks";
            }
            else
            {
                step = 3;
                state = "Unloading and saving chunks";
            }
            
            // Status message
            double pct = 1 - ((pendinglighting.size() > 0 ? 0.5 : 0.) + (double)queuedregions.size()) / totalregions;
            int region = totalregions - queuedregions.size() + (step == 1) ? 1 : 0;
            statusMsg(ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + String.format("%.2f", 100*pct) + "%" + ChatColor.DARK_GRAY + "]" + ChatColor.GRAY + " Section " + ChatColor.WHITE + region + ChatColor.GRAY + "/" + ChatColor.WHITE + totalregions + ChatColor.GRAY + " :: " + state);
            
            if (pendinglighting.size() > 0)
            {
                int chunksPerTick;
                if (speed == GenerationSpeed.FAST)
                    chunksPerTick = 15;
                else if (speed == GenerationSpeed.SLOW)
                    chunksPerTick = 3;
                else if (speed == GenerationSpeed.VERYSLOW)
                    chunksPerTick = 1;
                else
                    chunksPerTick = 5;
                // Run lighting step
                // TODO print stuff
                while ((speed == GenerationSpeed.ALLATONCE || speed == GenerationSpeed.VERYFAST || chunksPerTick > 0) && pendinglighting.size() > 0)
                {
                    GenerationChunk x = pendinglighting.pop();
                    // Chunks that don't need lighting dont count to the total
                    if (x.fixLighting()) chunksPerTick--;
                    pendingcleanup.push(x);
                }
            }
            else if (queuedregions.size() > 0)
            {
                QueuedRegion next = queuedregions.pop();
                // Load these chunks as our step
                GenerationChunk c;
                while ((c = next.getChunk(this.world)) != null)
                {
                    c.load();
                    if (this.fixlighting == GenerationLighting.NONE)
                        pendingcleanup.push(c);
                    else
                        pendinglighting.push(c);
                }
            }
            else
            {
                done = true;
            }
            
            // Handle pending-cleanup chunks
            if (done)
            {
                Iterator<GenerationChunk> cleaner = pendingcleanup.iterator();
                while (cleaner.hasNext())
                {
                    cleaner.next().unload();
                    cleaner.remove();
                }
            }
            
            if (debug)
                statusMsg("\tDebug: This step took " + String.format("%.2f", (double)(System.nanoTime() - stime) / 1000000) + "ms. Currently " + world.getLoadedChunks().length + " chunks loaded.");
            
            if (done)
                return true;
            if (speed == GenerationSpeed.ALLATONCE)
                return this.runStep();
            else
                return false;
        }
        
        // Returns number of chunks queued
        public int addCircularRegion(World world, int xCenter, int zCenter, int radius)
        {
            xCenter = _toChunk(xCenter);
            zCenter = _toChunk(zCenter);
            radius = _toChunk(radius);
            return this._addRegion(xCenter - radius, zCenter - radius, xCenter + radius, zCenter + radius, xCenter, zCenter, radius);
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
            statusMsg("\tDebug: In chunk values, xStart: " + xStart + ", zStart: " + zStart + " xEnd: " + xEnd + ", zEnd: " + zEnd + ", xCenter: " + xCenter + ", zCenter: " + zCenter + ", radius: " + radius);
            if (xStart > xEnd || zStart > zEnd || radius < 0)
                return 0;
            
            // Break into regions
            
            // Regions need to overlap by 2 so block populators
            // and lighting can run. (edge chunks wont work in either)
            int overlap = 2;
            
            int zNext = zStart + overlap;
            int xNext = xStart + overlap;
            
            while (zNext <= zEnd)
            {
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
            QueuedRegion(int xStart, int zStart, int xEnd, int zEnd, int xCenter, int zCenter, int radius)
            {
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
            public GenerationChunk getChunk(World world)
            {
                GenerationChunk ret = null;
                while (ret == null)
                {
                    if (z > zEnd)
                        return null;
                    
                    // Skip chunks outside circle radius
                    if ((radius == 0) || (radius >= Math.sqrt((double)(Math.pow(Math.abs(x - xCenter),2) + Math.pow(Math.abs(z - zCenter),2)))))
                        ret = new GenerationChunk(x, z, world);
                    
                    x++;
                    if (x > xEnd)
                    {
                        x = xStart;
                        z++;
                    }
                }
                return ret;
            }
            
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
        private boolean debug;
    }
    private class GenerationChunk
    {
        private int x, z;
        private World world;
        private Chunk chunk;
        GenerationChunk(int x, int z, World world) { this.x = x; this.z = z; this.world = world; }
        public int getX() { return x; }
        public int getZ() { return z; }
        // This references a lot of blocks, if calling this on a lot of chunks,
        // a System.gc() afterwards might be necessary to prevent overhead errors.
        public boolean fixLighting()
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
                ArrayList<BlockState> touchedblocks = new ArrayList<BlockState>();
                for (int bx = 0; bx < 16; bx++) for (int bz = 0; bz < 16; bz++)
                {
                    Block bl = this.chunk.getBlock(bx, worldHeight - 1, bz);
                    // All touched blocks have their state saved and re-applied after the loop.
                    // TODO -
                    // I *think* this should be safe, but I need to do testing on various tile
                    // entities placed at max height to ensure it doesn't damage them.
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
                
                bobthechicken.remove();
                return true;
            }
            else return false;
        }
        public void load()
        {
            this.chunk = this.world.getChunkAt(this.x, this.z);
            if (!this.chunk.isLoaded())
                this.chunk.load(true);
        }
        public void unload()
        {
            if (this.world.isChunkLoaded(this.x, this.z))
                this.world.unloadChunk(x, z, true);
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
                        String st[] = arg.substring(1).split("/:/");
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
    private ArrayList<GenerationChunk> ourChunks = new ArrayList<GenerationChunk>();
    private int taskId = 0;
    
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
        String msg = ChatColor.DARK_GRAY + "[" + ChatColor.GRAY + "WorldGenerationControl" + ChatColor.DARK_GRAY + "]" + ChatColor.WHITE + " " + str;
        
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
                    p.sendMessage(str);
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
            if (!sender.isOp())
            {
                statusMsg("Requires op status.", sender);
                return true;
            }
            if (this.taskId != 0)
            {
                statusMsg("Generation already in progress.", sender);
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
                        Chunk c = ((Player)sender).getLocation().getBlock().getChunk();
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
            GenerationRegion gen = new GenerationRegion(world, speed, GenerationLighting.NORMAL, args.getSwitch("debug") != null);
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
            statusMsg((sender instanceof Player ? ("Player " + ChatColor.GOLD + ((Player)sender).getName() + ChatColor.WHITE) : "The console") + " started generation of " + numChunks + " chunk region (" + (numChunks * 16) + " blocks).");
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
            this.taskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, this, 60, 60);
        }
    }

    public void cancelGeneration()
    {
        if (this.taskId != 0)
        {
            this.pendingRegions.clear();
        }
    }
    
    // use cancelGeneration to stop generation, this should only be used internally
    private void endTask()
    {
        if (this.taskId != 0)
            getServer().getScheduler().cancelTask(this.taskId);
        this.taskId = 0;
    }

    public void run()
    {
        if (this.taskId == 0) return; // Prevent inappropriate calls
        if (this.currentRegion.runStep())
        {
            if (this.pendingRegions.size() > 0)
                this.currentRegion = this.pendingRegions.pop();
            else
                this.endTask();
        }
    }
}
