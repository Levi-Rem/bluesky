BlueSky comes in two graphical flavours: Qt+OpenGL (the default) and PyGame (for older systems). If you followed the [[installation instructions|Installation]] you should be ready to go. You can check whether your installation is complete and capable of running BlueSky by running the BlueSky check script:

    python check.py

If there are missing packages you should install them. The instructions for Anaconda users can be found in the [[installation chapter|Installation]].

When successful, you should see something like:

    This script checks the availability of the libraries required
    by BlueSky, and the capabilities of your system.
    
    Checking for numpy               [OK]
    Checking for scipy               [OK]
    Checking for matplotlib          [OK]
    Checking for pyqt                [QT5]
    Checking for pyopengl            [OK]
    OpenGL module version is         [3.1.1a1]
    Checking GL capabilities         [OK]
    GL Version at least 3.3          [OK]
    Supported GL version             [4.1]
    Checking for pygame              [OK]
    
    You have all the required libraries to run BlueSky.
    You can use both the QTGL and the pygame versions.
    Checking bluesky modules
    Using Qt5 for windows and widgets
    Using BlueSky performance model
    Successfully loaded all BlueSky modules. Start BlueSky by running BlueSky.py.

You can now start BlueSky by running:

    python BlueSky.py

If this is the first time you run BlueSky, it will generate a configuration file `settings.cfg` in the root BlueSky folder, with all of the default settings. Once created you can modify startup settings in this file. 

The above method starts BlueSky with the default Qt+OpenGL-based gui. If for some reason this version has issues on your computer you can also try running an alternative PyGame-based gui, by running:

    python BlueSky_pygame.py

The first time you run the Qt+OpenGL version of BlueSky it will generate visualisation data for things like airport layouts. This generally takes a couple of minutes.

Congratulations, you have BlueSky running. To get the most out of BlueSky, have a look at:
* [[Command line options]]
* [[Basic Operation]]
* [[Data Logging]]
* [[Batch Simulation]]
* [[Network Communication]]
* [[Command Reference]]
* [[Tutorials]]
* [[Troubleshooting]]