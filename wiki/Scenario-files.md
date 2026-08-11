In this tutorial we will introduce scenario files, which can be used to run a simulation repeatedly.

# Typing a scenario file
Let's say that we designed a set of commands that construct a traffic situation that we want to run multiple times, to test its performance under different circumstances:

    CRE KL001, F27, 51.890958,5.177844, 300, FL100, 170
    TRAILS ON
    ADDWPT KL001 FLYOVER
    ADDWPT KL001 WOODY
    ADDWPT KL001 RIVER
    FF

The resulting flight plan for this set of commands looks like the following:

IMAGE

If we would have to enter these commands every time in the Command Line, this could be a huge effort - specifically for larger sets. We can streamline this process by making use of scenario files.

A scenario file is a text file with the extension **.scn**. They are stored in the folder bluesky/scenario. Along with your copy of BlueSky came a collection of various scenario files, which are already present. You can edit these files with any available text editor, such as notepad.

In a scenario file, each text line corresponds to an entry in the Command Line, preceded by a time stamp, when it should be executed. In our case, since we do all route planning at the start, the time stamp is "0:00:00.00>" - note the precence of the > sign. But let's say that we only want to start the fast-time simulations after 5 seconds, we could change the time stamp for this command, to be "0:00:05.00>". 

Note that, similar to python programming, it is possible to add comment lines, which are not interpreted by bluesky. We can do this by starting the line with the # sign.

Including two comments, our scenario file could look like this:

    # SCN file to create a KL001 aircraft fying over checkpoint woody
    0:00:00.00>CRE KL001, B737, 51.890958,5.177844, 300, FL100, 170
    0:00:00.00>TRAILS ON
    0:00:00.00>ADDWPT KL001 FLYOVER
    0:00:00.00>ADDWPT KL001 WOODY
    0:00:00.00>ADDWPT KL001 RIVER
    # Start fast forward after five seconds of normal flight.
    0:00:05.00>FF

Let's save the scenario with the name woody.scn.

# Opening scenario files
We can open our scenario with the command [[IC]].

    IC WOODY

This command will tell bluesky to open the scenario woody.scn. Let's type it, and observe the experiment.
We can use IC WOODY several times, and the scenario will every time be reloaded and performed from the start. If we like, we can also use the following command to reload:

    IC IC

This command will reload the last used scenario file - even across multiple instances of BlueSky. So if we shut down the version of BlueSky and we start it again, IC IC will immediately open our woody.scn file.

# Saving scenario files

As discussed above, scenarios can be created with a text editor. Next to that, a second method of building scenarios exists. If we type in the Command Line:

    SAVEIC WOODY2

Now, a scenario with the name woody2.scn is saved. We can check the bluesky/scenario folder to verify that the file is indeed there. We can also try to open the file:

    IC WOODY2

This opens the recent scenariofile. What we can see is, that the scenario saves the state of the current situation - so at the moment when we enter [[SAVEIC]] in the command line, the traffic situation at that moment is saved and stored.

Next tutorial: [[Editing Flight Plans]]