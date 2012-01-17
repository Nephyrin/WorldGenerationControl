WorldGenerationControl 2.6
=================
Formerly ForceGenChunks

*Which is a dumb name so I changed it*

This is a very simple plugin to allow you to pre-generate a region of your world. It does not affect already generated
regions. As of 2.0, it can also repair lighting of existing regions.

Features
-----------------
- Generate arbitrary regions by coordinates
- Queue multiple generations
- Various speed settings to control lag vs generation time
- Works on a live server
- Doesn't lock up the server (unless you use the /allAtOnce option)
- Low ram usage, works fine on servers with 1gig of memory.
- Generates trees & ore, not just land
- Can generate valid lighting
- Can force regenerate lighting on existing chunks, to fix light issues.

Bugs/Quirks
-----------------
- Generating land takes a lot of CPU - nothing the plugin can do to prevent that! If you don't want to lag a
  live server, use the /slow or /veryslow modes.
- Designed for servers with at least 1gig of memory allocated to them (-Xmx1024M). Servers with lower memory limits
  may encounter heap exceptions. If you have less than 1gig of memory, try using /slow or /veryslow modes.

Commands
-----------------

The /genregion (or /generateregion) command can be entered by any server op, the server console, or anyone with the
appropriate permission (see below).

The syntax is:

> /genregion WorldName StartX StartZ EndX EndZ

For circles (/generatecircularregion or /gencircle):

> /gencircle Radius [WorldName xCenter zCenter]

The world and center coordinates for the circle command are [optional] if you are a player (not the console). It will
default to your current/world position.

All coordinates are in normal, in-game coordinates - but will be adjusted to the nearest chunk boundary (inclusive).

Options
-----------------

All commands can have options applied to them like so:

> /gencircle 1600 MyWorld 0 0 /fast /lighting

Options are not case sensitive. The available options are:

- /allAtOnce - Don't pause between steps, generate everything requested all at once. This will make the server basically
  unusable until the generation completes, but get the job done fastest. Very useful in conjunction with /onlyWhenEmpty
- /veryFast, /fast, /slow, /verySlow - Adjust speed. Has no effect if /allAtOnce specified. Normally the plugin will
  cause mild lag while generating usually doing around 700ms of work per three seconds (depending on the server CPU).
  Raising the speed with fast or veryfast will cause more lag but speed up the generation, slow or veryslow will reduce
  lag while increasing generation times. Veryfast will cause a lot of lag. Veryslow will cause almost no lag, but will
  take something like 10x longer.
- /forceKeepUp - Force the server to 'keep up' with garbage collection and chunk saving.
  In particular, 1.9 Has a new async chunk saver, which appears to be rate limited, meaning it may not keep up with
  fast generations. This option forces the chunks to be saved immediately, rather than on a separate thread.
  You should use this option if you notice the plugin spending a lot of time "waiting for the server to catch up" and
  don't mind the minor increase in CPU usage caused by forcing it to keep up. /allAtOnce mode will always use this
  option.
- /lighting:none - Skip generating light data for loaded chunks. See **Notes on Lighting** below.
- /lighting:force - Reset and regenerate lighting for all chunks we pass over, even if they already have lighting data.
  Useful for fixing areas with corrupt lighting.
- /verbose - Print detailed timing info while generating. Doubles the amount of spam the plugin prints!
- /quitAfter - Shutdown the server once this (and any other pending generations) are complete. See the Using in a Script
  section below.
- /onlyWhenEmpty - Only do generating when the server is empty. The plugin will pause generation and wait until players
  leave, allowing you to generate lots of land without worrying at all about the extra CPU. You can use this in
  conjunction with /allAtOnce to have the server use 100% when it is empty towards generating land, without causing any
  lag when players are online.
- /destroyAndRegenerateArea - As the name says, this will **delete and destroy all land** in the area given, generating
  new land instead. I cannot stress enough how this will **delete your world** (or the specified area of it at least),
  so please understand what you're doing and make backups!

Permissions
-----------------

