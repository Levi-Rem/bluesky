# Dynamic Arrays
_Dynamic Arrays are the preferred way in Bluesky to store vectorized aircraft data. Functions for appending, shortening and resetting Dynamic Arrays are automatically executed. This greatly reduces the size of the code, while improving readability and flexibility._

Aircraft state information in Bluesky is vectorized as much as possible. This means that many arrays exist that contain aircraft parameters. Examples of these parameters are the aircraft name, speed and fuel consumption. At the moment of writing, Bluesky contains over 100 different state parameters. 

Let's say that a simulation contains 42 different aircraft at a given time. This means that now over 100 arrays of length 42 exist.

Whenever a new aircraft is [created](CRE), the initial values of the aircraft should be appended to all state vectors. When an aircraft is [removed](DEL) from the simulation, its index should be found and all the state values should be removed from the arrays.  When the simulation is [reset](RESET), all state arrays should be replaced by arrays of length 0.

This would require three new lines of code for each aircraft parameter in the simulation. That would make the files large and fragile, and vulnerable for type errors. The solution is to use Dynamic Arrays.

## Principle
Any subclass of traffic.py that contains aircraft state parameters can be a child class of the Dynamic Arrays class. When using the Parameter Register in the Dynamic Arrays class, state arrays will be **automatically** appended, shortened or reset if the number of aircraft in the simulation changes.

## Usage
We will use the files of the [traffic](traffic) and [autopilot](ap) modules as examples in the explanation. Let's focus on the traffic file first. When creating these modules, the classes are initialized as Dynamic Arrays:

```python
...
from bluesky.tools.dynamicarrays import DynamicArrays
...

class Traffic(DynamicArrays):
    def __init__(self):
...
```

Now that the traffic class is a Dynamic Array, we can call the Register. Every parameter that is initialized within the Register indentation will be saved as an aircraft parameter, of which the length should be the same as the number of aircraft.

```python
...
from bluesky.tools.dynamicarrays import DynamicArrays
from bluesky.tools.dynamicarrays import RegisterElementParameters

...

class Traffic(DynamicArrays):
    def __init__(self):
        with RegisterElementParameters(self):
            # Initialize all the aircraft parameters
            self.lat     = np.array([])  # latitude [deg]
            self.lon     = np.array([])  # longitude [deg]
            self.alt     = np.array([])  # altitude [m]
            ...

        self.ntraf = 0 # The number of aircraft in the simulation

...
```

After this code, traffic is now a dynamic array. This means that when the number of aircraft changes, the corresponding functions in DynamicArrays.py can be called:

```python
class Traffic(DynamicArrays):
    ...
    def delete(self, acid):
        """Delete an aircraft"""

        # Look up index of aircraft
        idx = self.id2idx(acid)

        # Delete all aircraft parameters
        super(Traffic, self).delete(idx)

        # Decrease number of aircraft
        self.ntraf = self.ntraf - 1

...
```

The line ```super(Traffic, self).delete(idx) ``` calls the function of the parent class of Traffic: the Dynamic Array. This function deletes all aircraft with index ```idx```. Similar super functions exist for create() and reset().

## Usage for subclasses of traffic
In Bluesky, traffic is the top class for all code about flight. Modules that add code about atm operations are subclasses of Traffic. An example of this is the [autopilot](ap) module. This module contains state information about the autopilot in the simulation, and is therefore also a Dynamic Array:

```python
from bluesky.tools.dynamicarrays import DynamicArrays, RegisterElementParameters

class Autopilot(DynamicArrays):
    def __init__(self):
        self.dt = 1.01   # interval for fms

        # From here, define object arrays
        with RegisterElementParameters(self):

            # FMS directions
            self.trk = np.array([])
            self.spd = np.array([])
            ...

```

Following this code, the autopilot now has a ```self.trk``` and a ```self.spd``` parameter (different from ```traf.trk``` and ```traf.spd```), which are automatically appended when new aircraft are created. But before that, the autopilot must be included in the Aircraft Parameter Register. This adds two lines with 'Autopilot' to ```traffic.py```:

```python
...
from bluesky.tools.dynamicarrays import DynamicArrays
from bluesky.tools.dynamicarrays import RegisterElementParameters

from .autopilot import Autopilot
...

class Traffic(DynamicArrays):
    def __init__(self):
        with RegisterElementParameters(self):
            # Initialize all the aircraft parameters
            self.lat     = np.array([])  # latitude [deg]
            self.lon     = np.array([])  # longitude [deg]
            self.alt     = np.array([])  # altitude [m]
            ...

            self.ap = Autopilot() 

        self.ntraf = 0 # The number of aircraft in the simulation

...
```

The [create](CRE), [delete](DEL) and [reset](reset) functions in [Stack](stack) all refer to the top level functions in ```traffic.py```. Since the autopilot is now registered as an subclass that contains aircraft parameters, these top level functions will automatically be carried down to the autopilot. In this way, all of the 100+ state arrays for the aircraft can be automatically updated whenever the number of aircraft changes.

## Data types and creation values
The Dynamic Arrays recognize aircraft state vectors of different data types. When new aircraft are created, standard values are appended to the arrays. These values depend on the data type of the vector:

| Data Type     | Create Value  |
| ------------- |:-------------:|
| float         | 0.0           |
| int           | 0             |
| bool          | False         |
| string        | ""            | 

If any other values are required at creation, it is possible to directly set those afterwards. This can be seen in the autopilot class, for example:

```python
...
class Autopilot(DynamicArrays):
...
    def create(self, n=1):
        super(Autopilot, self).create(n)

        # FMS directions
        self.tas[-n:] = bs.traf.tas[-n:]
        ...
...
```
Please note that the array ```self.tas``` is first appended by ``` super().create(), which will append values of ```0.0``` to the end of the ```self.tas``` array. These values are then replaced by other values by the line ```self.tas[-n:] = bs.traf.tas[-n:]```.

## Other Data Types
Sometimes it is necessary to create aircraft arrays with other data types than those defined in the section above. An exampe of this can also be found in autopilot.py. For each aircraft, the autopilot stores a route parameter. The route object can have any size (some aircraft have complicated routes programmed, and others have simple routes), and it is therefore impossible to replace it with standard data types such as floats, ints or strings.

To generate a route array for each aircraft, and to delete it, the code is added outside of the ```RegisterElementsParameters```. In the example below, these are the lines with ```self.route```:

```python
...
from .route import Route
from bluesky.tools.dynamicarrays import DynamicArrays, RegisterElementParameters

class Autopilot(DynamicArrays):
    def __init__(self):
        # From here, define object arrays
        with RegisterElementParameters(self):

            # FMS directions
            self.trk = np.array([])
            self.spd = np.array([])
            ...

        # Route objects
        self.route = []

    def create(self, n=1):
        super(Autopilot, self).create(n)
        ...

        # Route objects
        self.route.extend([Route()] * n)

    def delete(self, idx):
        super(Autopilot, self).delete(idx)
        
        # Route objects
        del self.route[idx]
```
