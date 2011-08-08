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

import org.bukkit.Server;
import org.bukkit.World;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import org.bukkit.scheduler.BukkitScheduler;

public class ForcegenChunks extends JavaPlugin implements Runnable
{
    private final static String VERSION = "1.0";
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
    // If more than this many blocks are loaded,
    // don't load more until some unload
    private static final int MAXCHUNKS = 1000;

    private ArrayList<ChunkXZ> ourChunks = new ArrayList<ChunkXZ>();
    private int taskId = 0;
    private World world;
    private int xStart;
    private int xEnd;
    private int zStart;
    private int zEnd;
    private int xNext;
    private int zNext;
    private boolean waiting;
    
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
        if (!sender.isOp())
        {
            sender.sendMessage("[ForcegenChunks] Requires op status.");
            return true;
        }
        if (args.length != 5) return false;
        if (this.taskId != 0)
        {
            sender.sendMessage("[ForcegenChunks] Generation already in progress.");
            return true;
        }
        World world = getServer().getWorld(args[0]);
        if (world == null)
        {
            sender.sendMessage("[ForcegenChunks] World \"" + args[0] + "\" does not exist.");
            return true;
        }
        int xStart = Integer.parseInt(args[1]);
        int zStart = Integer.parseInt(args[2]);
        int xEnd   = Integer.parseInt(args[3]);
        int zEnd   = Integer.parseInt(args[4]);
        if (xEnd - xStart < 1 || zEnd - zStart < 1)
        {
            sender.sendMessage("[ForcegenChunks] xEnd and zEnd must be greater than xStart and zStart respectively.");
            return true;
        }
        int num = (xEnd - xStart + 1) * (zEnd - zStart + 1);
        sender.sendMessage("[ForcegenChunks] Starting generation of " + num + " Chunks (" + (num * 16) + " blocks.)");
        this.generateChunks(world, xStart, xEnd, zStart, zEnd);
        
        return true;
    }

    public boolean generateChunks(World world, int xStart, int xEnd, int zStart, int zEnd)
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
        
        this.world = world;
        this.xStart = xStart;
        this.xNext = xStart;
        this.zNext = zStart;
        this.xEnd = xEnd;
        this.zStart = zStart;
        this.zEnd = zEnd;
        this.waiting = false;
        this.taskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, this, 50, 50);
        return true;
    }

    private int freeLoadedChunks()
    {
        // requesting an unloaded chunk wont always cause it to unload,
        // causing misc chunks to pile up,
        // so we keep the whole list we loaded and file unload requests for all
        // of them each frame.
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
        return ourChunks.length;
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
            }
            else
            {
                System.out.println("[ForcegenChunks] Finished generating, " + loaded + " chunks currently loaded.");
                getServer().getScheduler().cancelTask(this.taskId);
                this.taskId = 0;
            }
            return;
        }

        if (loaded > this.MAXCHUNKS)
        {
            if (!this.waiting)
            {
                System.out.println("[ForcegenChunks] More than " + this.MAXCHUNKS + " chunks loaded (" + loaded + "), waiting for some to finish unloading");
                this.waiting = true;
            }
            return;
        }
        this.waiting = false;

        int x1 = this.xNext - 2;
        int x2 = Math.min(x1 + this.BLOCKSIZE - 1, this.xEnd + 2);
        int z1 = this.zNext - 2;
        int z2 = Math.min(z1 + this.BLOCKSIZE - 1, this.zEnd + 2);

        System.out.println("[ForcegenChunks] Loading " + ((x2 - x1 + 1) * (z2 - z1 + 1)) + " chunks from ["+x1+","+z1+"] to ["+x2+","+z2+"], " + loaded + " currently loaded.");

        for (int nx = x1; nx <= x2; nx++)
        {
            for (int nz = z1; nz <= z2; nz++)
            {
                if (!world.isChunkLoaded(nx, nz))
                {
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
