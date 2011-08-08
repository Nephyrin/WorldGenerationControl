Readme
=====================
ForcegenChunks is a super simple Bukkit plugin to allow admins to pre-generate
sections of their world for various reasons. It ensures proper tree/ore
generation and neither locks up the server nor requires massive amounts of ram.

Installing
---------------------
Put ForcegenChunks.jar in your bukkit server's plugins directory

Using
---------------------
The /forcegenchunks (or /forcegen) command can be entered in the server console
or by any server Op. It doesn't use permissions, but I may add this if there is
demand for it.

The syntax is:

> /forcegenchunks WorldName StartX StartZ EndX EndZ [maxLoadedChunks]

Note that all coordinates are in *chunk coordinates*, not world coordinates. A
chunk is 16 blocks, so -10,-10 in chunk coordinates is about -160,-160 in
in-game coordinates

The maxLoadedChunks argument controls the maximum number of chunks that should
be tolerated in ram until the plugin stops generating and waits for some to
unload. It defaults to the number of chunks loaded when generation starts + 800.
On an empty server this would be ~1400 chunks. Setting this higher increases ram
requirements, but can improve generation time.

### Example

> /forcegenchunks OurBeautifulWorld -50 -50 50 50

Would generate from -50,-50 to 50,50, which is roughly -800,-800 to 800,800 in
in-game coordinates.

Compiling
---------------------
> cd src
> javac -cp .:/path/to/bukkit net/pointysoftware/forcegenchunks/ForcegenChunks.java
> zip -r ForcegenChunks_build.jar .

My apologies for not having a proper build script.

Contact
---------------------
john@pointysoftware.net

License
---------------------

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