Permissions are optional. Server ops and the server console can always use generation commands. However, if you have
permissions installed, the following permissions are used:

- worldgenerationcontrol.generate - Allows user to queue generations. Implicitly grants
  worldgenerationcontrol.statusupdates
- worldgenerationcontrol.statusupdates - Allows user to see generation progress messages. These can be quite spammy,
  explicitly removing this permission will cause only the server console to see these messages.

Examples
-----------------

> /genregion OurBeautifulWorld -50 -50 50 50

Would generate from -50,-50 to 50,50 in game coordinates. Simple!

> /genregion "Bob and Sam's \"Awesome\" World" -50 -50 50 50

The same command for world: Bob and Sam's "Awesome" World. Quotes can be placed around world names with spaces, world
names with actual quotes in them can be escaped with backlashes.

> /gencircle 1000 /fast /verbose /lighting

Would generate a 1000 block radius circle around the issuing player, using the /fast speed setting, with verbose output,
with lighting!

> /gencircle 1000 MyWorld 100 100 /veryslow

Would generate a 1000 block radius centered in MyWorld at 100x, 100z, using the veryslow speed setting so as not to
cause lag.

> /gencircle 10000 MyWorld 100 100 /allAtOnce /onlyWhenEmpty

Would generate a HEUG circle centered at MyWorld 100,100 at max possible speed, but pause the generation when players
join. Useful if you want your server to use 100% on generating when it would otherwise be idle.

Notes on Lighting
-----------------

By default, minecraft only generates lighting info for a chunk when it is first approached by a player. This is fine,
but if you want to generate an external map with something like Minecraft Overviewer, it means the areas players haven't
visited will only have 'fast' lighting, with pitch black shadows.

To fix this, by default, WorldGenerationControl forces new chunks to have lighting info, as if a player were nearby.
This shouldn't cause any problems, but takes about 8% more CPU-time. You can skip this step with /lighting:none -- the
chunks will still be lit when a player wanders by, so this is only an issue for external tools as mentioned above.

There is also /lighting:force, which will force-generate lighting for all chunks it passes over (even those already
generated and with proper lighting), which is useful for making Minecraft recalculate the lighting in areas with
glitched shadows.

Using in a Script
-----------------

The /quitAfter option lets this plugin be used as part of a script. For example, some users like to generate maps for a
lot of random seeds to share with the community or post on /r/minecraft for delicious karma. Because Bukkit/java freaks
out when EOF is encountered in input, the proper way to do this would be something like:

> echo "gencircle 1000 TestWorld 0 0 /allatonce /quitafter" | cat - /dev/full | java -jar craftbukkit-0.0.1-SNAPSHOT.jar --nojline

Download
-----------------
https://github.com/downloads/Nephyrin/WorldGenerationControl/WorldGenerationControl_v2.6.jar

Source
-----------------
https://github.com/Nephyrin/WorldGenerationControl

ChangeLog
-----------------
- 2.6
    - Updated to work with 1.0.1 / 1.1 builds.
