Start BlueSky by running BlueSky.py as a Python file. BlueSky tries to open the file settings.cfg, which is located in the root folder, next to BlueSky.py. The first time you run BlueSky, the settings.cfg file will not exist yet, and you will see the following message in your interpreter:

> No config file settings.cfg found in your BlueSky starting directory!
> 
> This config file contains several default settings related to the simulation loop and the graphics.
> 
> A default version will be generated, which you can change if necessary before the next time you run BlueSky.
> 
> BlueSky has several user interfaces to choose from. Please select which one to start by default.
> 
> You can always change this behavior by changing the settings.cfg file.
> 
> 1. QtGL:    This is the most current interface of BlueSky, but requires a graphics card that supports at least OpenGL 3.3.
> 
> 2. Pygame:  Use this version if your pc doesn't support OpenGL 3.3.
> 
> Default UI version: 
> 

Make a choice for the standard GUI for BlueSky. If your PC supports OpenGL 3.3, the QtGL is preferred.

Note: if you prefer to run the other GUI, regardless of your settings.cfg file, you can also run BlueSky_pygame.py or BlueSky_qtgl.py. 

## The file Settings.cfg
After you make a choice for the GUI, a settings.cfg file is created automatically, and BlueSky is prepared to run. This can take a minute or so. You can see the progress printed on the screen. The next time that you start BlueSky, this is not necessary anymore. 

Settings.cfg is a text file which you can open in any text editor (such as notepad). It looks like this:
![Main](images/settings.png)

You can change the parameters to alter the bluesky settings. The first setting is the choice between pygame or qtgl, which you can change if you prefer to alter your standard way of running BlueSky. Do not remove the apostrophes! ('')
 
The second setting is the choice for the performance model. Bluesky comes with a built-in open source aircraft performance model (the standard option, denoted "performance_model = 'bluesky'"). If you possess a license to the BADA performance files, you may use "performance_model = 'bada'". 

If you use BADA: make sure that the line 'perf_path_bada' contains the path to the BADA files. Either change the path, or place the BADA data files in the folder 'data/performance/BADA'.

The remainder of the parameters in settings.cfg will be explained in more detail in upcoming tutorials.

Next tutorial: [[The BlueSky Interface]]