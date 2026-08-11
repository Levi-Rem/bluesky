# BlueSky TrafScript commands
BlueSky gives complete control over the simulation through its own 'scripting language' called [TrafScript](https://www.researchgate.net/profile/Jacco_Hoekstra2/publication/304490055_BlueSky_ATC_Simulator_Project_an_Open_Data_and_Open_Source_Approach/links/5771118408ae6219474a345d.pdf). The provided commands can be used in four ways:

* In scenario files. These consist of a time-stamped list of TrafScript commands, which together can be run as a complete scenario.
* For network communication to/from BlueSky. BlueSky can be connected over TCP to send it simulation commands.
* Directly in the interface. The BlueSky interface has a command line, that can be used to interact with the simulation using TrafScript commands.
* In a plugin, to control the simulation from inside the plugin.

## Command arguments
Bluesky supports different types of argument formats. Follow the hyperlinks to learn about the different options for each argument type.

* [[Aircraft ID]]
* [[Aircraft speed|Speed]]
* [[Altitude]]
* [[Heading]]
* [[Coordinates|Coordinates]]
* [[Booleans]]
* [[Time|Entertime]]

## Command reference
This page gives a complete overview of the available commands in the following table. Below it is a table that lists the commands that have synonyms.

| Command       | Description
|---------------|-------------------------------------------------------------------------
| [[ADDNODES]]  | Add a simulation instance/node
| [[ADDWPT]]    | Add a waypoint to route of aircraft.
| [[ADDWPTMODE]]| Changes the type of waypoints added with the ADDWPT command, and other parameters.
| [[AFTER]]     | After waypoint, add a waypoint to route of aircraft
| [[ALT]]       | Altitude command
| [[AREA]]      | Define experiment area
| [[ASAS]]      | Airborne Separation Assurance System switch
| [[AT]]        | Edit, delete or show alt/speed constraints at a waypoint or add any command lines to be issued when passing this waypoint in the flight plan
| [[ATALT]]     | When an aircraft reaches a given altitude, execute the given command
| [[ATDIST]]    | When an aircraft reaches a certain distance to a position, execute the given command
| [[ATSPD]]     | When an aircraft reaches a given speed (CAS), execute the given command
| [[BANK]]      | Set bank angle limit in degrees of vehicle to be used by autopilot
| [[BATCH]]     | Start a scenario file as batch simulation
| [[BENCHMARK]] | Run benchmark
| [[BOX]]       | Define a box-shaped area
| [[CALC]]      | Simple in-line math calculator
| [[CASMACHTHR]]| Changes the threshold at which a velocity value is taken as a Mach number instead of CAS [kts]. 
| [[CDMETHOD]]  | Set conflict detection method
| [[CIRCLE]]    | Define a circle-shaped area
| [[CLRCRECMD]] | Clear CRECMD list
| [[COLOUR]]    | Colour an aircraft or graphical object (area,line, etc.)
| [[CRE]]       | Create an aircraft
| [[CRECMD]]    | Add a command to the list of commands to be issued for each aircraft immediately after creation
| [[CRECONFS]]  | Create an aircraft that is in conflict with an existing aircraft
| [[DEFWPT]]    | Define a waypoint only for this run
| [[DEL]]       | Delete command
| [[DELAY]]     | Delay a command by a specified time
| [[DELRTE]]    | Delete the complete route/dest/orig
| [[DELWPT]]    | Delete a waypoint from a route
| [[DEST]]      | Set destination airport
| [[DIRECT]]    | Go direct to specified waypoint in route
| [[DIST]]      | Distance and direction calculation between two coordinates positions
| [[DT]]        | Set simulation time step
| [[DTLOOK]]    | Set lookahead time in seconds for conflict detection
| [[DTMULT]]    | Sel multiplication factor for fast-time simulation
| DTNOLOOK      | Set interval for conflict detection
| [[DUMPRTE]]   | Write route to output
| [[ECHO]]      | Show a text in command window for user to read
| [[ENG]]       | Specify a different engine type
| [[FF]]        | Fast forward the simulation
| [[FIXDT]]     | Fix the time step
| [[GETWIND]]   | Get wind at a specified position
| [[HDG]]       | Heading command
| [[HELP]]      | Show help on a command, show pdf or write list of commands to file
| [[HOLD]]      | Pause the simulation
| [[IC]]        | Revert to initial conditions of scenario
| [[IMPL]]      | Replace implementation of classes in Bluesky.
| INSEDIT       | Insert text in edit line in command window
| [[LINE]]      | Draw a line on the radar screen
| [[LISTRTE]]   | Show aircraft FMS flight plan, list its route waypoints incl/\. alt, speed (per group of 5 waypoints)
| [[LNAV]]      | LNAV switch for autopilot
| [[MAGVAR]]    | Get magnetic variation/declination at position
| [[MCRE]]      | Multiple random create of _n_ aircraft in current view
| METRICS        | Complexity metrics module
| [[MOVE]]      | Move an aircraft to a new position
| ND            | Show navigation display with CDTI
| [[NOISE]]     | Turbulence/noise switch
| [[NORESO]]    | Switch off conflict resolution for this aircraft
| [[OP]]        | Start simulation or continue after pause
| [[ORIG]]      | Set origin airport of aircraft
| [[PAN]]       | Move view to a location
| [[PCALL]]     | Call commands in another scenario file
| [[PLUGINS]]   | List all plugins, load a plugin, or remove a loaded plugin.
| [[POLY]]      | Define a polygon-shaped area
| [[POLYALT]]   | Define a polygon-shaped area in 3D
| [[POS]]       | Get info on aircraft
| PRIORULES     | Define priority rules for conflict resolution
| [[QUIT]]      | Quit program/Stop simulation
| [[RESET]]     | Reset simulation
| [[RESO]]      | Set resolution method
| [[RESOOFF]]   | Switch for conflict resolution module
| [[RFACH]]     | Set resolution factor horizontal
| [[RFACV]]     | Set resolution factor vertical
| [[RMETHH]]    | Set resolution method to be used horizontally
| [[RMETHV]]    | Set resolution method to be used vertically
| [[RSZONEDH]]  | Set half of vertical dimension of resolution zone in ft
| [[RSZONER]]   | Set horizontal radius of resolution zone in nm
| [[RTA]]       | Set required time of arrival (RTA) at a waypoint in the route of an aircraft
| [[RUNWAYS]]   | List available runways on an airport
| [[SAVEIC]]    | Save current situation as IC
| [[SCEN]]      | Give current situation a scenario name
| [[SCHEDULE]]  | Define a time when a command is executed
| [[SEED]]      | Set seed for all functions using a randomizer
| [[SPD]]       | Speed command
| [[SSD]]       | Show conflict prevention display
| [[SWRAD]]     | Switch on/off elements and background of map/radar view
| [[SWTOC]]     | Switch on/off top of climb logic in VNAV autopilot
| [[SWTOD]]     | Switch on/off top of descent logic ion VNAV autopilot
| [[SYMBOL]]    | Toggle aircraft symbol
| TAXI          | Switch on/off ground/low altitude mode, prevents auto-delete at 1500 ft
| [[THR]]       | Set throttle setting or throttle AUTO(=default)
| [[TIME]]      | Set simulated clock time| [[TRAIL]]     | Toggle aircraft trails on/off
| [[VNAV]]      | Switch on/off VNAV mode, the vertical FMS mode
| [[VS]]        | Vertical speed command
| [[WIND]]      | Define a wind vector as part of the 2D or 3D wind field
| [[ZONEDH]]    | Set half of the vertical protected zone dimensions in ft
| [[ZONER]]     | Set the radius of the horizontal protected zone dimensions in nm
| [[ZOOM]]      | Zoom display in/out, you can also use +++ or -----

## Command synonyms
To maintain full compatibility with the TMX simulator, several commands can also be called by their synonym. The following table lists the currently available synonyms.

Synonym | Original function | Description
--------|-------------------|------------
? | HELP | Show help on a command, show pdf or write list of commands to file
CLOSE | QUIT | Quit program/Stop simulation
CONTINUE | OP | Start/Run simulation or continue after pause
CREATE | CRE | Create an aircraft
DELETE | DEL | Delete command (aircraft, wind, area)
DELWP | DELWPT | Delete a waypoint from a route (FMS)
DELROUTE | DELRTE | Delete for this a/c the complete route/dest/orig (FMS)
DIRECTTO | DIRECT | Go direct to specified waypoint in route (FMS)
DIRTO | DIRECT | Go direct to specified waypoint in route (FMS)
DISP | SWRAD | Switch on/off elements and background of map/radar view
END | QUIT | Quit program/Stop simulation
EXIT | QUIT | Quit program/Stop simulation
FWD | FF | Fast forward the simulation
HEADING | HDG | Heading command (autopilot)
HMETH | RMETHH | Set resolution method to be used horizontally
HRESOM | RMETHH | Set resolution method to be used horizontally
HRESOMETH | RMETHH | Set resolution method to be used horizontally
IMPL | IMPLEMENTATION | Replace implementation of classes in Bluesky.
LOAD | IC | Initial condition: (re)start simulation and open scenario file
OPEN | IC | Initial condition: (re)start simulation and open scenario file
PAUSE | HOLD | Pause(hold) simulation
Q | QUIT | Quit program/Stop simulation
RESOFACH | RFACH | Set resolution factor horizontal (to add a margin)
RESOFACV | RFACV | Set resolution factor vertical (to add a margin)
RUN | OP | Start/Run simulation or continue after pause
SAVE | SAVEIC | Save current situation as IC
SPEED | SPD | Speed command (autopilot)
START | OP | Start/Run simulation or continue after pause
STOP | QUIT | Quit program/Stop simulation
TURN | HDG | Heading command (autopilot)
VMETH | RMETHV | Set resolution method to be used vertically
VRESOM | RMETHV | Set resolution method to be used vertically
VRESOMETH | RMETHV | Set resolution method to be used vertically