- 2.5
    - /forceKeepUp now keeps up on garbage collection as well
    - Renamed /forceSave to /forceKeepUp to reflect that it also keeps up on garbage collection.
    - When the server is floating at >80% memory for too long, try invoking a GC. This fixes the issue where the default
      Java GC options would have it float at 80% memory forever as long as nothing forced it to catch up.
    - Cleaned up /verbose output a little.
    - Check if we have <200Megs free in addition to <20% free, prevents out of memory errors on 512Meg ram servers
      (which this plugin doesn't technically support)
- 2.4
    - Add /forceSave workaround for 1.9's AsyncChunkLoader silliness.
    - /allAtOnce now implies /forceSave
    - Make the memory limit a little more conservative to ensure we don't hit GC overhead errors on low memory servers
    - Once the memory limit has been hit, wait for ram to decrease below the limit by at least 10% before resuming
    - Tweak the NextTickList bug workaround - instead of flushing the list, just ensure it stays below a threshold.
      Should fix the minor lag spikes from the fix.
    - Minor optimizations
- 2.3 - Death to Memory Issues edition
    - Includes a fix for CraftBukkit's poor NextTickList handling, allowing high generation speeds on low memory
      servers
    - Invokes the GC directly when running close to memory limits to prevent GC Overhead errors
    - Takes a break if memory usage gets unacceptably high to let the server catch up.
- 2.2
    - Lighting now talks directly to CraftBukkit and is now approximately 115 times faster. Yep.
    - Because lighting has gone from taking up 92% of processing time to a trivial amount, the plugin no longer
      splits lighting/generating/saving into separate steps.
    - Because lighting is now very fast, /lighting is now the default. It can still be disabled with /lighting:none
    - /lighting:extreme is now named /lighting:force, and only eats a little bit more CPU.
    - Added /destroyAndRegenerateArea - which regenerates all chunks in the region. Beware!
    - Removed /lightExisting, lighting is now only done as-needed either way, and /lighting:force can be used
      to try and fix corrupt light areas.
    - Fixed /gencircle being centered incorrectly when called by a player without coordinates
    - Improved accuracy of some fuzzy math logic to ensure only requested areas are generated
    - Added /onlyWhenEmpty - this causes the plugin to only do its work when the server is empty, pausing and resuming
      as needed when players join/leave.
    - Minor speed/overhead improvements
- 2.1
    - Fixed issue with /allAtOnce being too aggressive on lighting, causing memory issues on low-memory servers.
    - allAtOnce mode now returns into the server briefly between ticks, allowing other commands (such as /cancelgen) to
      be run.
- 2.0
    - Name changed to WorldGenerationControl from ForceGenChunks
    - Large rewrite
    - Supports Minecraft 1.8's lighting methods
    - Added options: lighting, speed, verbose, quitafter
    - Added permissions support
    - Normal speed is much, much less laggy. Speed options provide control over lag during generation.
    - No longer trusts server to cleanup chunks, manages process through lighting step and cleans up chunks directly.
      maxLoadedChunks removed as a result, even on high speed settings the plugin will never load more than 1024 chunks
      into memory.
    - Cleaned up and improved status messages.
    - Use block coordinates instead of chunk coordinates
    - Support queueing multiple generations
    - Better in-plugin API for other plugins to interact with this one.
    - Other things I likely forgot
- 1.4
    - Added progress % to generating status messages.
- 1.3
    - Added support for quotes and spaces in worldnames via quotes and escape sequences.
    - To generate for map: Bob's "Wonderful" Funland
    - /forcegen "Bob's \"Wonderful\" Funland" -10 -10 10 10
    - Fixed the "Invalid world name" error message giving the wrong world name for /forcegencircle commands.
    - Separated generation and cleanup phases - plugin now prints a message when generation is complete, and a second
      message later when cleanup is complete.
    - New generations can be started even if cleanup isn't done, the remaining cleanup will just be merged with the new
      task's cleanup.
    - Removed warnings about players being in the world, with above changes there is no harm in them being there.
    - Added some colors to plugin messages. Pretty.
    - Plugin messages now show who did what.
    - Tested with recommended builds 1000 and 1060.
- 1.2
    - Added /forcegencircle
    - Players who use the command now see the progress, not just the 'generation started' message.
    - A few minor text tweaks.
- 1.1
    - Wait for chunks to unload, instead of assuming our unload requests succeed. Fixes a 'leak' of loaded chunks in
      large generations, chunks that never unload until a reboot.
    - Add a optional maxLoadedChunks argument to /forcegen, setting this higher reduces the time spent waiting for old
      chunks to unload, but causes more chunks to reside in ram, increasing memory usage.
    - Added /cancelforcegen, to cancel generation in progress.
- 1.0
    - Release

Compiling
-----------------
> cd src

> javac -cp .:/path/to/bukkit net/pointysoftware/worldgenerationcontrol/WorldGenerationControl.java

> zip -r WorldGenerationControl_build.jar .

My apologies for not having a proper build/ant/whatever script.

Contact
-----------------
john@pointysoftware.net

License
-----------------

   WorldGenerationControl - Bukkit chunk preloader 
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
