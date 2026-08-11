BlueSky is run without command-line arguments as follows:

    python3 BlueSky.py

In Windows this can also be achieved by double clicking on the BlueSky.py icon.
For command line options, edit a text file start-my-bluesky.txt and rename the file by changing the extension from .txt to .bat, to create start-my-bluesky.bat. This can then be double clicked to start BlueSky with optionally any desired command line options as specified below. 

When started the normal way, without extra options, two processes are created:

- One process containing the BlueSky server in combination with the gui
- and one process with a single simulation node.

Additional arguments can be passed to BlueSky to automatically start scenarios or to start BlueSky in a different mode. The possible arguments are:


Argument                       |   Description
-------------------------------|---
`--configfile <file>` |   Load settings from custom configuration file.
`--scenfile <file>`   |   Load scenario file on startup.
`-h,--help`           |   Display information on the possible command-line arguments to pass to BlueSky.
`--headless`          |   Start simulation server only, without GUI. A server started in headless mode makes itself visible through UDP discovery. This way the server becomes visible to any BlueSky client that is started afterwards.
`--client <address>`  |   Start client only, which can connect to an already running server. Note that multiple clients can connect to the same server. When no server address is specified the client will search for servers using BlueSky's UDP discovery.
`--console`           |   Start a text-based (console/terminal) client, to connect to an already running server.
`--sim`               |   Start only one simulation node.
`--detached`          |   Start only one simulation node, without networking.
`--discoverable`      |   Make simulation server discoverable. (Default in headless mode.)
