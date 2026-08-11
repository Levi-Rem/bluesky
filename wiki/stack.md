# The stack module
BlueSky gives complete control over the simulation through its own 'scripting language' called [TrafScript](https://www.researchgate.net/profile/Jacco_Hoekstra2/publication/304490055_BlueSky_ATC_Simulator_Project_an_Open_Data_and_Open_Source_Approach/links/5771118408ae6219474a345d.pdf). The stack is the module that parses these commands. A complete list of built-in stack commands can be found [[here|Command Reference]].

Stack commands can come from four sources:
* From scenario files. These consist of a time-stamped list of TrafScript commands, which together can be run as a complete scenario.
* From network communication to/from BlueSky. BlueSky can be connected over TCP to send it simulation commands. See for an example [[here|Connecting external applications to BlueSky]].
* Directly from the interface. The BlueSky interface has a command line, that can be used to interact with the simulation using TrafScript commands.
* From inside a plugin, using the `stack()` function defined below.

## Construction of functions in the stack
By default, the stack contains a [[large list|Command Reference]] of functions. In addition, more functions can be added to the stack from [[BlueSky plugins|plugin]]. In your plugin, functions and methods can be registered as stack functions using the `command` and `commandgroup` decorators:

```python
from bluesky.core import Entity
from bluesky.stack import command
from bluesky import traf


class Example(Entity):
    @command
    def npassengers(self, ac: 'acid', n: int = None):
        ''' Set the number of passengers for an aircraft.

            Arguments:
            - ac: Callsign of the aircraft
            - n: Number of passengers (optional. When no number is given, current number of passengers is printed)
        '''
        if n is None:
            return True, f'Aircraft {traf.id[ac]} has {self.n[ac]} passengers'
        self.n[ac] = n
        return True, f'Number of passengers for aircraft {traf.id[ac]} set to {self.n[ac]}'
```

### The resulting stack function:
When used without arguments, the `command` decorator creates a stack function with the command name set equal to the name of the python function/method that is decorated. The above example thus creates a stack function called `NPASSENGERS`. If a different name is required this can be indicated as argument to `command`:
```python
    @command(name='SETPAX', aliases=('PAX', 'PAXCOUNT'))
    def npassengers(self, ac: 'acid', n: int = None):
```
As shown in the above example, additional command aliases can be indicated as well, using a tuple of strings.

When present, the docstring of a function/method (indicated with the triple quotes at the beginning of the function) will be used as a help text in BlueSky: For the above example, when a user types `HELP NPASSENGERS`, the text in the docstring is printed in the text area of the BlueSky window.

### Specifying argument types
In BlueSky, commands that come from scenario files, from the command line, or from the network, are formatted in plain text. The Stack decodes these plain-text arguments into the required function arguments, but of course it needs to know what to expect for each argument. This is indicated for each argument of a stack function using _argument annotations_ (the colon (:) notation shown also in the example). Here, basic type annotations (`int`,`float`,`str`,`bool`) are accepted, as well as string-annotations of BlueSky-specific argument types (see table below). In addition, alternative types can be indicated with a slash ('/'), and arguments can be made optional by providing a default value (=). In the `NPASSENGERS` example above, the first argument is mandatory, and the second is optional. The table below describes the argument types recognised by the BlueSky stack.

Type|Resulting value|description
----|---------------|-----------
txt |Upper-case word|The most basic argument type. No parsing is performed. Argument ends at the first space or comma.
word |case-sensitive word| Same as txt, but not converted to all uppercase.
string|Case-sensitive string of all arguments|Special case: arguments are not parsed separately. Instead the whole argument string is passed as a whole to the function. Used, for instance, for the ECHO command.
float|Floating-point value|Basic numerical (floating-point) values.
int|Integer value|Basic numerical (integer) values.
bool/onoff|Boolean|Basic boolean (true/false) value. Parses True/False, ON/OFF, Yes/No, 1/0.
acid|Aircraft index| Parses an aircraft callsign, returns its index in the Traffic attribute vectors.
wpinroute|string|Looks for a waypoint in the route of the currently addressed aircraft (most often this aircraft is also referenced explicitly in the same function).
wpt|string|Returns a formatted waypoint name, based on a number of possible position arguments. See _latlon_ for possible position inputs.
latlon|(lat,lon) float vector|Parses a number of position arguments.<br>Valid position texts with examples: <br>**lat/lon**: N52.12,E004.23,N52'14'12',E004'23'10 <br>**navaid/fix**: SPY,OA,SUGOL <br>**airport**: EHAM <br>**runway**: EHAM/RW06 LFPG/RWY23 <br>**callsign**: KL204
spd|speed in m/s (float)|Parses speed inputs, automatically distinguishing between mach and knots.
vspd|Vertical speed in m/s (float)|Parses vertical speed, and converts from FPM to m/s.
alt|Altitude in meters (float)|Parses altitude, accepts feet and flight levels (e.g., FL300).
hdg|Heading in degrees (float)|Parses heading. Accepts true (090T) and magnetic (120M).
time|Time in seconds (float)|Parses time. Accepts seconds, or time notation (12:30:00).
color|A named colour, or an RGB tuple.

If you need to parse an argument whose type is not listed here, either use the `'txt'` type and parse it yourself, or propose a new argument type for the stack.



## Stack functions:

### get_scenname()
This function returns the name of the currently running scenario. This corresponds either to the name given by the [[SCEN]] command, or otherwise the filename of the scenario that was loaded.

### stack(cmdline)
Stack a TrafScript command given by `cmdline`. For example:

```python
from bluesky import stack
stack.stack('PAN KLAX;ZOOM 2')
```

Puts the command on the stack to pan the radarscreen to LA airport, and set the zoom to two. This will be executed the next time the stack is updated.
