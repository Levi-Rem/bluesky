# Basic Operation
Bluesky, being open-source software, is completely customizable and suitable for ATM-research. Many different functionalities have been developed by scientific contributors. Several of the existing key-features are introduced below.

## Interface
Two user interfaces are available - one built with the Pygame library and the other built on Qt + OpenGL. The key difference is whether the visual output is computed by the CPU (Pygame) or on the graphics card (Qtgl). When [[running Bluesky for the first time|Running Bluesky]], the user is asked which interface to use - this choice is stored in the configuration text file _settings.cfg_. A specific choice for Pygame or Qtgl can also be made by running _bluesky_pygame.py_ or _bluesky_qtgl.py_


The simulation screen contains a mercator projection of earth, with a top-down view of all aircraft. Nations borders, airports and navaids are indicated on the screen.

The same functional elements are present in both interfaces. The most important element is the command window (discussed in the paragraph below). Buttons for time control can be found, as well as switches for display of airports, navaids, aircraft labels etc.


## Command Window
The command window is the main interface between the user and the internal functions of Bluesky. This is where the user can enter [[commands | Command Reference]] to set up simulations, or to change them in runtime. Commands are entered as text, with arguments separated by spaces. The [[HELP]] command can be used to find more information about the specific commands.

## Scenario Files
Scenario files contain lines of commands for the command window. Additionally, each command is accompanied by a specific time stamp at which to execute it. Scenario files will therefore yield the exact same results when executed multiple times. Scenario files can be opened by Bluesky with the [[IC]] command.
The syntax of scenario files is explained in detail on [[this page|Scenarios]].

## Time Management
Simulations in Bluesky are standard performed in real-time, meaning that the elapsed time on screen is equal to the elapsed time in the simulation. Fast-time modus is also available, where a constant timestep is applied and the simulations are performed as fast as possible.

It is possible to have multiple simulations running in parallel in Bluesky. To do this, different simulation nodes can be created and each node is assigned processor power. During simulation, the results of a single node are displayed on the screen. It is possible to switch this display between nodes.

## Aircraft movement
Aircraft are the core of the ATM simulator. Different steering strategies are therefore available, to simulate different flight styles.

### Waypoints
Aircraft can be simulated to move through waypoints in the air, using LNAV. VNAV is also possible. Waypoints are defined on the hand of navigational beacon data, or as specific coordinates. The autopilot settings (such as altitude and speed) are specified for each leg of the flight plan. 

### Conflict resolution
Detection of future losses of airborne separation can be performed in Bluesky. Different strategies to [[resolve|RESO]] these airborne conflicts can be implemented. These strategies can originate from centralised or decentralised separation assurance responsibility. 

It is possible to combine waypoints and conflict resolution in a single simulation. If possible, aircraft follow their flight plans. But if necessary, conflict resolution has more priority.

### Ground traffic
Taxiing of aircraft on airports can be a part of Bluesky. Taxilanes of many airports over the world are present in the software.

## Performance
Bluesky can compute performance characteristics of aircraft in Bluesky. Based on the type of aircraft, statistics such as engine type and gross empty weight can be collected and performance computations can be enabled.

The results of the performance computations contain thrust limit settings, crossover altitude, fuel consumption and more. 

An internal open-source performance model is provided in Bluesky and holders of BADA-licences (Base of Aircraft Data) can also choose to use perfomance data from those models.

## Wind & weather
Bluesky can simulate [[wind]] for the aircraft. A wind vector field can be designed such that threedimensional wind profiles can be computed at all locations in the simulation. Turbulence in flights may also be added. Aircraft counteract for any disruptions caused by the winds.

## Experiments
Being a scientific ATM simulator, Bluesky is optimized for performing experiments and storing the results. 

### Aircraft centered, track up, navigation display
A navigation display can be displayed on the screen with the ND command, showing the situational awareness from the point of view of a specified aircraft. This view is useful for human-in-the-loop experiments that aim to simulate complex air traffic scenarios.

### Data Logging
A variety of options is available for data logging in Bluesky. Instantaneous logs that save snapshots of the traffic situation every time, as well as event logs that may be used to save data for example at the end of flights, the start of conflicts and possible losses of separation.

### Experiment Regions
[[Experiment regions|AREA]] can be defined in Bluesky, that allow the user to focus his/her research to all aircraft/events that occur within the region. Aircraft that leave those regions are deleted automatically. Regions can be three-dimensional, having an upper and lower bound.

[[Back to the homepage|Home]]