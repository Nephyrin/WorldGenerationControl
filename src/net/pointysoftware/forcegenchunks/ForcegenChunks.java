/*
   See README.markdown for more information
   
   ForcegenChunks - Bukkit chunk preloader
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

package net.pointysoftware.forcegenchunks;

import java.util.ArrayList;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import org.bukkit.scheduler.BukkitScheduler;

public class ForcegenChunks extends JavaPlugin implements Runnable
{
    private final static String VERSION = "1.1";
    private class ChunkXZ
    {
        private int x; private int z;
        ChunkXZ(int x, int z) { this.x = x; this.z = z; }
        public int getX() { return x; }
        public int getZ() { return z; }
    }
    // Max size of each block chunk to load at a time
    // A size of 12 would result in 16*16=256 blocks loaded per tick
    private static final int BLOCKSIZE = 16;

    private ArrayList<ChunkXZ> ourChunks = new ArrayList<ChunkXZ>();
    private int taskId = 0;
    private World world;
    private int xStart;
    private int xEnd;
    private int zStart;
    private int zEnd;
    private int xNext;
    private int zNext;
    private int radius;
    private int xCenter;
    private int zCenter;
    private int maxLoadedChunks;
    
    public void onEnable()
    {
        System.out.println("[ForcegenChunks] v"+VERSION+" Loaded");
    }

    public void onDisable()
    {
        if (this.taskId != 0)
        {
            System.out.println("[ForcegenChunks] Unloading, aborting generation");
            getServer().getScheduler().cancelTask(this.taskId);
            this.taskId = 0;
        }
        this.freeLoadedChunks();
    }

    public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args)
    {
        boolean bCircular = commandLabel.compareToIgnoreCase("forcegencircle") == 0;
        if (bCircular || commandLabel.compareToIgnoreCase("forcegenchunks") == 0 || commandLabel.compareToIgnoreCase("forcegen") == 0)
        {
            if (!sender.isOp())
            {
                sender.sendMessage("[ForcegenChunks] Requires op status.");
                return true;
            }
            if (this.taskId != 0)
            {
                sender.sendMessage("[ForcegenChunks] Generation already in progress.");
                return true;
            }
            if     ((bCircular && (args.length != 1 && args.length != 2 && args.length != 4 && args.length != 5))
                || (!bCircular && (args.length != 5 && args.length != 6)))
            {
                return false;
            }
            
            World world = null;
            int maxLoadedChunks = -1;
            int xCenter = 0, zCenter = 0, xStart, zStart, xEnd, zEnd, radius = 0;
            if (bCircular)
            {
                radius = Integer.parseInt(args[0]);
                if (radius < 1)
                {
                    sender.sendMessage("[ForcegenChunks] Radius must be > 1!");
                    return true;
                }
                
                boolean isPlayer = true;
                try { Player p = (Player)sender; }
                catch (ClassCastException e) { isPlayer = false; }
                
                if (isPlayer && args.length < 4)
                {
                    // Use player's location to center circle
                    Chunk c = ((Player)sender).getLocation().getBlock().getChunk();
                    world = c.getWorld();
                    xCenter = c.getX();
                    zCenter = c.getZ();
                }
                else
                {
                    if (args.length < 4)
                    {
                        sender.sendMessage("[ForcegenChunks] You're not a player, so you need to specify a world name and location.");
                        return true;
                    }
                    world = getServer().getWorld(args[1]);
                    xCenter = Integer.parseInt(args[2]);
                    zCenter = Integer.parseInt(args[3]);
                }
                xStart = xCenter - radius;
                xEnd = xCenter + radius;
                zStart = zCenter - radius;
                zEnd = zCenter + radius;
                if (args.length == 2) maxLoadedChunks = Integer.parseInt(args[1]);
                else if (args.length == 5) maxLoadedChunks = Integer.parseInt(args[4]);
            }
            else
            {
                world = getServer().getWorld(args[0]);
                xStart = Integer.parseInt(args[1]);
                zStart = Integer.parseInt(args[2]);
                xEnd   = Integer.parseInt(args[3]);
                zEnd   = Integer.parseInt(args[4]);
                if (args.length == 6) maxLoadedChunks = Integer.parseInt(args[5]);
            }
            
            if (world == null)
            {
                sender.sendMessage("[ForcegenChunks] World \"" + args[0] + "\" does not exist.");
                return true;
            }
            
            int loaded = world.getLoadedChunks().length;
            if (maxLoadedChunks < 0) maxLoadedChunks = loaded + 800;
            else if (maxLoadedChunks < loaded + 200)
            {
                sender.sendMessage("[ForcegenChunks] maxLoadedChunks too low, there are already " + loaded + " chunks loaded - need a value of at least " + (loaded + 200));
                return true;
            }
            
            if (xEnd - xStart < 1 || zEnd - zStart < 1)
            {
                sender.sendMessage("[ForcegenChunks] xEnd and zEnd must be greater than xStart and zStart respectively.");
                return true;
            }
            int num = (xEnd - xStart + 1) * (zEnd - zStart + 1);
            sender.sendMessage("[ForcegenChunks] Starting generation of " + num + " Chunks (" + (num * 16) + " blocks.)");
            if (world.getPlayers().size() > 0) sender.sendMessage("[ForcegenChunks] ... Warning: There are currently players in this world. If players wander into the generation zone, generation will not finish until they leave.");

            this.generateChunks(world, xStart, xEnd, zStart, zEnd, maxLoadedChunks, radius, xCenter, zCenter);
        }
        else if (commandLabel.compareToIgnoreCase("cancelforcegenchunks") == 0 || commandLabel.compareToIgnoreCase("cancelforcegen") == 0)
        {
            if (this.taskId == 0 || this.zNext > this.zEnd)
            {
                sender.sendMessage("[ForcegenChunks] There is no chunk generation in progress");
                return true;
            }
            else
            {
                // Push it past the end of the region, so it will still
                // continue the task while waiting for unloaded chunks.
                this.zNext = this.zEnd + 1;
                sender.sendMessage("[ForcegenChunks] Canceling generation, waiting for remaining chunks to unload");
            }
        }
        return true;
    }

    public boolean generateChunks(World world, int xStart, int xEnd, int zStart, int zEnd, int maxLoadedChunks, int radius, int xCenter, int zCenter)
    {
        if (this.taskId != 0) return false;

        // The generation routine adds 2 to the edges of the generation cells
        // so bump these borders in by two (if possible) to avoid generating
        // more than requested chunks. It's still possible to generate extra
        // chunks if the height or width of the requested block is < 5
        if (xEnd - xStart > 2) xEnd -= 2;
        if (xEnd - xStart > 2) xStart += 2;
        if (zEnd - zStart > 2) zEnd -= 2;
        if (zEnd - zStart > 2) zStart += 2;

        this.maxLoadedChunks = maxLoadedChunks;
        this.world = world;
        this.xStart = xStart;
        this.xNext = xStart;
        this.zNext = zStart;
        this.xEnd = xEnd;
        this.zStart = zStart;
        this.zEnd = zEnd;
        this.xCenter = xCenter;
        this.zCenter = zCenter;
        this.radius = radius;
        this.taskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, this, 50, 50);
        return true;
    }

    private int freeLoadedChunks()
    {
        // requesting an unloaded chunk wont always cause it to unload,
        // causing misc chunks to pile up, so we keep issueing unload requests
        // until the chunk disappears. This is why players near the generation
        // area can cause the plugin to sit on 'waiting for chunks to unload'
        // until they leave.
        for (int i = ourChunks.size() - 1; i >= 0; i--)
        {
            int x = ourChunks.get(i).getX();
            int z = ourChunks.get(i).getZ();
            if (world.isChunkLoaded(x, z))
            {
                world.unloadChunkRequest(x, z, true);
            }
            else
            {
                ourChunks.remove(i);
            }
        }
        return ourChunks.size();
    }

    public void run()
    {
        if (this.taskId == 0) return; // Prevent inappropriate calls

        int remainingChunks = this.freeLoadedChunks();

        int loaded = world.getLoadedChunks().length;

        if (this.zNext > this.zEnd)
        {
            if (remainingChunks > 0)
            {
                System.out.println("[ForcegenChunks] Waiting for "+remainingChunks+" chunks to finish unloading, " + loaded + " chunks currently loaded.");
                if (world.getPlayers().size() > 0) System.out.println("[ForcegenChunks] -- There are players in this world, some chunks may not finish unloading until they leave.");
            }
            else
            {
                System.out.println("[ForcegenChunks] Finished generating, " + loaded + " chunks currently loaded.");
                getServer().getScheduler().cancelTask(this.taskId);
                this.taskId = 0;
            }
            return;
        }

        if (loaded > this.maxLoadedChunks)
        {
            System.out.println("[ForcegenChunks] More than " + this.maxLoadedChunks + " chunks loaded (" + loaded + "), waiting for some to finish unloading");
            return;
        }

        int x1 = this.xNext - 2;
        int x2 = Math.min(x1 + this.BLOCKSIZE - 1, this.xEnd + 2);
        int z1 = this.zNext - 2;
        int z2 = Math.min(z1 + this.BLOCKSIZE - 1, this.zEnd + 2);

        System.out.println("[ForcegenChunks] Generating " + ((x2 - x1 + 1) * (z2 - z1 + 1)) + " chunk region from ["+x1+","+z1+"] to ["+x2+","+z2+"], " + loaded + " currently loaded.");

        for (int nx = x1; nx <= x2; nx++)
        {
            for (int nz = z1; nz <= z2; nz++)
            {
                if (!world.isChunkLoaded(nx, nz))
                {
                    // If we're doing a circular generation, this block might be skipped
                    if ((radius > 0) && (radius < Math.sqrt((double)(Math.pow(Math.abs(nx - xCenter),2) + Math.pow(Math.abs(nz - zCenter),2)))))
                            continue;
                    // Keep tracks of chunks we caused to load so we can unload them
                    ourChunks.add(new ChunkXZ(nx, nz));
                    world.loadChunk(nx, nz, true);
                }
            }
        }

        //loaded = world.getLoadedChunks().length;
        //System.out.println("[ForcegenChunks] ... now loaded: " + loaded);
        this.xNext = x2 + 1;

        if (this.xNext > this.xEnd)
        {
            this.xNext = this.xStart;
            this.zNext = z2 + 1;
        }
    }
}
