# Optimization through Vectorized Operations
Aircraft data is vectorized in Bluesky. This means that many arrays exist in the program that contain the current state of all aircraft. Over 100 different aircraft parameters are tracked during any simulation. These include position and velocity information, but also the local air pressure and the airport of destination.

To give a visual example of the aircraft data structure:

|Aircraft Name| KLM01 | TUD02 | CNS03 | ATM04 | JBM05|
|--|--|--|--|--|--|

|Altitude\[m\]| 123.45 | 7000.00 | 505.26 | 5682.89 | 1357.91|
|--|--|--|--|--|--|

|Vertical Speed \[m/s\]| 24.12| 00.00 | 00.00 | -4.78 | 00.00|
|--|--|--|--|--|--|

These aircraft vectors can be lists, for example for storing aircraft names. They can also be Numpy arrays, for storing numeric values. When performing computations in the simulations, Numpys array operations can be used to perform computations for all aircraft at once. The core of these aircraft computations is in the file ```traffic.py```. In the simulation step, the code looks like the following:

```python
...
class Traffic(Entity):
...
    def update_pos(self):
        # Update position:
        # self.alt: altitude [m]
        # self.vs: vertical speed [m/s]
        # simdt: simulation time step [s]
        self.alt = self.alt + self.vs * bs.sim.simdt
        ...

```

The benefit of having all aircraft data in arrays is that updating the altitude is much faster when vectorized arrays are used, than when each aircraft computes its altitude on its own. Also the line of code stays the same, independent of the number of aircraft in the simulation. This applies to all kinds of computations that are performed on the aircraft states, ranging from fuel consumption to navigation.

Although ```traffic.py``` is the central module for aircraft state operations, vectorization is used in many different classes and files in Bluesky, e.g. in conflict resolution, aerodynamics and performance limits. A few examples of usage in ```traffic.py``` are given below:

```python

# self.gsnorth: groundspeed in north direction [m/s]
# self.tas: true airspeed [m/s]
# self.hdg: aircraft heading [degrees]
# windnorth: wind speed in north direction [m/s]
self.gsnorth  = self.tas * np.cos(np.radians(self.hdg)) + windnorth

# self.lat: latitude [deg]
# simdt: simulation time step [s]
# self.gsnorth: groundspeed in north direction [m/s]
# Rearth: Radius of the standard earth model [m]
self.lat = self.lat + np.degrees(simdt * self.gsnorth / Rearth)

# Update heading
# self.hdg: heading [deg]
# simdt: simulation time step [s]
# turnrate: turning speed [deg/s]
# self.swhdgsel: boolean switch whether a specific heading should be followed [-]
# delhdg: difference between current and desired heading [deg]
self.hdg = (self.hdg + simdt * turnrate * self.swhdgsel * np.sign(delhdg)) % 360.

# Pressure
# p: air pressure [Pa]
# rho: air density [kg/m3]
# R: universal gas constant [-]
# T: temperature [K]
p = rho * R * T
```