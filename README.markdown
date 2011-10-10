WorldGenerationControl 2.1
=================
Formerly ForceGenChunks
*Which is a confusing ass name so I changed it*

This is a very simple plugin to allow you to pre-generate a region of your world. It does not affect already generated
regions. As of 2.0, it can also repair lighting of existing regions.

Features
-----------------
- Generate arbitrary regions by coordinates
- Queue multiple generations
- Various speed settings to control lag vs generation time
- Works on a live server
- Doesn't lock up the server (unless you use the /allAtOnce option)
- Low ram usage, works on servers with 1gig of memory.
- Generates trees & ore, not just land
- Can generate valid lighting
- Can force regenerate lighting on existing chunks, to fix light issues.
- Bugs/Quirks
- Generating land and light take lots of CPU - nothing the plugin can do to prevent that! If you don't want to lag a
  live server, use the /slow or /veryslow modes.
- Generated regions wont have proper lighting until a player explores them, unless you specify the /lighting option,
  which is very slow. See Notes on Lighting below.

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

- /allAtOnce - Don't pause between steps, generate everything requested all at once. This will lock up the server until
  the generation completes, but will get the job done quickest.
- /veryFast, /fast, /slow, /verySlow - Adjust speed. Has no effect if /allAtOnce specified. Normally the plugin will
  cause mild lag while generating usually doing around 500ms of work per three seconds. Raising the speed with fast or
  veryfast will cause more lag but speed up the generation, slow or veryslow will reduce lag while increasing generation
  times. Veryfast will cause a lot of lag. Veryslow will cause almost no lag, but will take something like 5x longer.
- /lighting - Generate lighting for the loaded chunks. See **Notes on Lighting** below!
- /lighting:extreme - Generate lighting for the loaded chunks using a much more aggressive method. This is primarily
  useful in conjunction with /lightExisting to repair glitched light areas.
- /lightExisting - Also generate lighting for already-generated chunks in the specified region. Useful for fixing areas
  with corrupt lighting, or lighting areas that haven't been explored yet.
- /verbose - Print detailed timing info while generating. Doubles the amount of spam the plugin prints!
- /quitAfter - Shutdown the server once this (and any other pending generations) are complete. See the Using in a Script
  section below.

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

Notes on Lighting
-----------------

By default, the plugin wont generate lighting info for the generated regions. This is also the default for minecraft -
chunks are only lit for the first time when a player wanders near, so normally this is not a problem.

However, if you're generating world maps with Minecraft Overviewer or something similar that includes shadows, OR if you
want to save the server CPU power later when players are exploring, you may want the new regions to be lit properly. For
that, specifying /lighting will cause valid light to be generated. However, due to the way the server works,
forced-lighting takes way more cpu power than just generating the land! So if generation time is a concern, think twice.

There is also /lightExisting, which will re-calculate the lighting on existing chunks in the specified area (in addition
to new chunks). This is useful for lighting chunks that were generated previously without lighting, or fixing chunks
with buggy lighting.

Finally, there is /lighting:extreme. This takes a more aggressive approach to forcing the lighting of a chunk, and is
primarily useful in conjunction with /lightExisting to fix areas with buggy/bad lighting that /lighting alone wont
tackle. This will make lighting take twice as long, so only use it if you need it.

Using in a Script
-----------------

The /quitAfter option lets this plugin be used as part of a script. For example, some users like to generate maps for a
lot of random seeds to share with the community or post on /r/minecraft for delicious karma. Because bukkit/java freaks
out when EOF is encountered in input, the proper way to do this would be something like:

> echo "gencircle 1000 TestWorld 0 0 /allatonce /quitafter" | cat - /dev/full | java -jar craftbukkit-0.0.1-SNAPSHOT.jar

Download
-----------------
https://github.com/downloads/Nephyrin/WorldGenerationControl/WorldGenerationControl_v2.1.jar

Source
-----------------
https://github.com/Nephyrin/WorldGenerationControl

ChangeLog
-----------------
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
