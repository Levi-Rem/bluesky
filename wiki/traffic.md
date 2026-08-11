# The Traffic object
In BlueSky, the Traffic object is a global singleton (only one instance exists) that contains all of the data related to the actual simulation of the traffic. This data can be aircraft-related (e.g., aircraft positions, velocities, etc.), but can also include other simulated aspects affecting the aircraft such as weather (a wind model, turbulence) or aircraft performance models.

The Traffic object offers a number of methods to help with managing all aircraft at once. However, inside the object the various parameters are represented by numpy vectors that contain the value of that parameter for all existing aircraft. This enables the use of fast numpy vector calculations wherever these traffic parameters are used. (To find out more about this, look [[here|vecvsobj]].) 

The number of aircraft that exist in the simulation at any given time is represented in the Traffic object by the variable ```ntraf```. In the simulation each aircraft has a unique identifier which is shown in the aircraft label on the radar screen (and which can be used to send [[commands|Navigation-Commands]] to the aircraft). For an aircraft with identifier ```AC123```, the index of this aircraft in all parameter vectors can be found using the Traffic object member function ```id2idx("AC123")```.

The Traffic object is accessible as a top-level import from the bluesky package:

```python
from bluesky import traf

# Get number of aircraft
num_ac = traf.ntraf

# Get the index of the aircraft with identifier KL204 and print its latitude
idx = traf.id2idx("KL204")
print(traf.lat[idx])
...
```

Part of the Traffic object are also system simulations like the Airborne Separation Assurance System (ASAS, in ```traf.asas```), the autopilot (in ```traf.ap```) and the flight management system (FMS, in ```traf.ap.route``` and ```traf.actwp``` for the active waypoint data).

## Traffic member variables
The variables are available as part of the traffic object:

**Basic aircraft information**

|Name|Unit|Description
|----|------|-----------------
id   |string| Aircraft identifier / callsign
type |string| Aircraft type

**Aircraft location and orientation**

|Name|Unit|Description
|----|----|-----------------
lat|deg| Aircraft latitude
lon|deg| Aircraft longitude
alt|m  | Aircraft altitude
hdg|deg| Aircraft heading
trk|deg| Aircraft track angle
distflown|m | Aircraft distance flown

**Aircraft velocities**

|Name|Unit|Description
|----|----|-----------------
tas    |m/s| Aircraft true airspeed
gs     |m/s| Aircraft ground speed
gsnorth|m/s| Aircraft ground speed north component
gseast |m/s| Aircraft ground speed east component
cas    |m/s| Aircraft calibrated airspeed
M      |-| Aircraft mach number
vs     |m/s| Aircraft vertical speed

**Atmospheric properties per aircraft**

|Name|Unit|Description
|----|----|-----------------
p    |N/m^2|Air pressure
rho  |kg/m^3|Air density
Temp |K|Air temperature
dtemp|K|Delta-t for non-ISA conditions

**Aircraft autopilot settings**

|Name|Unit|Description
|----|----|-----------------
selspd |m/s or -|Aircraft selected speed (CAS or Mach)
selalt|m|Aircraft selected altitude
selvs  |m/s|Aircraft selected vertical speed

**Aircraft navigation settings**

|Name|Unit|Description
|----|----|-----------------
swlnav|boolean|Lateral navigation (LNAV) guidance mode is on/off
swvnav|boolean|Vertical navigation (VNAV) guidance is on/off
swvnavspd|boolean|Vertical navigation speed guidance is on/off

**Child objects**
The traffic object has several child objects with specific functionality:
* [[OpenAP aircraft performance module|openap]]
* [[Autopilot logic|ap]]
* [[Pilot logic|pilot]]
* [[ASAS/CD&R|asasapi]]
* [[ADS-B model|adsb]]
* [[Wind model|windsim]]
* [[Turbulence model|turbulence]]
