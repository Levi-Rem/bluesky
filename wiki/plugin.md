# BlueSky plugins
The easiest way to extend BlueSky is to create a self-contained plugin. In BlueSky, a plugin can:

- Add new commands to the [[command stack|stack]], in addition to the [[standard commands|Command Reference]].
- Define [new per-aircraft states](#adding-new-traffic-states-in-your-plugin).
- Define functions that are [periodically called by the simulation](#periodically-timed-functions).
- Re-implement parts of BlueSky by [subclassing BlueSky classes](#subclassing-bluesky-core-implementations).

Inside a plugin you have direct access to the [[Traffic]], [[Simulation]], [[Screen]], and [[Navdb]] simulation objects. In a plugin, you can also import the [[stack]] module to interact with the simulation by stacking commands, import [[settings]] to get access to application-wide settings (and add your own, plugin-specific settings), and import [[tools]] to get access to BlueSky tools such as aerodynamic functions, geographic functions, datalogging, and more. The example below is also available in the `plugins` folder in the BlueSky distribution, and [here](https://github.com/TUDelft-CNS-ATM/bluesky/blob/master/plugins/simexample.py).

## Using existing plugins
To be able to use a plugin in BlueSky, put it in your `plugins` directory (in the bluesky root folder when you run BlueSky from source, or in `$home/bluesky/plugins` when you run a packaged version of BlueSky). On startup, BlueSky automatically detects the available plugins in this folder. You can enable a plugin from the BlueSky command line using the [[PLUGINS]] command. If you want to automatically load particular plugins on startup add the following to your bluesky settings file (settings.cfg, situated either in the bluesky root folder when you run from source, or in `$home/bluesky`):
```python
enabled_plugins = ['plugin_1', 'plugin_2', ..., 'plugin_n']
```
Here, `'plugin_n'` is the name you give your plugin. These names are not case-sensitive.

## Creating plugins
BlueSky plugins are basically plain python modules, which have at least an `init_plugin()` function that, in addition to your own plugin initialisation, defines and returns a specific plugin configuration dict. In addition, BlueSky can use the docstring of the module as a description text of the plugin in the BlueSky interface. Together with any other imports you may have, the first couple of lines of your plugin will look like this:

```python
""" BlueSky plugin template. The text you put here will be visible
    in BlueSky as the description of your plugin. """
# Import the global bluesky objects. Uncomment the ones you need
from bluesky import core, stack, traf  #, settings, navdb, sim, scr, tools
```

Here, the docstring should be used to describe your plugin. From BlueSky, you can import the [[navdb|Navdb]], [[traf|Traffic]], and [[sim|Simulation]] objects as inputs, [[stack]] and [[scr|Screen]] as outputs. You can import the [[settings]] module to get access to the global BlueSky settings, and to specify default values for configuration parameters of your plugin that you want stored in the central `settings.cfg` file. The [[tools]] module provides access to BlueSky functions and constants for aerodynamic calculations, geographic calculations, and data logging.

### Initialization of your plugin
BlueSky recognises a python script as a plugin if it contains an `init_plugin()` funtion, which defines and returns a configuration dict when it is called.

```python
### Initialization function of your plugin. Do not change the name of this
### function, as it is the way BlueSky recognises this file as a plugin.
def init_plugin():

    # Addtional initilisation code
```

The configuration dict specified in the init function is used to define the basic properties of the module, and to connect its update function(s). In this dict, `plugin_name` and `plugin_type` specify the name and type of the module, respectively. The plugin name can be anything, but is not case sensitive. The plugin type can be 'sim' for simulation-side plugins, or '[gui](https://github.com/TUDelft-CNS-ATM/bluesky/blob/master/plugins/guiexample.py)' for client-side plugins. 

```python
    # Configuration parameters
    config = {
        # The name of your plugin
        'plugin_name':     'EXAMPLE',

        # The type of this plugin.
        'plugin_type':     'sim'
        }
```

The `init_plugin()` ends by returning the configuration dict:
```python
    # init_plugin() should always return the config dict.
    return config
```

After the `init_plugin()` you can define the actual implementation of your plugin. Although you can make a plugin with only module-level functions, the most powerful way to create new implementations in BlueSky is by creating a new *Entity*: an object that is only created once (a singleton, like, e.g., the built-in Traffic object, the Simulation object, ...). You can specify its implementation in two ways:

- By deriving from `core.Entity`. You do this when you are implementing new functionality for BlueSky.
- By subclassing an existing implementation in BlueSky. You do this when you want to replace some functionality in BlueSky. (A good example is creating a new Conflict Resolution algorithm, by deriving from `bluesky.traffic.asas.ConflictResolution`. See the plugins/asas folder for examples).

In this example we will implement new functionality, by inheriting from `core.Entity`. We will create a class that introduces a new state to each aircraft, storing the number of passengers on board.
```python
class Example(core.Entity):
    ''' Example new entity object for BlueSky. '''
    def __init__(self):
        super().__init__()
```
## Adding new traffic states in your plugin
In the constructor of your entity you can introduce new per-aircraft states using the `settrafarrays()` method:
```python
        # All classes deriving from Entity can register lists and numpy arrays
        # that hold per-aircraft data. This way, their size is automatically
        # updated when aircraft are created or deleted in the simulation.
        with self.settrafarrays():
            self.npassengers = np.array([])
```
In the constructor, we can use Entities `settrafarrays()` to inform BlueSky that we are specifying one or more new states for each aircraft. We do this using a `with` statement.

## Detailed control over aircraft creation
An Entity class can implement a `create` function to gain more control over the initialisation of states of new aircraft:
```python
    def create(self, n=1):
        ''' This function gets called automatically when new aircraft are created. '''
        # Don't forget to call the base class create when you reimplement this function!
        super().create(n)
        # After base creation we can change the values in our own states for the new aircraft
        self.npassengers[-n:] = [randint(0, 150) for _ in range(n)]
```
In the create function, you generally start by calling the create function of the parent class, to appropriately resize the aircraft states. After that you can make changes to each state for the new aircraft.

## Periodically timed functions
In a simulation, often calculations have to be made every timestep, or at a lower periodic interval. You can indicate your own functions as periodic functions using the `core.timed_function` decorator:

```python
    @core.timed_function(name='example', dt=5)
    def update(self):
        ''' Periodic update function for our example entity. '''
        stack.stack('ECHO Example update: creating a random aircraft')
        stack.stack('MCRE 1')
```
Optional arguments to this decorator are the name by which the timer of this function becomes visible within bluesky, and the default update interval of this function. You can change this interval during the simulation with the `DT` stack command.

## Defining new stack functions
Stack functions are the core of BlueSky, and provide the means to create and control a simulation. For a list of the default BlueSky stack functions, look [[here|Command Reference]]. In a plugin, you can also create new stack commands, using the `stack.command` decorator:
```python
    @stack.command
    def passengers(self, acid: 'acid', count: int = -1):
        ''' Set the number of passengers on aircraft 'acid' to 'count'. '''
        if count < 0:
            return True, f'Aircraft {traf.id[acid]} currently has {self.npassengers[acid]} passengers on board.'

        self.npassengers[acid] = count
        return True, f'The number of passengers on board {traf.id[acid]} is set to {count}.'
```
Here, we create a stack command `PASSENGERS`, which takes two arguments, of which the second argument is optional. We can indicate the type of each argument using python's argument annotations (the colon-syntax: `count: int`). For this function these are the following annotations:
- The `acid` argument takes a bluesky-specific type, annotated with `'acid'`: This tells bluesky to expect an aircraft callsign, which it converts to the corresponding traffic array index, which is passed on to the function.
- The `count` argument takes a default integer.

## Inputs and outputs
Its likely that you are designing a plugin to interact in some way with objects in the simulation. By default, you can access data from the simulation as _**input**_ by directly accessing the corresponding objects. For example, if I want to access the position of all aircraft in the simulation, I would do the following:

```python
from bluesky import traf, scr
import numpy as np
# ...

def update(self):
    # I want to print the average position of all aircraft periodically to the
    # BlueSky console
    scr.echo('Average position of traffic lat/lon is: %.2f, %.2f'
        % (np.average(traf.lat), np.average(traf.lon)))
```

For all available inputs, have a look at [[Traffic]], [[Simulation]], and [[Navdb]].

Sending commands through the [[stack]] is the default way to _**output**_ to the simulation. For instance, if the result of a plugin is to add a waypoint to the route of a particular aircraft, you can do the following:

```python
from bluesky import stack
# ...

def update(self):
    # ... exiting stuff happens before here, where an aircraft ID is found, and a waypoint name
    ac  = 'MY_AC'
    wpt = 'SPY'
    stack.stack('ADDWPT %s %s' % (ac, wpt))
```

Of course it is perfectly possible to avoid going through the stack here, and instead directly modify the data in, e.g., the `traf` object. In fact, there will be more advanced situations where this is even required. Nevertheless, going through the stack is preferred, as the syntax of stack commands doesn't change, while the layout of the code and objects behind it may, making direct modifications from inside a plugin a brittle implementation.

## Subclassing BlueSky core implementations
In BlueSky, several core classes can be subclassed to add or replace functionality. Currently, these are the performance model,
[[Conflict Detection|asascd]], [[Conflict Resolution|asascr]], the [[Wind model|windsim]], the [[Turbulence model|turbulence]], the [[ADSB model|adsb]], the [[Route|route]] object, the [[Autopilot logic|ap]], and the [[ActiveWaypoint|activewp]] object.

Subclassing is performed in a regular manner. For example, subclassing the Route object in a plugin, your code could look like this:

```python
from bluesky.traffic import Route

class MyRoute(Route):
    def __init__(self):
        super().__init__()
        # my initialisation here

    def delwpt(self,delwpname,iac=None):
        success = super().delwpt(delwpname, iac)
        # do something with your own data
        return success
```
When running BlueSky, and loading this plugin, the new route implementation becomes available, and can be selected with the `IMPLEMENTATION` command:

    IMPL ROUTE MYROUTE

The complete example can be found [here](https://github.com/TUDelft-CNS-ATM/bluesky/blob/master/plugins/simexample.py), and in the `plugins` folder in your BlueSky installation.